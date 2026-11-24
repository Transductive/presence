+++
title = "Observe Presence"
description = "Measure membership operations, filter growth, and occupancy with OpenTelemetry, then watch a local workload in Grafana."
weight = 7
+++

**`presence-otel`** adds optional OpenTelemetry metrics to **[`BloomFilter`][fixed]**
and **[`ScalableBloomFilter`][scalable]**. It wraps an existing filter without
copying or clearing membership. The core remains dependency-free; your application
supplies the **`Meter`** and owns its SDK, readers, exporters, and shutdown.

For a Flink job, use [Flink's metrics integration](@/docs/flink.md#enable-detailed-bloom-metrics)
and a Flink reporter. The standalone wrapper is not required in that path.

## Add the Module

The current `0.1.0-SNAPSHOT` is built from source and is **not on Maven Central**.
From the repository root, run `mise run publish:local`, then add:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.transductive:presence-otel:0.1.0-SNAPSHOT")
}
```

The adapter brings in Presence and the OpenTelemetry **API**. Configure an SDK
and exporter in your application using the
[OpenTelemetry Java guide](https://opentelemetry.io/docs/languages/java/).
The adapter installs no global telemetry, starts no threads, and exports nothing
by itself. A no-op meter is supported.

## Wrap a Filter

Create one wrapper for the lifetime of a logical membership filter. For example,
your application's setup can return a filter ready for its event loop:

```java
import io.opentelemetry.api.OpenTelemetry;
import io.transductive.presence.bloom.ScalableBloomFilter;
import io.transductive.presence.otel.OtelBloomFilter;
import java.time.Duration;

