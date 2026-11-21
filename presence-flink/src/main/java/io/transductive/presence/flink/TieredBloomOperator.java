package io.transductive.presence.flink;

import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;

/**
 * Supplies keyed state and switches key context while flushing all dirty heap groups.
 * @param <K> stream group key type
 * @param <T> input record type
 * @param <OUT> output record type
 * @serial exclude
 */
final class TieredBloomOperator<K, T, OUT> extends KeyedProcessOperator<K, T, OUT> {
    private static final long serialVersionUID = 1L;
    static final String LAYERS_STATE = "presence-bloom-layers-v1";
    static final String METADATA_STATE = "presence-bloom-metadata-v1";

    TieredBloomOperator(HeapBloomProcessFunction<K, T, OUT> function) { super(function); }

    @Override
    public void open() throws Exception {
        var layers = getRuntimeContext().getMapState(
                new MapStateDescriptor<>(LAYERS_STATE, Types.INT, PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO));
        var metadata = getRuntimeContext().getState(
                new ValueStateDescriptor<>(METADATA_STATE, PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO));
        function().setManagedStore(new TieredBloomIndex.Store<K>() {
            @Override public void select(K key) { setCurrentKey(key); }
            @Override public byte[] metadata() throws Exception { return metadata.value(); }
            @Override public byte[] layer(int index) throws Exception { return layers.get(index); }
            @Override public void metadata(byte[] bytes) throws Exception { metadata.update(bytes); }
            @Override public void layer(int index, byte[] bytes) throws Exception { layers.put(index, bytes); }
            @Override public void clearLayers() throws Exception { layers.clear(); }
        });
        super.open();
    }

    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {
        Object previousKey = getCurrentKey();
        try {
            function().flushManagedState();
        } finally {
            if (previousKey != null) setCurrentKey(previousKey);
        }
        super.snapshotState(context);
    }

    @SuppressWarnings("unchecked")
    private HeapBloomProcessFunction<K, T, OUT> function() {
        return (HeapBloomProcessFunction<K, T, OUT>) getUserFunction();
    }
}
