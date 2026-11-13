# Presence Flink

Approximate duplicate suppression for **keyed Java DataStreams on Flink 2.x**.
Uses the established DataStream API. Java 21 is required by Presence; configure
the Flink runtime to use Java 21 too. The separate experimental DataStream API V2,
Table/SQL, and Python APIs are outside this module.

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
the callback, which chooses `DROP` or `FORWARD`. The callback is mandatory; there
is no implicit dropping policy. `DROP` accepts that false positives can discard
genuinely new records. `FORWARD` also forwards actual repeats. Neither decision
means the event has been delivered to a sink. This is not an at-most-once or
exactly-once delivery guarantee, nor latest-event-time selection.

The callback runs synchronously on the operator thread. Keep it quick, do not
mutate the record, and do not capture non-serializable objects such as a live
database client. Callback failures fail the task; null decisions and null IDs
are errors. This version accepts string event IDs with the core library's UTF-8
semantics. It does not implicitly use `toString()` to encode arbitrary IDs.

## Choose Filter Sizing

| Option | Behavior |
| --- | --- |
| `.scalable()` | Default: start at 1,000 distinct IDs per group and grow |
| `.scalable(initialCapacity)` | Choose the first layer's expected capacity |
| `.fixed(expectedInsertions)` | Fixed storage; overfilling increases false positives |
| `.falsePositiveProbability(0.01)` | Default target: 1%; scalable budgets this across all layers |
| `.hashingStrategy(strategy)` | Choose a core strategy; default is Murmur3 |
| `.backend(BloomBackend.HEAP)` | Operator-owned memory; the only implemented backend |

Building configuration allocates no Bloom arrays. Actual sizing is subject to
the core filter's supported limits when a group is first created. The number
of groups and scalable layers can grow without a fixed total memory bound.

## Understand Heap Lifetime

HEAP means ordinary operator-owned memory, **not** Flink-managed heap state.
The adapter retains group keys and Bloom bits, but no incoming records or event
IDs. It copies retained group keys through Flink's configured serializer so
upstream object reuse cannot mutate stored map keys. Membership is released
when the task closes and starts empty after restart, restore, or rescaling.

This first keyed operator has no TTL, window ownership, or window cleanup.
Putting it before or after a window operator does not make its heap expire.
A future window-specific adapter must explicitly implement that lifecycle.
Managed state, snapshots, restoration, reference-set loading, and broadcasting
are not implemented here.

## Checkpoint Awareness

Configure Flink checkpointing **before** `applyTo` for a startup warning. When
checkpointing is enabled, each operator instance warns that HEAP membership
is excluded from checkpoints and savepoints. S3, filesystem, and JobManager
checkpoint storage all have the same limitation. The configured Flink state
backend is independent of both the checkpoint destination and our HEAP choice.

Only a storage description (such as `s3 checkpoint storage`) is logged, never a
bucket, full URI, credentials, or query parameters. The diagnostic does not
contact the checkpoint destination or claim to verify its durability.

If checkpointing is enabled after the operator is attached, the first actual
checkpoint triggers the warning instead. Restore also triggers a warning.
Checkpoint callbacks are used only for this diagnostic; no Bloom state is
registered or saved. The job continues, and the warning is emitted at most
once per operator instance per task attempt.

Metrics under the `presence` group expose `possibleDuplicates`, `forwarded`,
`dropped`, and `bloomGroups`. A possible duplicate is not an exact duplicate
count or a measured false-positive count.

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
Run it from IntelliJ's test gutter or from the repository root:

```sh
mise run flink:test -- --tests '*BloomDeduplicatorMiniClusterTest'
```

Integration tests execute a local Flink job with parallel
tasks and object reuse. No external Kafka broker or S3 service is required.

Test reports are generated in this module's `build/reports/tests/`. Benchmarks
remain separate, manually invoked tasks.
