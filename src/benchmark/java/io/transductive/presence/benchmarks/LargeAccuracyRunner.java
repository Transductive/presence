package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.BlockedBloomFilter;
import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Separate calibration and correctness gates; these elapsed times are not lookup benchmarks. */
public final class LargeAccuracyRunner {
    private static final List<HashingStrategy> STRATEGIES = List.of(HashingStrategy.MURMUR3_128, HashingStrategy.RAPIDHASH_V3);
    private static final String SELECTION_RULE = "k=3..9; ten bisection candidates in8..18 bits/key per k; "
            + "training abs(delta)<=0.0000625; select least storage, then fewer probes, then smaller abs(delta); "
            + "bisection is a heuristic because block remapping need not be monotone; "
            + "one independent heldout gate per selected strategy; no lookup timing used";
    private static volatile int sink;

    record CalibrationDomains(int round, int training, int heldout) { }

    static CalibrationDomains calibrationDomains(String value) {
        int round = Integer.parseInt(value);
        if (round < 0 || round > 60) {
            throw new IllegalArgumentException("presence.large.calibrationRound must be from 0 through 60");
        }
        // Domains1..5 include the timing pool, prior observed calibration data,
        // final validation, and allocation audit. Every new round gets fresh IDs.
        return new CalibrationDomains(round, 6 + 2 * round, 7 + 2 * round);
    }

    record Candidate(HashingStrategy strategy, double bitsPerKey, int hashCount, long bitSize, long storageBytes,
            long standardFalsePositives, long falsePositives, int trials, long falseNegatives) {
        double delta() { return (double) (falsePositives - standardFalsePositives) / trials; }
    }

    record Accuracy(long bitSize, long storageBytes, int hashCount, int blockCount, double bitsPerKey,
            long misses, long falsePositives, long insertedChecked, long falseNegatives,
            long allocationLookups, long allocatedBytes) {
        String json() {
            var interval = LargeAccuracyStatistics.wilson(falsePositives, misses);
            return "{\"bitSize\":" + bitSize + ",\"storageBytes\":" + storageBytes + ",\"hashCount\":" + hashCount
                    + ",\"blockCount\":" + blockCount + ",\"bitsPerKey\":" + bitsPerKey + ",\"misses\":" + misses
                    + ",\"falsePositives\":" + falsePositives + ",\"rate\":" + ((double) falsePositives / misses)
                    + ",\"wilsonLower\":" + interval.lower() + ",\"wilsonUpper\":" + interval.upper()
                    + ",\"insertedChecked\":" + insertedChecked + ",\"falseNegatives\":" + falseNegatives
                    + ",\"allocationLookups\":" + allocationLookups + ",\"allocatedBytes\":" + allocatedBytes + "}";
        }
    }

    record Pair(HashingStrategy strategy, Accuracy standard, Accuracy blocked) {
        LargeAccuracyStatistics.Difference difference() {
            return LargeAccuracyStatistics.difference(standard.falsePositives(), blocked.falsePositives(), standard.misses());
        }
        boolean correct() {
            return standard.falseNegatives() == 0 && blocked.falseNegatives() == 0
                    && standard.allocatedBytes() == 0 && blocked.allocatedBytes() == 0;
        }
        String json() {
            var difference = difference();
            return "{\"strategy\":" + LargeBenchmarkData.json(strategy.name()) + ",\"standard\":" + standard.json()
                    + ",\"blocked\":" + blocked.json() + ",\"difference\":{\"estimate\":" + difference.estimate()
                    + ",\"lower\":" + difference.lower() + ",\"upper\":" + difference.upper()
                    + ",\"equivalent\":" + difference.equivalent() + "}}";
        }
    }

