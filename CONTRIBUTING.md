# Contributing to Presence

Thanks for helping build Presence at Transductive. Bug reports, API feedback,
tests, and new membership structures all help move the project forward.

## Build and test

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

Keep production code dependent only on the Java standard library. Tests may use
JUnit Jupiter; JMH and the JFR converter belong only to the benchmark source set.
Add tests for observable behavior and boundary cases; probability
checks should use deterministic data and generous, explained bounds.

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
6. Open `BloomFilterTest` under `src/test/java` and use the gutter controls to
   run or debug the class or an individual test.

An IDE launched from the desktop may not inherit mise's shell environment;
selecting the JDK explicitly handles that. Any installed JDK 21 also works.

## Toolchain updates

Update the Java pin in `mise.toml` deliberately, then run `mise install`
and `mise run ci`. Keep Java on the project's target major version, 21.
To update Gradle, run its `wrapper` task with the new version and official
distribution SHA-256 checksum, then run the wrapper task again using the new
wrapper. Commit both scripts, the wrapper JAR, and its properties together.

## Benchmarks

Benchmark sources are in `src/benchmark/java`, with fixture checks in
`src/benchmarkTest/java`. Run `mise run benchmark:check` explicitly to compile
the harness and verify its fixtures. Regular builds and CI only package the
library and run its unit tests. Benchmarks remain manual until a dedicated
comment-triggered workflow is added. See [Benchmarking](docs/benchmarking.md)
before changing workloads.

Run `mise run benchmark:smoke` after changing the harness. Use
`mise run benchmark` for comparisons and `mise run benchmark:flamegraph` to
inspect sampled execution stacks. Keep the N=1,000 baseline, deterministic input
data, and the same workload for every implementation. Avoid data generation or
random-number generation in timed lookup methods. Return results so JMH consumes
them, and preserve fork isolation.

Record the commit, machine, and full JMH JSON when sharing measurements. Do not
use smoke runs, profiled timings, or unrelated workloads to claim an improvement.
