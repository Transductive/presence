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
mise run benchmark:scalable
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
| `benchmark:scalable` | Fixed-size vs. scalable Bloom; Murmur3 and rapidhash; N=1,000 / 10,000 / 100,000; 0% / 100% hits; BROAD queries | 2 forks, 3 × 500 ms warmups, 5 × 500 ms measurements; preliminary local comparison |

The focused scalable comparison takes roughly four minutes plus setup. Fixed
Bloom is sized for the final N; scalable starts at 1,000 for every case and uses
its default growth factor 2 and tightening ratio 0.9. Both have a 1% whole-filter
target. Observed false-positive rates and memory are **not calibrated to match**:
scalable's tighter layers and spare capacity may make it more selective. The
report includes layer counts, bit-array payload bytes, and a separate untimed
accuracy check over 262,144 absent queries with 95% Wilson intervals. Payload
is not total retained heap; it excludes object headers, counters, and workspace.
Each evidence case also verifies every inserted key. These lookup timings exclude
construction and growth; hits are sampled across the whole set, including older
layers. Existing strings are reused by default; neither filter uses cached Java
string hashes. Raw evidence is in `scalable-evidence.csv` beside JMH JSON.

Override N/hits/inputs/working sets with the existing benchmark properties. For
longer confirmation, use the regular full harness with the same fixtures:

```sh
mise run benchmark -- '-PbenchmarkInclude=.*ScalableMembershipBenchmark.*' '-PbenchmarkSizes=1000,10000,100000' '-PbenchmarkHits=0,100' -PbenchmarkWorkingSets=BROAD
```

These standard timed runs use a single benchmark thread, Java 21, a 256 MiB initial fork
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

Normal `build`, `check`, `test`, and `ci` commands only build the library and/or
run its unit tests. They do not compile benchmark sources, verify benchmark
fixtures, or execute timed benchmarks. Run `mise run benchmark:check` explicitly
to compile the harness and run fixture tests. All benchmark commands are manual;
there is no comment-triggered CI workflow yet.

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
mise run benchmark -- -PbenchmarkSizes=1000 '-PbenchmarkInclude=.*[.]MembershipBenchmark[.].*'

# Compare mixed lookups across all five default sizes, without construction.
mise run benchmark -- -PbenchmarkHits=50 '-PbenchmarkInclude=.*[.]MembershipBenchmark[.].*'

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

## Large filters and the blocked experiment

The separate large fixture compares only Murmur3 and rapidhash v3, each with
the existing Bloom layout and an experimental blocked layout, plus JDK HashSet
and HashMap. The production `BloomFilter` remains the baseline. The blocked
implementation belongs to the benchmark source set and is not published in the
library JAR.

```sh
mise run benchmark:large:smoke
mise run benchmark:large
mise run benchmark:large -- -PbenchmarkSizes=10000000 -PbenchmarkQueryModes=CACHED
mise run benchmark:large:flamegraph -- -PbenchmarkSizes=50000000 -PbenchmarkQueryModes=CACHED
```

Defaults are N=1,000 / 10,000,000 / 50,000,000, 0% true hits, and FRESH
queries. This models incoming strings whose Java hash has not been computed.
Each measured operation includes constructing the String and checking membership;
it is not an isolated timing of a function receiving an already-created input.
CACHED means **reused, prehashed queries**, a supplementary reuse scenario.
Use `-PbenchmarkQueryModes=CACHED,FRESH` to include both modes.
Full timing uses two forks, three one-second warmups, and
five one-second measurements. Each fork starts with 512 MiB and may grow to
10 GiB; override the maximum with `-PbenchmarkMaxHeap=...`. Construction at
large N adds substantial time beyond the measured iterations. Fixture tests
are available through `mise run benchmark:check`; both those tests and these
experiments are excluded from normal builds and CI.

