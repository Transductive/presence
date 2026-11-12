# Bloom filter design

Presence's first membership structure is an insert-only, fixed-capacity Bloom
filter. Its public API is `io.transductive.presence.bloom.BloomFilter<T>`.

## Algorithm

The implementation follows sections 2–4 of Justin Wyss-Gallifent's
[*CMSC 420: Bloom Filters*](../resources/bloomfilters.pdf) (University of Maryland,
November 30, 2023). Insertion sets all `k` hash positions in an `m`-bit array.
Lookup returns false as soon as any of those positions is unset; otherwise it
returns true.

With consistent encoding and correctly synchronized access, inserted values
have no false negatives. Positive answers may be false positives. Repeated
insertion is harmless. Removing a value by clearing its bits could erase bits
shared with another value, so individual removal is not supported.

## Storage and hashing

A JDK `BitSet` stores the bits. `HashingStrategy.MURMUR3_128` is the default:
Presence implements Austin Appleby's [MurmurHash3 x64 128-bit algorithm](https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp)
in pure Java, with seed zero and explicit little-endian block reads on every
platform. The reference algorithm is public domain. Independent reference
vectors cover empty input, all tail lengths, multiple blocks, and high-bit bytes.
The tail reader loads complete eight- and four-byte words before assembling any
remaining bytes. It never reads padding beyond the logical input length.

Three more noncryptographic strategies are available:

| Strategy | Algorithm | Hashing per encoded value |
| --- | --- | --- |
| `MURMUR3_128` (default) | MurmurHash3 x64 128-bit | One 128-bit hash, seed zero |
| `RAPIDHASH_V3` | rapidhash v3, default secrets and FAST multiplication | Up to two 64-bit hashes with different seeds |
| `RAPIDHASH_NANO_V3` | rapidhash Nano v3 | Up to two 64-bit hashes with different seeds |
| `KOMIHASH_5` | Komihash 5.34 | Up to two 64-bit hashes with different seeds |

The rapidhash and Komihash ports use Java 21's `Math.unsignedMultiplyHigh` and
explicit little-endian reads. Each hashes the complete input with seeds `0` and
`0x9e3779b97f4a7c15`; seed initialization is precomputed. This supplies two probe
values without treating a single 64-bit result as a native 128-bit hash.
The seeds are fixed, not secret. Nano and full rapidhash produce the same hashes
through 48 bytes; their longer-input loops differ. Actual speed depends on the
JVM, CPU, input lengths, and the rest of the lookup path.

Insertion computes both hashes. Lookup checks the first bit before computing
the second seeded hash: if that bit is unset, the value is already definitely
absent. A one-probe filter never needs the second hash during lookup. Murmur3
produces both halves together, but still postpones reducing the step until the
first bit passes. This changes the amount of work, not the hash values or probe
positions.

The ports match 4,095 vectors per algorithm generated from pinned upstream C
implementations, including the two Bloom seeds. See the
[reproducible reference generator](../src/test/reference/README.md) and
[upstream attributions and licenses](../NOTICE).

