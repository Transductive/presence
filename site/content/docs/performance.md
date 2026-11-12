+++
title = "Meet the machine"
description = "Measure lookup cost, allocation, retained memory, and false positives together."
weight = 4
+++

A Bloom filter trades exact answers for compact membership storage. Whether it saves time depends on the work it can avoid. Compare complete workloads, including verification of possible positives.

## Start with your own evidence

```sh
mise run benchmark:smoke
mise run benchmark
```

The smoke task checks wiring at **N = 1,000**. It is not performance evidence. The full comparison covers 1,000, 10,000, 100,000, 500,000, and 1,000,000 entries, with hit and miss mixes. Each run prints the HTML report location and retains its raw evidence.

Generated reports belong to the person running the experiment. They are ignored by Git and are not automatically included in this website.

## Separate the dimensions

| Question | Command |
| --- | --- |
| What does an absent lookup cost? | `mise run benchmark:misses` |
| Does a Bloom guard save downstream work? | `mise run benchmark:prefilter` |
| How much is allocated per lookup? | `mise run benchmark:allocation` |
| How much memory stays reachable? | `mise run benchmark:heap` |
| Where does execution time go? | `mise run benchmark:flamegraph` |

**Allocated bytes per operation and retained heap are different measurements.** A prebuilt collection can allocate nothing during a query while retaining a large set of keys and nodes. The report's heap measurements account for storage separately.

## Fresh versus reused queries

A `String` stores its computed `hashCode` in that object. Reusing a query after its hash was computed lets a JDK collection reuse that value. Fresh input includes computing it. Equal text does not imply the same object, and ordinary method calls do not automatically intern strings.

Report both paths with their labels. Fresh queries model incoming values; reused queries describe an already hashed working set. Query pools consume retained memory, so pool size and heap footprint belong beside the timing result. Input construction must also be understood when interpreting the benchmark boundary.

## The large-N experiment

```sh
mise run benchmark:large:smoke
mise run benchmark:large
```

This separate experiment compares **Murmur3 and rapidhash**, standard and blocked Bloom layouts, and JDK `HashSet` / `HashMap`. Its default sizes are 1,000, 10 million, and 50 million, with fresh absent queries. It defaults to a 10 GiB fork heap; allow room for the host and other processes.

The blocked layout keeps a key's probes close together to study cache behavior. It is **benchmark-only**, not part of the published API. Match observed false-positive rates before comparing its speed to the baseline. A more permissive filter has an easier timing problem.

```sh
mise run benchmark:large:accuracy
mise run benchmark:large:heap
mise run benchmark:large:flamegraph
```

Use the [benchmarking reference](https://github.com/Transductive/presence/blob/main/docs/benchmarking.md) for calibration, validation, memory requirements, and parameter overrides.

## Follow the flamegraph

Flamegraphs show Presence frames under `io.transductive`. Wider bars mean more sampled execution passed through that frame. The focused view omits unrelated stacks; it is not a picture of all process time. The original JFR recording remains available for broader investigation.

To render saved recordings again without running JMH:

```sh
mise run benchmark:flamegraph:render -- -PprofileRun=build/reports/benchmarks/YOUR_RUN
```

To combine saved runs, configure `benchmark-review.local.json` from the repository example and run `mise run benchmark:review`. The generated HTML reflects the supplied runs, not a hard-coded list of sizes.

## Interpret the result

Look at error bars, forks, warmup, query distribution, working-set size, observed false-positive rate, and machine metadata. A win at one size is evidence about that experiment. A useful application-level win includes the exact downstream work and the cost of false positives.