OtelBloomFilter<String> observeRecentEvents(OpenTelemetry telemetry) {
    var core = ScalableBloomFilter.forStrings(1_000, 0.01);
    var meter = telemetry.getMeter("io.transductive.presence");
    return OtelBloomFilter.wrap(core, meter, "recent-events",
            Duration.ofSeconds(30));
}
```

Use the returned **[`OtelBloomFilter`][wrapper]** for membership operations:

```java
members.put("event-123");
boolean maybePresent = members.mightContain("event-123");
```

The same `wrap` overloads accept a fixed-size `BloomFilter`. Every operation
delegates once, then records a successful call. A failed core operation propagates
its exception without incrementing the success counter. Counters start when the
wrapper is used; they do not reconstruct earlier activity.

Keep the wrapper open while the filter is in use. At retirement, refresh its
statistics and flush your application's SDK if you need a final sample, then
call `members.close()` to unregister its collection callback. Closing neither
clears the underlying filter nor closes the SDK. Later calls through the retired
wrapper throw `IllegalStateException`.

## Read the Metrics

| Instrument | Type | What It Tells You |
| --- | --- | --- |
| `presence.bloom.puts` | Counter | Successful put calls, including repeated values |
| `presence.bloom.lookups` | Counter | Successful lookups, split by approximate outcome |
| `presence.bloom.layers` | Observable up/down counter | Allocated layers, including an empty initial layer |
| `presence.bloom.bit_array.bytes` | Observable up/down counter | Backing bit-array payload bytes, including word rounding |
| `presence.bloom.elements.estimated` | Observable gauge | Occupancy-based estimate of represented distinct values |
| `presence.bloom.active_layer.fill_ratio` | Observable gauge | Fixed bit fill or the fullest slice in the active scalable layer |
| `presence.bloom.false_positive_probability.estimated` | Observable gauge | Occupancy-based false-positive probability estimate |

All instruments carry **`presence.filter.name`**, **`presence.filter.type`**
(`fixed` or `scalable`), and **`presence.hashing.strategy`**. Lookups also carry
**`presence.lookup.outcome`**: `possibly_present` or `definitely_absent`.

Choose a **bounded set of logical names**, such as `recent-events`. Each live
wrapper in a meter needs a distinct name. Event IDs, tenant IDs, and random UUIDs
would create unbounded metric series. For many per-key filters, aggregate at their
owner as the Flink integration does, rather than wrapping and labeling each key.

These distinctions matter when reading a dashboard:

- **Put calls are not element count.** Repeated puts count as operations without
  increasing estimated cardinality. Scalable filters can skip new inputs that
  already produce a possible match.
- **Bit-array bytes are not total heap.** They exclude object headers, references,
  keys, slice counters, hash workspaces, and telemetry. They also differ from
  serialized checkpoint size.
- **Possible presence is not a measured false positive.** Measuring false positives
  requires known-absent queries or another source of truth. The local smoke has
  that ground truth; the library generally does not.
- **Estimates assume uniform hashing.** Fixed cardinality estimates become
  unreliable near saturation and reach positive infinity when all bits are set.
  Scalable active-layer fill drops when a new layer is allocated.

## Refresh on the Owning Thread

Statistics are sampled when wrapping, then after the first successful operation
following the **30-second default interval**. Time is measured with a monotonic
clock. The operation that triggers a refresh performs the work synchronously
before returning. Idle filters keep their cached sample.

```java
members.refreshStatistics(); // Sample now; restart the automatic interval.
```

Fixed filters currently perform three bit-array cardinality scans per refresh.
Scalable filters inspect slice counters across their layers. **A longer interval
reduces sampling frequency, not the pause of the individual operation that samples.**
Single-pass or incremental sampling remains future work.

The filter and wrapper retain their **single-owner / external synchronization**
contract. Perform operations, refreshes, and close on the owning thread or under
the same external lock. SDK collection can run concurrently because it reads
cached, immutable values; it never scans the live filter. Moving refresh to an
unsynchronized background thread would violate this contract.

Layer and byte footprint updates are immediate when a wrapped insertion grows a
scalable filter. Direct calls to the original filter bypass operation counters.
After deliberate direct mutation, use `refreshStatistics()` for a full sample or
`refreshFootprint()` for size only; the latter does not restart the statistics
interval. Neither recovers bypassed counts.

## Watch a Local Workload

With Docker running Linux containers, mise provisions the local tools and starts
a **k3d** cluster containing the **OpenTelemetry Collector**, **VictoriaMetrics**,
and **Grafana**. The workload sends metrics through the Collector to VictoriaMetrics;
Grafana queries them for display:

```sh
mise run telemetry:smoke                  # Default: one minute
mise run telemetry:smoke -- --minutes 5
mise run telemetry:down                   # Stop the cluster; preserve its data
```

The command prints a live Grafana link **before the workload starts**. The
dashboard groups membership operations, footprint, capacity, measured accuracy,
and JVM memory. The smoke exports at one-second intervals. These are local
development settings; the library's default statistics interval stays 30 seconds.

For Flink's operator, checkpoint, and native state metrics:

```sh
mise run telemetry:flink                  # Host MiniCluster, managed heap backend
mise run telemetry:flink:rocksdb          # Linux Docker MiniCluster, RocksDB backend
```

Presence lookups remain on heap in both managed-state runs. RocksDB stores the
managed-state copy; its native metrics describe that backend, separately from
Presence's bit arrays and the JVM heap. A heap-backend run has no RocksDB samples,
so those panels show **No data**. Missing data is not proof of zero activity.

See the [local telemetry guide](https://github.com/Transductive/presence/blob/main/infra/telemetry/README.md)
for fixed localhost ports, prerequisites, cleanup, and how to read each dashboard.
The stack and timed workloads are explicit local tasks. CI runs smoke-unit tests
without starting Kubernetes or exporting telemetry.

## Measure Instrumentation Cost

```sh
mise run benchmark:otel
mise run benchmark:otel:smoke
```

The full JMH run compares raw filters, no-op-meter wrappers, active SDK recording,
and operations whose automatic statistics refresh fires. It covers fixed and
scalable filters at **1,000, 100,000, and 1,000,000** inserted values. Reports use
the actual run sizes and are generated locally, with browser links printed by
the task. Generated reports stay out of Git.

The forced-refresh case measures warm-cache average cost, not production tail
latency. SDK collection, export, and network traffic are outside the measurement.
The short smoke validates the harness; its scores are not performance evidence.
Read the [benchmark method](https://github.com/Transductive/presence/blob/main/presence-otel/README.md#measure-instrumentation-cost)
before comparing results.

## API Reference

Browse the [OpenTelemetry API reference]({{ get_url(path="api/otel/index.html") }})
or go directly to **[`OtelBloomFilter`][wrapper]** for wrapping, refresh, and
lifecycle methods. The website assembles the reference from the module's Javadoc
artifact. Only documentation is copied; the browser playground uses core Presence.

[fixed]: {{ get_url(path="api/io/transductive/presence/bloom/BloomFilter.html") }}
[scalable]: {{ get_url(path="api/io/transductive/presence/bloom/ScalableBloomFilter.html") }}
[wrapper]: {{ get_url(path="api/otel/io/transductive/presence/otel/OtelBloomFilter.html") }}
