package io.transductive.presence.flink;

/** The action to take for a record whose membership key may already be present. */
public enum Decision {
    /** Suppress the record, accepting that a false positive may suppress a new value. */
    DROP,
    /** Forward the original record without changing it. */
    FORWARD,
    /**
     * Forward the original record together with its pre-insertion membership result.
     * Requires {@link BloomDeduplicator#applyToWithMembership}; that output includes
     * {@code false} for definitely-absent IDs and {@code true} for possible duplicates.
     * A true result can be a false positive, not proof of an actual duplicate.
     */
    FORWARD_WITH_MEMBERSHIP
}
