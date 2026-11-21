package io.transductive.presence.flink;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.metrics.Counter;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses checkpoint callbacks only for diagnostics; it never registers managed state.
 * @param <K> stream group key type
 * @param <T> stream record type
 * @param <OUT> forwarded record type
 * @serial exclude
 */
final class HeapBloomProcessFunction<K, T, OUT> extends KeyedProcessFunction<K, T, OUT>
        implements CheckpointedFunction {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(HeapBloomProcessFunction.class);
    private final KeySelector<T, String> idSelector;
    private final PossibleDuplicateHandler<T> handler;
    private final BloomConfiguration configuration;
    private final TypeInformation<K> keyType;
    private final CheckpointAwareness checkpoints;
    private final Output<T, OUT> output;
    private final ArrayList<BeforeLookup<? super T>> beforeLookup;
    private transient HeapBloomIndex<K> index;
    private transient Counter possibleDuplicates;
    private transient Counter forwarded;
    private transient Counter dropped;
    private transient Counter membershipResets;
    private transient boolean warned;
    private transient boolean restored;

    HeapBloomProcessFunction(KeySelector<T, String> idSelector, PossibleDuplicateHandler<T> handler,
            BloomConfiguration configuration, TypeInformation<K> keyType, CheckpointAwareness checkpoints,
            Output<T, OUT> output) {
        this(idSelector, handler, configuration, keyType, checkpoints, output, List.of());
    }

    HeapBloomProcessFunction(KeySelector<T, String> idSelector, PossibleDuplicateHandler<T> handler,
            BloomConfiguration configuration, TypeInformation<K> keyType, CheckpointAwareness checkpoints,
            Output<T, OUT> output, List<BeforeLookup<? super T>> beforeLookup) {
        this.idSelector = idSelector;
        this.handler = handler;
        this.configuration = configuration;
        this.keyType = keyType;
        this.checkpoints = checkpoints;
        this.output = output;
        this.beforeLookup = new ArrayList<>(beforeLookup);
    }

    static <T> Output<T, T> records() {
        return (event, maybePresent, decision) -> {
            if (decision == Decision.FORWARD_WITH_MEMBERSHIP) {
                throw new IllegalStateException(
                        "FORWARD_WITH_MEMBERSHIP requires applyToWithMembership(stream), not applyTo(stream)");
            }
            return event;
        };
    }

    static <T> Output<T, MembershipResult<T>> withMembership() {
        return (event, maybePresent, decision) -> new MembershipResult<>(event, maybePresent);
    }

    @Override
    public void open(OpenContext context) {
        var serializer = getRuntimeContext().createSerializer(keyType);
        index = new HeapBloomIndex<>(configuration, serializer::copy);
        var metrics = getRuntimeContext().getMetricGroup().addGroup("presence");
        possibleDuplicates = metrics.counter("possibleDuplicates");
        forwarded = metrics.counter("forwarded");
        dropped = metrics.counter("dropped");
        membershipResets = metrics.counter("membershipResets");
        metrics.gauge("bloomGroups", index::groupCount);
        if (checkpoints.enabled() || restored) warnOnce();
    }

    @Override
    public void processElement(T event, Context context, Collector<OUT> collector) throws Exception {
        String id = Objects.requireNonNull(idSelector.getKey(event), "Membership ID must not be null");
        if (!beforeLookup.isEmpty()) {
            try (var membership = new HeapMembershipControl<>(index, context.getCurrentKey(), membershipResets)) {
                for (var hook : beforeLookup) hook.beforeLookup(event, membership);
            }
        }
        boolean maybePresent = index.observe(context.getCurrentKey(), id);
        Decision decision = Decision.FORWARD;
        if (maybePresent) {
            possibleDuplicates.inc();
            decision = Objects.requireNonNull(handler.onPossibleDuplicate(event),
                    "onPossibleDuplicate must return a non-null Decision");
            if (decision == Decision.DROP) {
                dropped.inc();
                return;
            }
        }
        OUT result = output.map(event, maybePresent, decision);
        forwarded.inc();
        collector.collect(result);
    }

    @Override
    public void initializeState(FunctionInitializationContext context) {
        restored = context.isRestored();
        // Intentionally do not request keyed or operator state for the HEAP backend.
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) {
        // Covers checkpointing enabled after applyTo(), including cluster-side settings.
        warnOnce();
    }

    private void warnOnce() {
        if (!warned) {
            LOG.warn(checkpoints.warning());
            warned = true;
        }
    }

    @Override
    public void close() {
        if (index != null) index.clear();
    }

    @FunctionalInterface
    interface Output<T, OUT> extends Serializable {
        OUT map(T event, boolean maybePresent, Decision decision);
    }
}
