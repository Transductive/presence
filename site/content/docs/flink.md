+++
title = "Keyed Streams with Flink"
description = "Make approximate membership decisions in Java DataStreams, with an explicit duplicate policy."
weight = 6
+++

`presence-flink` adds membership checks to **keyed Java DataStreams on Flink 2.x**.
Choose fixed-size or scalable Bloom storage, decide what to do with possible
duplicates, and optionally carry the membership observation downstream.

This guide uses the established DataStream API and Java 21. The integration is
separate from the dependency-free core library. Run Flink examples locally or on
your cluster; the browser playground remains focused on core Bloom filters.

## Add the Module

The current `0.1.0-SNAPSHOT` is built from source; it is not on Maven Central.
From the repository root, run `mise run publish:local`, then add these dependencies
to your job's Gradle build:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.transductive:presence-flink:0.1.0-SNAPSHOT")
    compileOnly("org.apache.flink:flink-streaming-java:2.0.0")
}
```

Use the Flink version deployed on your cluster. Maven consumers declare Presence
normally and Flink with `provided` scope. The module brings `presence` transitively;
Flink is supplied by your job or cluster. Include both Presence JARs in your job
package and keep Flink runtime dependencies out of that package. For local IDE
execution, your launch configuration must also supply the Flink runtime.

## Choose the Membership Scope

Assume each event has a tenant, a string event ID, and a payload:

```java
public record Event(String tenantId, String eventId, String payload) {}
```

`keyBy(Event::tenantId)` selects **which Bloom** to consult.
`by(Event::eventId)` selects **what identity** to check within that Bloom.
One filter is created lazily per tenant. The same event ID in two tenants has
independent membership. Group many IDs together: keying by the individual event
ID would allocate an entire Bloom for each ID.

## Suppress Possible Duplicates

```java
import io.transductive.presence.flink.BloomBackend;
import io.transductive.presence.flink.BloomDeduplicator;
import io.transductive.presence.flink.Decision;

var output = BloomDeduplicator.<Event>by(Event::eventId)
    .scalable(1_000)
    .backend(BloomBackend.HEAP)
    .onPossibleDuplicate(event -> Decision.DROP)
    .build()
    .applyTo(events.keyBy(Event::tenantId))
    .uid("tenant-event-membership");
```

The output still contains `Event`. A definitely-absent ID is remembered before
its original event is forwarded. A possible duplicate invokes your callback.
`DROP` accepts that a false positive can discard a genuinely new event; choose
this only when that loss is acceptable. `FORWARD` preserves possible duplicates.

The callback is mandatory and runs synchronously on the operator thread. Keep
it quick, avoid blocking I/O, and do not mutate the event. The ID selector and
callback must be serializable, including captured objects. Null IDs, null
decisions, and callback failures fail the task. IDs use core's UTF-8 semantics.

## Carry Membership Downstream

```java
import io.transductive.presence.flink.MembershipResult;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;

SingleOutputStreamOperator<MembershipResult<Event>> checked =
    BloomDeduplicator.<Event>by(Event::eventId)
        .scalable(1_000)
        .onPossibleDuplicate(event -> Decision.FORWARD_WITH_MEMBERSHIP)
        .build()
        .applyToWithMembership(events.keyBy(Event::tenantId));
```

Every forwarded result exposes `value()` for the unchanged event and
`maybePresent()` for the observation **before insertion**:

| Flag | Meaning |
| --- | --- |
| `false` | Definitely absent from this tenant's current Bloom membership; the ID has now been remembered |
| `true` | Possibly present, including false positives; this is not proof of a duplicate |

`applyToWithMembership` supplies the wrapper's Flink type information using the
input stream's payload type. Event timestamps and watermarks are preserved.
The wrapper does not copy the event; normal Flink object-reuse rules apply.

For possible duplicates, `DROP` emits nothing. Both forwarding decisions produce
`MembershipResult<Event>` with `true` in this mode. Definitely-absent events
produce a result with `false` without invoking the callback. Mixing policies
never mixes output types. Use `applyToWithMembership` for this output: returning
`FORWARD_WITH_MEMBERSHIP` from a callback attached with plain `applyTo` fails the
task when that decision is encountered.

## Size Each Group

| Configuration | Meaning |
| --- | --- |
| `.scalable()` | Default initial capacity: 1,000 distinct IDs per group |
| `.scalable(initialCapacity)` | Grow by adding layers as membership fills |
| `.fixed(expectedInsertions)` | Fixed-size storage; overfilling raises false positives |
| `.falsePositiveProbability(0.01)` | Default 1% target; scalable filters budget it across all layers |
| `.hashingStrategy(strategy)` | Core hashing strategy; defaults to Murmur3 |
| `.backend(BloomBackend.HEAP)` | Default: heap membership excluded from checkpoints |
| `.backend(BloomBackend.MANAGED_STATE)` | Heap lookups with checkpointed layers and counters |

Capacity is per group, not per job or parallel task. More groups allocate more
filters; scalable layers can also grow. There is no global memory cap or eviction.
Building the configuration allocates no Bloom arrays. Filter sizing limits are
checked when a group's filter is created.

## Recover Membership with Managed State

```java
environment.enableCheckpointing(10_000);

