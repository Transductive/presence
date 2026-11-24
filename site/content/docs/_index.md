+++
title = "The Field Guide"
description = "Build with Presence. Understand the tradeoffs. Measure the result."
sort_by = "weight"
template = "docs.html"
page_template = "doc-page.html"
+++

Presence is an open-source membership library by **Transductive**, built with Java 21 and a dependency-free core. Start with a Bloom filter, then measure what it saves in your application.

New here? [Build your first filter](@/docs/getting-started.md). Capacity unknown? [Let the filter grow](@/docs/scalable-bloom-filter.md). Looking for a method or constructor? Open the [generated API reference]({{ get_url(path="api/index.html") }}).

Working with Flink **`KeyedStream`** inputs? [Add Presence to a Flink job](@/docs/flink.md), or
open the separate [Flink API reference]({{ get_url(path="api/flink/index.html") }}).

Need visibility into membership operations and filter growth? [Observe Presence](@/docs/observability.md)
with the optional OpenTelemetry adapter, or browse its [API reference]({{ get_url(path="api/otel/index.html") }}).
