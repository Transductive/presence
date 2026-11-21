/**
 * Approximate membership decisions for keyed Java DataStreams on Apache Flink 2.x.
 * Forward original records or typed membership results for downstream decisions.
 * Optional before-lookup callbacks reset the current key by event count, tombstone, or caller policy.
 * Heap lookups optionally synchronize dirty layers to Flink managed state at checkpoints.
 * Flink's state backend and checkpoint destination remain application choices.
 */
package io.transductive.presence.flink;
