# Presence OpenTelemetry

Optional metrics for Presence's fixed-size and scalable Bloom filters. Java 21.

Read the website's [observability guide](https://transductive.github.io/presence/docs/observability/)
or browse the [OpenTelemetry API reference](https://transductive.github.io/presence/api/otel/index.html).

`presence-otel` wraps a filter and records operations through the
OpenTelemetry **API**. The core stays dependency-free. Your application supplies
the `Meter` and owns its SDK, readers, exporters, and shutdown lifecycle. This
module starts no threads, configures no global telemetry, and exports nothing
by itself. Passing a no-op meter works without installing an SDK.

## Get Started

Build this source snapshot with `mise run publish:local`, then add:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.transductive:presence-otel:0.1.0-SNAPSHOT")
}
```

The adapter transitively supplies `presence` and the OpenTelemetry API. It does
not bring in the SDK or exporters. Configure those in your application using
the [OpenTelemetry Java documentation](https://opentelemetry.io/docs/languages/java/).
This snapshot has not been published to Maven Central.

```java
import io.opentelemetry.api.OpenTelemetry;
import io.transductive.presence.bloom.ScalableBloomFilter;
import io.transductive.presence.otel.OtelBloomFilter;

// OpenTelemetry is supplied by your application, for example as a method argument.
void useMembership(OpenTelemetry openTelemetry) {
    var meter = openTelemetry.getMeter("io.transductive.presence");
    var core = ScalableBloomFilter.forStrings(1_000, 0.01);

    // Keep the wrapper for the same lifetime as this logical membership filter.
    try (var members = OtelBloomFilter.wrap(core, meter, "recent-events")) {
        members.put("event-123");
        boolean maybePresent = members.mightContain("event-123");
        // true means possibly present; verify against your source of truth if needed.
    }
}
```

Use `BloomFilter.forStrings(1_000, 0.01)` with the same `wrap` API for a fixed-size
filter. Existing filters can be wrapped without copying or clearing membership.
Operation counters begin when the wrapper is used; prior operations are not reconstructed.

## How Delegation Works

```text
Application -> OtelBloomFilter -> BloomFilter or ScalableBloomFilter
                     |
                     +-> OTel operation counters -> application-owned SDK

