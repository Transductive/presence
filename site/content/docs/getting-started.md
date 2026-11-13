+++
title = "Your first filter"
description = "From a fresh checkout to your first membership check."
weight = 1
+++

Presence targets **Java 21**. The current `0.1.0-SNAPSHOT` is built from source and published to your local Maven repository; it is not on Maven Central yet.

## Set up once

Install [mise](https://mise.jdx.dev/installing-mise.html) for Windows, macOS, or Linux, then:

```sh
git clone https://github.com/Transductive/presence.git
cd presence
mise trust
mise install
mise run publish:local
```

Mise provisions the pinned JDK and Zola. The Gradle wrapper pins the build system. `mise run` supplies `JAVA_HOME` and the tool paths for every command; shell activation is optional.

## Add the dependency

In your application's Gradle Kotlin build:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.transductive:presence:0.1.0-SNAPSHOT")
}
```

The published library has **zero runtime dependencies**. Website and benchmark tooling do not enter the library JAR.

## Ask a membership question

```java
import io.transductive.presence.bloom.BloomFilter;

var members = BloomFilter.forStrings(10_000, 0.01);
members.put("alice");

boolean possible = members.mightContain("alice"); // true
boolean missing = members.mightContain("bob");    // may be false or true
```

The constructor plans for 10,000 distinct values at an approximate 1% false-positive rate. `false` means definitely absent. `true` means possibly present: consult your source of truth when you need an exact answer.

Inserted values remain discoverable with consistent encoding and properly synchronized access. More inserts than planned increase the false-positive rate. Individual removal is not supported.

## Let an unknown set grow

Choose scalable Bloom when an initial estimate is easier to provide than a final size:

```java
import io.transductive.presence.bloom.ScalableBloomFilter;

var growing = ScalableBloomFilter.forStrings(1_000, 0.01);
growing.put("alice");
growing.mightContain("alice"); // true, including after later growth
```

It adds larger layers as bits fill, using a shared probability budget. Memory grows, and a definite miss checks every layer. Read [the growth guide](@/docs/scalable-bloom-filter.md) or [run the example in your browser]({{ get_url(path="playground/") }}?example=scalable).

## Use it before expensive work

An illustrative lookup flow:

```java
if (!members.mightContain(key)) {
    return Optional.empty();
}
return repository.findByKey(key);
```

Keep the filter populated with every value that the backing source could return. Updates, lifecycle, and synchronization belong to your application. A filter missing an insertion cannot safely guard that source.

## Open it in IntelliJ IDEA

Open the repository as a Gradle project. Run `mise where java` to print the installed JDK directory, and select that directory for both the Project SDK and Gradle JVM. Run the JUnit tests from the IDE or use `mise run test`.

Continue with [the filter contract](@/docs/bloom-filter.md), [hashing and encoding](@/docs/hashing.md), or the [BloomFilter API]({{ get_url(path="api/io/transductive/presence/bloom/BloomFilter.html") }}).
