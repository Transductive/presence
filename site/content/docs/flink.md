+++
title = "Keyed Streams with Flink"
description = "Make approximate membership decisions in Java DataStreams, with an explicit duplicate policy."
weight = 6
+++

`presence-flink` adds membership checks to **keyed Java DataStreams on Flink 2.x**.
Choose a fixed-size **[`BloomFilter`][presence-fixed]** or a growing
**[`ScalableBloomFilter`][presence-scalable]**, decide what to do with possible
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

**[`keyBy(Event::tenantId)`][flink-keyby]** is a **partition-by-key** operation:
Flink uses hash partitioning to route records and returns a
**[`KeyedStream<Event, String>`][flink-keyed-stream]**. Attaching Presence to that
stream creates a **keyed operator**. Setting its **[operator parallelism][flink-parallelism]** to
`P` runs it as `P` **[operator subtasks][flink-subtasks]**. At that parallelism,
records with the same **stream key** reach the same subtask. Each subtask can
handle many distinct keys; `keyBy` does not create an operator or subtask per tenant.

A **[keyed operator][flink-keyed-operator]** can use
**[Flink-managed keyed state][flink-keyed-state]**, whose values are scoped to the
current record's stream key. Presence's **[`BloomDeduplicator`][presence-deduplicator]**
also scopes its heap membership by that key: each subtask lazily creates one
**[`ScalableBloomFilter<String>`][presence-scalable] per distinct stream key**
when configured with `.scalable(...)`, or one
**[`BloomFilter<String>`][presence-fixed] per distinct stream key** with `.fixed(...)`.

For example, with **parallelism 3**, four tenant keys might be distributed as
follows. The assignments are illustrative; Flink determines the actual routing.

| Operator Subtask | Stream Keys Assigned | Heap Filter Instances with `.scalable(...)` |
| --- | --- | --- |
| 0 | Tenant A, tenant D | A's `ScalableBloomFilter`, D's `ScalableBloomFilter` |
| 1 | Tenant B | B's `ScalableBloomFilter` |
| 2 | Tenant C | C's `ScalableBloomFilter` |

After all four keys have been accessed, this operator has **four filter instances
distributed across three subtasks**. No filter is shared globally across the
subtasks. Changing **parallelism** can change key ownership; with `MANAGED_STATE`,
Flink redistributes the checkpointed keyed state and the new owning subtasks
reconstruct their per-key filter instances.

**`by(Event::eventId)`** selects the **membership ID** checked inside that
per-key filter. In this example, `tenantId` is the stream key and `eventId` is
the membership ID. The same membership ID in two tenants has independent
membership. Capacity is **per stream key**; choose a key that groups many IDs
together, since keying by individual event ID would allocate a whole filter
for each ID.

Both **[`BloomBackend.HEAP`][presence-backend]** and
**[`BloomBackend.MANAGED_STATE`][presence-backend]** use this partitioning and
keep their live filter instances in JVM heap. `HEAP` stores membership only in
operator-owned memory. `MANAGED_STATE` also registers Flink-managed keyed state
for recovery; calling `keyBy` alone does not checkpoint Presence's heap objects.

### Connect Parallelism to Worker Memory

A **[TaskManager][flink-task-slots]** is a worker JVM. Its **task slots** provide
scheduling capacity; the Presence operator's **parallelism** determines its
number of **subtasks**. Multiple subtasks can run inside the same TaskManager
and share that JVM's heap. A task slot does not impose a separate memory cap on
Presence's operator-owned filter instances.

For example, **one TaskManager with eight task slots**, running Presence at
**parallelism 8**, can hold **10,000 `ScalableBloomFilter` instances** after
processing 10,000 distinct tenant keys. The eight subtasks divide ownership of
those keys, but all 10,000 filter instances consume the **same worker's heap**.
Increasing parallelism alone redistributes the same keys and adds subtask
overhead; it does not multiply the filter count for a fixed set of keys.

Budget **heap per TaskManager** from the filters for all resident stream keys
owned by its subtasks, including every `ScalableBloomFilter` layer, plus key,
index, and other job overhead. **Per-key capacity** sizes each filter; it is not
a limit on the number of keys. **`MANAGED_STATE`** adds recovery storage while
keeping those active filter instances in heap. No automatic eviction limits
this resident-key count.

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
| `false` | Definitely absent from the current stream key's filter; the membership ID has now been remembered |
| `true` | Possibly present, including false positives; this is not proof of a duplicate |

