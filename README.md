# Presence

**Membership checks, in plain Java.**

Presence is an open-source library by **[Transductive](https://github.com/Transductive)**
for answering a common question: *have we seen this value before?*

Use it to rule out missing values before reaching for a database, a remote
service, or another expensive lookup. Presence offers fixed-size and scalable
Bloom filters: compact storage, definite negatives, and possible positives you
can verify against your source of truth.

**Java 21 · Zero runtime dependencies · MIT licensed**

## A First Look

```java
import io.transductive.presence.bloom.BloomFilter;

// Plan for 10,000 distinct values with a target false-positive rate of 1%.
var members = BloomFilter.forStrings(10_000, 0.01);

members.put("alice");

members.mightContain("alice"); // true: inserted values remain discoverable
members.mightContain("bob");   // false: definitely absent; true: check your source of truth
```

Start with UTF-8 strings, or bring your own byte encoder for IDs and domain
objects. Size a filter from your expected workload, or specify its bit count
and number of hashes directly.

If the set will grow beyond an initial estimate, use a scalable filter:

```java
import io.transductive.presence.bloom.ScalableBloomFilter;

// Start around 1,000 distinct values; target 1% across all growth layers.
var growing = ScalableBloomFilter.forStrings(1_000, 0.01);
growing.put("alice");
growing.mightContain("alice"); // true, including after later growth
```

It adds larger layers as bits fill, preserving earlier membership. Memory grows
with the set, and a definite miss checks every layer. Repeated inserts do not
consume new capacity. See [Scalable Bloom](docs/scalable-bloom-filter.md) for
growth settings, probability budgeting, and operational limits.

## Features & Roadmap

| Feature | Status |
| --- | --- |
| Fixed-capacity Bloom filter | Available |
| Sizing from expected insertions and target false-positive rate | Available |
| UTF-8 strings and custom byte encoders | Available |
| Reusable lookup workspace for strings and pre-encoded bytes | Available |
| Murmur3, rapidhash v3 / Nano, Komihash, and SHA-256 strategies | Available |
| Counting Bloom filter | Proposed |
| Scalable Bloom filter with a shared false-positive budget | Available |
| Filter serialization and restoration | Proposed |
| JMH lookup/construction timing, allocation, and HTML flamegraphs | Available |
| Hot/broad absent-query benchmarks and exact downstream verification | Available |
| Reachable heap and incremental memory comparisons | Available |

`BloomFilter` keeps its original fixed size; `ScalableBloomFilter` grows by adding
layers. Both are insert-only. Proposed features are directions for future
releases; feedback and contributions will help shape the roadmap.

## Get Started

Presence is in early development. To try the current `0.1.0-SNAPSHOT`, build it
from source and publish it to your local Maven repository.

### Set Up Your Tools

Install [mise-en-place](https://mise.jdx.dev/installing-mise.html) for your platform:

| Platform | Install |
| --- | --- |
| Windows PowerShell | `winget install --id jdx.mise --exact` |
| macOS with Homebrew | `brew install mise` |
| Linux | Use your package manager via the [mise installation guide](https://mise.jdx.dev/installing-mise.html). |

Open a fresh terminal, clone the repository, and install the pinned tools:

```sh
git clone https://github.com/Transductive/presence.git
cd presence
mise trust
mise install
mise tasks ls
```

The repository pins Temurin 21 through `mise.toml` and Gradle through its wrapper.
Developer commands also live in `mise.toml`, separate from Gradle's build
definition. [Mise tasks](https://mise.jdx.dev/tasks/) supply the JDK and
`JAVA_HOME` automatically on Windows, macOS, and Linux, without requiring shell
activation.

### Build and Publish Locally

On any supported platform:

```sh
mise run publish:local
```

Then add Presence to your application's Gradle Kotlin build:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.transductive:presence:0.1.0-SNAPSHOT")
}
```

This snapshot is available through your local build; it has not been published
to Maven Central.

## Bring Your Own Types

Provide a deterministic byte encoder to define what makes a value unique:

```java
import java.nio.ByteBuffer;
import io.transductive.presence.bloom.BloomFilter;

var memberIds = BloomFilter.<Long>create(
        10_000,
        0.01,
        id -> ByteBuffer.allocate(Long.BYTES).putLong(id).array());

memberIds.put(42L);
memberIds.mightContain(42L); // true
```

The same logical value must produce the same bytes on every insert and lookup.
Null values and null encodings are rejected; empty strings and byte arrays are
supported. Presence does not retain your values or their encoded arrays.

`forStrings` processes UTF-8 using a fixed reusable workspace, including Unicode
and malformed surrogate replacement. It avoids a fresh encoded array on each
lookup. Custom encoders control their own allocation; use `Function.identity()`
when your inputs are already `byte[]`.

## Fixed-Size Bloom Filter Behavior

- **A negative answer is definite.** A positive answer may be a false positive.
- **Capacity is planned up front.** Adding more values increases the
  false-positive rate. The sizing target is approximate, not an exact bound.
- **Inserts are additive.** Repeated inserts are safe; individual values cannot
  be removed.
- **Instances need external synchronization when shared.** The initial
  implementation is not thread-safe, including concurrent lookups, because each
  filter reuses its hashing workspace.

See the [Bloom filter design notes](docs/bloom-filter.md) for the algorithm,
hashing, sizing, and encoding contract.

MurmurHash3 with double hashing is the default. Try rapidhash v3, rapidhash Nano
v3, or Komihash 5.34 by selecting a strategy when you create the filter. All are
implemented in pure Java; the original `MessageDigest` fallback remains available:

```java
import io.transductive.presence.bloom.HashingStrategy;

var rapid = BloomFilter.forStrings(10_000, 0.01, HashingStrategy.RAPIDHASH_V3);
var nano = BloomFilter.forStrings(10_000, 0.01, HashingStrategy.RAPIDHASH_NANO_V3);
var komi = BloomFilter.forStrings(10_000, 0.01, HashingStrategy.KOMIHASH_5);
var sha = BloomFilter.forStrings(10_000, 0.01, HashingStrategy.SHA_256);
```

## Develop with Presence

### Documentation Website

The website uses **Zola + DevLab**, styled with Transductive's *Theory Meets the
Machine* editorial system. It combines Markdown guides, local browser search,
and an API reference generated from Java source comments.

```sh
mise run site:serve
```

Open **http://127.0.0.1:8787/**. Stop with Ctrl+C; restart after editing to rebuild.
Use `mise run site:build` for the static artifact in `build/site/` and
`mise run site:check` to verify internal links, anchors, and assets. See the
[website authoring guide](site/README.md) for content and hosting configuration.
Generated pages remain ignored; editable site and theme templates are tracked.

The homepage's **Open playground** link lets you edit and run Java 21 examples
against the actual Presence JAR in your browser. Choose a fixed-size first lookup
or a scalable example that grows from 1,000 to 5,000 values and checks earlier
membership. CheerpJ loads on the first Run;
there is no compiler server or account. Stop and Reset keep experimentation
small and disposable. The website guide covers runtime licensing and limits.

The `Deploy documentation` workflow publishes validated site builds from `main`
to GitHub Pages after the repository's Pages source is set to **GitHub Actions**.
See the [publishing setup](site/README.md#publish-with-github-pages) for the
one-time configuration and manual deployment option.

### Developer Commands

Start with `mise tasks ls` (or `mise run`) to list the available commands:

| Command | What it does |
| --- | --- |
| `mise run build` | Compile, test, and package the library. |
| `mise run test` | Run the unit tests. |
| `mise run check` | Run Gradle's verification tasks. |
| `mise run clean` | Remove generated build outputs. |
| `mise run docs` | Generate API documentation. |
| `mise run publish:local` | Build, test, and publish to local Maven. |
| `mise run ci` | Clean, package the library, and run unit tests. |
| `mise run ci:lint` | Validate GitHub Actions workflows with actionlint. |
| `mise run ci:local` | Run the Ubuntu CI job locally with act and Docker. |
| `mise run benchmark` | Compare Bloom, HashSet, and HashMap with JMH. |
| `mise run benchmark:check` | Manually compile the benchmark harness and verify its fixtures. |
| `mise run benchmark:smoke` | Quickly verify the benchmark harness at N=1,000. |
| `mise run benchmark:scalable` | Compare fixed-size vs. growing Bloom, with timing, layer counts, and accuracy. |
| `mise run benchmark:flamegraph` | Record JFR profiles and generate HTML flamegraphs. |
| `mise run benchmark:allocation` | Audit lookup allocation, then compare five string input kinds at N=1,000. |
| `mise run benchmark:misses` | Compare absent lookups with hot and broad query pools across all five sizes. |
| `mise run benchmark:prefilter` | Measure Bloom plus exact verification, including false positives. |
| `mise run benchmark:heap` | Compare reachable heap and memory added beyond shared input keys. |
| `mise run benchmark:large` | Compare standard and experimental blocked Bloom with JDK collections at 1,000, 10 million, and 50 million keys. |
| `mise run benchmark:large:accuracy` | Calibrate blocked sizing or check held-out false-positive rates. |
| `mise run benchmark:large:heap` | Measure the large benchmark's retained query-pool memory. |
| `mise run benchmark:review` | Turn your saved large runs into a combined browser review. |

These commands work without shell activation. Arguments after `--` go to the
task's underlying command (Gradle for build and test tasks):

```sh
mise run test -- --tests 'io.transductive.presence.bloom.BloomFilterTest'
mise run gradle -- dependencies --configuration runtimeClasspath
```

The HTML report is written to `build/reports/tests/test/index.html`. A full
`mise run build` also produces the library, sources, and Javadoc JARs in `build/libs/`.
API documentation is generated at `build/docs/javadoc/index.html`. CI uses
`mise run ci` on Windows, macOS, and Linux. Mise delegates builds to the Gradle wrapper,
so Gradle controls build and test behavior in both the terminal and IntelliJ.
Regular `build`, `check`, and `ci` do not compile or run benchmark sources or
fixtures. Use `mise run benchmark:check` explicitly when working on the harness.

**Using IntelliJ IDEA?** Open the repository as a Gradle project, run
`mise where java`, and select that JDK as both the project SDK and Gradle JVM.
Use the Gradle wrapper and run or debug `BloomFilterTest` from the editor.
[Full IDE setup and contribution guide →](CONTRIBUTING.md)

### Test Workflow Changes Locally

Mise installs pinned [actionlint](https://github.com/rhysd/actionlint) and
[act](https://nektosact.com/) versions when their tasks run. They are optional;
the usual Java build does not require Docker.

```sh
mise run ci:lint
mise run ci:local -- --list
mise run ci:local
```

`ci:lint` checks workflow syntax and expressions without Docker. `--list`
lists the selected workflow job without running it. For execution (including
act's `--dryrun`, which still connects to Docker),
install and start [Docker](https://nektosact.com/installation/index.html#docker),
using Linux containers on Windows, and confirm `docker info` succeeds.
The first run downloads the Ubuntu runner image, actions, and build tools.

`ci:local` lints first, then runs the **Ubuntu build job** from
`.github/workflows/ci.yml`, compiling and packaging the library and running its unit tests against
your local working tree. It uses an Ubuntu 26.04 amd64 container; Apple Silicon
runs it through emulation. Windows and macOS jobs still run on GitHub. The
container approximates the hosted runner, so hosted CI remains the final check.
Website verification and Pages deployment stay in the separate Pages workflow
on GitHub because act does not reproduce its permissions,
OIDC, or deployment environments. See act's
[runner guide](https://nektosact.com/usage/runners.html) and
[unsupported features](https://nektosact.com/not_supported.html).

No repository secrets are required for this build. If you need a GitHub token
for API rate limits, use `mise run ci:local -- --secret GITHUB_TOKEN` to enter it
interactively. Optional root `.secrets`, `.env`, and `.vars` files are ignored by Git.

## Benchmark and Profile

```sh
mise run benchmark
mise run benchmark:misses
mise run benchmark:prefilter
mise run benchmark:heap
mise run benchmark:flamegraph
```

Each command prints an **Open this file in your browser** link when it finishes.
The monochrome, offline HTML report shows lookup scaling across N, compares
implementations, and links to the raw JMH JSON.
Profile reports also link to interactive flamegraphs and the original JFR files.
Runs are saved separately under `build/reports/benchmarks/`; `clean` removes them.

The timing suite tests **N=1,000 / 10,000 / 100,000 / 500,000 / 1,000,000** values.
It measures hit, miss, and mixed lookups, plus construction of a populated
structure. Lookup compares all five Bloom hashing strategies with the JDK
collections. Allow at least 32 minutes for the full suite. The default flamegraph run
focuses on mixed lookups across those same five sizes, using Java Flight Recorder on Windows,
macOS, and Linux.

For a fast harness check, run `mise run benchmark:smoke`. Smoke scores are
explicitly labeled as unsuitable for performance conclusions. Benchmark compilation,
fixture tests, and timed runs are all opt-in; use `mise run benchmark:check` for
fixture verification. JMH and profiling dependencies are isolated from the library artifact.

See [Benchmarking](docs/benchmarking.md) for parameters, methodology, and how to
interpret the graphs. The miss suite separates a small reused query pool from
a broader working set; fresh query construction can also be selected to charge
string creation and hashing to every implementation. The prefilter suite checks
Bloom positives against an exact map and includes controlled downstream work.
The heap report distinguishes the reachable object graph from memory added
beyond already-shared keys. These answer different questions from allocation
per lookup.

## Reading behind the Code

The Bloom filter follows the insertion and lookup algorithms in Justin
Wyss-Gallifent's *CMSC 420: Bloom Filters* (University of Maryland, 2023).

[Read the bundled PDF](resources/bloomfilters.pdf) ·
[Original source](https://www.math.umd.edu/~immortal/CMSC420/notes/bloomfilters.pdf) ·
[Reference metadata](resources/README.md)

The scalable filter follows Almeida, Baquero, Preguiça, and Hutchison's
*Scalable Bloom Filters* (2007): [bundled paper](resources/scalable-bloom-filters.pdf)
and [implementation notes](docs/scalable-bloom-filter.md).

## Contributing

Bug reports, API feedback, and contributions are welcome. Have a workload that
would benefit from another membership structure? Open an issue describing the
data, access pattern, and tradeoffs that matter to you.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.

## License

[MIT](LICENSE) © 2026 Transductive contributors.
Hash algorithm attributions and upstream licenses are in [NOTICE](NOTICE) and
[licenses/](licenses/), and are included in the published JARs.
The bundled reference PDFs retain their authors' rights and are excluded from the
project's MIT license.