SDK collection thread -> cached footprint -> OTel size instruments
```

The wrapper calls the underlying filter exactly once for each `put` or
`mightContain`, then records the successful operation. There is no reflection,
agent requirement, dynamic proxy, or extra membership lookup. Exceptions from
the filter propagate and do not increment the success counters.

Calls directly to `core` bypass the counters. Prefer using the wrapper for all
membership operations. If deliberate direct mutation grows the original filter,
call `members.refreshFootprint()` on its owning thread to refresh the size metrics.
This does not recover bypassed operation counts.

## Metrics

| Instrument | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `presence.bloom.puts` | Counter | `{operation}` | Successful `put` calls, including repeated values; not distinct inserted IDs |
| `presence.bloom.lookups` | Counter | `{operation}` | Successful lookups, split by approximate outcome |
| `presence.bloom.layers` | Observable up/down counter | `{layer}` | Current allocated layers; an empty filter still has one |
| `presence.bloom.bit_array.bytes` | Observable up/down counter | `By` | Current backing bit-array payload, including storage-word rounding |
| `presence.bloom.elements.estimated` | Observable gauge | `{element}` | Sampled estimate of represented distinct elements from occupancy |
| `presence.bloom.active_layer.fill_ratio` | Observable gauge | `1` | Sampled fixed bit fill or scalable active-layer maximum slice fill |
| `presence.bloom.false_positive_probability.estimated` | Observable gauge | `1` | Sampled occupancy-based probability estimate |

All instruments have `presence.filter.name`, `presence.filter.type` (`fixed` or
`scalable`), and `presence.hashing.strategy` (the `HashingStrategy` enum name).
Lookups also have `presence.lookup.outcome`: `possibly_present` or
`definitely_absent`. A possible positive is not an observed false positive or
proof of duplication.

Choose a **bounded set of logical filter names**. Each live wrapper in a meter
must have a distinct logical name; multiple wrappers with identical attributes
would produce conflicting footprint observations. Never name a filter after an
event, tenant, UUID, or other unbounded identifier. Many per-key filters should
be aggregated at their owner, as in the Flink adapter, rather than individually
wrapped and labelled.

Bit-array bytes exclude object headers, references, hash workspaces, per-slice
counters, encoders, telemetry, and application-held keys. They are neither total
retained heap nor serialized checkpoint bytes. This adapter does not collect
process/JVM metrics or time individual operations.

Element, fill, and probability estimates are sampled at wrapping and refreshed
automatically after the first successful `put` or `mightContain` following a
**30-second interval**. The check uses monotonic `System.nanoTime()`, not wall
clock time. Sampling runs synchronously on the **caller's owning thread**, before
that operation returns. Fixed filters scan their bit arrays; scalable filters
read slice counters. Most operations only check the interval. The operation
that refreshes pays the sampling cost; the wrapper adds no thread or lock.

**Refresh can delay the membership hot path.** A longer interval amortizes the
work but does not shorten the individual operation that recalculates statistics.
Fixed statistics currently perform three cardinality scans of the same bit
array. Single-pass, incremental, or other sampling approaches are future work;
the local instrumentation benchmark is the baseline for evaluating them. Moving
these live-filter reads onto a background thread or SDK callback would violate
the core's ownership contract. Sampling publishes cached values for collection.

Configure a different positive interval with the same overload for either filter:

```java
var members = OtelBloomFilter.wrap(core, meter, "recent-events",
        java.time.Duration.ofSeconds(5));

