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
| `.backend(BloomBackend.HEAP)` | Operator-owned memory, the only supported backend |

Capacity is per group, not per job or parallel task. More groups allocate more
filters; scalable layers can also grow. There is no global memory cap or eviction.
Building the configuration allocates no Bloom arrays. Filter sizing limits are
checked when a group's filter is created.

## Understand Recovery and Lifetime

HEAP membership belongs to the operator and is **excluded from checkpoints and
savepoints**. Restart, restore, and rescaling start with empty membership, so
previously seen IDs can be forwarded again. Configuring RocksDB, managed heap
state, or durable checkpoint storage does not change that behavior.

When checkpointing is enabled, the operator warns and continues. Configure
checkpointing before attaching it for the startup warning. If enabled later,
the first checkpoint triggers the warning instead; restore also warns.

The operator retains copied group keys and Bloom bits, not incoming events or
event IDs. Membership lasts until the task closes. There is no TTL or window
cleanup; placing this operator before or after a window does not make its heap
expire. Managed state and reference-set loading are outside this first version.

This operator does not enforce a delivery guarantee or select the event with
the largest timestamp per key. Use exact verification when a possible duplicate
must be confirmed before discarding an event.

## Observe and Verify

Metrics under `presence` report `possibleDuplicates`, `forwarded`, `dropped`, and
`bloomGroups`. A possible-duplicate count includes false positives.

```sh
mise run flink:build
mise run flink:test -- --tests '*BloomDeduplicatorMiniClusterTest'
mise run flink:integration
mise run flink:integration -- "-PflinkVersion=2.3.0"
```

The MiniCluster tests run two TaskManagers in JUnit, with no Kafka, S3, or Docker
requirement. They exercise keyed routing, both Bloom layouts, all decisions,
object reuse, and network serialization of membership results. Open the root
Gradle project in IntelliJ IDEA and run the same tests from the gutter.

The library compiles against Flink 2.0.0; CI tests runtimes 2.0.0 and 2.3.0 on
Java 21. Other 2.x versions are not individually verified. Flink's
[Java compatibility guidance](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/deployment/java_compatibility/)
describes Java 21 support as experimental; validate your own cluster and connectors.

Read the [Flink API reference]({{ get_url(path="api/flink/index.html") }}),
[BloomDeduplicator]({{ get_url(path="api/flink/io/transductive/presence/flink/BloomDeduplicator.html") }}),
or [MembershipResult]({{ get_url(path="api/flink/io/transductive/presence/flink/MembershipResult.html") }}).