    private LargeAccuracyRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("calibrate") || args[0].equals("validate"))) {
            throw new IllegalArgumentException("Usage: calibrate outDir [N] [misses] | validate outDir N misses murmurBpk murmurK rapidBpk rapidK");
        }
        boolean calibrate = args[0].equals("calibrate");
        if (calibrate ? args.length > 4 : args.length != 8) {
            throw new IllegalArgumentException("Unexpected calibration/validation arguments");
        }
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 100_000;
        int misses = args.length > 3 ? Integer.parseInt(args[3]) : 2_000_000;
        LargeBenchmarkData.requireSize(size);
        LargeBenchmarkData.requireMissCount(misses);
        CalibrationDomains domains = calibrate
                ? calibrationDomains(System.getProperty("presence.large.calibrationRound", "0")) : null;
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("run.txt"), "mode=" + args[0] + "; N=" + size + "; misses=" + misses
                + "; FPR difference margin=" + LargeAccuracyStatistics.MARGIN + System.lineSeparator()
                + BenchmarkEnvironment.describe() + "Key IDs: nonnegative inserted IDs; disjoint negative domains for training, holdout, validation, queries, allocation.\n"
                + (domains == null ? "validationDomain=" + LargeBenchmarkData.VALIDATION_DOMAIN + "\n"
                        : "calibrationRound=" + domains.round() + "; trainingDomain=" + domains.training()
                                + "; heldoutDomain=" + domains.heldout() + "\nselectionRule=" + SELECTION_RULE + "\n")
                + "FPR intervals: Wilson95%; difference interval: two Wilson97.5% marginal intervals (Bonferroni), approximately95% simultaneous coverage.\n"
                + "Coverage applies per standard/blocked comparison, not familywise across strategies or dataset sizes.\n"
                + "Memory: bit-array payload only, excluding wrappers, headers, encoder workspace, queries, and JVM overhead.\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("Accuracy output directory: " + directory);
        if (calibrate) {
            calibrate(directory, size, misses, domains);
        } else {
            var pairs = new ArrayList<Pair>();
            pairs.add(validatePair(size, misses, Double.parseDouble(args[4]), Integer.parseInt(args[5]),
                    HashingStrategy.MURMUR3_128, LargeBenchmarkData.VALIDATION_DOMAIN));
            pairs.add(validatePair(size, misses, Double.parseDouble(args[6]), Integer.parseInt(args[7]),
                    HashingStrategy.RAPIDHASH_V3, LargeBenchmarkData.VALIDATION_DOMAIN));
            boolean referenceOnly = size == 1_000;
            boolean passed = pairs.stream().allMatch(pair -> pair.correct() && (referenceOnly || pair.difference().equivalent()));
            String json = "{\"N\":" + size + ",\"misses\":" + misses + ",\"margin\":" + LargeAccuracyStatistics.MARGIN
                    + ",\"validationDomain\":" + LargeBenchmarkData.VALIDATION_DOMAIN
                    + ",\"referenceOnly\":" + referenceOnly + ",\"passed\":" + passed + ",\"results\":" + pairsJson(pairs) + "}\n";
            Files.writeString(directory.resolve("accuracy.json"), json, StandardOpenOption.CREATE_NEW);
            writeAccuracyCsv(directory.resolve("accuracy.csv"), size, pairs);
            System.out.println("Accuracy JSON: " + directory.resolve("accuracy.json"));
            if (!passed) {
                throw new IllegalStateException("Accuracy gate failed: inspect false negatives, allocation, and FPR equivalence before timing");
            }
        }
    }

    private static void calibrate(Path directory, int size, int misses, CalibrationDomains domains) throws Exception {
        String[] training = new String[misses];
        for (int i = 0; i < misses; i++) {
            training[i] = LargeBenchmarkData.key(LargeBenchmarkData.absentId(domains.training(), size, i));
        }
        var all = new ArrayList<Candidate>();
        var selected = new ArrayList<Candidate>();
        for (HashingStrategy strategy : STRATEGIES) {
            var standard = BloomFilter.forStrings(size, 0.01, strategy);
            for (int i = 0; i < size; i++) {
                standard.put(LargeBenchmarkData.key(i));
            }
            long baseline = count(standard::mightContain, training);
            var candidates = new ArrayList<Candidate>();
            for (int probes = 3; probes <= 9; probes++) {
                double lower = 8;
                double upper = 18;
                for (int iteration = 0; iteration < 10; iteration++) {
                    double bits = (lower + upper) / 2;
                    var filter = new BlockedBloomFilter(size, bits, probes, strategy);
                    for (int i = 0; i < size; i++) {
                        filter.put(LargeBenchmarkData.key(i));
                    }
                    long falseNegatives = 0;
                    for (int i = 0; i < size; i++) {
                        if (!filter.mightContain(LargeBenchmarkData.key(i))) {
                            falseNegatives++;
                        }
                    }
                    long falsePositives = count(filter::mightContain, training);
                    var candidate = new Candidate(strategy, bits, probes, filter.bitSize(), filter.storageBytes(),
                            baseline, falsePositives, misses, falseNegatives);
                    candidates.add(candidate);
                    all.add(candidate);
                    System.out.printf(Locale.ROOT, "%s bpk=%.6f k=%d delta=%+.6f FN=%d%n",
                            strategy, bits, probes, candidate.delta(), falseNegatives);
                    if (falsePositives > baseline) {
                        lower = bits;
                    } else {
                        upper = bits;
                    }
                }
            }
            var choice = candidates.stream().filter(candidate -> candidate.falseNegatives() == 0
                    && Math.abs(candidate.delta()) <= LargeAccuracyStatistics.MARGIN / 8)
                    .min(Comparator.comparingLong(Candidate::storageBytes).thenComparingInt(Candidate::hashCount)
                            .thenComparingDouble(candidate -> Math.abs(candidate.delta())));
            writeCalibrationCsv(directory.resolve("calibration.csv"), all, size, misses, domains);
            if (choice.isEmpty()) {
                throw new IllegalStateException("No training candidate met the declared selection rule for " + strategy);
            }
            selected.add(choice.orElseThrow());
        }
        training = null; // The independently generated heldout stream does not reuse selection queries.
        var pairs = new ArrayList<Pair>();
        for (Candidate candidate : selected) {
            pairs.add(validatePair(size, misses, candidate.bitsPerKey(), candidate.hashCount(), candidate.strategy(),
                    domains.heldout()));
        }
        boolean passed = pairs.stream().allMatch(pair -> pair.correct() && pair.difference().equivalent());
        Candidate murmur = selected.get(0);
        Candidate rapid = selected.get(1);
        String parameters = "{\"murmurBlockedBitsPerKey\":" + murmur.bitsPerKey()
                + ",\"murmurBlockedHashCount\":" + murmur.hashCount() + ",\"rapidBlockedBitsPerKey\":" + rapid.bitsPerKey()
                + ",\"rapidBlockedHashCount\":" + rapid.hashCount() + "}";
        String json = "{\"N\":" + size + ",\"trainingMisses\":" + misses + ",\"heldoutMisses\":" + misses
                + ",\"calibrationRound\":" + domains.round() + ",\"trainingDomain\":" + domains.training()
                + ",\"heldoutDomain\":" + domains.heldout()
                + ",\"margin\":" + LargeAccuracyStatistics.MARGIN + ",\"passed\":" + passed
                + ",\"selectionRule\":" + LargeBenchmarkData.json(SELECTION_RULE)
                + ",\"parameters\":" + parameters + ",\"results\":" + pairsJson(pairs) + "}\n";
        Files.writeString(directory.resolve("selected.json"), json, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("selected.properties"), "murmurBlockedBitsPerKey=" + murmur.bitsPerKey()
                + "\nmurmurBlockedHashCount=" + murmur.hashCount() + "\nrapidBlockedBitsPerKey=" + rapid.bitsPerKey()
                + "\nrapidBlockedHashCount=" + rapid.hashCount() + "\n", StandardOpenOption.CREATE_NEW);
        writeAccuracyCsv(directory.resolve("heldout.csv"), size, pairs);
        System.out.println("Selection JSON: " + directory.resolve("selected.json"));
        System.out.println("Selected parameters: " + parameters);
        if (!passed) {
            throw new IllegalStateException("Independent calibration holdout failed; do not time these parameters");
        }
    }

    private static Pair validatePair(int size, int misses, double bits, int probes, HashingStrategy strategy, int domain) {
        System.out.println("Validating " + strategy + ": N=" + size + ", heldout misses=" + misses);
        var standard = BloomFilter.forStrings(size, 0.01, strategy);
        var blocked = new BlockedBloomFilter(size, bits, probes, strategy);
        for (int i = 0; i < size; i++) {
            String key = LargeBenchmarkData.key(i);
            standard.put(key);
            blocked.put(key);
        }
        long standardFn = 0;
        long blockedFn = 0;
        for (int i = 0; i < size; i++) {
            String key = LargeBenchmarkData.key(i); // Complete independent pass; fresh String identity.
            if (!standard.mightContain(key)) { standardFn++; }
            if (!blocked.mightContain(key)) { blockedFn++; }
        }
        long standardFp = 0;
        long blockedFp = 0;
        for (int i = 0; i < misses; i++) {
            String key = LargeBenchmarkData.key(LargeBenchmarkData.absentId(domain, size, i));
            if (standard.mightContain(key)) { standardFp++; }
            if (blocked.mightContain(key)) { blockedFp++; }
        }
        String[] auditQueries = new String[4_096];
        for (int i = 0; i < auditQueries.length; i++) {
            auditQueries[i] = LargeBenchmarkData.key(LargeBenchmarkData.absentId(LargeBenchmarkData.ALLOCATION_DOMAIN, size, i));
        }
        long standardAllocation = allocated(standard::mightContain, auditQueries);
        long blockedAllocation = allocated(blocked::mightContain, auditQueries);
        var first = new Accuracy(standard.bitSize(), ((standard.bitSize() + 63L) >>> 6) * Long.BYTES,
                standard.hashFunctionCount(), 0, (double) standard.bitSize() / size, misses, standardFp, size, standardFn,
                100_000, standardAllocation);
        var second = new Accuracy(blocked.bitSize(), blocked.storageBytes(), probes, blocked.blockCount(),
                (double) blocked.bitSize() / size, misses, blockedFp, size, blockedFn, 100_000, blockedAllocation);
        var pair = new Pair(strategy, first, second);
        System.out.printf(Locale.ROOT, "%s standard=%.6f blocked=%.6f difference95=[%+.6f,%+.6f] equivalent=%s FN=%d/%d allocation=%d/%d%n",
                strategy, (double) standardFp / misses, (double) blockedFp / misses,
                pair.difference().lower(), pair.difference().upper(), pair.difference().equivalent(),
                standardFn, blockedFn, standardAllocation, blockedAllocation);
        return pair;
    }

    private static long count(Predicate<String> filter, String[] queries) {
        long positives = 0;
        for (String query : queries) {
            if (filter.test(query)) { positives++; }
        }
        return positives;
    }

    private static long allocated(Predicate<String> filter, String[] queries) {
        var management = ManagementFactory.getThreadMXBean();
        if (!(management instanceof com.sun.management.ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Pinned JDK thread-allocation counters are required");
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) { bean.setThreadAllocatedMemoryEnabled(true); }
        long thread = Thread.currentThread().threadId();
        long until = System.nanoTime() + 300_000_000L;
        int value = 0;
        do {
            for (String query : queries) { value += filter.test(query) ? 1 : 0; }
        } while (System.nanoTime() < until);
        sink = value;
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100_000; i++) { value += filter.test(queries[i & (queries.length - 1)]) ? 1 : 0; }
        long after = bean.getThreadAllocatedBytes(thread);
        sink = value;
        return after - before;
    }

    private static String pairsJson(List<Pair> pairs) {
        return "[" + String.join(",", pairs.stream().map(Pair::json).toList()) + "]";
    }

    private static void writeCalibrationCsv(Path path, List<Candidate> candidates, int size, int misses,
            CalibrationDomains domains) throws Exception {
        var csv = new StringBuilder("strategy,bitsPerKey,hashCount,bitSize,storageBytes,standardFalsePositives,falsePositives,trials,delta,falseNegatives\n");
        for (Candidate row : candidates) {
            csv.append(row.strategy()).append(',').append(row.bitsPerKey()).append(',').append(row.hashCount()).append(',')
                    .append(row.bitSize()).append(',').append(row.storageBytes()).append(',').append(row.standardFalsePositives()).append(',')
                    .append(row.falsePositives()).append(',').append(row.trials()).append(',').append(row.delta()).append(',')
                    .append(row.falseNegatives()).append('\n');
        }
        Files.writeString(path, csv);
        var json = new StringBuilder("{\"N\":").append(size).append(",\"trainingMisses\":").append(misses)
                .append(",\"calibrationRound\":").append(domains.round()).append(",\"trainingDomain\":").append(domains.training())
                .append(",\"heldoutDomain\":").append(domains.heldout()).append(",\"selectionRule\":")
                .append(LargeBenchmarkData.json(SELECTION_RULE)).append(",\"candidates\":[");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate row = candidates.get(i);
            if (i != 0) { json.append(','); }
            json.append("{\"strategy\":").append(LargeBenchmarkData.json(row.strategy().name()))
                    .append(",\"bitsPerKey\":").append(row.bitsPerKey()).append(",\"hashCount\":").append(row.hashCount())
                    .append(",\"bitSize\":").append(row.bitSize()).append(",\"storageBytes\":").append(row.storageBytes())
                    .append(",\"standardFalsePositives\":").append(row.standardFalsePositives())
                    .append(",\"falsePositives\":").append(row.falsePositives()).append(",\"trials\":").append(row.trials())
                    .append(",\"delta\":").append(row.delta()).append(",\"falseNegatives\":").append(row.falseNegatives()).append('}');
        }
        Files.writeString(path.resolveSibling("calibration.json"), json.append("]}\n"));
    }

    private static void writeAccuracyCsv(Path path, int size, List<Pair> pairs) throws Exception {
        var csv = new StringBuilder("model,strategy,N,bitSize,storageBytes,hashCount,blockCount,bitsPerKey,misses,falsePositives,rate,wilsonLower,wilsonUpper,insertedChecked,falseNegatives,allocationLookups,allocatedBytes,differenceLower,differenceUpper,equivalent\n");
        for (Pair pair : pairs) {
            for (String model : List.of("standard", "blocked")) {
                Accuracy row = model.equals("standard") ? pair.standard() : pair.blocked();
                var interval = LargeAccuracyStatistics.wilson(row.falsePositives(), row.misses());
                csv.append(model).append(',').append(pair.strategy()).append(',').append(size).append(',').append(row.bitSize()).append(',')
                        .append(row.storageBytes()).append(',').append(row.hashCount()).append(',').append(row.blockCount()).append(',')
                        .append(row.bitsPerKey()).append(',').append(row.misses()).append(',').append(row.falsePositives()).append(',')
                        .append((double) row.falsePositives() / row.misses()).append(',').append(interval.lower()).append(',').append(interval.upper()).append(',')
                        .append(row.insertedChecked()).append(',').append(row.falseNegatives()).append(',').append(row.allocationLookups()).append(',')
                        .append(row.allocatedBytes()).append(',').append(pair.difference().lower()).append(',').append(pair.difference().upper()).append(',')
                        .append(pair.difference().equivalent()).append('\n');
            }
        }
        Files.writeString(path, csv, StandardOpenOption.CREATE_NEW);
    }
}
