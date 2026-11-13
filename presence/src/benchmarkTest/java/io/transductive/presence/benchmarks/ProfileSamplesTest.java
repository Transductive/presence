package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileSamplesTest {
    @TempDir
    Path directory;

    @Test
    void countsConvertedStacksAndRequiresTheRendererToAgree() throws Exception {
        Path stacks = write("java/lang/Thread.run;MembershipBenchmark.lookup;BloomFilter.mightContain 162\n"
                + "java/lang/Thread.run;MembershipBenchmark.lookup;RapidHash.finish 36\n");
        assertEquals(198, ProfileSamples.convertedCount(stacks, "unpack(cpool);\nn(3,198)\nu(4)", 200));
        assertThrows(IOException.class, () -> ProfileSamples.convertedCount(stacks, "unpack(cpool);\nn(3,197)", 200));
    }

    @Test
    void rejectsTheEmptyGraphThatPreviouslyHidAvailableJdkSamples() throws Exception {
        Path stacks = write("");
        assertThrows(IOException.class, () -> ProfileSamples.convertedCount(stacks, "unpack(cpool);\nn(2)", 348));
        assertEquals(0, ProfileSamples.convertedCount(stacks, "unpack(cpool);\nn(2)", 0));
    }

    @Test
    void rejectsUnfilteredStacksAndInvalidCounts() throws Exception {
        Path stacks = write("java/lang/Thread.run;UnrelatedWork.run 10\n");
        assertThrows(IOException.class, () -> ProfileSamples.convertedCount(stacks, "unpack(cpool);\nn(3,10)", 0));
        write("MembershipBenchmark.lookup -1\n");
        assertThrows(IOException.class, () -> ProfileSamples.convertedCount(stacks, "unpack(cpool);\nn(3,10)", 1));
        write("MembershipBenchmark.lookup NaN\n");
        assertThrows(IOException.class, () -> ProfileSamples.convertedCount(stacks, "unpack(cpool);\nn(3,10)", 1));
    }

    @Test
    void rejectsMissingOrChangedRendererDataFormat() throws Exception {
        Path stacks = write("PrefilterBenchmark.bloomThenExact 1\n");
        assertThrows(IOException.class, () -> ProfileSamples.convertedCount(stacks, "<html>No frames</html>", 1));
        assertThrows(IOException.class, () -> ProfileSamples.convertedCount(stacks, "unpack(cpool);\nx(3,1)", 1));
    }

    private Path write(String content) throws IOException {
        Path stacks = directory.resolve("stacks.collapsed");
        Files.writeString(stacks, content);
        return stacks;
    }
}
