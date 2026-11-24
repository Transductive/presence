package io.transductive.presence.otel.smoke;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.Meter;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;

/** Local smoke JVM diagnostics. MXBeans are safe to query from the SDK collection thread. */
final class JvmMetrics implements AutoCloseable {
    private final BatchCallback callback;

    static JvmMetrics register(Meter meter) {
        return new JvmMetrics(meter, ManagementFactory.getMemoryPoolMXBeans(),
                ManagementFactory.getGarbageCollectorMXBeans(),
                ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class));
    }

    JvmMetrics(Meter meter, List<MemoryPoolMXBean> memoryPools,
            List<GarbageCollectorMXBean> collectors, List<BufferPoolMXBean> bufferPools) {
        // Names come from the active JVM; do not invent generations for non-generational collectors.
        var pools = memoryPools.stream().map(bean -> new Pool(bean, Attributes.builder()
                .put("jvm.memory.pool.name", bean.getName())
                .put("jvm.memory.type", bean.getType() == MemoryType.HEAP ? "heap" : "non_heap").build())).toList();
        var gcs = collectors.stream().map(bean -> new Collector(bean,
                Attributes.builder().put("jvm.gc.name", bean.getName()).build())).toList();
        var buffers = bufferPools.stream().map(bean -> new BufferPool(bean,
                Attributes.builder().put("jvm.buffer.pool.name", bean.getName()).build())).toList();

        var used = meter.gaugeBuilder("presence.smoke.jvm.memory.pool.used").ofLongs().setUnit("By").buildObserver();
        var committed = meter.gaugeBuilder("presence.smoke.jvm.memory.pool.committed").ofLongs().setUnit("By").buildObserver();
        var limit = meter.gaugeBuilder("presence.smoke.jvm.memory.pool.limit").ofLongs().setUnit("By").buildObserver();
        var peak = meter.gaugeBuilder("presence.smoke.jvm.memory.pool.peak").ofLongs().setUnit("By")
                .setDescription("Peak used bytes since JVM startup or the last external peak reset.").buildObserver();
        var afterGc = meter.gaugeBuilder("presence.smoke.jvm.memory.pool.after_gc").ofLongs().setUnit("By")
                .setDescription("Usage after the most recent collection where supported; not current usage.").buildObserver();
        var gcCount = meter.counterBuilder("presence.smoke.jvm.gc.collections").setUnit("{collection}")
                .setDescription("Cumulative JVM-reported collections, by collector.").buildObserver();
        var gcTime = meter.counterBuilder("presence.smoke.jvm.gc.collection_time").ofDoubles().setUnit("s")
                .setDescription("Approximate cumulative collector elapsed time, not a stop-the-world pause histogram.")
                .buildObserver();
        var bufferUsed = meter.gaugeBuilder("presence.smoke.jvm.buffer.used").ofLongs().setUnit("By").buildObserver();
        var bufferCapacity = meter.gaugeBuilder("presence.smoke.jvm.buffer.capacity").ofLongs().setUnit("By").buildObserver();
        var bufferCount = meter.gaugeBuilder("presence.smoke.jvm.buffer.count").ofLongs().setUnit("{buffer}").buildObserver();
        callback = meter.batchCallback(() -> {
            for (var pool : pools) {
                var bean = pool.bean();
                if (!bean.isValid()) continue;
                var usage = bean.getUsage();
                if (usage == null) continue;
                used.record(usage.getUsed(), pool.attributes());
                committed.record(usage.getCommitted(), pool.attributes());
                // -1 means undefined; publishing zero would imply a capacity the JVM has not reported.
                if (usage.getMax() >= 0) limit.record(usage.getMax(), pool.attributes());
                var peakUsage = bean.getPeakUsage();
                if (peakUsage != null) peak.record(peakUsage.getUsed(), pool.attributes());
                var collectedUsage = bean.getCollectionUsage();
                if (collectedUsage != null) afterGc.record(collectedUsage.getUsed(), pool.attributes());
            }
            for (var gc : gcs) {
                if (!gc.bean().isValid()) continue;
                long count = gc.bean().getCollectionCount();
                long millis = gc.bean().getCollectionTime();
                if (count >= 0) gcCount.record(count, gc.attributes());
                if (millis >= 0) gcTime.record(millis / 1_000.0, gc.attributes());
            }
            for (var buffer : buffers) {
                long bytes = buffer.bean().getMemoryUsed();
                long capacity = buffer.bean().getTotalCapacity();
                long count = buffer.bean().getCount();
                if (bytes >= 0) bufferUsed.record(bytes, buffer.attributes());
                if (capacity >= 0) bufferCapacity.record(capacity, buffer.attributes());
                if (count >= 0) bufferCount.record(count, buffer.attributes());
            }
        }, used, committed, limit, peak, afterGc, gcCount, gcTime, bufferUsed, bufferCapacity, bufferCount);
    }

    @Override public void close() { callback.close(); }

    private record Pool(MemoryPoolMXBean bean, Attributes attributes) {}
    private record Collector(GarbageCollectorMXBean bean, Attributes attributes) {}
    private record BufferPool(BufferPoolMXBean bean, Attributes attributes) {}
}