Each trial constructs only the selected contender. Insertion IDs generate
unique strings deterministically, without retaining a separate key array.
Query contents originate from a fixed shuffled pool of 1,048,576 strings, independently of N;
`-PbenchmarkQueryCount` supports powers of two from 1,024 through 4,194,304.
True hits use equal-content strings with independent object identity. Miss IDs
are provably disjoint from insertion IDs. This is a different fixture from
the original benchmark, so its scores are not direct before/after comparisons
with earlier reports.
The retained pool costs memory in both modes: CACHED retains the String array,
Strings, and their backing byte arrays; FRESH retains a `char[][]` after dropping
the String array. This is benchmark fixture storage, not a production requirement
to retain incoming queries. Zero bytes allocated per lookup never means zero
retained heap. Computing a String's cached hash uses its existing field, while
retaining that String keeps the whole object and its backing array alive.
The FRESH character arrays are materialized in query traversal order, while
CACHED String references are shuffled. Freshly constructed String bytes are
also warm after copying. Cross-mode scores therefore reflect input preparation
and memory layout as well as Java hash caching; compare contenders within the
same mode when evaluating the blocked layout.

The blocked candidate chooses a 512-bit logical block, then derives all probes
inside it using full-width mixed counters. Adjacent array words do not guarantee
physical cache-line alignment. Block occupancy and duplicate probes affect
false positives; assigning both layouts the same nominal target is insufficient.

Calibrate and validate accuracy separately:

```sh
mise run benchmark:large:accuracy -- -PaccuracySize=1000000
mise run benchmark:large:accuracy -- -PaccuracyMode=validate -PaccuracySize=10000000
mise run benchmark:large:accuracy -- -PaccuracyMode=validate -PaccuracySize=50000000
```

Calibration writes all candidate measurements and `selected.properties` under
`build/reports/large-accuracy/<run-id>/`. The four parameter names are
`murmurBlockedBitsPerKey`, `murmurBlockedHashCount`, `rapidBlockedBitsPerKey`,
and `rapidBlockedHashCount`; pass them as Gradle `-P` options to use a different
selection. Selection uses measured accuracy and storage, never lookup timing.
The checked-in defaults use six probes and request 9.89453125 bits/key for
Murmur3 or 9.904296875 bits/key for rapidhash. They were selected at N=1,000,000
using eight million training queries and a separate eight-million-query
holdout. Storage rounds up to whole blocks, so actual bits/key can differ.
The calibration task defaults to that sample size; validation defaults to
two million misses. An earlier two-million-query calibration did not establish
equivalence for Murmur3 and was not used for timings. The revised calibration
used new miss domains and a tighter training-selection margin.
The standard filter uses its existing 1% sizing target. Calibration selection,
calibration holdout, validation, timed queries, and allocation queries have
separate miss domains.

Validation checks every inserted key for false negatives and at least two
million independent absent keys per layout/strategy. The equivalence margin is
an absolute probability difference of 0.0005, or **0.05 percentage points**.
The conservative difference interval, formed from two 97.5% Wilson marginal
intervals, must fit entirely within that margin at large N. Individual rates
also have 95% Wilson intervals. N=1,000 is a reference point that records actual
accuracy and block rounding without claiming equivalence.

The accuracy runner also audits 100,000 warmed lookups of pre-existing ASCII
absent strings per layout/strategy using calling-thread allocation counters.
This excludes input creation. FRESH JMH timing includes constructing a new
String and reports that allocation separately.

Large timing reports retain raw JMH results and per-fork `setup/*.json`
sidecars. Sidecars record build time, bit-array payload, and used JVM heap after
requesting GC. That heap includes the selected structure, query pool, and
harness; it is not an exclusive retained graph measurement. GC is requested,
not guaranteed. Timed and profiled results are kept separate.

## Your own browser review

