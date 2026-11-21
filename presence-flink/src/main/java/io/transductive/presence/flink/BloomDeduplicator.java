package io.transductive.presence.flink;

import io.transductive.presence.bloom.HashingStrategy;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;

/**
 * Approximate duplicate suppression for the established Java DataStream API on Flink 2.x.
 *
 * <p>The stream's {@code keyBy} defines a group (for example a tenant or shard);
 * {@link #by} selects the string event ID within that group. Each task lazily
 * creates one independent Bloom per group. Capacity is per group, not per job.
 * Prefer grouping many event IDs together: one Bloom per individual event ID
 * usually wastes memory compared with an exact seen flag.
 *
 * <p>Definitely-absent IDs are remembered before their records are forwarded.
 * Possible duplicates invoke the required handler. A {@link Decision#DROP}
 * policy can discard genuinely new events due to false positives. Records are
 * never modified or retained. String identity follows the core library's UTF-8
 * encoding, including its replacement of malformed surrogates.
 * Use {@link #applyToWithMembership} to include the membership observation in a
 * {@link MembershipResult} alongside every forwarded record.
 *
 * <p>Optional {@link Builder#beforeLookup} hooks can reset the current group's
 * membership before an event. Without hooks, there is no automatic reset.
 * HEAP membership has no TTL or window lifecycle, grows with active groups,
 * and is lost on restart, restore, or rescaling. Checkpointing other operators
 * does not checkpoint this membership. This operator does not provide an
 * end-to-end delivery guarantee or latest-event-time selection.
 *
 * @param <T> the stream record type
 */