members.put("event-123"); // Refreshes automatically when due.
members.refreshStatistics(); // Optional: refresh now on the owning thread.
```

Manual refresh restarts the automatic interval when it completes. An **idle
filter does not refresh** until its next successful operation or a manual call;
SDK collection only reads the latest published sample. Failed operations do not
refresh or postpone a due refresh. Direct core mutations remain outside operation
counters, so explicitly refresh after them if immediate statistics are needed.

Before the application's final SDK export, call `members.refreshStatistics()`,
then flush the SDK while the wrapper remains open. Closing unregisters callbacks;
it does not perform a final refresh or flush. `refreshFootprint()` remains a
cheaper size-only hook and does not restart the statistics interval. Layer-growth
footprint updates still happen immediately regardless of the configured interval.

These are uniform-hashing models, not exact counts or measured false positives.
Repeated puts do not increase estimated cardinality. Scalable can skip a new
input that is already a possible match. Fixed cardinality estimates deteriorate
near saturation and become positive infinity when all bits are set. Fixed fill
ranges from 0 to 1; scalable active fill rolls over before exceeding 0.5.

## Ownership and Collection

The wrapper preserves the core's **single-owner / external synchronization**
contract. It does not make the filter thread-safe. Serialize membership calls,
wrapping, refreshing, and closing on the owning thread or under a shared lock.

Size metrics are initialized at wrapping and updated when insertions grow a
scalable filter. The collection callback reads an immutable footprint published
through a volatile reference. It never traverses a mutable filter, scans its bit
arrays, or invokes its encoder. Footprint publication allocates a small object
on growth or an automatic/manual refresh, not on every lookup or insertion.

`close()` unregisters the collection callback and retires the wrapper. Later
wrapper operations throw `IllegalStateException`; the original filter remains
usable. Recorded counters follow the SDK's normal export/aggregation lifecycle
and are not erased by closing. Closing the wrapper does not close the application
SDK. Always close wrappers when retiring filters, even though the callback is
deliberately structured not to retain the underlying filter.

Operation instrumentation still has a cost: delegation and SDK counter recording.
Attributes are prepared once during wrapping. A no-op meter avoids SDK recording
work, but the wrapper itself remains. Use the raw core when telemetry is unwanted;
do not assume instrumented and uninstrumented throughput are identical.

## With Flink

`presence-flink` already records metrics through Flink's `MetricGroup`. Configure
Flink's [OpenTelemetry metric reporter](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/deployment/metric_reporters/#opentelemetry)
to export those metrics. `presence-otel` is for direct, standalone filter usage;
it is not needed to export the Flink adapter's existing metrics. Avoid instrumenting
the same operation through both paths.

For aggregate Bloom size, layer, occupancy, and probability estimates, opt in
with `.metrics(BloomMetrics.DETAILED)` on the Flink builder. Its configurable
30-second sampling runs on the operator thread; reporters only read a cached
sample. See [Flink metrics](../presence-flink/README.md#detailed-bloom-metrics)
for scope, costs, the manual hook, and reporter configuration.

## Development

```sh
mise run otel:build
mise run otel:test
```

The root `build`, `check`, and CI tasks include this module. Tests use a real
OpenTelemetry SDK with an in-memory reader; no collector or network endpoint is
needed. SDK dependencies belong only to tests, local smoke tooling, and benchmarks,
not the published library.

## Measure Instrumentation Cost

```sh
mise run benchmark:otel                  # Full JMH run and offline browser report
mise run benchmark:otel:smoke            # Harness validation only
mise run benchmark:otel -- -PbenchmarkSizes=1000,10000,100000
```

The full run compares raw operations, a no-op-meter wrapper, an active SDK
wrapper between refreshes, and an SDK wrapper whose automatic refresh fires on
every call. It covers present queries, absent queries, and repeated puts for
fixed and scalable filters at **1,000, 100,000, and 1,000,000** inserted values.
Both use Murmur3 128 and a 1% false-positive target. Fixed capacity equals N;
scalable begins at 1,000. Repeated puts hold occupancy steady; fresh insertion,
layer growth, and first metric-series registration are outside this experiment.

Steady paths use the default 30-second interval and the real monotonic clock;
the interval is reset outside each short JMH iteration. To exercise automatic
refresh deterministically, a benchmark-only clock offset advances 30 seconds
before each refresh-path operation. Both real `System.nanoTime()` reads still
execute; the offset increment and additions are included in its score. No
production API or default behavior is changed. These amplified refresh scores
measure **warm-cache average cost**, not a cold scan, production p99 latency, or
an actual 30-second workload. Fixed statistics currently traverse the bit array
three times; scalable statistics use existing slice counters.

Each case runs on one owner thread, with two JVM forks, three 500 ms warmups,
five 500 ms measurement iterations, and JMH's GC allocation profiler. The SDK
has a registered in-memory reader so counters really aggregate; collection,
exporters, networking, and contention are excluded. The bounded pool contains
8,192 prebuilt ASCII queries spanning the inserted set or a disjoint absent set.
False positives remain in the absent timings; input construction is not timed.

The task first verifies the fixtures, then prints browser links to the generated
HTML and raw JSON in `build/reports/benchmarks/`. Reports are rendered from a
Pebble template using the actual run sizes, include confidence errors and
allocated B/op, and stay ignored by Git. JMH and report dependencies belong only
to the benchmark source set. Benchmarks and their fixture tests are explicit
local tasks; normal `build`, `check`, and CI do not run them.

## Watch Locally

With Docker running Linux containers:

```sh
mise run telemetry:smoke                   # Live link, then a one-minute workload
mise run telemetry:smoke -- --minutes 5
mise run telemetry:down                    # Preserve metrics and stop the cluster
```

The [local telemetry guide](../infra/telemetry/README.md) covers fixed localhost
ports, the operations dashboard, IntelliJ, and cleanup. The smoke source set owns
its SDK, exporter, and JOL dependencies; none enter the published adapter.
