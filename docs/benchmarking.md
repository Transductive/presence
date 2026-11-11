# Benchmarking Presence

The benchmark suite compares `BloomFilter<String>` with Java 21's `HashSet<String>`
and `HashMap<String, Boolean>`. Lookup measures Murmur3 (the default), rapidhash
v3, rapidhash Nano v3, Komihash 5.34, and the SHA-256 fallback; construction uses
the default strategy.
It uses [OpenJDK JMH](https://github.com/openjdk/jmh)
1.37, with JMH's annotation processor generating the harness. Benchmark
dependencies belong to a separate Gradle source set and do not enter Presence's
runtime dependencies, published POM, or library JAR.

## Commands

Run these from the repository root after `mise trust` and `mise install`:

```sh
mise run benchmark
mise run benchmark:smoke
mise run benchmark:flamegraph
mise run benchmark:allocation
mise run benchmark:misses
mise run benchmark:prefilter
mise run benchmark:heap
```

| Command | Workload | Harness settings |
| --- | --- | --- |
| `benchmark` | Lookup and construction; N=1,000 / 10,000 / 100,000 / 500,000 / 1,000,000; lookup hits=0% / 50% / 100% | 2 forks, 3 × 1-second warmups, 5 × 1-second measurements |
| `benchmark:smoke` | Same operations at N=1,000 | 1 fork, 1 × 100 ms warmup, 1 × 200 ms measurement |
| `benchmark:flamegraph` | Lookup only; the same five sizes; 50% hits | 1 fork, 3 × 1-second warmups, 5 × 1-second measurements, JFR enabled |
| `benchmark:allocation` | Lookup only; N=1,000; 50% hits; ASCII, Latin-1, Unicode, malformed UTF-16, and long Unicode | Calling-thread allocation audit, then 2 forks, 3 × 1-second warmups, 5 × 1-second measurements |
| `benchmark:misses` | rapidhash v3, HashSet, HashMap; all five N; 0% hits; HOT and BROAD query pools | 30 cases, 2 forks, 3 × 1-second warmups, 5 × 1-second measurements |
| `benchmark:prefilter` | Direct exact lookup vs. Bloom then exact; all five N; 0% / 50% hits; downstream CPU tokens=0 / 256 | 40 cases, same full timing settings |
| `benchmark:heap` | Bloom, HashSet, HashMap, and Bloom + HashMap; all five N | Separate JOL object-graph measurement; 2 GiB maximum analysis heap; no timing claims |

All timed runs use a single benchmark thread, Java 21, a 256 MiB initial fork
heap, a 512 MiB maximum by default, and JMH's GC profiler. The full suite has 120 benchmark cases
across its parameters; profiling has 35. Allow at least 32 minutes for the full
suite or 4 minutes 40 seconds for profiling, plus JVM startup and dataset setup.
The allocation comparison has 35 cases and takes at least 9 minutes 20 seconds,
plus its separate allocation audit and setup.
The focused miss suite needs at least 8 minutes; the prefilter suite needs at
least 10 minutes 40 seconds, before JVM startup and dataset setup. Changing
parameters multiplies cases, so use focused includes when adding dimensions.
Smoke mode validates execution
and report generation; it is not a performance measurement suitable for claims.

Normal `build`, `test`, and `ci` commands do not execute timed benchmarks.
`build` and `ci` compile the harness and run its fixture tests. To run just those
checks, use `mise run gradle -- benchmarkTest`.

## Open the reports

Successful runs finish with a browser URL and local path, for example:

```text
Presence benchmark review is ready.
Open this file in your browser (Ctrl+click if your terminal supports file links):
  file:///.../build/reports/benchmarks/<run-id>/index.html
```

Open `index.html` directly in your browser; no server or external website is
needed. Each run has a separate directory containing:

- `index.html`: a lookup scaling overview ordered by N, detailed comparison
  tables, relative timing bars, and profile links, in black, white, and gray.
- `results.json`: complete JMH results, parameters, fork settings, JVM details,
  raw iteration values, and secondary metrics.
- `run.txt`: selected workload and basic environment information.
- Heap runs use `heap.csv` instead of JMH JSON, and record JVM object-layout
  details alongside CPU, OS, and JVM information in `run.txt`.
- Allocation runs also include `allocation-check.txt`: per-strategy, per-input
  calling-thread counters, including pre-encoded byte arrays.
- For profiling runs, `jfr/profile-001/profile.jfr`, `flamegraph.html`, and
  `benchmark.txt` for each case. Short numbered directories avoid Windows path
  length problems; the report and metadata preserve the full benchmark label.

`clean` removes the entire build directory, including these reports. Copy any
run you want to preserve before cleaning. Failed runs may leave partial raw
artifacts, but do not print a success link or generate a completed report.

## Change the workload

Arguments after `--` are Gradle properties:

```sh
# Run the full harness at the smallest dataset.
mise run benchmark -- -PbenchmarkSizes=1000

# Compare all five Bloom strategies and JDK lookups only at N=1,000.
mise run benchmark -- -PbenchmarkSizes=1000 '-PbenchmarkInclude=.*MembershipBenchmark.*'

# Compare mixed lookups across all five default sizes, without construction.
mise run benchmark -- -PbenchmarkHits=50 '-PbenchmarkInclude=.*MembershipBenchmark.*'

# Profile mixed and absent lookups at two sizes.
mise run benchmark:flamegraph -- '-PbenchmarkSizes=1000,10000' '-PbenchmarkHits=0,50'

# Time specific input kinds (the allocation task always audits all built-in kinds).
mise run benchmark:allocation -- '-PbenchmarkInputs=ASCII,UNICODE'

# Measure construction only.
mise run benchmark -- '-PbenchmarkInclude=.*ConstructionBenchmark.*'

# Profile only the default Murmur3 Bloom lookups.
mise run benchmark:flamegraph -- '-PbenchmarkInclude=.*MembershipBenchmark\.bloom$'

# Compare newly constructed, initially uncached strings with a broad working set.
mise run benchmark:misses -- -PbenchmarkWorkingSets=BROAD -PbenchmarkQueryModes=FRESH

# Extend absent lookups to five million stored keys, with room for the larger fixtures.
mise run benchmark:misses -- '-PbenchmarkSizes=1000,5000000' -PbenchmarkWorkingSets=BROAD -PbenchmarkMaxHeap=3g

# Focus prefilter tuning on N=1,000, absent queries, and selected downstream costs.
mise run benchmark:prefilter -- -PbenchmarkSizes=1000 -PbenchmarkHits=0 '-PbenchmarkDownstreamTokens=0,64,256'

# Validate new benchmark wiring quickly before a full timed run.
mise run benchmark:smoke -- '-PbenchmarkInclude=.*PrefilterBenchmark.*' -PbenchmarkHits=0

# Compare graph footprints for two key representations.
mise run benchmark:heap -- '-PbenchmarkInputs=ASCII,UNICODE'
```

`benchmarkSizes` is sorted and deduplicated, and always includes the N=1,000
baseline even when omitted from an override. Values below 1,000 are rejected.
`benchmarkHits` accepts 0, 50, and 100. Construction does not use the hit-ratio
parameter. `benchmarkInclude` is a JMH regular expression selecting benchmark
methods. A pattern matching no benchmarks fails rather than producing an empty
success report. Report rows, hit-ratio sections, and implementation columns come
from the actual JMH results, so a single-size or filtered run only shows what ran.
`benchmarkInputs` accepts `ASCII`, `LATIN1`, `UNICODE`, `MALFORMED`, and
`LONG_UNICODE`; ordinary runs default to `ASCII`. Report tables separate input
kinds. The long case adds a 2,560-byte UTF-8 prefix to every key and query, so
it crosses many workspace boundaries. Large N values with long strings require
more heap for the input data and collections.

`benchmarkWorkingSets` accepts `HOT` and `BROAD`; `benchmarkQueryModes` accepts
`CACHED` and `FRESH`. Defaults remain HOT/CACHED except the focused miss task,
which selects both working sets. `benchmarkDownstreamTokens` accepts nonnegative
integers and applies only to `PrefilterBenchmark`. `benchmarkStrategies` selects
its Bloom hash strategy (default `RAPIDHASH_V3`). Heap reports currently use
rapidhash v3. All scaling tables are grouped by the actual workload dimensions;
fresh strings, broad pools, different input kinds, and downstream costs never
share a timing row.

`benchmarkMaxHeap` changes the maximum heap of each timed JMH fork; it does not
change Gradle's heap or the separate heap-analysis task. It defaults to `512m`
and accepts positive integer `m` or `g` values, at least the fixed `256m` initial
heap. The validated fork arguments are saved in `run.txt` and JMH's raw results.
For N=5,000,000, BROAD uses 8,388,608 queries, so fixture memory is substantially
larger than the measured membership structures alone. The example runs six
cases (three implementations at N=1,000 and N=5,000,000), with normal full-run
forks and measurement settings. Keep heap settings aligned when comparing runs.

## What is measured

**Lookup:** one membership query against a structure populated during trial
setup. The query cursor and array read are included equally for all seven
implementations, including all five Bloom strategies. HOT uses a deterministic
shuffled pool of 4,096 queries. BROAD uses the next power of two at least
max(65,536, N): 65,536 / 65,536 / 131,072 / 524,288 / 1,048,576 queries for the
five default sizes. The shuffled pool is read sequentially and repeats.
This increases the query working set without claiming cold caches or random
DRAM access. Both modes hold N distinct stored values.
Hits have equal content but different object identity and backing storage from
the stored strings. CACHED query and stored string hash codes are computed during
setup, so the JDK collections can use cached string hashes. FRESH builds a new
String from a prepared `char[]` inside every timed lookup; all contenders pay
that construction cost. Its Java hash starts uncached. The FRESH timing and
allocation columns therefore include query creation; they do not contradict
the separate zero-allocation audit of the Bloom call on an existing String.
Construction also leaves the new key bytes warm in cache. This scenario changes
data locality as well as hash caching; subtracting CACHED from FRESH scores does
not isolate the cost of `String.hashCode()` alone.
FRESH retains only prepared character arrays, avoiding an unnecessary second
copy of the query pool. Bloom's input scanning,
UTF-8 processing, and hashing remain part of each measured lookup. Its input window,
hash output, and optional SHA-256 buffers are reused. `bloom` uses one Murmur3 128-bit hash with
double hashing for the probes. `bloomRapidhash`, `bloomRapidhashNano`, and
`bloomKomihash` derive double-hash probes from separately seeded hashes;
an absent lookup may stop before computing the second hash or later probes.
`bloomSha256` uses the original
seeded SHA-256 hash per probe. All target the same false-positive probability.
For keys up to 48 bytes, the two rapidhash variants share the short-input path
and return identical hashes. The default ASCII keys exercise that path;
`LONG_UNICODE` also exercises their different longer-input loops.

**Construction:** one operation allocates a new structure and inserts all N
pre-generated strings. HashSet and HashMap are pre-sized for N entries; Bloom
uses its sizing factory. The report labels these scores ns/build and B/build,
while the raw JMH JSON uses ns/op and B/op. Strings are shared inputs and their
creation is not timed. Results are returned to JMH to prevent elimination.

All implementations receive equivalent deterministic inputs. The Bloom target
false-positive probability is 1%; the collections provide exact answers. The
cost of verification after a Bloom positive is outside the core lookup tables
and is explicitly included in the separate prefilter suite below.
These measurements describe this implementation and workload; they do not
establish that one data structure is universally preferable.

Time is reported as average nanoseconds per operation with JMH's 99.9%
confidence half-width. A single smoke observation has no usable error estimate.
The GC profiler reports allocation per operation, not retained memory footprint.
Construction allocations include transient work as well as the final structure.
Live graph footprint is measured separately; see the heap methodology below.

The allocation task first checks the calling thread with the JDK's thread
allocation counters. It warms each case for 300 ms, then checks five batches
of 20,000 mixed lookups for every Bloom strategy and all string input kinds,
plus pre-encoded byte arrays. Inputs, filters, and result formatting are outside
these counters. Any allocated byte fails this audit before JMH starts. The
audit requires the thread-allocation support provided by the pinned Temurin JDK.
JMH separately measures timing and process-wide allocation in fresh forks. Its
GC metric includes harness/background activity, so small fractional B/op can
remain even when the lookup-thread audit measures zero. Raw values are preserved.

## Exact answers with a Bloom prefilter

`benchmark:prefilter` compares direct downstream membership with Bloom followed
by the same downstream membership on positives. Both return exact answers.
The downstream operation executes JMH `Blackhole.consumeCPU(tokens)` then
`HashMap.containsKey`. Every true positive and false positive pays the full
downstream cost. A negative Bloom answer avoids it. At zero tokens there is no
synthetic CPU work, so the comparison asks whether a Bloom guard helps an
in-memory HashMap on this workload.

Tokens represent synthetic CPU work, not nanoseconds, I/O, network latency,
or a real database. Increase them to explore a cost crossover; measure your
actual backing service before extrapolating. The default tests 0 and 256.
The report includes the percentage of queries reaching downstream and the
observed false-positive percentage among absent queries, derived from JMH
event counters. Both paths include counter bookkeeping; compare within this
suite, rather than subtracting these scores from plain membership timings.
The 1% setting is a design target, not a guarantee that each finite query pool
observes exactly 1% false positives.

## Live heap footprint

`benchmark:heap` uses benchmark-only [OpenJDK JOL](https://github.com/openjdk/jol)
0.17, with no additions to the library's runtime dependency graph. It constructs
the same keys and structures but runs separately from JMH so graph traversal,
GC, and analysis allocations cannot contaminate timing measurements. The
analysis JVM gets a 2 GiB maximum heap; that is workspace for traversal, not a
claim about the data structure's footprint.

The heap task and benchmark fixture tests opt into JOL's Java 21 self-attachment
using `-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading`.
Object sizes come from `Instrumentation.getObjectSize`; heap measurement fails
if that support is unavailable instead of publishing JOL's fallback layout
estimates. The sizing method and actual JVM arguments are recorded in `run.txt`.
JOL may still report unavailable Serviceability Agent support or guessed object
addresses. This report does not use object addresses: traversal deduplicates
by identity, and comparisons subtract numeric byte totals.

Two byte counts answer different ownership questions:

- **Reachable bytes** count distinct objects reachable from the structure.
  Maps and sets include their stored Strings and backing arrays. Bloom retains
  its bit storage and reusable workspace, not the supplied keys.
- **Incremental bytes** equal `size(keys ∪ structure) − size(keys)`. The caller's
  same String array and keys form the baseline for every contender. Objects
  already owned by that baseline are excluded. This subtracts numeric graph
  totals, not object addresses, which may change during GC.

The report also shows incremental bytes per stored key and the shared key
payload (excluding the caller's String array). The combined Bloom + HashMap row
deduplicates their union and shows the extra memory of keeping both in process.
A compact Bloom replacement and an added prefilter are different memory choices.

These are reachable graph footprints, **not exclusively retained bytes** from
heap-dump dominator analysis. Shared helpers, enum instances, and collection
sentinels can also be reachable elsewhere. JVM object headers, reference
compression, and alignment affect the results; layout details are saved in
`run.txt`. Process RSS, peak fixture creation, query pools, GC workspace, and
JOL's own analysis objects are not included. Allocation per lookup remains a
different metric.

A Bloom filter trades exact answers for compact membership state. This can
avoid costly backing-store work and reduce storage, but it does not establish
a size at which every Bloom lookup must beat a HashMap lookup. Cached String
hashes, key size, cache residency, hit rate, hashing work, and downstream cost
all matter; the separate suites expose those differences.

## Flamegraphs on all three platforms

The profile command uses JMH's built-in Java Flight Recorder profiler, which
starts recording for the measurement phase after warmup. It converts recordings
using the standalone Java [async-profiler JFR converter](https://github.com/async-profiler/async-profiler/blob/master/docs/ConverterUsage.md)
4.6. Native async-profiler installation, WSL, and Linux `perf` are not required.
JDK 21 supplies JFR and `jcmd` through mise.

These are JFR execution-sample flamegraphs, not complete native/system stack
profiles. The converter includes stacks containing our benchmark methods and
their JMH harness; unrelated JVM threads are excluded. Width represents sample
share, not elapsed time or chronological order. Click frames to zoom and use
the graph's search to locate methods. The report includes sample counts and
marks zero-sample recordings as insufficient evidence. Counts come from the
filtered, converted stacks and are checked against the rendered graph. The report
also links the collapsed stacks for independent inspection. Conversion explicitly
selects runnable JDK samples; the converter's async-profiler CPU-state selector
would otherwise discard them.

Profiling uses one fork because the JMH JFR profiler writes a fixed recording
name for each benchmark/parameter combination. Different runs still have unique
directories. JFR adds overhead: use the ordinary benchmark command for timing
comparisons and the profile command to investigate where sampled execution
occurs. Preserve the JFR file for deeper inspection in JDK Mission Control or
an IDE that supports JFR.
