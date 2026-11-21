# Presence Flink

Approximate duplicate suppression for **keyed Java DataStreams on Flink 2.x**.
Uses the established DataStream API. Java 21 is required by Presence; configure
the Flink runtime to use Java 21 too. The separate experimental DataStream API V2,
Table/SQL, and Python APIs are outside this module.

Read the [Flink field guide](https://transductive.github.io/presence/docs/flink/),
[memory and recovery architecture](https://transductive.github.io/presence/docs/flink/#from-memory-to-recovery),
and [API reference](https://transductive.github.io/presence/api/flink/index.html).
Flink examples run in a JVM; the website's browser playground is for core filters
and does not load Flink dependencies.

## Make a Membership Decision

```java
import io.transductive.presence.flink.BloomBackend;
import io.transductive.presence.flink.BloomDeduplicator;
import io.transductive.presence.flink.Decision;

// Event::eventId returns a non-null String.
var deduplicator = BloomDeduplicator.<Event>by(Event::eventId)
    .scalable(1_000)
    .backend(BloomBackend.HEAP)
    .onPossibleDuplicate(event -> Decision.DROP)
    .build();

var output = deduplicator
    .applyTo(events.keyBy(Event::tenantId))
    .uid("event-membership");

output.sinkTo(yourSink);
```

`keyBy` defines the membership scope: each tenant has an independent Bloom.
`by` selects the event identity inside that scope. The same ID in two tenants
can pass once in each tenant. One Bloom is allocated lazily per group, per task
attempt. Capacity is **per group**, not per job or per parallel task. Grouping
by each individual event ID usually wastes memory: an exact seen flag would
be smaller than a whole Bloom for a single identity.

The operator checks membership before insertion. A definitely-absent ID is
remembered and its original record is forwarded. A possible duplicate invokes
the callback, which chooses `DROP`, `FORWARD`, or `FORWARD_WITH_MEMBERSHIP`.
The callback is mandatory; there is no implicit dropping policy. `DROP` accepts
that false positives can discard genuinely new records. Both forwarding decisions
also forward actual repeats. No decision means the event has been delivered to a
sink. This is not an at-most-once or exactly-once delivery guarantee, nor
latest-event-time selection.

The callback runs synchronously on the operator thread. Keep it quick, do not
mutate the record, and do not capture non-serializable objects such as a live
database client. Callback failures fail the task; null decisions and null IDs
are errors. This version accepts string event IDs with the core library's UTF-8
semantics. It does not implicitly use `toString()` to encode arbitrary IDs.

## Forward with Membership

Use `FORWARD_WITH_MEMBERSHIP` and `applyToWithMembership` when downstream code
needs the observation alongside the original event:

```java
var output = BloomDeduplicator.<Event>by(Event::eventId)
    .scalable(1_000)
    .onPossibleDuplicate(event -> Decision.FORWARD_WITH_MEMBERSHIP)
    .build()
    .applyToWithMembership(events.keyBy(Event::tenantId))
    .uid("event-membership-result");
```

The output type is `MembershipResult<Event>`, a Java record in
`io.transductive.presence.flink`:

```java
Event event = result.value();
boolean maybePresent = result.maybePresent();
```

| Observation Before Insertion | Output |
| --- | --- |
| Definitely absent from this group's current Bloom | Original event with `maybePresent() == false`; the ID is remembered |
| Possibly present and the callback forwards it | Original event with `maybePresent() == true` |
| Possibly present and the callback returns `DROP` | No output |

The flag describes the Bloom's observation, **not proof of a duplicate**. A new
ID can receive `true` because of a false positive. After a HEAP reset, a
previously observed ID can receive `false` again. The callback still runs only
for possible duplicates; definitely-absent events do not invoke it.

Flink needs one output type when the job is constructed. `applyToWithMembership`
wraps **every forwarded event**, including definitely-absent events. If a callback
mixes `FORWARD` and `FORWARD_WITH_MEMBERSHIP`, both yield a result with the actual
membership flag. `DROP` still suppresses output. The existing `applyTo` preserves
`T`; if its callback returns `FORWARD_WITH_MEMBERSHIP`, the task fails with an
instruction to use `applyToWithMembership` instead. A callback's decision is
only known when a possible duplicate arrives, so this check happens at runtime.

Presence supplies the wrapper's Flink type information using the input stream's
payload type, so callers do not need an extra `.returns(...)`. The wrapper uses
Flink's record/POJO serializer and preserves the configured serializer for `T`.
It wraps the incoming value without copying or mutating it; ordinary Flink
object-reuse rules apply. Event timestamps and watermarks are preserved.

## Choose Filter Sizing

| Option | Behavior |
| --- | --- |
| `.scalable()` | Default: start at 1,000 distinct IDs per group and grow |
| `.scalable(initialCapacity)` | Choose the first layer's expected capacity |
| `.fixed(expectedInsertions)` | Fixed storage; overfilling increases false positives |
| `.falsePositiveProbability(0.01)` | Default target: 1%; scalable budgets this across all layers |
| `.hashingStrategy(strategy)` | Choose a core strategy; default is Murmur3 |
| `.backend(BloomBackend.HEAP)` | Default: operator-owned memory, excluded from checkpoints |
| `.backend(BloomBackend.MANAGED_STATE)` | Heap primary with checkpointed layers and counters |

Building configuration allocates no Bloom arrays. Actual sizing is subject to
the core filter's supported limits when a group is first created. The number
of groups and scalable layers can grow without a fixed total memory bound.

## Keep Lookups on Heap and Recover Membership

Select `MANAGED_STATE` to retain fast heap lookups while including membership in
Flink's keyed checkpoints and savepoints:

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

Each stream key has a `MapState<Integer, byte[]>`: the map key is a layer index,
and the value is that layer's versioned snapshot. A small `ValueState<byte[]>`
manifest carries filter configuration, layer count, and the event counter used
by reset hooks. A fixed Bloom uses layer zero. Flink's state backend (managed
heap or RocksDB) and checkpoint destination remain application choices.

Inserts and lookups run against the heap filter. Mutations mark layers dirty;
repeated mutations are coalesced. Before the backend captures a checkpoint,
the operator flushes dirty layers for **every dirty stream key**, then writes
its manifest. Duplicate-only traffic updates the counter without rewriting
layer bytes. No background threads access keyed state, and snapshots never
share writable bit arrays with the live filter.

After restore, each key's saved layers are loaded once on first access, before
its hooks or membership check. Resident keys need no further backend reads.
Untouched restored keys remain in managed state and are included in later
checkpoints without being loaded. New jobs do not read state to create filters.
Flink redistributes keyed state during rescaling; heap copies are reconstructed
under their new owners.

A reset discards the heap layers and schedules deletion of the old saved layers.
That deletion, the replacement layer, and the reset counter enter the same next
checkpoint. Recovery returns to the selected checkpoint's membership lifetime;
it does not resurrect a pre-reset filter from a later checkpoint.

Recovery requires a **completed checkpoint or savepoint**, replayable inputs,
and the normal Flink recovery configuration. A backend write or `close()` is not
a durable recovery boundary. Dirty state may lag on the backend between
checkpoints, but is synchronized when a snapshot is taken. The Bloom remains
approximate; persistence does not make `DROP` an exact deduplicator.

Keep the operator UID, key semantics, ID encoding, filter type, capacity,
probability, and hashing strategy stable across restores. Configuration changes
are rejected when a saved key is first loaded. Snapshot versions and checksums
reject unsupported or corrupt core payloads; there is no automatic state migration.

All resident Blooms still need to fit in JVM heap. Managed state adds a second
copy and checkpoint serialization/write costs; it is not an overflow tier or
an eviction policy. Flushing a large dirty layer can delay the synchronous
checkpoint phase. Only changed **layers** are written, not individual changed words.

## Reset Membership before a Lookup

No reset policy is enabled by default. Add custom or built-in callbacks with
`beforeLookup`; repeated calls append hooks in registration order. Hooks run
for every event after its ID is validated, before membership is checked or the
current event is counted. They work with both filter types and output modes.

### Reset by Event Count

```java
import io.transductive.presence.flink.ResetPolicy;

var output = BloomDeduplicator.<Event>by(Event::eventId)
    .scalable(1_000)
    .beforeLookup(ResetPolicy.BATCH_RESET_SIZE.every(10_000))
    .onPossibleDuplicate(event -> Decision.DROP)
    .build()
    .applyTo(events.keyBy(Event::tenantId));
```

Each tenant has its own counter. Events 1–10,000 share membership; event 10,001
clears that tenant before its lookup and becomes event 1 of the next batch.
**Every observed event counts**, including duplicates and dropped events. This
is independent of initial Bloom capacity and is not a count of distinct IDs.
The threshold must be positive. The reset occurs on the next arriving event,
so an idle tenant keeps its last filter until it receives another event.

### Reset on a Tombstone

Implement `Tombstone` on the stream's declared event type:

```java
import io.transductive.presence.flink.Tombstone;

public record Event(String tenantId, String eventId, boolean tombstone)
        implements Tombstone {
    @Override public boolean isTombstone() { return tombstone; }
}
```

Then add this builder option:

```java
.beforeLookup(ResetPolicy.ON_TOMBSTONE)
```

When `isTombstone()` is true, the policy clears **the entire current tenant's
membership**, including its counter. It does not delete a single event ID.
`applyTo` and `applyToWithMembership` reject a declared stream type that does not
implement `Tombstone`, before attaching an operator. The policy also checks
actual event instances at runtime. Declare the implementing type, rather than
an unrelated superclass or `Object`.

A tombstone is still an ordinary input record for lookup/output: it requires a
non-null event ID, is remembered in the new filter, counts as its first event,
and is forwarded with `maybePresent() == false` in membership-output mode.
It is not consumed as a control-only message. A downstream filter can suppress
markers when your sink should not receive them.

### Supply a Custom Hook

For an application event with a `startsNewBatch()` signal:

```java
.beforeLookup((event, membership) -> {
    if (event.startsNewBatch()) {
        membership.clearCurrentKey();
    }
})
```

`membership.eventsSinceReset()` returns this key's earlier event count, excluding
the current event. `clearCurrentKey()` removes its filter and counter; later
hooks see zero and the current event begins fresh membership. Repeated clears
are harmless. Custom callbacks implement the serializable `BeforeLookup<T>`
interface; captured objects must also be serializable. Exceptions fail the task.

Custom hooks and canned policies can be registered together. They run on the
operator thread, so avoid blocking I/O and event mutation. The control handle
is valid only during that record's before-lookup callbacks; retaining it for
later calls or using it from another thread throws `IllegalStateException`.

### Understand the Reset Boundary

Clearing releases the old filter for garbage collection, including all scalable
layers; it does not merely zero their bit arrays. The current event creates a
new filter at the configured initial size. IDs from a previous batch may pass
again. With `HEAP`, counters and membership start empty after restart or restore.
With `MANAGED_STATE`, both recover from the selected checkpoint.

These are event-driven, per-key resets. They do not evict idle groups, bound the
total number of groups, or guarantee a heap/probability budget. There is no
timer, TTL, or Flink window lifecycle attached to this policy.

## Understand Heap Lifetime

HEAP means ordinary operator-owned memory, **not** Flink-managed heap state.
The adapter retains group keys and Bloom bits, but no incoming records or event
IDs. It copies retained group keys through Flink's configured serializer so
upstream object reuse cannot mutate stored map keys. Membership is released
on a per-key reset or when the task closes, and starts empty after restart,
restore, or rescaling. Without a reset hook, membership lasts for the task attempt.

This first keyed operator has no TTL, window ownership, or window cleanup.
Putting it before or after a window operator does not make its heap expire.
A future window-specific adapter must explicitly implement that lifecycle.
Reference-set loading and broadcasting are not implemented here. Choose
`MANAGED_STATE` above when membership must participate in recovery.

## Checkpoint Awareness

With `HEAP`, configure Flink checkpointing **before** `applyTo` or `applyToWithMembership` for
a startup warning. When checkpointing is enabled, each operator instance warns
that HEAP membership is excluded from checkpoints and savepoints. S3, filesystem, and JobManager
checkpoint storage all have the same limitation. The configured Flink state
backend is independent of both the checkpoint destination and our HEAP choice.

Only a storage description (such as `s3 checkpoint storage`) is logged, never a
bucket, full URI, credentials, or query parameters. The diagnostic does not
contact the checkpoint destination or claim to verify its durability.

If checkpointing is enabled after the operator is attached, the first actual
checkpoint triggers the warning instead. Restore also triggers a warning.
In `HEAP` mode checkpoint callbacks are used only for this diagnostic; no Bloom state is
registered or saved. The job continues, and the warning is emitted at most
once per operator instance per task attempt.

Metrics under the `presence` group expose `possibleDuplicates`, `forwarded`,
`dropped`, `bloomGroups`, and `membershipResets`. The reset counter counts existing
groups removed by hooks; clearing an absent group is a no-op. A possible duplicate
is not an exact duplicate count or a measured false-positive count.

`MANAGED_STATE` also exposes `stateLayerReads`, `stateLayerWrites`, and
`stateLayerBytesWritten`. Reads count restored layer loads; writes count successful
backend puts, including retries, rather than completed checkpoints. Bytes exclude
the small manifests and backend overhead. `bloomGroups` counts heap-resident
groups, so restored but not yet loaded groups are not included. Metrics reset on
each task attempt; the per-key batch counter is checkpointed separately.

## Add the Module to a Job

The snapshot is built from this repository and is not yet published to Maven
Central. From the repository root:

```sh
mise run publish:local
```

In the consuming Gradle project:

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

Select the Flink version matching your job's runtime. The integration brings
in `io.transductive:presence` transitively. Flink itself is supplied by the job
or cluster and is not a transitive dependency of the published integration.
Maven consumers likewise declare Flink with `provided` scope. Package both
Presence JARs with your job; never bundle Flink's runtime into the job JAR.

For Maven, run the same `mise run publish:local` command first, then declare:

```xml
<dependencies>
  <dependency>
    <groupId>io.transductive</groupId>
    <artifactId>presence-flink</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-streaming-java</artifactId>
    <version>2.0.0</version>
    <scope>provided</scope>
  </dependency>
</dependencies>
```

`compileOnly`/`provided` dependencies do not supply a standalone local runtime.
When launching your own job from the IDE, include the matching Flink runtime
(including `flink-clients`) in that launch's classpath. The repository's tests
already supply their local runtime through test-only dependencies.
See Flink's [project setup guide](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/dev/configuration/overview/)
for the runtime and packaging requirements.

## Develop and Verify

```sh
mise run flink:build
mise run flink:test
mise run flink:integration
mise run flink:integration -- "-PflinkVersion=2.3.0"
```

The library always compiles against Flink 2.0.0; the `flinkVersion` option selects
the test runtime. CI checks 2.0.0 and 2.3.0 on Java 21. Compatibility
with every other 2.x release is not implied. Flink's
[Java compatibility guidance](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/deployment/java_compatibility/)
still describes Java 21 support as experimental; the tests cover this adapter,
not every connector or cluster configuration.

Unit tests cover group isolation, both filter types, false positives, policy
decisions, serializable configuration, copied keys, and heap reset after a
checkpoint restore. `BloomDeduplicatorMiniClusterTest` is also in the ordinary
test suite: a JUnit-managed MiniCluster runs two TaskManagers and exercises both
fixed/scalable filters with `DROP` and `FORWARD`. It verifies tenant isolation,
exact output records, preserved payloads, and output from both parallel tasks.
It also exercises all three decisions with `MembershipResult` output, forces
network serialization with generic types disabled, and verifies the original
payload and membership flag. Operator tests cover pre-insertion observations,
false positives, timestamps, watermarks, mixed policies, and HEAP reset behavior.
`ResetPolicyMiniClusterTest` combines batch, tombstone, and custom hooks across
parallel tenants, including configuration serialization and immutable builder
snapshots. Hook tests also cover dropped-event counting, reset boundaries,
declared-type validation, and control-handle lifetime/thread restrictions.
Managed-state tests verify dirty-only writes, failed-write retries, checkpoint
isolation, reset recovery, aborted checkpoints, and scale-out/scale-in. The
integration suite deliberately fails a MiniCluster job after checkpoint completion
and verifies membership after restart. Native RocksDB cases run on Linux (CI or
local `act`); Windows/macOS report those cases as skipped and run managed-heap
recovery tests. RocksDB is a test dependency only, never bundled with Presence.
Run it from IntelliJ's test gutter or from the repository root:

```sh
mise run flink:test -- --tests '*BloomDeduplicatorMiniClusterTest'
```

Integration tests execute a local Flink job with parallel
tasks and object reuse. No external Kafka broker or S3 service is required.

Test reports are generated in this module's `build/reports/tests/`. Benchmarks
remain separate, manually invoked tasks.
