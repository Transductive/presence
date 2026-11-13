+++
title = "Hashing & encoding"
description = "Choose a strategy without changing the membership contract."
weight = 4
+++

Presence implements its hashing in pure Java. MurmurHash3 x64 128-bit is the default. The other strategies are selectable at construction and stay fixed for the lifetime of the filter.

## Choose a strategy

```java
import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;

var members = BloomFilter.forStrings(
        10_000, 0.01, HashingStrategy.RAPIDHASH_V3);
```

| Strategy | Implementation |
| --- | --- |
| `MURMUR3_128` | MurmurHash3 x64 128-bit, seed zero; the default |
| `RAPIDHASH_V3` | rapidhash v3, up to two seeded 64-bit hashes |
| `RAPIDHASH_NANO_V3` | rapidhash Nano v3; a different long-input loop |
| `KOMIHASH_5` | Komihash 5.34, up to two seeded 64-bit hashes |
| `SHA_256` | The original `MessageDigest` fallback, one seeded digest per probe |

In fixed-size `BloomFilter`, the two-seed strategies can avoid computing the second hash on a miss at the first bit. Murmur3 obtains both halves together, but can skip the step reduction on that same early exit. This changes the work performed, not the resulting probe positions.

`ScalableBloomFilter` supports the same strategies and encoder factories. Its fast strategies compute a hash pair once per operation and derive probes for each layer's slices. The SHA-256 fallback uses a digest seeded by layer and slice. The two filters have different bit layouts; their probe sequences are not interchangeable.

These fixed seeds are not secret. The noncryptographic strategies are intended for ordinary membership workloads, not cryptographic authentication. Speed depends on the CPU, JVM, input length, and access pattern. [Measure the complete lookup](@/docs/performance.md).

## Strings without per-lookup encoding arrays

`forStrings` hashes UTF-8 through a bounded, reusable workspace. It handles ASCII, Unicode, and malformed surrogate replacement without retaining the queried string or growing a key cache.

Avoiding a fresh UTF-8 array removes a source of allocation. It does not mean the filter takes no memory. Construction allocates the bit storage and workspace; the allocation benchmark measures bytes allocated per operation after setup.

## Bring another type

A deterministic byte encoder defines value identity:

```java
import java.nio.ByteBuffer;
import io.transductive.presence.bloom.BloomFilter;

var ids = BloomFilter.<Long>create(10_000, 0.01,
        id -> ByteBuffer.allocate(Long.BYTES)
                .putLong(id)
                .array());

ids.put(42L);
```

This simple custom encoder allocates. Encoder allocation belongs to the caller; the string workspace does not remove it. For already encoded inputs, use `Function.identity()`:

```java
import java.util.function.Function;

var encoded = BloomFilter.<byte[]>create(
        10_000, 0.01, Function.identity());
```

The same logical value must produce the same bytes on every insert and query. Mutating a value so its encoding changes changes the membership question.

See [HashingStrategy]({{ get_url(path="api/io/transductive/presence/bloom/HashingStrategy.html") }}) for the public enum and [NOTICE](https://github.com/Transductive/presence/blob/main/NOTICE) for algorithm provenance.
