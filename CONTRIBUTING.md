# Contributing to Presence

Thanks for helping build Presence at Transductive. Bug reports, API feedback,
tests, and new membership structures all help move the project forward.

## Build and Test

Use the JDK pinned in `mise.toml` and the committed Gradle wrapper. Follow the
setup instructions in [README.md](README.md), then run the
same clean build as CI on Windows, macOS, or Linux:

```sh
mise run ci
```

For everyday work, use `mise run build` or `mise run test`; mise supplies the
project's Java environment automatically. Run `mise tasks ls` to discover all
commands. Developer commands live in `mise.toml`, separate from the build
definition in `build.gradle.kts`. Gradle owns compilation, test execution, and
incremental builds; mise provides the shared command interface.

When changing GitHub Actions workflows, run `mise run ci:lint`. If you have
Docker running Linux containers, `mise run ci:local` also runs the Ubuntu CI job
against your working tree. Mise installs the pinned tooling on demand; Docker
is optional for other development. See the
[local CI guide](README.md#test-workflow-changes-locally) for setup and limitations.

To run one test method or pass another Gradle option:

```sh
mise run test -- --tests 'io.transductive.presence.bloom.BloomFilterTest.emptyFilterReportsAbsence'
mise run build -- --stacktrace
```

Use `mise run gradle -- <arguments>` for Gradle commands that do not have
a named mise task. Build and publish commands propagate failures, including
test failures, back to your terminal and CI.

Keep core production code in `presence/` dependent only on the Java standard library. Tests may use
JUnit Jupiter; JMH and the JFR converter belong only to the benchmark source set.
Add tests for observable behavior and boundary cases; probability
checks should use deterministic data and generous, explained bounds.

`presence-flink/` owns the Java DataStream integration and its Flink API dependencies.
`presence-otel/` owns the optional OpenTelemetry wrapper and API dependency; the
SDK belongs only to its tests, local smoke, and benchmark source sets. Use `mise run otel:test`
for adapter tests. `mise run telemetry:smoke` starts the optional
[local Grafana/VictoriaMetrics environment](infra/telemetry/README.md).
Use `mise run telemetry:flink` for the equivalent bounded MiniCluster workload.
The timed workload and Kubernetes infrastructure never run as part of CI;
smoke unit tests do (the standalone adapter uses an in-memory SDK reader;
the Flink smoke checks configuration and workload semantics without starting a cluster).
The root coordinates all modules and the website; shared Java packaging rules
live in `gradle/library-conventions.gradle.kts`. Use `mise run build:all` for all
libraries, `mise run flink:test` for Flink adapter tests, and `mise run flink:integration`
for the local parallel Flink job. CI runs all libraries' unit tests and a separate
Flink compatibility job. Integration tests do not require Docker, Kafka, or S3.
RocksDB JNI recovery cases run on Linux; Windows and macOS skip those native cases
and verify recovery with Flink's managed heap backend. Use local `act` to exercise
Linux RocksDB from another OS. The integration suite includes a deliberate job
failure after a completed checkpoint, followed by restored membership checks.

The root Gradle project is named `presence-build` so it has a distinct component
identity from `:presence`. The published coordinates stay `io.transductive:presence`
and `io.transductive:presence-flink`; the optional telemetry module publishes as
`io.transductive:presence-otel`. Do not give the coordinator the core module's
name: dependency resolution can otherwise select the disabled root JAR.

CI also runs `mise run site:check` in a separate documentation job on pull
requests. It verifies the guides, all three API references, and the core-only playground.
Pages repeats that check before publishing from `main`.

Document public APIs with Javadoc, including null handling, probabilistic
semantics, and thread-safety constraints. Compiler and Javadoc warnings fail the
build. Follow `.editorconfig` and avoid committing generated files or local IDE
configuration.

For a pull request, describe the behavior changed and include the build/test
results. Changes to byte encoding, hashing, or sizing need particular care:
insertion and lookup must always agree, and sizing targets are approximate.

## IntelliJ IDEA

1. Run `mise trust` and `mise install` in the repository root.
2. Run `mise where java` to find the installed JDK directory.
3. Open the repository as a Gradle project (or open `build.gradle.kts`).
4. In **File → Project Structure → SDKs**, add that directory as a JDK and select
   it as the project SDK. Set the language level to Java 21.
5. In **Settings → Build, Execution, Deployment → Build Tools → Gradle**, use the
   Gradle wrapper, choose that JDK as **Gradle JVM**, and choose **Gradle** for
   running tests. Reload the Gradle project.
6. Open `BloomFilterTest` under `presence/src/test/java` and use the gutter controls to
   run or debug the class or an individual test.

An IDE launched from the desktop may not inherit mise's shell environment;
selecting the JDK explicitly handles that. Any installed JDK 21 also works.

## Toolchain Updates

Update the Java pin in `mise.toml` deliberately, then run `mise install`
and `mise run ci`. Keep Java on the project's target major version, 21.
To update Gradle, run its `wrapper` task with the new version and official
distribution SHA-256 checksum, then run the wrapper task again using the new
wrapper. Commit both scripts, the wrapper JAR, and its properties together.

## Writing Titles

Use title case for page titles, section headings, cards, and example names:
**Make Your First Lookup**, **Grow with the Set**, and **Fixed-Size Bloom**.
Capitalize the first and last words, nouns, pronouns, verbs, adjectives, and
adverbs. Keep articles, coordinating conjunctions, and prepositions lowercase
within a title. Preserve API names, acronyms, units, and intentional all-caps
display styling. Body copy, instructions, and action labels use sentence case.

Apply this convention to the website, repository documentation, and generated
report templates. Edit the authored source; generated HTML stays ignored.

## Benchmarks

Benchmark sources are in `presence/src/benchmark/java`, with fixture checks in
`presence/src/benchmarkTest/java`. Run `mise run benchmark:check` explicitly to compile
the harness and verify its fixtures. Regular builds and CI only package the
libraries and run unit tests, with a separate Flink integration job. Benchmarks remain manual until a dedicated
comment-triggered workflow is added. See [Benchmarking](docs/benchmarking.md)
before changing workloads.

The optional adapter has its own harness in `presence-otel/src/benchmark` and
fixture checks in `presence-otel/src/benchmarkTest`. Use
`mise run benchmark:otel:smoke` to validate it or `mise run benchmark:otel` to
measure instrumentation and automatic refresh costs. Both commands verify their
fixtures first. Keep the registered SDK reader, default-interval steady path,
and explicit forced-clock methodology; see the
[adapter benchmark guide](presence-otel/README.md#measure-instrumentation-cost).

Run `mise run benchmark:smoke` after changing the harness. Use
`mise run benchmark` for comparisons and `mise run benchmark:flamegraph` to
inspect sampled execution stacks. Keep the N=1,000 baseline, deterministic input
data, and the same workload for every implementation. Avoid data generation or
random-number generation in timed lookup methods. Return results so JMH consumes
them, and preserve fork isolation.

Record the commit, machine, and full JMH JSON when sharing measurements. Do not
use smoke runs, profiled timings, or unrelated workloads to claim an improvement.