**[`MembershipResult<T>`][presence-result]** is the output wrapper.
`applyToWithMembership` supplies its Flink type information using the
input stream's payload type. Event timestamps and watermarks are preserved.
The wrapper does not copy the event; normal Flink object-reuse rules apply.

For possible duplicates, `DROP` emits nothing. Both forwarding decisions produce
`MembershipResult<Event>` with `true` in this mode. Definitely-absent events
produce a result with `false` without invoking the callback. Mixing policies
never mixes output types. Use `applyToWithMembership` for this output: returning
`FORWARD_WITH_MEMBERSHIP` from a callback attached with plain `applyTo` fails the
task when that decision is encountered.

## Size Each Filter

| Configuration | Meaning |
| --- | --- |
| `.scalable()` | Default: a **`ScalableBloomFilter`** with initial capacity 1,000 per stream key |
| `.scalable(initialCapacity)` | A **`ScalableBloomFilter`** that grows by adding layers |
| `.fixed(expectedInsertions)` | A fixed-size **`BloomFilter`**; overfilling raises false positives |
| `.falsePositiveProbability(0.01)` | Default 1% target; scalable filters budget it across all layers |
| `.hashingStrategy(strategy)` | Core hashing strategy; defaults to Murmur3 |
| `.backend(BloomBackend.HEAP)` | Default: heap membership excluded from checkpoints |
| `.backend(BloomBackend.MANAGED_STATE)` | Heap lookups with checkpointed layers and counters |

Capacity is **per stream key**, not per job or operator subtask. More distinct
stream keys allocate more filter instances; each `ScalableBloomFilter` can also
add layers. There is no global memory cap or eviction. Building the configuration
allocates no filter bit arrays. Filter sizing limits are checked when a stream
key's filter is created.

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

Both modes query and mutate the configured `ScalableBloomFilter` or `BloomFilter`
in **JVM heap**. **`BloomBackend.MANAGED_STATE`** adds a recovery copy in
**[Flink-managed keyed state][flink-keyed-state]**:

- **[`MapState<Integer, byte[]>`][flink-map-state]** stores a **layer index** and
  its serialized layer bytes. The outer **stream key** is supplied by Flink's
  current-key context; the integer map key is a layer index within that key's
  filter, not a tenant ID.
- **[`ValueState<byte[]>`][flink-value-state]** stores the **manifest**: filter
  configuration, layer count, and the per-key event counter.

A `ScalableBloomFilter` can have multiple saved layers; a fixed-size `BloomFilter`
uses only layer zero. The application chooses Flink's **state backend** and
**checkpoint storage** independently of Presence's `BloomBackend` option.

### From Memory to Recovery

{% include "partials/architecture-diagram.html" %}

Read the diagram from left to right for an event, and from top to bottom for a
checkpoint:

1. **Partition by stream key.** **[`keyBy`][flink-keyby]** returns a
   **[`KeyedStream`][flink-keyed-stream]** and routes equal keys to the same
   **[operator subtask][flink-subtasks]** among the operator's configured
   **parallelism**. The diagram shows **one subtask**, not the whole parallel
   operator. Within that subtask, Presence selects
   the **[`ScalableBloomFilter`][presence-scalable]** belonging to the current
   stream key. The diagram's keys A and B have separate filter instances.
2. **Probe the `ScalableBloomFilter` layers.** Check the **membership ID** from
   newest layer to oldest, stopping on any possible match. Only when every
   layer says no does Presence insert into the **active layer**. When that layer
   reaches its occupancy limit, a larger one becomes active.
3. **Track dirty layers.** Inserts mark the affected **layer** dirty. Every
   observed event also updates the stream key's **event counter**, including
   possible duplicates.
4. **Snapshot keyed state.** Before Flink snapshots its backend, Presence writes
   changed layers to **[`MapState`][flink-map-state]** and manifests to
   **[`ValueState`][flink-value-state]** for every dirty stream key. A
   **completed checkpoint** is the recovery boundary.
5. **Restore per stream key.** Flink restores **[keyed state][flink-keyed-state]**;
   the first event for a key reconstructs its **`ScalableBloomFilter`** in JVM
   heap. Subsequent membership checks use that heap instance.

The drawing shows **`ScalableBloomFilter`** with doubling growth. A fixed-size
**[`BloomFilter`][presence-fixed]** uses the same recovery path with a single
layer. The membership flag is included
downstream only with `applyToWithMembership`; `applyTo` forwards the original
record alone, and a `DROP` decision emits nothing.

