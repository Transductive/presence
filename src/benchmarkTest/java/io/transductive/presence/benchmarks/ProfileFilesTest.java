package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileFilesTest {
    @TempDir
    Path directory;

    @Test
    void shortensParameterHeavyPathsAndPreservesRecordingAndFullLabel() throws Exception {
        String label = "MembershipBenchmark.bloomRapidhash-N1000000-hitPercent0-inputKindASCII-"
                + "queryModeCACHED-workingSetBROAD-" + "parameter".repeat(10);
        Path original = Files.createDirectories(directory.resolve("jfr").resolve(label)).resolve("profile.jfr");
        byte[] bytes = {0x46, 0x4c, 0x52, 0, 1, 2, 3, 4};
        Files.write(original, bytes);
        var prepared = ProfileFiles.shorten(directory, original, 1);
        assertEquals(directory.resolve("jfr/profile-001/profile.jfr").toAbsolutePath().normalize(), prepared.recording());
        assertEquals(label, prepared.label());
        assertArrayEquals(bytes, Files.readAllBytes(prepared.recording()));
        assertEquals(label + System.lineSeparator(), Files.readString(prepared.recording().resolveSibling("benchmark.txt")));
        assertFalse(Files.exists(original.getParent()));
        assertTrue(prepared.recording().toString().length() < original.toString().length());
    }

    @Test
    void rejectsExistingDestinationWithoutChangingEitherProfile() throws Exception {
        Path original = Files.createDirectories(directory.resolve("jfr/original")).resolve("profile.jfr");
        Files.writeString(original, "original recording");
        Path existing = Files.createDirectories(directory.resolve("jfr/profile-001")).resolve("profile.jfr");
        Files.writeString(existing, "existing recording");
        assertThrows(FileAlreadyExistsException.class, () -> ProfileFiles.shorten(directory, original, 1));
        assertEquals("original recording", Files.readString(original));
        assertEquals("existing recording", Files.readString(existing));
    }

    @Test
    void rejectsPathsOutsideTheProfileRootAndNeverMovesTheRootItself() throws Exception {
        Files.createDirectories(directory.resolve("jfr"));
        Path outside = Files.createDirectories(directory.resolve("outside")).resolve("profile.jfr");
        Files.writeString(outside, "outside recording");
        Path traversal = directory.resolve("jfr/../outside/profile.jfr");
        assertThrows(IllegalArgumentException.class, () -> ProfileFiles.shorten(directory, traversal, 1));
        assertThrows(IllegalArgumentException.class, () -> ProfileFiles.shorten(directory, directory.resolve("jfr/profile.jfr"), 1));
        assertThrows(IllegalArgumentException.class, () -> ProfileFiles.shorten(directory, outside, 0));
        assertEquals("outside recording", Files.readString(outside));
        assertTrue(Files.isDirectory(directory.resolve("jfr")));
        assertFalse(Files.exists(directory.resolve("jfr/profile-001")));
    }
}
