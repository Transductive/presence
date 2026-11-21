package io.transductive.presence.flink;

import java.io.Serializable;

/**
 * A serializable, synchronous callback run before each event's membership lookup.
 * The event ID is validated first. Hooks run in registration order, before the
 * current event is counted. Do not mutate events, block on I/O, or retain the
 * control handle. Captured objects must be serializable. Exceptions fail the task.
 *
 * @param <T> the input record type
 */
@FunctionalInterface
public interface BeforeLookup<T> extends Serializable {
    /**
     * Optionally resets the current key's membership before processing this event.
     *
     * @param event the unchanged incoming record, including possible duplicates
     * @param membership controls for this record's current stream key
     * @throws Exception if the callback fails
     */
    void beforeLookup(T event, MembershipControl membership) throws Exception;

    /**
     * Validates the stream's declared record class when the operator is attached.
     * The default accepts every class. Built-in policies use this to reject
     * incompatible event types before execution, without allocating Bloom storage.
     *
     * @param eventType the class supplied by the input stream's Flink type information
     * @throws IllegalArgumentException if the policy cannot accept this event type
     */
    default void validateInputType(Class<?> eventType) {}
}
