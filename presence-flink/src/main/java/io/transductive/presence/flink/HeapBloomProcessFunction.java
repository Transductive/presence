package io.transductive.presence.flink;

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
 * @serial exclude
 */
final class HeapBloomProcessFunction<K, T> extends KeyedProcessFunction<K, T, T>
        implements CheckpointedFunction {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(HeapBloomProcessFunction.class);
    private final KeySelector<T, String> idSelector;
    private final PossibleDuplicateHandler<T> handler;
    private final BloomConfiguration configuration;
    private final TypeInformation<K> keyType;
    private final CheckpointAwareness checkpoints;
    private transient HeapBloomIndex<K> index;
    private transient Counter possibleDuplicates;
    private transient Counter forwarded;
    private transient Counter dropped;
    private transient boolean warned;
    private transient boolean restored;

    HeapBloomProcessFunction(KeySelector<T, String> idSelector, PossibleDuplicateHandler<T> handler,
            BloomConfiguration configuration, TypeInformation<K> keyType, CheckpointAwareness checkpoints) {
        this.idSelector = idSelector;
        this.handler = handler;
        this.configuration = configuration;
        this.keyType = keyType;
        this.checkpoints = checkpoints;
    }

    @Override
    public void open(OpenContext context) {
        var serializer = getRuntimeContext().createSerializer(keyType);
        index = new HeapBloomIndex<>(configuration, serializer::copy);
        var metrics = getRuntimeContext().getMetricGroup().addGroup("presence");
        possibleDuplicates = metrics.counter("possibleDuplicates");
        forwarded = metrics.counter("forwarded");
        dropped = metrics.counter("dropped");
        metrics.gauge("bloomGroups", index::groupCount);
        if (checkpoints.enabled() || restored) warnOnce();
    }

    @Override
    public void processElement(T event, Context context, Collector<T> output) throws Exception {
        String id = Objects.requireNonNull(idSelector.getKey(event), "Membership ID must not be null");
        if (index.observe(context.getCurrentKey(), id)) {
            possibleDuplicates.inc();
            Decision decision = Objects.requireNonNull(handler.onPossibleDuplicate(event),
                    "onPossibleDuplicate must return DROP or FORWARD");
            if (decision == Decision.DROP) {
                dropped.inc();
                return;
            }
        }
        forwarded.inc();
        output.collect(event);
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
}
