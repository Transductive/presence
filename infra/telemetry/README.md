# Local Telemetry

Run a bounded Presence workload on your computer and watch its operations
overview in Grafana. Infrastructure runs in k3d; Java runs on the host, including
from IntelliJ. No port-forward process is required.

```text
Host Java / presence-otel -- OTLP --> OpenTelemetry Collector
                                      |
                                      v
                                VictoriaMetrics <-- Grafana
                                 (k3d / Helm)        (browser)
```

## Run the Smoke

Start Docker with **Linux containers**, then run from the repository root:

```sh
mise run telemetry:smoke
```

Mise installs pinned k3d, Helm, and kubectl tools on demand. The command prints
the **live, run-specific Grafana link first**, creates or resumes the dedicated
cluster, provisions its charts and dashboard, and exercises the filters for
**one minute**. First startup also downloads container images. Setup and Gradle
compilation do not consume workload time. Subsequent runs reuse the cluster.

```sh
mise run telemetry:smoke -- --minutes 5
mise run telemetry:smoke -- --minutes 0.5
```

Duration must be positive and at most 1,440 minutes. The workload is paced at
roughly 200 cycles/second, not designed to measure maximum throughput. Each
cycle inserts a new ID, repeats an insertion, queries an inserted ID, and queries
a never-inserted ID in both filters. Both start at **N = 1,000**, with a **1%**
false-positive sizing target. Scalable adds layers; fixed eventually saturates.

Exports run every **1 second**. The Collector batches for at most one second;
Grafana refreshes every second. This is **OTLP push**, not Prometheus scraping.
End-to-end visibility can lag due to batching and storage.

This is an **eyes-on-glass exercise**. It checks inserted-ID queries for false
negatives and flushes the SDK at shutdown, but does not assert Grafana contents
or query counts. After completion, it prints another dashboard link with the
exact run time range. Use that link when live charts have scrolled past the run.
Summaries and completed-run links are saved under ignored
`build/reports/telemetry/<run-id>/`.

## Flink Smoke

```sh
mise run telemetry:flink
mise run telemetry:flink -- --minutes 5
mise run telemetry:flink:rocksdb             # Linux container, native RocksDB metrics
mise run telemetry:flink:rocksdb -- --minutes 5
```

This uses the same telemetry cluster, with a separate **Presence Flink** dashboard.
The command prints its live link before setup and saves a completed-run link.
The default workload duration is **one minute**, excluding startup, seeding, and
a three-second final export grace period.

A host-local **MiniCluster** runs **one JobManager, one TaskManager, and two slots**.
Four branches compare fixed/scalable filters with HEAP/MANAGED_STATE. Each branch
has parallelism two and receives the same inputs across 16 stream keys, starting
with capacity 1,000 per key. Each source cycle emits a new ID and an immediate
repeat; three quarters of repeats are dropped and one quarter are forwarded with
membership. Known-absent positives are forwarded so the sink can count them in
the terminal summary. Every 3,000 cycles a tombstone clears one rotating key.

**Detailed statistics and OTel export run every second.** The Collector converts
Flink's delta counters into cumulative counters before sending them to
VictoriaMetrics. Existing cumulative standalone metrics pass through unchanged.
The native Flink reporter exports the Presence metrics; `presence-otel` is not
needed in this job. The runner uses Flink 2.0 with its matching OTel SDK, confined
to the smoke source set. Normal library consumers do not acquire these runtimes.

The host-local command uses **`HashMapStateBackend`**, with filesystem checkpoints
every two seconds. **`telemetry:flink:rocksdb`** uses `EmbeddedRocksDBStateBackend`
and incremental checkpoints instead. Expect HEAP branches to log the checkpoint warning: their membership
is intentionally not checkpointed. The job has no restart strategy; its generator
does not snapshot input position. This demonstrates checkpoint writes, not recovery
or exactly-once source behavior. Separate integration tests cover restoration.

Read the dashboard from cluster/job health and task flow through membership,
capacity/sampling, decisions, checkpoints, native RocksDB state, and JVM.
There is one visible run selector; the **State Backend** card identifies the run.
Fixed is
gray, scalable is dark, and managed-state lines are dashed.

- Each branch has 16 keyed Bloom groups distributed over two subtasks: **64 groups
  across the job**, not one filter per subtask. Scalable groups may own several layers.
- Payload bytes exclude object headers, keys, maps, workspace, and Flink overhead.
  Estimated memberships sum across keys; risk and fill use the worst resident key.
- Refresh duration is the latest synchronous scan on the operator thread, not a
  latency percentile. Lower refresh intervals add sampling overhead.
- Layer writes/bytes describe writes to MapState, not storage IOPS or total checkpoint
  bytes. Layer reads should remain zero in this run because it never restores.
- JobManager and TaskManager share **one JVM**. Charts use only TaskManager JVM
  readings, avoiding double-counting. JVM memory includes all four branches and Flink.
