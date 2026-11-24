package io.transductive.presence.flink;

/** Membership is always queried in heap; implementations differ in recovery ownership. */
interface BloomIndex<K> {
    default void prepare(K key) throws Exception {}
    boolean observe(K key, String id) throws Exception;
    long eventsSinceReset(K key);
    boolean clear(K key);
    int groupCount();
    /** Samples only resident heap filters on the owning operator thread; never reads managed state. */
    BloomStatistics statistics();
    void clear();
}
