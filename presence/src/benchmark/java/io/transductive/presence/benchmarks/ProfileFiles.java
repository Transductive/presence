package io.transductive.presence.benchmarks;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

final class ProfileFiles {
    record Prepared(String label, Path recording) { }

    private ProfileFiles() { }

    static Prepared shorten(Path reportDirectory, Path recording, int sequence) throws IOException {
        if (sequence < 1) {
            throw new IllegalArgumentException("Profile sequence must be positive");
        }
        Path root = reportDirectory.toAbsolutePath().normalize().resolve("jfr");
        Path source = recording.toAbsolutePath().normalize();
        Path sourceDirectory = source.getParent();
        Path target = root.resolve(String.format(Locale.ROOT, "profile-%03d", sequence)).normalize();
        // JMH creates one immediate child directory per benchmark. Never move the jfr root itself.
        if (sourceDirectory == null || !source.startsWith(root) || !root.equals(sourceDirectory.getParent())
                || !target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Profile source and destination must stay inside the run's jfr directory");
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing regular JFR recording: " + source);
        }
        Path realRoot = root.toRealPath();
        if (!realRoot.equals(reportDirectory.toRealPath().resolve("jfr"))
                || !sourceDirectory.toRealPath().equals(realRoot.resolve(sourceDirectory.getFileName()))) {
            throw new IllegalArgumentException("Profile directories must not redirect outside their expected paths");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (Files.exists(sourceDirectory.resolve("benchmark.txt"), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(sourceDirectory.resolve("benchmark.txt").toString());
        }
        String label = sourceDirectory.getFileName().toString();
        // No REPLACE_EXISTING: a collision fails without overwriting another profile.
        Files.move(sourceDirectory, target);
        Files.writeString(target.resolve("benchmark.txt"), label + System.lineSeparator(), StandardOpenOption.CREATE_NEW);
        return new Prepared(label, target.resolve(source.getFileName()));
    }
}
