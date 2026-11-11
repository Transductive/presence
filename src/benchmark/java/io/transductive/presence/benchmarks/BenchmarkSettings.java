package io.transductive.presence.benchmarks;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

record BenchmarkSettings(String mode, List<String> sizes, List<String> hits, String include, List<String> inputs,
        List<String> workingSets, List<String> queryModes, List<String> downstreamTokens, List<String> strategies) {
    static BenchmarkSettings parse(String mode, String sizes, String hits, String include) {
        return parse(mode, sizes, hits, include, "ASCII");
    }

    static BenchmarkSettings parse(String mode, String sizes, String hits, String include, String inputs) {
        return parse(mode, sizes, hits, include, inputs, "HOT", "CACHED", "0,256", "RAPIDHASH_V3");
    }

    static BenchmarkSettings parse(String mode, String sizes, String hits, String include, String inputs,
            String workingSets, String queryModes, String downstreamTokens, String strategies) {
        if (!List.of("full", "smoke", "profile", "allocation", "misses", "prefilter").contains(mode)) {
            throw new IllegalArgumentException("Unknown benchmark mode: " + mode);
        }
        var sortedSizes = new TreeSet<Integer>();
        sortedSizes.add(1_000); // Every comparison includes the requested baseline.
        for (String value : sizes.split(",", -1)) {
            int size = Integer.parseInt(value.trim());
            BenchmarkData.requireSize(size);
            sortedSizes.add(size);
        }
        var sortedHits = new TreeSet<Integer>();
        for (String value : hits.split(",", -1)) {
            int hit = Integer.parseInt(value.trim());
            if (hit != 0 && hit != 50 && hit != 100) {
                throw new IllegalArgumentException("benchmarkHits must contain only 0, 50, or 100");
            }
            sortedHits.add(hit);
        }
        // Validate the regex before creating an output directory or forking a JVM.
        java.util.regex.Pattern.compile(include);
        var inputKinds = Arrays.stream(inputs.split(",", -1)).map(String::trim).distinct().sorted().toList();
        if (!BenchmarkData.INPUT_KINDS.containsAll(inputKinds)) {
            throw new IllegalArgumentException("benchmarkInputs must use " + BenchmarkData.INPUT_KINDS);
        }
        var parsedSets = choices(workingSets, List.of("HOT", "BROAD"), "benchmarkWorkingSets");
        var parsedModes = choices(queryModes, List.of("CACHED", "FRESH"), "benchmarkQueryModes");
        var parsedTokens = new TreeSet<Integer>();
        for (String token : downstreamTokens.split(",", -1)) {
            int value = Integer.parseInt(token.trim());
            if (value < 0) {
                throw new IllegalArgumentException("benchmarkDownstreamTokens must be nonnegative");
            }
            parsedTokens.add(value);
        }
        var parsedStrategies = choices(strategies, Arrays.stream(
                io.transductive.presence.bloom.HashingStrategy.values()).map(Enum::name).toList(), "benchmarkStrategies");
        return new BenchmarkSettings(mode,
                sortedSizes.stream().map(String::valueOf).toList(),
                sortedHits.stream().map(String::valueOf).toList(), include, inputKinds, parsedSets, parsedModes,
                parsedTokens.stream().map(String::valueOf).toList(), parsedStrategies);
    }

    private static List<String> choices(String values, List<String> allowed, String name) {
        var parsed = Arrays.stream(values.split(",", -1)).map(String::trim).distinct().sorted().toList();
        if (!allowed.containsAll(parsed)) {
            throw new IllegalArgumentException(name + " must use " + allowed);
        }
        return parsed;
    }

    boolean smoke() {
        return mode.equals("smoke");
    }

    boolean profile() {
        return mode.equals("profile");
    }

    @Override
    public String toString() {
        return mode + "; N=" + Arrays.toString(sizes.toArray()) + "; hit%=" + hits + "; inputs=" + inputs
                + "; working sets=" + workingSets + "; queries=" + queryModes
                + "; downstream tokens=" + downstreamTokens + "; strategies=" + strategies;
    }
}
