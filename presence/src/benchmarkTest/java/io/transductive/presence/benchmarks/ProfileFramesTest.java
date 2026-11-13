package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileFramesTest {
    @TempDir
    Path directory;

    @Test
    void removesExternalRootsBridgesAndLeavesAndMergesWeights() throws Exception {
        String root = "io/transductive/presence/benchmarks/MembershipBenchmark.bloom_[j]";
        String leaf = "io/transductive/presence/bloom/BloomFilter.mightContain_[i]";
        String full = "java/lang/Thread.run;" + root + ";jdk/internal/reflect/Method.invoke;" + leaf + ";java/util/Arrays.fill 3\n"
                + "org/openjdk/jmh/runner/BenchmarkHandler.call;" + root + ";" + leaf + ";java/lang/String.hashCode 5\n";
        Path input = directory.resolve("full.collapsed"), output = directory.resolve("stacks.collapsed");
        Files.writeString(input, full);
        assertEquals(8, ProfileFrames.transductiveOnly(input, output));
        assertEquals(root + ";" + leaf + " 8\n", Files.readString(output));
        assertEquals(full, Files.readString(input));
        assertEquals(8, ProfileSamples.convertedCount(output, "unpack(cpool);\nn(0,8)\n", 8));
    }

    @Test
    void acceptsBothNamespaceNotationsAndPreservesRecursiveFrames() throws Exception {
        Path input = directory.resolve("full"), output = directory.resolve("focused");
        Files.writeString(input, "io.transductive.presence.Work.run;io/transductive/presence/Work.run;io/transductive/presence/Work.run 2\n"
                + "io/transductiveExtra/Work.run;io.transductiveExtra.Work.run;java/lang/Thread.run 4\n");
        assertEquals(2, ProfileFrames.transductiveOnly(input, output));
        assertEquals("io.transductive.presence.Work.run;io/transductive/presence/Work.run;io/transductive/presence/Work.run 2\n",
                Files.readString(output));
    }

    @Test
    void rejectsInvalidCountsBeforeChangingAnExistingGraphInput() throws Exception {
        Path input = directory.resolve("full"), output = directory.resolve("focused");
        Files.writeString(output, "existing");
        for (String invalid : new String[] {"-1", "0", "NaN", "9223372036854775808"}) {
            Files.writeString(input, "io/transductive/Work.run " + invalid + "\n");
            assertThrows(IOException.class, () -> ProfileFrames.transductiveOnly(input, output));
            assertEquals("existing", Files.readString(output));
        }
        Files.writeString(input, "io/transductive/Work.run 9223372036854775807\nio/transductive/Work.run 1\n");
        assertThrows(IOException.class, () -> ProfileFrames.transductiveOnly(input, output));
        assertEquals("existing", Files.readString(output));
        assertThrows(IllegalArgumentException.class, () -> ProfileFrames.transductiveOnly(input, input));
    }

    @Test
    void noMatchingFramesProducesAnExplicitlyEmptyStackFile() throws Exception {
        Path input = directory.resolve("full"), output = directory.resolve("focused");
        Files.writeString(input, "\njava/lang/Thread.run;java/util/HashMap.containsKey 10\n");
        assertEquals(0, ProfileFrames.transductiveOnly(input, output));
        assertTrue(Files.readString(output).isEmpty());
        assertThrows(IOException.class, () -> ProfileSamples.convertedCount(output, "unpack(cpool);\nn(0)\n", 10));
    }
}
