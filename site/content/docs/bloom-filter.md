+++
title = "Inside the Bloom Filter"
description = "The bit budget, the probes, and what each answer means."
weight = 2
+++

A Bloom filter represents membership with an array of bits. It retains neither your keys nor a list of exact entries.

This guide describes fixed-size `BloomFilter`. For unknown capacity, see [Scalable Bloom](@/docs/scalable-bloom-filter.md), which adds layers with a shared false-positive budget.

## Insert and Query

Insertion sets `k` positions in an `m`-bit array. A lookup checks the positions for the same encoded value:

- If any position is unset, the value is **definitely absent**.
- If every position is set, the value is **possibly present**. Other keys may have set those bits.

Repeated insertion is harmless. Clearing bits to remove a value could erase bits shared with another key, so this implementation does not support deletion.

## Size the Filter

For expected distinct insertions `n` and target false-positive probability `p`, the classical approximation gives:

```text
m = ceil(-n × ln(p) / ln(2)²)
k = max(1, round((m / n) × ln(2)))
```

At 10,000 values and 1%, that is **95,851 bits and 7 probes**. The backing bit storage rounds to 1,498 64-bit words, or 11,984 bytes. This is not the complete object graph footprint: the filter also has metadata and a reusable hashing workspace.

The probability is an approximate target, not an exact finite-filter upper bound. Double hashing and small or composite bit counts can produce repeating probes. Validate observed false-positive rates for workloads where the distinction matters.

## Storage and Probe Sequence

A JDK `BitSet` stores the bits. The default MurmurHash3 strategy produces a pair of 64-bit values. Unsigned reductions select a starting position and a step; a zero step becomes one. Probes follow:

```text
position(i) = (start + i × step) mod m
```

Incremental addition avoids repeated division. A precomputed reciprocal accelerates the initial reductions while preserving the exact unsigned remainder. The SHA-256 fallback uses its original seeded digest scheme instead.

## The Instance Contract

| Property | Current behavior |
| --- | --- |
| Capacity | Fixed, chosen at construction |
| Insertions | Additive; repeated values are safe |
| Removal | Not supported |
| Key retention | Values and encoded arrays are not retained |
| Concurrency | External synchronization required, including concurrent queries |
| Encoding | Deterministic; equal logical values must produce equal bytes |
| Nulls | Null values and null encodings are rejected |
| Empty inputs | Empty strings and byte arrays are supported |

Concurrent queries need synchronization because the instance reuses mutable hashing workspace. Do not infer thread safety from the absence of inserts.

## Read the Theory

The starting reference is Justin Wyss-Gallifent's **CMSC 420: Bloom Filters**, University of Maryland, November 30, 2023. The implementation follows its treatment of insertion, lookup, and sizing in sections 2–4.

[Read the bundled Bloom Filters notes (PDF)]({{ get_url(path="resources/bloomfilters.pdf") }}) · [Original PDF](https://www.math.umd.edu/~immortal/CMSC420/notes/bloomfilters.pdf)

Double hashing follows [Kirsch and Mitzenmacher, Less Hashing, Same Performance](https://www.eecs.harvard.edu/~michaelm/postscripts/esa2006a.pdf). Its asymptotic result does not turn an approximate finite-filter target into an exact guarantee.

The [repository design notes](https://github.com/Transductive/presence/blob/main/docs/bloom-filter.md) document the implementation in more detail. The [generated API]({{ get_url(path="api/io/transductive/presence/bloom/BloomFilter.html") }}) is the reference for signatures and validation.