var output = BloomDeduplicator.<Event>by(Event::eventId)
    .scalable(1_000)
    .backend(BloomBackend.MANAGED_STATE)
    .onPossibleDuplicate(event -> Decision.DROP)
    .build()
    .applyTo(events.keyBy(Event::tenantId))
    .uid("tenant-event-membership");
```

Both modes query and mutate the Bloom on heap. `MANAGED_STATE` adds a recovery
copy: each Flink key owns a `MapState<Integer, byte[]>` from layer index to
serialized layer. A small `ValueState<byte[]>` manifest records configuration,
layer count, and the per-key event counter. Fixed filters use one layer. The
application still chooses Flink's state backend and checkpoint storage.

### From Memory to Recovery

{% include "partials/architecture-diagram.html" %}

Read the diagram from left to right for an event, and from top to bottom for a
checkpoint:

1. **Route by key.** Each tenant has its own heap Bloom inside the subtask.
2. **Probe the layers.** Start with the newest layer and stop on any possible
   match. Only when every layer says no does the operator remember the ID in
   the active layer. When that layer fills, a larger one becomes active.
3. **Track changes.** Inserts mark the affected layer dirty. Every observed event
   also changes the key's reset counter, including possible duplicates.
4. **Snapshot dirty state.** Before Flink snapshots its backend, flush changed
   layers and manifests for all dirty keys. The completed checkpoint is the
   recovery boundary.
5. **Restore on demand.** Flink restores the keyed state; the first event for a
   key hydrates its heap Bloom. Subsequent lookups stay on heap.

The drawing shows scalable storage with doubling growth. A fixed Bloom uses
the same recovery path with a single layer. The membership flag is included
downstream only with `applyToWithMembership`; `applyTo` forwards the original
record alone, and a `DROP` decision emits nothing.

Mutations mark layers dirty, coalescing many inserts into one pending write.
Before each checkpoint snapshot, the operator flushes changed layers for every
dirty key on the operator thread. Duplicate-only traffic writes the counter
manifest without rewriting layer bytes. A reset removes obsolete saved layers
and persists the replacement filter and counter in that checkpoint.

After recovery, a key's saved Bloom is loaded once on its first event, before
callbacks run. Warm lookups perform no backend reads. Keys not yet accessed stay
in managed state without heap allocation. Rescaling redistributes keyed state,
and the new owners reconstruct heap membership as needed.

Enable checkpoints, retain a stable operator UID, and use replayable inputs for
Flink recovery. Durability comes from a **completed checkpoint/savepoint**, not
from a backend put or a shutdown hook. Heap and backend may differ between
checkpoints; the snapshot boundary synchronizes them. Restoring an earlier
checkpoint intentionally restores its earlier membership and reset counter.

Keep key and ID semantics stable. Changing filter type, capacity, probability,
or hashing strategy is rejected when restored membership is first loaded.
Snapshots use versioned formats; unsupported/corrupt payloads fail restoration.
Automatic state migration is not implemented.

All resident membership must still fit in JVM heap. This adds backend storage
and checkpoint work, with no eviction or overflow tier. Changed layers are
written in full, so a large dirty layer can increase synchronous checkpoint
latency. Persistence preserves Bloom membership; it does not eliminate false
positives or turn approximate duplicate suppression into an exact guarantee.

## Reset Membership Explicitly

Use `beforeLookup` for event-driven resets. No reset is enabled by default.
Each call appends a hook; they run in registration order after ID validation,
before the current event is checked or counted.

### Count Events per Key

```java
import io.transductive.presence.flink.ResetPolicy;

var output = BloomDeduplicator.<Event>by(Event::eventId)
    .scalable(1_000)
    .beforeLookup(ResetPolicy.BATCH_RESET_SIZE.every(10_000))
    .onPossibleDuplicate(event -> Decision.DROP)
    .build()
    .applyTo(events.keyBy(Event::tenantId));
```

Each tenant gets an independent event counter. Events 1–10,000 share a Bloom;
event 10,001 clears it before lookup and becomes the first event in a new batch.
Duplicates and dropped records count too. This is an event limit, independent
of the Bloom's initial capacity or the number of distinct IDs. The size must
be positive; an idle tenant is not cleared until its next event arrives.

### Recognize Tombstones

For a stream with reset markers, replace the event declaration with a type that
implements `Tombstone`:

```java
import io.transductive.presence.flink.Tombstone;

