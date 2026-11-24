package io.transductive.presence.flink;

/** An immutable sample of resident filters in one operator subtask; not checkpoint state. */
record BloomStatistics(long groups, long layers, long bitArrayBytes, double estimatedElements,
        double maximumActiveLayerFillRatio, double maximumEstimatedFalsePositiveProbability) {
    static final BloomStatistics EMPTY = new BloomStatistics(0, 0, 0, 0, 0, 0);

    /** Created once per refresh, never once per record or per stream key. */
    static final class Accumulator {
        private long groups;
        private long layers;
        private long bytes;
        private double elements;
        private double maximumFill;
        private double maximumProbability;

        void add(BloomGroup group) {
            groups++;
            layers += group.layerCount();
            bytes += group.bitArrayBytes();
            elements += group.estimatedElementCount();
            maximumFill = Math.max(maximumFill, group.activeLayerFillRatio());
            // Different keys query different filters. Summing their FPPs would
            // misrepresent the risk of any one lookup; expose the worst filter.
            maximumProbability = Math.max(maximumProbability, group.estimatedFalsePositiveProbability());
        }

        BloomStatistics snapshot() {
            return new BloomStatistics(groups, layers, bytes, elements, maximumFill, maximumProbability);
        }
    }
}