- Task slots describe physical execution capacity, not a count of keyed Blooms or
  operators. Task busy/backpressure charts include chained operators and the verifier
  sink; record rates are per branch, and fan-out must not be summed as unique input.
- Missing samples remain **No data**. No Grafana assertions run. The sink does check
  expected fresh/repeated outputs and fails if an immediate repeat is a false negative.

The MiniCluster stops at completion; the k3d telemetry stack stays up for review.
Finite-job teardown retires operator metrics, so use the saved time-range link to
review history. For IntelliJ, start `telemetry:up` and run
`:presence-flink:telemetrySmoke` with optional `-PsmokeMinutes=5`.

### RocksDB Native State

The RocksDB command builds a local runner bundle, then runs the same MiniCluster
job in a disposable **Linux Docker container** on the telemetry cluster's network.
This avoids host-native JNI differences on Windows/macOS. The Temurin Java 21
image is pinned by digest in `versions.properties`. The container has a 2 GiB limit,
four CPUs, a 1 GiB Java heap, and 256 MiB of configured Flink managed memory.
RocksDB uses Flink's managed memory; that budget is not a cap on every native allocation.

Only the assembled JAR directory (read-only) and that run's result directory are
mounted. No additional host ports are published. Checkpoints are local files under
the ignored report directory, not a production remote store. The runner container
is removed on completion/failure, while the telemetry cluster retains chart history.

The native sections show memtables, SST reader memory, shared block cache usage and
capacity, live SST files, logical writes, compaction output/backlog, pending flushes,
writer stalls, cache hits/misses, write stops, and background errors. These come from
Flink's native RocksDB metrics, not estimates of Presence's bit arrays.

- **Native memory is outside Java heap.** Do not treat JVM heap as total process RSS.
- Column-family metrics use a `column_family` label. Memtable/SST-family sizes can
  be summed across families; shared cache observations cannot. Cache panels take
  a maximum over families per operator/subtask and leave the lines separate because
  operators in the same slot can share the cache. DB-wide error/flag observations
  also use a maximum over families instead of counting duplicates.
- Native statistics tickers are cumulative gauges; rate panels use their changes
  over time. Logical bytes written are not physical disk throughput. Compaction
  writes and checkpoint bytes are separate signals.
- Even HEAP membership branches can have Flink timer state in RocksDB. Their native
  backend overhead does not imply that Presence HEAP membership became checkpointed.
- Flink's native metric views typically update every five seconds. One-second OTLP
  export/dashboard refresh does not make the underlying native sample newer. Brief
  flush/compaction activity can occur between samples; small runs may show no backlog
  or stalls. Native collection adds overhead and is enabled only in this local variant.
- HashMap runs show **No data** in RocksDB panels; absent metrics are not coerced to zero.

