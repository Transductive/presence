package io.transductive.presence.flink;

/** The action to take for a record whose membership key may already be present. */
public enum Decision {
    /** Suppress the record, accepting that a false positive may suppress a new value. */
    DROP,
    /** Forward the original record without changing it. */
    FORWARD
}
