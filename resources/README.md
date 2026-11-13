# Reference Material

## Fixed-Size Bloom Filter

- **Title:** CMSC 420: Bloom Filters
- **Author:** Justin Wyss-Gallifent, University of Maryland
- **Document date:** November 30, 2023
- **Source:** https://www.math.umd.edu/~immortal/CMSC420/notes/bloomfilters.pdf
- **Local copy:** [bloomfilters.pdf](bloomfilters.pdf), downloaded November 10, 2026
- **SHA-256:** `507175a1c39b732aeeca4b1c7b70b92e177e7b8d32d90a8f51baf415adcf01ed`

The PDF is preserved as downloaded. It is third-party reference material and is
not licensed under Presence's MIT license; its author's rights remain intact.
The root `resources` directory is documentation only and is excluded from the
library JAR. Sections 2–4 inform the basic algorithm; sections 5–7 explain removal,
false positives, and capacity planning. Counting filters are outside the initial
implementation's scope.

## Scalable Bloom Filter

- **Title:** Scalable Bloom Filters
- **Authors:** Paulo Sérgio Almeida, Carlos Baquero, Nuno Preguiça, David Hutchison
- **Publication:** Information Processing Letters 101(6), 255–261 (2007)
- **DOI:** https://doi.org/10.1016/j.ipl.2006.10.007
- **Source:** https://gsd.di.uminho.pt/members/cbm/ps/dbloom.pdf
- **Local copy:** [scalable-bloom-filters.pdf](scalable-bloom-filters.pdf), downloaded November 12, 2026
- **SHA-256:** `bf2660fbc66afd7210f666f895ee3e97e98233f6d92bd6d4eff466fb9a4ebd48`

The author's nine-page manuscript is preserved as downloaded. It is third-party
reference material, outside Presence's MIT license and library JAR. Sections 3–4
inform the partitioned layers and geometric error budget in `ScalableBloomFilter`;
[implementation notes](../docs/scalable-bloom-filter.md) describe our choices and
the limits of the probabilistic model.
