package io.transductive.presence.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/** Projects collapsed stacks onto Presence's namespace while preserving sample weights. */
final class ProfileFrames {
    private ProfileFrames() { }

    static long transductiveOnly(Path input, Path output) throws IOException {
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Full and focused stack files must be different");
        }
        Map<String, Long> stacks = new TreeMap<>();
        long retained = 0;
        try (var reader = Files.newBufferedReader(input)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) {
                    continue;
                }
                int separator = line.lastIndexOf(' ');
                if (separator < 1) {
                    throw new IOException("Missing collapsed-stack sample count");
                }
                long samples;
                try {
                    samples = Long.parseLong(line.substring(separator + 1));
                    if (samples <= 0) {
                        throw new NumberFormatException("Sample count must be positive");
                    }
                } catch (NumberFormatException exception) {
                    throw new IOException("Invalid collapsed-stack sample count", exception);
                }
                var frames = new ArrayList<String>();
                for (String frame : line.substring(0, separator).split(";", -1)) {
                    if (frame.isEmpty()) {
                        throw new IOException("Empty collapsed-stack frame");
                    }
                    if (frame.startsWith("io/transductive/") || frame.startsWith("io.transductive.")) {
                        frames.add(frame);
                    }
                }
                if (!frames.isEmpty()) {
                    String stack = String.join(";", frames);
                    try {
                        retained = Math.addExact(retained, samples);
                        stacks.merge(stack, samples, Math::addExact);
                    } catch (ArithmeticException exception) {
                        throw new IOException("Collapsed-stack sample count overflow", exception);
                    }
                }
            }
        }
        // Validate and aggregate before touching an existing focused file.
        var content = new StringBuilder();
        stacks.forEach((stack, samples) -> content.append(stack).append(' ').append(samples).append('\n'));
        Files.writeString(output, content);
        return retained;
    }
}
