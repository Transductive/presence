+++
title = "Presence"
description = "Small memory. Certain negatives. Membership checks in pure Java, by Transductive."
+++

```java
import io.transductive.presence.bloom.BloomFilter;

var members = BloomFilter.forStrings(10_000, 0.01);

members.put("alice");

members.mightContain("alice"); // true
members.mightContain("bob");   // false or true
```
