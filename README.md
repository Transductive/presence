# Presence

**Membership checks, in plain Java.**

Presence is an open-source library by **[Transductive](https://github.com/Transductive)**
for answering a common question: *have we seen this value before?*

Use it to rule out missing values before reaching for a database, a remote
service, or another expensive lookup. Presence starts with a Bloom filter:
compact storage, definite negatives, and possible positives you can verify
against your source of truth.

**Java 21 · Zero runtime dependencies · MIT licensed**

## A first look

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

## Features & roadmap

| Feature | Status |
| --- | --- |
| Fixed-capacity Bloom filter | Available |
| Sizing from expected insertions and target false-positive rate | Available |
| UTF-8 strings and custom byte encoders | Available |
| Reusable lookup workspace for strings and pre-encoded bytes | Available |
| Murmur3, rapidhash v3 / Nano, Komihash, and SHA-256 strategies | Available |
| Counting Bloom filter | Proposed |
| Scalable Bloom filter | Proposed |
| Filter serialization and restoration | Proposed |
| JMH lookup/construction timing, allocation, and HTML flamegraphs | Available |
| Hot/broad absent-query benchmarks and exact downstream verification | Available |
| Reachable heap and incremental memory comparisons | Available |

The current Bloom filter has a fixed capacity and accepts new inserts throughout
its lifetime. Proposed features are directions for future releases; feedback and
contributions will help shape the roadmap.

## Get started

Presence is in early development. To try the current `0.1.0-SNAPSHOT`, build it
from source and publish it to your local Maven repository.

### Set up your tools

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

### Build and publish locally

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

## Bring your own types

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

## Bloom filter behavior

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

### Documentation website

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

The `Deploy documentation` workflow publishes validated site builds from `main`
to GitHub Pages after the repository's Pages source is set to **GitHub Actions**.
See the [publishing setup](site/README.md#publish-with-github-pages) for the
one-time configuration and manual deployment option.

### Developer commands

Start with `mise tasks ls` (or `mise run`) to list the available commands:

| Command | What it does |
| --- | --- |
| `mise run build` | Compile, test, and package the library. |
| `mise run test` | Run the unit tests. |
| `mise run check` | Run Gradle's verification tasks. |
| `mise run clean` | Remove generated build outputs. |
| `mise run docs` | Generate API documentation. |
| `mise run publish:local` | Build, test, and publish to local Maven. |
| `mise run ci` | Run a clean build, matching CI. |
| `mise run benchmark` | Compare Bloom, HashSet, and HashMap with JMH. |
| `mise run benchmark:smoke` | Quickly verify the benchmark harness at N=1,000. |
| `mise run benchmark:flamegraph` | Record JFR profiles and generate HTML flamegraphs. |
| `mise run benchmark:allocation` | Audit lookup allocation, then compare five string input kinds at N=1,000. |
| `mise run benchmark:misses` | Compare absent lookups with hot and broad query pools across all five sizes. |
| `mise run benchmark:prefilter` | Measure Bloom plus exact verification, including false positives. |
| `mise run benchmark:heap` | Compare reachable heap and memory added beyond shared input keys. |
| `mise run benchmark:large` | Compare standard and experimental blocked Bloom with JDK collections at 1,000, 10 million, and 50 million keys. |
| `mise run benchmark:large:accuracy` | Calibrate blocked sizing or check held-out false-positive rates. |
| `mise run benchmark:large:heap` | Measure the large benchmark's retained query-pool memory. |
| `mise run benchmark:review` | Turn your saved large runs into a combined browser review. |

These commands work without shell activation. Arguments after `--` go to Gradle:

```sh
mise run test -- --tests 'io.transductive.presence.bloom.BloomFilterTest'
mise run gradle -- dependencies --configuration runtimeClasspath
```

The HTML report is written to `build/reports/tests/test/index.html`. A full
`mise run build` also produces the library, sources, and Javadoc JARs in `build/libs/`.
API documentation is generated at `build/docs/javadoc/index.html`. CI uses
`mise run ci` on Windows, macOS, and Linux. Mise delegates builds to the Gradle wrapper,
so Gradle controls build and test behavior in both the terminal and IntelliJ.

**Using IntelliJ IDEA?** Open the repository as a Gradle project, run
`mise where java`, and select that JDK as both the project SDK and Gradle JVM.
Use the Gradle wrapper and run or debug `BloomFilterTest` from the editor.
[Full IDE setup and contribution guide →](CONTRIBUTING.md)

## Benchmark and profile

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
explicitly labeled as unsuitable for performance conclusions. Benchmarks run
separately from the unit tests; normal builds only compile them and check their
fixtures. JMH and profiling dependencies are isolated from the library artifact.

See [Benchmarking](docs/benchmarking.md) for parameters, methodology, and how to
interpret the graphs. The miss suite separates a small reused query pool from
a broader working set; fresh query construction can also be selected to charge
string creation and hashing to every implementation. The prefilter suite checks
Bloom positives against an exact map and includes controlled downstream work.
The heap report distinguishes the reachable object graph from memory added
beyond already-shared keys. These answer different questions from allocation
per lookup.

## Reading behind the code

The Bloom filter follows the insertion and lookup algorithms in Justin
Wyss-Gallifent's *CMSC 420: Bloom Filters* (University of Maryland, 2023).

[Read the bundled PDF](resources/bloomfilters.pdf) ·
[Original source](https://www.math.umd.edu/~immortal/CMSC420/notes/bloomfilters.pdf) ·
[Reference metadata](resources/README.md)

## Contributing

Bug reports, API feedback, and contributions are welcome. Have a workload that
would benefit from another membership structure? Open an issue describing the
data, access pattern, and tradeoffs that matter to you.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow.

## License

[MIT](LICENSE) © 2026 Transductive contributors.
Hash algorithm attributions and upstream licenses are in [NOTICE](NOTICE) and
[licenses/](licenses/), and are included in the published JARs.
The bundled reference PDF retains its author's rights and is excluded from the
project's MIT license.
