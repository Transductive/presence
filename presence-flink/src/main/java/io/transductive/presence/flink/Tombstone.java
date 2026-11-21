package io.transductive.presence.flink;

/**
 * An event that can request a reset of its entire current stream key's membership.
 * Used by {@link ResetPolicy#ON_TOMBSTONE}. This does not delete one ID from a
 * Bloom or suppress the event: after a reset the event is looked up, remembered,
 * and forwarded normally, so it must still have a non-null membership ID.
 */
public interface Tombstone {
    /**
     * Indicates whether this event starts a new membership lifetime for its group.
     *
     * @return true to clear the current key before this event's lookup
     */
    boolean isTombstone();
}