Large benchmark commands generate the grayscale review automatically from the
cases that actually ran. The HTML uses checked-in [Pebble](https://pebbletemplates.io/)
templates: a Jinja-like Java engine with automatic HTML escaping. Templates live
in `src/benchmark/resources/reports/`; `layout.peb` owns the shared design, and
`review.peb` composes charts, tables, evidence, and profiles. Existing benchmark
and heap reports use the same rendering engine.

To combine saved large runs without measuring again, copy
`docs/benchmark-review.example.json` to `benchmark-review.local.json` at the
repository root and replace its placeholder with your run directories:

```json
{
  "title": "Membership, measured.",
  "runs": [
    "build/reports/benchmarks/YOUR-TIMING-RUN",
    "build/reports/benchmarks/YOUR-PROFILE-RUN"
  ],
  "accuracy": ["build/reports/large-accuracy/YOUR-VALIDATION/accuracy.json"],
  "calibrations": ["build/reports/large-accuracy/YOUR-CALIBRATION/selected.json"],
  "queryPools": ["build/reports/query-pools/YOUR-MEMORY-RUN/query-pool.json"],
  "notes": ["Describe the workload or any limitations you want to preserve."]
}
```

Only `runs` is required. Omit optional fields or use empty arrays when you have
no corresponding evidence. Paths resolve relative to the manifest's directory;
forward slashes work on Windows, macOS, and Linux. The run directories must be
outputs of the **large** benchmark; existing smaller suites retain their own
automatic reports.

```sh
mise run benchmark:review
# Or select a different manifest:
mise run benchmark:review -- -PreviewManifest=path/to/review.json
```

The command prints a browser link and local path under
`build/reports/benchmark-reviews/<run-id>/`. Each review copies its selected run
directories, including raw JSON, settings, setup sidecars, and JFR artifacts,
plus supplied evidence JSON. Keep the full directory when sharing. Existing
runs are not modified. Repeated workloads from different runs stay separate;
profile timings are excluded from comparison charts. Smoke results remain
clearly labeled. Missing accuracy or memory measurements are shown as missing,
and accuracy intervals and blocked dimensions are checked against the inputs.
Attach all calibration attempts you want to preserve, including failed gates.
No measurements, test counts, or machine-specific conclusions are embedded in
the templates.

Measure retained query fixtures separately when needed:

```sh
mise run benchmark:large:heap
mise run benchmark:large:heap -- -PbenchmarkSizes=1000 -PbenchmarkQueryCount=1024
```

This measures both FRESH and CACHED pools using JOL instrumentation, without
building a membership structure or rerunning timing measurements. It accepts
`benchmarkSizes`, `benchmarkHits`, and `benchmarkQueryCount`. Add the printed
JSON path to `queryPools`. These reachable graphs exclude transient String
construction and the measurement harness; they are distinct from whole-JVM
heap observations and per-operation allocation.

Report libraries are benchmark-only dependencies; the published library keeps
zero runtime dependencies. Generated `.html` / `.htm`, `build/`, and the local
review manifest are ignored by Git. Editable `.peb` templates and the example
manifest are tracked, so every contributor can generate their own results.

## Flamegraphs on all three platforms

The profile command uses JMH's built-in Java Flight Recorder profiler, which
starts recording for the measurement phase after warmup. It converts recordings
using the standalone Java [async-profiler JFR converter](https://github.com/async-profiler/async-profiler/blob/master/docs/ConverterUsage.md)
4.6. Native async-profiler installation, WSL, and Linux `perf` are not required.
JDK 21 supplies JFR and `jcmd` through mise.

These are JFR execution-sample flamegraphs, not complete native/system stack
profiles. The converter selects stacks containing our benchmark methods, then
keeps only frames in `io.transductive` (slash and dot notation are supported).
Frames such as `java/lang/Thread.run`, reflection, and external JMH classes are
hidden. Benchmark and generated harness frames inside our own namespace remain.
Equal stacks after filtering are merged by adding their sample weights.
Hidden callees still contribute to their visible callers' widths: removing a
JDK leaf does not turn its sampled cost into evidence of exclusive Presence work.
The graph labels this scope. Width represents sample
share, not elapsed time or chronological order. Click frames to zoom and use
the graph's search to locate methods. The report includes sample counts and
marks zero-sample recordings as insufficient evidence. Counts come from the
filtered, converted stacks and are checked against the rendered graph. The report
also links the collapsed stacks for independent inspection. Conversion explicitly
selects runnable JDK samples; the converter's async-profiler CPU-state selector
would otherwise discard them. `stacks.full.collapsed` retains the selected
benchmark stacks before frame filtering; the original JFR remains unchanged.

To update an existing run's graphs without rerunning benchmarks:

```sh
mise run benchmark:flamegraph:render -- -PprofileRun=build/reports/benchmarks/YOUR-PROFILE-RUN
mise run benchmark:review
```

The render command updates HTML and collapsed-stack outputs beside each saved
recording and prints their browser links. Rebuild a combined review afterwards
so its copied profiles pick up the change.

Profiling uses one fork because the JMH JFR profiler writes a fixed recording
name for each benchmark/parameter combination. Different runs still have unique
directories. JFR adds overhead: use the ordinary benchmark command for timing
comparisons and the profile command to investigate where sampled execution
occurs. Preserve the JFR file for deeper inspection in JDK Mission Control or
an IDE that supports JFR.
