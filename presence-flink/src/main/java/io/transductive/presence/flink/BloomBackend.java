package io.transductive.presence.flink;

/** Storage ownership for Bloom membership; independent of Flink checkpoint storage. */
public enum BloomBackend {
    /**
     * Operator-owned JVM heap. Membership is not checkpointed or redistributed on
     * rescaling and starts empty on each task attempt. This is not Flink's managed
     * heap state backend.
     */
    HEAP,
    /**
     * Heap remains the primary store. Changed Bloom layers and counters are flushed
     * to Flink keyed managed state before checkpoints, and lazily loaded after restore.
     * Recovery requires a completed checkpoint or savepoint. This does not select
     * Flink's state backend or checkpoint storage, and all resident Blooms must fit in heap.
     */
    MANAGED_STATE
}