public record Event(String tenantId, String eventId, String payload, boolean tombstone)
        implements Tombstone {
    @Override public boolean isTombstone() { return tombstone; }
}
```

Register the built-in hook before `.build()`:

```java
.beforeLookup(ResetPolicy.ON_TOMBSTONE)
```

`isTombstone() == true` clears the **whole current tenant's membership**, not
one ID. Both attachment methods validate that the stream's declared record
class implements `Tombstone`; incompatible types fail during job construction.
Actual instances are checked at runtime as well.

The tombstone must still have a non-null event ID. After resetting, it is looked
up, remembered, and forwarded normally, with `maybePresent() == false` when
using membership output. It counts as the first event in the new lifetime.
This hook does not consume the marker; filter it downstream if your sink should
not receive it.

### Add Custom Logic

Custom callbacks use the same per-key controls. For example, an application that
uses a particular payload as its reset signal could register:

```java
.beforeLookup((event, membership) -> {
    if ("reset-membership".equals(event.payload())) {
        membership.clearCurrentKey();
    }
})
```

`eventsSinceReset()` reports earlier events for the current key, excluding the
current one. `clearCurrentKey()` removes that key's filter and counter; subsequent
hooks see zero, and lookup creates a fresh filter for the current event. You
can combine custom callbacks with either canned policy by calling `beforeLookup`
multiple times. Hooks implement `BeforeLookup<T>` and must be serializable.

Callbacks run synchronously on the operator thread. Do not mutate events, block
on I/O, retain the control handle, or call it from another thread. Expired or
cross-thread control calls throw `IllegalStateException`; callback failures
fail the task.

A reset makes the old filter and all its layers eligible for garbage collection.
It does not force garbage collection or impose a global memory limit. Old IDs
can pass again, and idle groups are not evicted. `HEAP` loses membership and
counters on restart; `MANAGED_STATE` restores both from the selected checkpoint.

## Understand Recovery and Lifetime

HEAP membership belongs to the operator and is **excluded from checkpoints and
savepoints**. Restart, restore, and rescaling start with empty membership, so
previously seen IDs can be forwarded again. Configuring RocksDB, managed heap
state, or durable checkpoint storage does not change that behavior.

With `HEAP` and checkpointing enabled, the operator warns and continues. Configure
checkpointing before attaching it for the startup warning. If enabled later,
the first checkpoint triggers the warning instead; restore also warns.

The operator retains copied group keys and Bloom bits, not incoming events or
event IDs. Heap membership lasts until a hook resets its key or the task closes.
`MANAGED_STATE` can rebuild it from a checkpoint. There is no TTL or window
cleanup; placing this operator before or after a window does not make its heap
expire. Reference-set loading is outside this version.

This operator does not enforce a delivery guarantee or select the event with
the largest timestamp per key. Use exact verification when a possible duplicate
must be confirmed before discarding an event.

## Observe and Verify

Metrics under `presence` report `possibleDuplicates`, `forwarded`, `dropped`,
`bloomGroups`, and `membershipResets`. The reset counter counts existing groups
removed by hooks; clearing an absent group does not increment it. A
possible-duplicate count includes false positives.

Managed mode adds `stateLayerReads`, `stateLayerWrites`, and
`stateLayerBytesWritten`. They count restored layer loads and successful backend
layer writes (including retries), excluding manifests and backend overhead.
They are not counts of durable checkpoints. `bloomGroups` counts heap-resident
keys, excluding restored keys not yet accessed. Metrics restart per task attempt;
the per-key batch-reset counter is separately persisted.

```sh
mise run flink:build
mise run flink:test -- --tests '*BloomDeduplicatorMiniClusterTest'
mise run flink:test -- --tests '*ResetPolicyMiniClusterTest'
mise run flink:integration
mise run flink:integration -- "-PflinkVersion=2.3.0"
```

The MiniCluster tests run two TaskManagers in JUnit, with no Kafka, S3, or Docker
requirement. They exercise keyed routing, both Bloom layouts, all decisions,
object reuse, and network serialization of membership results. Open the root
Gradle project in IntelliJ IDEA and run the same tests from the gutter.
Reset-policy tests also exercise custom/canned hook composition, per-tenant
batch boundaries, tombstone validation, and heap/counter reset semantics.
Managed-state tests check dirty-layer writes, write retries, reset recovery,
aborted checkpoints, and rescaling in both directions. A recovery MiniCluster job
fails after a completed checkpoint and verifies membership after restarting.
RocksDB JNI cases run on Linux, including local `act`; other platforms skip those
native cases while running managed-heap recovery tests. No Flink or RocksDB
dependencies are added to the browser playground.

The library compiles against Flink 2.0.0; CI tests runtimes 2.0.0 and 2.3.0 on
Java 21. Other 2.x versions are not individually verified. Flink's
[Java compatibility guidance](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/deployment/java_compatibility/)
describes Java 21 support as experimental; validate your own cluster and connectors.

Read the [Flink API reference]({{ get_url(path="api/flink/index.html") }}),
[BloomDeduplicator]({{ get_url(path="api/flink/io/transductive/presence/flink/BloomDeduplicator.html") }}),
[MembershipResult]({{ get_url(path="api/flink/io/transductive/presence/flink/MembershipResult.html") }}),
[BeforeLookup]({{ get_url(path="api/flink/io/transductive/presence/flink/BeforeLookup.html") }}),
or [ResetPolicy]({{ get_url(path="api/flink/io/transductive/presence/flink/ResetPolicy.html") }}).