Mutations mark layers dirty, coalescing many inserts into one pending write.
Before each checkpoint snapshot, the operator flushes changed layers for every
dirty stream key on the operator thread. Duplicate-only traffic writes the counter
manifest without rewriting layer bytes. A reset removes obsolete saved layers
and persists the replacement filter and counter in that checkpoint.

After recovery, a stream key's saved filter is loaded once on its first event,
before callbacks run. Warm lookups perform no backend reads. Keys not yet accessed stay
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

Each **stream key** gets an independent **event counter**. In this `.scalable(...)`
example, events 1–10,000 use the same **`ScalableBloomFilter`**; event 10,001
replaces it before lookup and becomes the first event in a new batch.
Duplicates and dropped records count too. This is an event limit, independent
of the filter's initial capacity or the number of distinct membership IDs. The
size must be positive; an idle stream key is not reset until its next event arrives.

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

`isTombstone() == true` clears the **current stream key's entire filter and event
counter**, not one membership ID. Both attachment methods validate that the
stream's declared record class implements `Tombstone`; incompatible types fail during job construction.
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
can pass again, and idle stream keys are not evicted. `HEAP` loses membership and
counters on restart; `MANAGED_STATE` restores both from the selected checkpoint.

## Understand Recovery and Lifetime

**`BloomBackend.HEAP`** membership belongs to the operator subtask and is
**excluded from checkpoints and savepoints**. Restart, restore, and rescaling start with empty membership, so
previously seen IDs can be forwarded again. Configuring RocksDB, managed heap
state, or durable checkpoint storage does not change that behavior.

With `HEAP` and checkpointing enabled, the operator warns and continues. Configure
checkpointing before attaching it for the startup warning. If enabled later,
the first checkpoint triggers the warning instead; restore also warns.

The operator retains copied **stream keys** and filter bits, not incoming events
or membership IDs. Heap membership lasts until a hook resets its key or the task closes.
`MANAGED_STATE` can rebuild it from a checkpoint. There is no TTL or window
cleanup; placing this operator before or after a window does not make its heap
expire. Reference-set loading is outside this version.

This operator does not enforce a delivery guarantee or select the event with
the largest timestamp per key. Use exact verification when a possible duplicate
must be confirmed before discarding an event.

## Observe and Verify

Metrics under `presence` report `possibleDuplicates`, `forwarded`, `dropped`,
`bloomGroups`, and `membershipResets`. The reset counter counts existing per-key
filters reset by hooks; clearing an absent filter does not increment it. A
possible-duplicate count includes false positives.

Managed mode adds `stateLayerReads`, `stateLayerWrites`, and
`stateLayerBytesWritten`. They count restored layer loads and successful backend
layer writes (including retries), excluding manifests and backend overhead.
They are not counts of durable checkpoints. **`bloomGroups`** counts
**heap-resident stream keys**, excluding restored keys not yet accessed; it does
not count layers or Flink **[key groups][flink-key-groups]**. Metrics restart per
task attempt; the per-key batch-reset counter is separately persisted.

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

[flink-keyby]: https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/datastream/operators/overview/#keyby
[flink-keyed-stream]: https://nightlies.apache.org/flink/flink-docs-release-2.0/api/java/org/apache/flink/streaming/api/datastream/KeyedStream.html
[flink-subtasks]: https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/concepts/flink-architecture/#tasks-and-operator-chains
[flink-parallelism]: https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/datastream/execution/parallel/#operator-level
[flink-task-slots]: https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/concepts/flink-architecture/#task-slots-and-resources
[flink-keyed-operator]: https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/datastream/operators/process_function/
[flink-keyed-state]: https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/datastream/fault-tolerance/state/#using-keyed-state
[flink-key-groups]: https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/concepts/stateful-stream-processing/#keyed-state
[flink-map-state]: https://nightlies.apache.org/flink/flink-docs-release-2.0/api/java/org/apache/flink/api/common/state/MapState.html
[flink-value-state]: https://nightlies.apache.org/flink/flink-docs-release-2.0/api/java/org/apache/flink/api/common/state/ValueState.html
[presence-fixed]: {{ get_url(path="api/io/transductive/presence/bloom/BloomFilter.html") }}
[presence-scalable]: {{ get_url(path="api/io/transductive/presence/bloom/ScalableBloomFilter.html") }}
[presence-deduplicator]: {{ get_url(path="api/flink/io/transductive/presence/flink/BloomDeduplicator.html") }}
[presence-backend]: {{ get_url(path="api/flink/io/transductive/presence/flink/BloomBackend.html") }}
[presence-result]: {{ get_url(path="api/flink/io/transductive/presence/flink/MembershipResult.html") }}
