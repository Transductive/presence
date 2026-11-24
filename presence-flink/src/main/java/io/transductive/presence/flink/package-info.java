/**
 * Approximate membership decisions for keyed Java DataStreams on Apache Flink 2.x.
 * Forward original records or typed membership results for downstream decisions.
 * Optional before-lookup callbacks reset the current key by event count, tombstone, or caller policy.
 * Heap lookups optionally synchronize dirty layers to Flink managed state at checkpoints.
 * Flink's state backend and checkpoint destination remain application choices.
 * Optional detailed metrics sample resident filters synchronously per subtask;
 * Flink reporters read cached values and never traverse live membership.
 */
package io.transductive.presence.flink;