See [Flink state backends](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/ops/state/state_backends/)
and [RocksDB metric options](https://nightlies.apache.org/flink/flink-docs-release-2.0/docs/deployment/config/#rocksdb-native-metrics).

## Read the Standalone Overview

Choose a **Smoke Run** and read down the page. It is the only visible variable;
fixed/scalable comparisons appear together and JVM pools expand automatically.

The page starts with scalable health, then groups membership and memory, lookup
quality, workload activity, JVM heap and generations, garbage collection, and
non-heap memory/buffers. Short titles, consistent grayscale lines, and section
headers keep the overview readable. Panel information icons explain the details.

| Signal | Meaning |
| --- | --- |
| Core reachable heap | JOL object-graph bytes including headers, arrays, workspaces, and shared reachable objects; excludes the OTel wrapper/SDK. Not exclusive retained heap; sizes across filters are not additive. |
| Bit-array payload | Exact allocated array payload bytes, without object overhead. |
| Estimated elements | Uniform-hashing occupancy estimate. Repeated puts do not raise it. Scalable can skip new IDs that were possible matches. Fixed estimates become unreliable near saturation and can reach infinity. |
| Distinct input IDs | Exact generator count: seed IDs plus new event IDs. A general Bloom cannot know this count exactly. |
| Allocated layers | Storage layers, including the initial layer. |
| Active fill | Fixed: overall bit occupancy, saturating at 100%. Scalable: fullest slice in the active layer, rolling over at 50%. |
| Inputs / initial capacity | Workload growth relative to N=1,000; not a hard capacity ceiling for scalable. |
| Estimated false-positive probability | Occupancy model; scalable sums layer probability estimates. |
| Measured false positives | Positive answers to never-inserted IDs. Ordinary possible matches alone cannot establish false positives. |
| JVM heap context | Whole smoke JVM used/committed/maximum heap from `MemoryMXBean`, including both filters, SDK, JOL, and application allocations. Not container RSS. |

The JVM sections expand that process view:

- Repeated heap-pool charts show used, committed, reported maximum, and peak
  usage. Names come from `MemoryPoolMXBean`; G1 commonly exposes Eden, Survivor,
  and Old Gen. Other collectors can expose a different pool layout.
- Post-GC pool usage shows the last collection snapshot where supported, not
  current occupancy. Used memory can include unreachable objects awaiting
  collection. The smoke does not request a forced GC.
- Non-heap pools expose Metaspace, Compressed Class Space, and code heaps where
  supported. Direct/mapped buffer memory, logical capacity, and counts are
  shown separately; these are not total native memory or resident set size.
- GC charts show cumulative collections, cumulative elapsed seconds, and their
  per-second rates by actual collector. JVM-reported elapsed collection time is
  approximate, not a stop-the-world pause histogram or CPU utilization.

These panels describe the whole process. Every reported heap pool gets its own
chart, with no pool selector required. Pool maxima can be
undefined and committed sizes can change; undefined values are omitted, never
displayed as zero. Pool maxima must not be added as reserved shares of `-Xmx`.
The one-minute workload may perform few or no collections. Historical runs
made before this instrumentation have no JVM-detail samples.

Cumulative false-positive counters start at **zero**. Missing telemetry stays
**No data**, not zero. Rate charts need multiple samples. A completed workload
stops exporting; use its completed-run time-range link.

The smoke overrides the adapter's **30-second default** with a **one-second
automatic statistics refresh interval**, checked after successful operations.
It uses the manual refresh hook after seeding and before the final SDK export.
The owner thread separately samples heap diagnostics once a second and publishes
immutable values for SDK collection. JOL graph walks belong only to this local smoke,
not the published library. The Gradle task enables JOL's Java 21 attach flags
and requires `Instrumentation` object sizing. Sampling has overhead; this is
not a latency benchmark or production sizing guarantee.

## Endpoints and Lifecycle

All published ports bind to **127.0.0.1**:

| Service | Host Endpoint |
| --- | --- |
| Grafana overview | [Presence Operations](http://localhost:3000/d/presence-smoke/presence-smoke?theme=light) |
| Flink overview | [Presence Flink](http://localhost:3000/d/presence-flink-smoke/presence-flink?theme=light) |
| OTLP/HTTP metrics | `http://localhost:4318/v1/metrics` |
| OTLP/gRPC | `localhost:4317` |
| VictoriaMetrics UI | [VMUI](http://localhost:8428/vmui/) |
| Kubernetes API | `https://127.0.0.1:6447` |

Port conflicts are reported before creating or resuming a cluster. The Collector
accepts metrics; this stack does not provision trace or log storage.

```sh
mise run telemetry:up       # Provision/resume infrastructure without a workload
mise run telemetry:status   # Inspect cluster, pods, services, storage
mise run telemetry:down     # Stop containers; keep metrics for the next up
mise run telemetry:reset    # Delete this cluster AND its local metrics data
```

The dedicated `presence-telemetry` cluster has a 4 GiB server container limit.
VictoriaMetrics uses a 2 GiB local-path volume with one-day retention. Retention
is age-based, not a hard disk-space cap. Grafana's dashboard and datasource are
provisioned from Git and need no Grafana persistence. Anonymous access is
read-only, intended for these loopback-only endpoints.

Kubeconfig credentials and Helm caches live under ignored `.local/telemetry/`.
Commands explicitly select this kubeconfig and context; they do not switch
your default Kubernetes context. Commands refuse to modify a same-named cluster
without the Presence ownership label. Tool versions live in mise tasks; Kubernetes
and chart pins live in `versions.properties`.

## IntelliJ and Troubleshooting

Run `mise run telemetry:up`, then the Gradle task `:presence-otel:telemetrySmoke`
from IntelliJ. Pass `-PsmokeMinutes=5` for a longer run. Gradle applies the JDK,
classpath, and JOL agent flags. For direct main-class execution, use the `smoke`
classpath, VM flags `-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading`,
and three program arguments: minutes, a run UUID, and an output properties path.

If charts are empty, select the current run and a time window containing it.
Port 4318 is an ingestion endpoint, not a webpage; browsing it may return 404.
Inspect status and Collector logs without changing your default context:

```sh
mise run telemetry:status
mise exec kubectl@1.37.2 -- kubectl --kubeconfig .local/telemetry/kubeconfig.yaml --context k3d-presence-telemetry -n presence-telemetry logs deployment/presence-collector --tail=80
```

Normal build/CI runs adapter and smoke **unit tests** without Docker or network
services. Infrastructure and the timed workload are opt-in.

References: [VictoriaMetrics OTLP ingestion](https://docs.victoriametrics.com/victoriametrics/integrations/opentelemetry/),
[Collector Helm chart](https://opentelemetry.io/docs/platforms/kubernetes/helm/collector/),
[JOL](https://github.com/openjdk/jol).
