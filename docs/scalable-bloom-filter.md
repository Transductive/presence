# Scalable Bloom Filter

`io.transductive.presence.bloom.ScalableBloomFilter<T>` represents a growing set
without retaining its keys. An initial estimate controls the first allocation;
it is not a lifetime insertion limit. The implementation follows the layered,
partitioned design in Almeida, Baquero, Preguiça, and Hutchison's
[*Scalable Bloom Filters*](../resources/scalable-bloom-filters.pdf).

## Use It

```java
import io.transductive.presence.bloom.ScalableBloomFilter;

var members = ScalableBloomFilter.forStrings(1_000, 0.01);
for (int i = 0; i < 100_000; i++) {
    members.put("member-" + i);
}

members.mightContain("member-0"); // true: earlier layers remain intact
members.layerCount();            // number of allocated layers
members.bitSize();               // total bit-array payload, in bits
members.estimatedFalsePositiveProbability(); // occupancy-based estimate
```

`create(initialCapacity, probability, encoder)` supports custom types. As with
`BloomFilter`, encoded bytes define identity; an encoder runs once per operation
and must be deterministic and return non-null bytes. Empty values are valid.
The UTF-8 string factory uses the existing reusable encoding workspace, including
JDK-compatible replacement of malformed surrogates. Values and input arrays are
released after each operation. Queries reuse workspace; growth allocates storage.

All five `HashingStrategy` values are supported, with Murmur3 as the default.
Use `forStrings(n, p, strategy)` or `create(n, p, encoder, strategy)` to select one.
The full overload also accepts `growthFactor` (2 or 4) and `tighteningRatio`
(strictly between 0 and 1):

```java
import io.transductive.presence.bloom.HashingStrategy;

var members = ScalableBloomFilter.forStrings(
        1_000, 0.01, HashingStrategy.RAPIDHASH_V3, 4, 0.9);
```

Defaults are doubling slice sizes and a tightening ratio of 0.9. A factor of 4
usually creates fewer layers, with larger allocation jumps and more unused space
immediately after growth. The existing fixed-size `BloomFilter` is unchanged.

## Layers and the Probability Budget

For whole-filter target `P` and tightening ratio `r`, layer `i` receives budget
`P × (1 − r) × r^i`. The sum over all layers is at most `P`. With `P = 1%` and
`r = 0.9`, the first budgets are 0.1%, 0.09%, and 0.081%.

Each layer has `ceil(-log2(layerBudget))` disjoint slices, with one bit probe per
slice. Slice sizes are powers of two, starting at the next power of two above
`ceil(initialCapacity / ln(2))`, with a 64-bit minimum. New layers multiply the
slice size by the growth factor. Probability calculations use logarithms to
avoid underflow when sizing very small targets.

The implementation counts occupied bits in each slice. Before setting any bits,
it checks whether the insertion would push a slice beyond half full. If so, it
adds a layer. This conservative per-slice rule can grow before the nominal
capacity; rounding can also provide additional capacity. Neither the initial
estimate nor the number of calls to `put` is an exact rollover threshold.

An insertion already matching any layer is a no-op. Even when that match was a
false positive, the key remains discoverable because no layer loses bits.
This keeps repeated inserts from consuming new capacity without storing an
exact set of keys or claiming an exact distinct-element count.

## Lookup and Hashing

Layers are checked newest first. Any positive gives a possible match; only all
negative answers prove absence. A definite miss therefore checks every layer.
Geometric growth keeps the layer count logarithmic in the represented set size
under ordinary hash distributions. There is no executor, query cache, or routing
index. No particular layer order is optimal for every workload.

The four fast strategies hash the input once into the existing pair of 64-bit
values. Each layer has a distinct salt; each derived slice probe goes through
Murmur3's public-domain finalizer before masking into its slice. This avoids
simple arithmetic probe patterns in small power-of-two slices. SHA-256 computes
a digest per probe over a big-endian layer index, big-endian slice index, and the
encoded value, using reusable buffers. These positions are specific to the
scalable filter, not compatible with the fixed-size filter's bit layout.

`estimatedFalsePositiveProbability()` sums the products of observed slice fill
ratios. It reads counters, not the bit arrays. This is a conservative union-bound
estimate **under the uniform, independent-probe model**. Our derived probes are
deterministic pseudorandom mixing, not truly independent random functions. The
target and estimate are not guarantees for adversarial or correlated inputs;
test the observed rate on representative data. The hashing seeds are not secret.

## Operational Contract

- Accepted values have no false negatives with consistent encoding and properly
  synchronized access. Growth preserves all earlier layers.
- Instances require external synchronization, including concurrent queries.
- Total memory grows. There is no expiration, deletion, persistence, merge, or
  fixed total memory ceiling. `bitSize()` excludes array headers, per-slice
  counters/scratch space, and the shared hashing workspace.
- Each layer is limited to `Integer.MAX_VALUE` logical bits. Unsupported initial
  sizing throws `IllegalArgumentException`; growth beyond that limit throws
  `IllegalStateException` before changing membership. Actual allocations also
  depend on available heap and can fail with `OutOfMemoryError`.
- A positive is not proof of membership. Discarding records based only on a
  positive answer implements approximate, potentially lossy deduplication.

## Try and Measure

Run `mise run site:serve`, open `/playground/?example=scalable`, and run the
growth example against the built Presence JAR. It starts at 1,000, inserts 5,000
values, and checks that earlier membership survives. This is a behavior demo,
not a browser performance benchmark.

Use `mise run benchmark:scalable` for the focused native-JVM comparison against
fixed Bloom. It reports hit/miss timing, actual layer counts, bit-array payload,
and observed false positives at N=1,000 / 10,000 / 100,000. Both filters request
1%, but measured accuracy and memory are not matched. See [benchmark methodology](benchmarking.md)
for the measurement boundary and a longer confirmation run.

Run `mise run test -- --tests 'io.transductive.presence.bloom.ScalableBloomFilterTest'`
for growth, duplicate, encoding, probability, and boundary tests. The paper's
[original PDF](https://gsd.di.uminho.pt/members/cbm/ps/dbloom.pdf) and the
[bundled reference metadata](../resources/README.md) document the source.
