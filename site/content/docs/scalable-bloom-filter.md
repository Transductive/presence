+++
title = "Grow with the Set"
description = "Scalable Bloom: an initial estimate, a shared probability budget, and room to grow."
weight = 3
+++

Use `ScalableBloomFilter` when the final number of distinct values is unknown. Start with an estimate; the filter adds larger layers as bits fill and preserves earlier membership.

[Try the growth example in your browser]({{ get_url(path="playground/") }}?example=scalable)

## Start Small

```java
import io.transductive.presence.bloom.ScalableBloomFilter;

var members = ScalableBloomFilter.forStrings(1_000, 0.01);
for (int i = 0; i < 5_000; i++) {
    members.put("member-" + i);
}

members.mightContain("member-0"); // true: earlier membership survives growth
members.layerCount();            // allocated layers, including the first
members.bitSize();               // bit-array payload, not the full heap footprint
```

The 1,000 is an **initial sizing estimate**, not a lifetime limit or an exact rollover threshold. The 1% is a target for the **whole filter**, shared across its layers. As with fixed Bloom, a negative is definite and a positive needs verification against your source of truth.

## Choose Your Capacity Model

| | Fixed-size `BloomFilter` | `ScalableBloomFilter` |
| --- | --- | --- |
| Expected size | Plan for the final set | Estimate the first layer |
| Growth | Bit storage stays fixed; overfilling increases false positives | Allocates larger layers and keeps earlier ones |
| Definite miss | Checks one filter | Checks every layer |
| Positive | May be a false positive | Stops at the first matching layer; may be a false positive |
| Storage | One bit array | Multiple partitioned layers, counters, and workspace |

If you know the final size, fixed Bloom is the simpler starting point. Scalable Bloom accommodates an unknown size with additional memory and lookup work. Neither retains the original keys, supports individual deletion, nor provides an exact element count.

## How Growth Works

Each layer divides its bit array into slices, one per probe. An insert goes into the newest layer. Before changing bits, the filter checks whether any slice would exceed half full; if so, it allocates the next layer. Allocation depends on occupied bits and rounding, not the count of calls to `put`.

Queries check layers **newest first**. Any positive means possibly present. Only negative answers from every layer establish absence. A key may match more than one layer, so a positive does not identify where it was originally inserted.

An insertion already matching any layer is a no-op, including a false positive. Because layers never lose bits, that key remains discoverable. Repeated values therefore do not force growth.

## Share the Probability Budget

For whole-filter target `P` and tightening ratio `r`, layer `i` receives:

```text
layerBudget(i) = P × (1 − r) × r^i
sum of all layer budgets = P
```

At a 1% target with the default ratio of 0.9, the first budgets are 0.1%, 0.09%, and 0.081%. A stricter budget uses more probes; the slice sizes grow geometrically. Defaults double the slice size. The full factory lets you choose a factor of 2 or 4 and a ratio strictly between 0 and 1:

```java
import io.transductive.presence.bloom.HashingStrategy;

var members = ScalableBloomFilter.forStrings(
        1_000, 0.01, HashingStrategy.RAPIDHASH_V3, 4, 0.9);
```

A factor of 4 usually produces fewer layers, with larger allocation jumps and more spare space immediately after growth. All five hashing strategies are supported; Murmur3 is the default. Custom types use `create(initialCapacity, probability, encoder)` with the same deterministic encoding contract as fixed Bloom.

`estimatedFalsePositiveProbability()` uses slice occupancy to estimate the whole-filter rate. It is **not a measured false-positive rate**. The probability model assumes uniform, independent probes; our deterministic hash mixing does not establish a guarantee for arbitrary or adversarial inputs. Measure representative absent values when accuracy matters.

## Lifecycle and Limits

- Accepted values remain discoverable with consistent encoding and properly synchronized access.
- Shared instances need external synchronization, including concurrent queries, because hashing workspace is reused.
- Memory grows with the set. There is no TTL, eviction, removal, persistence, merge, or fixed total memory ceiling.
- `bitSize()` excludes object headers, per-slice counters and scratch space, and hashing workspace.
- Each layer is limited to `Integer.MAX_VALUE` logical bits. Unsupported initial sizing throws `IllegalArgumentException`; growth beyond that limit throws `IllegalStateException` before changing membership. Available heap may impose an earlier limit.

## Measure the Tradeoff

```sh
mise run benchmark:scalable
```

The focused JMH run compares fixed and scalable Bloom with Murmur3 and rapidhash at 1,000, 10,000, and 100,000 values. Fixed Bloom knows the final size; scalable starts at 1,000 throughout. Reports include hit/miss timings, layer counts, bit storage, and separately measured false positives. Both target 1%, but their observed rates and storage are not matched. See [the performance guide](@/docs/performance.md#fixed-versus-scalable) before interpreting the timing.

## Read the Theory

This implementation follows the layered, partitioned design in **Scalable Bloom Filters**, by Paulo Sérgio Almeida, Carlos Baquero, Nuno Preguiça, and David Hutchison (2007).

[Read the bundled paper (PDF)]({{ get_url(path="resources/scalable-bloom-filters.pdf") }}) · [Original PDF](https://gsd.di.uminho.pt/members/cbm/ps/dbloom.pdf)

The paper retains its authors' rights and is outside Presence's MIT license. For implementation details, see the [repository design notes](https://github.com/Transductive/presence/blob/main/docs/scalable-bloom-filter.md) and the [ScalableBloomFilter API]({{ get_url(path="api/io/transductive/presence/bloom/ScalableBloomFilter.html") }}).
