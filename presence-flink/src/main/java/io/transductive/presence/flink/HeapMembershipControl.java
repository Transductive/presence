package io.transductive.presence.flink;

import org.apache.flink.metrics.Counter;

/** A record-scoped handle; closing it releases all references to task membership and the key. */
final class HeapMembershipControl<K> implements MembershipControl, AutoCloseable {
    private Thread owner = Thread.currentThread();
    private BloomIndex<K> index;
    private K key;
    private Counter resets;
    private BloomStatisticsMetrics statistics;

    HeapMembershipControl(BloomIndex<K> index, K key, Counter resets) {
        this(index, key, resets, null);
    }

    HeapMembershipControl(BloomIndex<K> index, K key, Counter resets, BloomStatisticsMetrics statistics) {
        this.index = index;
        this.key = key;
        this.resets = resets;
        this.statistics = statistics;
    }

    @Override
    public long eventsSinceReset() {
        requireActive();
        return index.eventsSinceReset(key);
    }

    @Override
    public void clearCurrentKey() {
        requireActive();
        if (index.clear(key)) resets.inc();
    }

    @Override
    public void refreshStatistics() {
        requireActive();
        if (statistics == null) throw new IllegalStateException("Enable BloomMetrics.DETAILED to refresh statistics");
        statistics.refresh();
    }

    private void requireActive() {
        if (Thread.currentThread() != owner || index == null) {
            throw new IllegalStateException("MembershipControl is only valid during beforeLookup on the operator thread");
        }
    }

    @Override
    public void close() {
        owner = null;
        index = null;
        key = null;
        resets = null;
        statistics = null;
    }
}
