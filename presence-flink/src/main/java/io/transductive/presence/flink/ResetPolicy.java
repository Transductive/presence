package io.transductive.presence.flink;

import java.util.Objects;

/**
 * Built-in before-lookup policies. Register with {@link BloomDeduplicator.Builder#beforeLookup}.
 * All resets affect only the current stream key and intentionally forget its history.
 */
public enum ResetPolicy implements BeforeLookup<Object> {
    /**
     * Counts incoming events per key. Configure with {@link #every(long)};
     * registering this unconfigured enum value is an error.
     */
    BATCH_RESET_SIZE,
    /**
     * Clears the current key when {@link Tombstone#isTombstone()} returns true.
     * The stream's declared record class must implement {@link Tombstone}.
     */
    ON_TOMBSTONE;

    /**
     * Configures {@link #BATCH_RESET_SIZE} to start a fresh batch before event N+1.
     * Every observed event counts, including duplicates and dropped records. Idle
     * keys are not cleared until their next event. This does not bound total group
     * count, total heap, or false-positive probability.
     *
     * @param events positive number of events per key in each membership lifetime
     * @return a serializable policy whose counter is held by each key's membership
     * @throws IllegalArgumentException if events is not positive
     * @throws IllegalStateException if called on a policy other than BATCH_RESET_SIZE
     */
    public BeforeLookup<Object> every(long events) {
        if (this != BATCH_RESET_SIZE) {
            throw new IllegalStateException("Only BATCH_RESET_SIZE accepts every(events)");
        }
        if (events <= 0) throw new IllegalArgumentException("events must be positive");
        return new BatchReset(events);
    }

    @Override
    public void validateInputType(Class<?> eventType) {
        Objects.requireNonNull(eventType, "eventType");
        requireConfigured();
        if (!Tombstone.class.isAssignableFrom(eventType)) {
            throw new IllegalArgumentException("ON_TOMBSTONE requires the stream event type to implement "
                    + Tombstone.class.getName() + "; declared type is " + eventType.getName());
        }
    }

    @Override
    public void beforeLookup(Object event, MembershipControl membership) {
        requireConfigured();
        if (!(event instanceof Tombstone tombstone)) {
            throw new IllegalArgumentException("ON_TOMBSTONE requires events implementing "
                    + Tombstone.class.getName());
        }
        if (tombstone.isTombstone()) membership.clearCurrentKey();
    }

    private void requireConfigured() {
        if (this == BATCH_RESET_SIZE) {
            throw new IllegalArgumentException("Configure BATCH_RESET_SIZE.every(events) before registering it");
        }
    }

    private record BatchReset(long events) implements BeforeLookup<Object> {
        private static final long serialVersionUID = 1L;

        @Override
        public void beforeLookup(Object event, MembershipControl membership) {
            if (membership.eventsSinceReset() >= events) membership.clearCurrentKey();
        }
    }
}
