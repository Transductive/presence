package io.transductive.presence.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Checks the stacks actually sent to the pinned flamegraph renderer. */
final class ProfileSamples {
    private static final Pattern ROOT = Pattern.compile("(?m)^n\\(\\d+(?:,(\\d+))?\\)\\s*$");
    private static final Pattern BENCHMARK = Pattern.compile(".*(Membership|Construction|Prefilter)Benchmark.*");

    private ProfileSamples() { }

    static long convertedCount(Path collapsed, String html, long sourceSamples) throws IOException {
        long samples = 0;
        try (var lines = Files.newBufferedReader(collapsed)) {
            for (String line; (line = lines.readLine()) != null;) {
                if (line.isBlank()) {
                    continue;
                }
                int separator = line.lastIndexOf(' ');
                if (separator < 1 || !BENCHMARK.matcher(line.substring(0, separator)).matches()) {
                    throw new IOException("Converted profile contains an unrecognized or unrelated stack");
                }
                try {
                    long count = Long.parseLong(line.substring(separator + 1));
                    if (count <= 0) {
                        throw new NumberFormatException("Sample count must be positive");
                    }
                    samples = Math.addExact(samples, count);
                } catch (NumberFormatException | ArithmeticException exception) {
                    throw new IOException("Invalid converted profile sample count", exception);
                }
            }
        }
        int framesStart = html.indexOf("unpack(cpool);");
        var root = ROOT.matcher(html);
        if (framesStart < 0 || !root.find(framesStart)) {
            throw new IOException("Missing flamegraph root in converter output");
        }
        long rendered = root.group(1) == null ? 0 : Long.parseLong(root.group(1));
        if (rendered != samples || (sourceSamples > 0 && samples == 0)) {
            throw new IOException("Profile sample mismatch: source=" + sourceSamples
                    + ", converted=" + samples + ", rendered=" + rendered);
        }
        return samples;
    }
}
