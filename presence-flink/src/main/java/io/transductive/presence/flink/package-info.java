/**
 * Approximate membership decisions for keyed Java DataStreams on Apache Flink 2.x.
 * Forward original records or typed membership results for downstream decisions.
 * Optional before-lookup callbacks reset the current key by event count, tombstone, or caller policy.
 * Operator-owned heap storage is independent of managed state and checkpoint storage.
 */
package io.transductive.presence.flink;
