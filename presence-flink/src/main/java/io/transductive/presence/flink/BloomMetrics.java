package io.transductive.presence.flink;

import java.time.Duration;

/** Metrics registered through Flink's metric group, independent of its reporter. */
public enum BloomMetrics {
    /** Existing decision/reset counters, live resident-group count, and managed-state I/O counters. */
    BASIC,
    /**
     * Basic metrics plus sampled aggregate Bloom statistics per operator subtask.
     * Sampling is synchronous on the operator thread after a successful record
     * when the interval has elapsed. It inspects all heap-resident filters, and
     * fixed filters scan their arrays. Reporter callbacks only read cached values.
     */
    DETAILED;

    /** Default minimum interval between detailed statistics refreshes. */
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(30);
}