For these four strategies, the two 64-bit values supply a starting position and a step, each reduced modulo
`m` as an unsigned value. A zero step becomes one, including for empty input.
The `k` probes follow `(start + i * step) mod m`; incremental addition in a `long`
avoids overflow and repeated division. This uses the double-hashing approach
studied by [Kirsch and Mitzenmacher](https://www.eecs.harvard.edu/~michaelm/postscripts/esa2006a.pdf).
Its asymptotic result does not make finite-filter false-positive targets exact;
small or composite bit counts can produce repeating probe sequences.

The initial unsigned reductions use a precomputed reciprocal instead of integer
division on every query. For `m > 1`, `R = ceil(2^64 / m)` gives a quotient
estimate from the high half of `hash × R`. The estimate is exact or one too
large, so a sign correction recovers the exact unsigned remainder. `m = 1`
returns zero directly. Independent `BigInteger` tests cover signed boundaries,
powers of two, random divisors, and the complete filter's existing bit layout.

`HashingStrategy.SHA_256` preserves the original `MessageDigest` hash scheme. Each
position comes from SHA-256 over a four-byte, big-endian seed followed by the
encoded value. The first eight digest bytes are treated as an unsigned integer
and reduced modulo `m`. Seeds run from `0` to `k - 1`. The digest instance and its
32-byte output buffer are allocated at filter construction and reused; no digest
array is created for each probe. Provider buffers are initialized at construction.

Select a strategy when constructing the filter; it stays fixed for the filter's
lifetime. Existing overloads select Murmur3. The explicit constructor, `create`,
and `forStrings` also accept a final `HashingStrategy` argument:

```java
import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;

var fast = BloomFilter.forStrings(10_000, 0.01);
var rapid = BloomFilter.forStrings(10_000, 0.01, HashingStrategy.RAPIDHASH_V3);
var nano = BloomFilter.forStrings(10_000, 0.01, HashingStrategy.RAPIDHASH_NANO_V3);
var komi = BloomFilter.forStrings(10_000, 0.01, HashingStrategy.KOMIHASH_5);
var sha = BloomFilter.forStrings(10_000, 0.01, HashingStrategy.SHA_256);
```

The hashing strategy does not change the encoder's identity contract.
`forStrings` uses a fixed 256-byte UTF-8 window and a reusable hash result. Short
values fit in the window; longer ASCII values are read directly, and longer
Unicode values are encoded incrementally with a 64-byte overlap for hash tail
reads. The workspace never grows with input length and does not cache keys.
Input references are cleared after every operation, including exceptions.

For strings of up to 85 UTF-16 code units, a reusable `char[85]`, cached buffer
views, and a core-JDK `CharsetEncoder` fill the same byte window. UTF-8 needs at
most three bytes per UTF-16 code unit, so these strings fit in at most 255 bytes;
surrogate pairs need four bytes for two units. The encoder replaces malformed
surrogates with `?`, matching the existing encoding contract. Its workspace and
lazy surrogate parser are initialized at filter construction. Longer strings
keep the bounded streaming path. Filters using byte-array encoders omit this
optional string workspace.

The encoded byte sequence matches `String.getBytes(StandardCharsets.UTF_8)`,
including `?` replacement of unpaired surrogates. Tests cover every UTF-16 code
unit, supplementary characters, window boundaries, and randomized long values.
Generic constructors and `create` still call the supplied byte encoder, whose
allocations remain the caller's responsibility. An identity encoder can reuse
existing byte arrays without copying them.

The built-in processing paths reuse their working storage after construction.
SHA-256 provider behavior and JVM instrumentation can affect allocation metrics;
the `benchmark:allocation` task verifies the pinned JDK with both a lookup-thread
allocation audit and JMH. JMH's process-wide counter can include tiny fractional
bytes per operation from the harness, especially for slower, long-input cases.

## Sizing

Automatic sizing uses the conventional approximations:

```text
m = ceil(-n × ln(p) / ln(2)²)
k = max(1, round((m / n) × ln(2)))
```

Here `n` is the expected number of distinct encoded values and `p` is the target
false-positive probability. As section 6 of the reference explains, familiar
probability formulas are approximate. The requested target is not an exact
guarantee, especially for tiny filters or unusual input distributions. Exceeding
the planned capacity raises the false-positive rate without causing false
negatives.

For explicit dimensions, use the constructor:

```java
import java.nio.charset.StandardCharsets;
import io.transductive.presence.bloom.BloomFilter;

var filter = new BloomFilter<String>(20, 3,
        value -> value.getBytes(StandardCharsets.UTF_8));
```

`bitSize()` reports the logical bit count; `hashFunctionCount()` reports `k`
bit probes, and `hashingStrategy()` reports the selected strategy. Murmur3 derives
all probes from one 128-bit hash; rapidhash and Komihash derive them from two
separately seeded 64-bit hashes. SHA-256 computes a digest for each probe.

## Encoding contract

Encoded bytes define identity rather than `Object.equals`. An encoder must
produce the same bytes for the same logical value on every call. Do not mutate
identifying fields between insertion and lookup and expect the changed encoding
to match the original value.

Inputs and encoded arrays must be non-null. Empty strings and empty arrays are
valid. The string factory uses UTF-8 without Unicode normalization. Neither the
input values nor their byte arrays are retained.

## Operational limits

- The filter is fixed size, with no removal, resizing, persistence, or merge
  operation. It does not track an exact element count.
- Instances are not thread-safe, including concurrent lookups. Use external
  synchronization when sharing them; lookups mutate the reusable workspace.
- Storage is approximately `m / 8` bytes, rounded to 64-bit words, plus object
  overhead and a fixed hashing workspace (plus provider state for SHA-256).
  The logical bit count is limited to `Integer.MAX_VALUE`; actual
  capacity also depends on the JVM heap.
- Murmur3, rapidhash, and Komihash insertion and lookup cost `O(L + k)` for an encoded value of `L` bytes.
  SHA-256 costs `O(k × (L + 1))`. Lookup can stop probing early. These bounds
  exclude the caller's encoding cost.

See [the source metadata](../resources/README.md) for the reference PDF's origin
and checksum, and [the project README](../README.md) for usage and setup.