public final class BloomDeduplicator<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    /** The serializable event identity selector. */
    private final KeySelector<T, String> idSelector;
    /** The explicit policy for possible duplicates. */
    private final PossibleDuplicateHandler<T> handler;
    /** Filter sizing and storage choices; contains no live membership. */
    private final BloomConfiguration configuration;
    /** Immutable registration order; hooks hold no library-owned live membership. */
    private final ArrayList<BeforeLookup<? super T>> beforeLookup;

    private BloomDeduplicator(Builder<T> builder) {
        idSelector = builder.idSelector;
        handler = Objects.requireNonNull(builder.handler, "onPossibleDuplicate must be configured explicitly");
        beforeLookup = new ArrayList<>(builder.beforeLookup);
        configuration = new BloomConfiguration(builder.scalable, builder.capacity,
                builder.probability, builder.hashingStrategy, builder.backend);
    }

    /**
     * Starts a builder that uses non-null string IDs within each stream key.
     *
     * @param idSelector deterministic, serializable selector; must not mutate records
     * @param <T> record type
     * @return a builder with scalable capacity 1,000, a 1% target, Murmur3, and HEAP
     */
    public static <T> Builder<T> by(KeySelector<T, String> idSelector) {
        return new Builder<>(Objects.requireNonNull(idSelector, "idSelector"));
    }

    /**
     * Attaches duplicate handling to a keyed stream, preserving its record type.
     * Configure checkpointing before this call for the startup diagnostic. If
     * enabled later, the first actual checkpoint also triggers a warning.
     *
     * <p>A handler returning {@link Decision#FORWARD_WITH_MEMBERSHIP} fails the
     * task with {@link IllegalStateException}: use {@link #applyToWithMembership}
     * to declare the membership output type when constructing the job.
     *
     * @param stream a stream grouped by tenant, shard, or another membership scope
     * @param <K> stream group key type
     * @return the output operator, whose UID and parallelism can be configured normally
     * @throws IllegalArgumentException if a hook rejects the stream's declared event type
     */
    public <K> SingleOutputStreamOperator<T> applyTo(KeyedStream<T, K> stream) {
        Objects.requireNonNull(stream, "stream");
        validateHooks(stream);
        var checkpoints = CheckpointAwareness.capture(stream.getExecutionEnvironment().getCheckpointConfig());
        return stream.process(new HeapBloomProcessFunction<>(idSelector, handler, configuration,
                stream.getKeyType(), checkpoints, HeapBloomProcessFunction.records(), beforeLookup), stream.getType())
                .name("Presence Bloom Deduplicator");
    }

    /**
     * Attaches duplicate handling and includes membership with every forwarded record.
     * {@link MembershipResult#value()} contains the original record;
     * {@link MembershipResult#maybePresent()} is the Bloom's
     * membership result before insertion into this group. False means definitely
     * absent from the current heap membership; true means possibly present,
     * including false positives. It does not prove an actual duplicate.
     *
     * <p>Definitely-absent IDs are remembered and emitted with false, without
     * invoking the handler. For possible duplicates, {@link Decision#DROP}
     * suppresses output; {@link Decision#FORWARD_WITH_MEMBERSHIP} and
     * {@link Decision#FORWARD} both emit the original record with true.
     * Every emitted record uses the same result type, even if decisions vary.
     * Records and event timestamps are preserved. The result wraps the incoming
     * record without copying it; normal Flink object-reuse rules still apply.
     *
     * <p>Configure checkpointing before this call for the startup diagnostic.
     * HEAP lifetime and checkpoint behavior are the same as {@link #applyTo}.
     *
     * @param stream a stream grouped by tenant, shard, or another membership scope
     * @param <K> stream group key type
     * @return the typed membership output, with configurable UID and parallelism
     * @throws IllegalArgumentException if a hook rejects the stream's declared event type
     */
    public <K> SingleOutputStreamOperator<MembershipResult<T>> applyToWithMembership(KeyedStream<T, K> stream) {
        Objects.requireNonNull(stream, "stream");
        validateHooks(stream);
        var checkpoints = CheckpointAwareness.capture(stream.getExecutionEnvironment().getCheckpointConfig());
        var outputType = MembershipResult.typeInformation(stream.getType());
        return stream.process(new HeapBloomProcessFunction<>(idSelector, handler, configuration,
                stream.getKeyType(), checkpoints, HeapBloomProcessFunction.withMembership(), beforeLookup), outputType)
                .name("Presence Bloom Deduplicator With Membership");
    }

    private void validateHooks(KeyedStream<T, ?> stream) {
        for (var hook : beforeLookup) hook.validateInputType(stream.getType().getTypeClass());
    }

    /**
     * Mutable construction options; {@link #build()} takes an independent snapshot.
     * The builder is intended for use on the job construction thread.
     *
     * @param <T> stream record type
     */
    public static final class Builder<T> {
        private final KeySelector<T, String> idSelector;
        private PossibleDuplicateHandler<T> handler;
        private final List<BeforeLookup<? super T>> beforeLookup = new ArrayList<>();
        private boolean scalable = true;
        private long capacity = 1_000;
        private double probability = 0.01;
        private HashingStrategy hashingStrategy = HashingStrategy.MURMUR3_128;
        private BloomBackend backend = BloomBackend.HEAP;

        private Builder(KeySelector<T, String> idSelector) { this.idSelector = idSelector; }

        /**
         * Selects a fixed-size Bloom; overfilling raises false positives, without eviction.
         * @param expectedInsertions expected distinct IDs per stream group, positive
         * @return this builder
         */
        public Builder<T> fixed(long expectedInsertions) {
            requireCapacity(expectedInsertions);
            scalable = false;
            capacity = expectedInsertions;
            return this;
        }

        /**
         * Selects scalable growth with initial capacity 1,000 per group.
         * @return this builder
         */
        public Builder<T> scalable() { return scalable(1_000); }

        /**
         * Selects scalable growth with a caller-specified initial size.
         * @param initialCapacity expected distinct IDs in the first layer per group, positive
         * @return this builder
         */
        public Builder<T> scalable(long initialCapacity) {
            requireCapacity(initialCapacity);
            scalable = true;
            capacity = initialCapacity;
            return this;
        }

        /**
         * Sets the probability target; for scalable filters this covers all layers together.
         * @param probability a finite value strictly between zero and one
         * @return this builder
         */
        public Builder<T> falsePositiveProbability(double probability) {
            if (!(probability > 0 && probability < 1)) {
                throw new IllegalArgumentException("falsePositiveProbability must be between 0 and 1");
            }
            this.probability = probability;
            return this;
        }

        /**
         * Chooses the core library's hashing strategy.
         * @param strategy non-null strategy
         * @return this builder
         */
        public Builder<T> hashingStrategy(HashingStrategy strategy) {
            hashingStrategy = Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        /**
         * Chooses membership storage ownership; this release supports HEAP only.
         * @param backend non-null backend, independent of Flink's state backend
         * @return this builder
         */
        public Builder<T> backend(BloomBackend backend) {
            this.backend = Objects.requireNonNull(backend, "backend");
            return this;
        }

        /**
         * Sets the required synchronous policy for possible duplicates.
         * @param handler serializable policy returning a non-null decision
         * @return this builder
         */
        public Builder<T> onPossibleDuplicate(PossibleDuplicateHandler<T> handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Appends a callback before every membership lookup, including possible duplicates.
         * Callbacks run in registration order after ID validation. A clear removes only
         * the current key's filter and event counter; the current event then starts its
         * new membership lifetime. No hooks or automatic resets are enabled by default.
         *
         * <p>Use {@code ResetPolicy.BATCH_RESET_SIZE.every(1_000)} to reset before each
         * key's event 1,001, or {@link ResetPolicy#ON_TOMBSTONE} for events implementing
         * {@link Tombstone}. Policy type requirements are checked when attaching to a
         * stream. Custom callbacks can use {@link MembershipControl#clearCurrentKey()}.
         *
         * @param hook serializable synchronous callback; do not retain its control handle
         * @return this builder, with the callback appended
         * @throws NullPointerException if hook is null
         * @throws IllegalArgumentException if BATCH_RESET_SIZE has no event count
         */
        public Builder<T> beforeLookup(BeforeLookup<? super T> hook) {
            Objects.requireNonNull(hook, "hook");
            if (hook == ResetPolicy.BATCH_RESET_SIZE) {
                throw new IllegalArgumentException("Configure BATCH_RESET_SIZE.every(events) before registering it");
            }
            beforeLookup.add(hook);
            return this;
        }

        /**
         * Builds reusable configuration, without allocating any Bloom storage.
         * @return a deduplicator that can be attached to a keyed stream
         * @throws NullPointerException if no possible-duplicate handler was supplied
         */
        public BloomDeduplicator<T> build() { return new BloomDeduplicator<>(this); }

        private static void requireCapacity(long capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        }
    }
}
