package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/** Allocation audit of the calling thread, separate from JMH's process-wide GC metric. */
final class AllocationCheck {
    private static final int OPERATIONS = 100_000;
    private static volatile int sink;

    private AllocationCheck() { }

    static void run(Path directory) throws Exception {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("The allocation audit requires thread allocation counters (available in the pinned Temurin JDK)");
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        long thread = Thread.currentThread().threadId();
        bean.getThreadAllocatedBytes(thread); // Initialize the counter before measuring.
        var report = new StringBuilder("Presence lookup-thread allocation audit\n"
                + "Java: " + Runtime.version() + "\n"
                + "N=1000; 50% hits; prebuilt query pool; 300 ms warmup per case.\n"
                + "Counts allocations on the calling thread only; timings come from the separate JMH run.\n"
                + "Input construction, filter construction, warmup, and report writing are outside the counters.\n"
                + "Each case measures five batches of 20,000 lookups. Any nonzero total fails the audit.\n\n"
                + "Strategy\tInput\tLookups\tAllocated bytes\n");
        long total = 0;
        long lookups = 0;
        for (HashingStrategy strategy : HashingStrategy.values()) {
            for (String kind : BenchmarkData.INPUT_KINDS) {
                String[] keys = BenchmarkData.keys(1_000, kind);
                String[] queries = BenchmarkData.queries(keys, 50, kind);
                var filter = BloomFilter.forStrings(1_000, 0.01, strategy);
                for (String key : keys) {
                    filter.put(key);
                }
                long allocated = measure(bean, thread, filter, queries);
                total += allocated;
                lookups += OPERATIONS;
                report.append(strategy).append('\t').append(kind).append('\t').append(OPERATIONS)
                        .append('\t').append(allocated).append('\n');
            }
            String[] keys = BenchmarkData.keys(1_000, "LONG_UNICODE");
            String[] strings = BenchmarkData.queries(keys, 50, "LONG_UNICODE");
            byte[][] queries = new byte[strings.length][];
            var filter = BloomFilter.<byte[]>create(1_000, 0.01, Function.identity(), strategy);
            for (String key : keys) {
                filter.put(key.getBytes(StandardCharsets.UTF_8));
            }
            for (int i = 0; i < queries.length; i++) {
                queries[i] = strings[i].getBytes(StandardCharsets.UTF_8);
            }
            long allocated = measure(bean, thread, filter, queries);
            total += allocated;
            lookups += OPERATIONS;
            report.append(strategy).append("\tPREENCODED_BYTES\t").append(OPERATIONS)
                    .append('\t').append(allocated).append('\n');
        }
        report.append("\nTotal: ").append(total).append(" allocated bytes across ").append(lookups).append(" lookups.\n");
        Files.writeString(directory.resolve("allocation-check.txt"), report);
        System.out.println("Lookup-thread allocation audit: " + total + " bytes across " + lookups + " lookups.");
        if (total != 0) {
            throw new IllegalStateException("Lookup allocation audit failed; see " + directory.resolve("allocation-check.txt"));
        }
    }

    private static <T> long measure(com.sun.management.ThreadMXBean bean, long thread,
            BloomFilter<T> filter, T[] queries) {
        long deadline = System.nanoTime() + 300_000_000L;
        do {
            consume(filter, queries, 4_096);
        } while (System.nanoTime() < deadline);
        long allocated = 0;
        for (int batch = 0; batch < 5; batch++) {
            long before = bean.getThreadAllocatedBytes(thread);
            consume(filter, queries, OPERATIONS / 5);
            allocated += bean.getThreadAllocatedBytes(thread) - before;
        }
        return allocated;
    }

    private static <T> void consume(BloomFilter<T> filter, T[] queries, int operations) {
        int positives = 0;
        for (int i = 0; i < operations; i++) {
            if (filter.mightContain(queries[i & (BenchmarkData.QUERY_COUNT - 1)])) {
                positives++;
            }
        }
        sink = positives;
    }
}
