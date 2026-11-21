package io.transductive.presence.flink;

/**
 * Controls the current stream key's heap membership during a before-lookup callback.
 * In managed mode, resets and counters are included in the next checkpoint.
 * This handle is valid only while that record's callbacks run, on the operator
 * thread. Do not retain it or use it asynchronously. It cannot address other keys.
 */
public interface MembershipControl {
    /**
     * Returns the number of earlier events observed for this key since its last reset.
     * Counts duplicates and dropped events, excludes the current event, and saturates
     * at {@link Long#MAX_VALUE}. A missing or freshly cleared group reports zero.
     *
     * @return events observed in the current membership lifetime
     * @throws IllegalStateException if used outside the callback scope or operator thread
     */
    long eventsSinceReset();

    /**
     * Removes this key's filter and counter, allowing its Bloom storage to be reclaimed.
     * Repeated calls are harmless. The current event's subsequent lookup creates a
     * fresh filter, remembers its ID, and reports definitely absent. Previously seen
     * IDs can pass again after a reset. Garbage collection is not forced.
     *
     * @throws IllegalStateException if used outside the callback scope or operator thread
     */
    void clearCurrentKey();
}
