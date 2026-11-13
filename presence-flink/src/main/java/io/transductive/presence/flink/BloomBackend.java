package io.transductive.presence.flink;

/** Storage ownership for Bloom membership; independent of Flink checkpoint storage. */
public enum BloomBackend {
    /**
     * Operator-owned JVM heap. Membership is not checkpointed or redistributed on
     * rescaling and starts empty on each task attempt. This is not Flink's managed
     * heap state backend. Managed state is not implemented by this release.
     */
    HEAP
}
