package io.transductive.presence.site;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlaygroundRunnerTest {
    private static final String LIBRARY = System.getProperty("presence.playground.jar");

    @Test void shippedExampleCompilesAndRuns() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("presence.playground.example")));
        assertEquals("", PlaygroundRunner.compile(source, LIBRARY));
        String result = PlaygroundRunner.run();
        assertTrue(result.startsWith("{\"ok\":true,"));
        assertTrue(result.contains("alice: true"));
    }

    @Test void compilesAndRunsTheRealLibraryWithUnicode() throws Exception {
        String source = """
                import io.transductive.presence.bloom.BloomFilter;
                public class Playground {
                    public static void main(String[] args) {
                        var filter = BloomFilter.forStrings(1000, 0.01);
                        filter.put("世界");
                        System.out.println("世界: " + filter.mightContain("世界"));
                        System.err.println("stderr");
                    }
                }
                """;
        assertEquals("", PlaygroundRunner.compile(source, LIBRARY));
        assertEquals("{\"ok\":true,\"output\":\"世界: true\\nstderr\\n\"}", PlaygroundRunner.run().replace("\\r\\n", "\\n"));
    }

    @Test void scalableExampleGrowsAndPreservesEveryInsertedValue() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                System.getProperty("presence.playground.scalableExample")));
        assertEquals("", PlaygroundRunner.compile(source, LIBRARY));
        String result = PlaygroundRunner.run();
        assertTrue(result.startsWith("{\"ok\":true,"), result);
        assertTrue(result.contains("Starting layers: 1"), result);
        assertTrue(java.util.regex.Pattern.compile("Layers after 5,000 inserts: ([2-9]|[1-9][0-9]+)\\\\[rn]")
                .matcher(result).find(), result);
        assertTrue(result.contains("First member: true"), result);
        assertTrue(result.contains("Last member:  true"), result);
        assertTrue(result.contains("Inserted values missing: 0"), result);
    }

    @Test void reportsLineNumbersAndDoesNotReuseAnEarlierProgram() throws Exception {
        assertEquals("", PlaygroundRunner.compile(program("System.out.print(42);"), LIBRARY));
        assertTrue(PlaygroundRunner.compile("public class Playground { broken }", LIBRARY).contains("Line 1:"));
        assertTrue(PlaygroundRunner.run().contains("Compile a valid Playground"));
    }

    @Test void repeatedRunsUseFreshClasses() throws Exception {
        for (int value : new int[]{1, 2}) {
            assertEquals("", PlaygroundRunner.compile(program("System.out.print(" + value + ");"), LIBRARY));
            assertEquals("{\"ok\":true,\"output\":\"" + value + "\"}", PlaygroundRunner.run());
        }
    }

    @Test void capturesExceptionsAndRestoresStreams() throws Exception {
        var original = System.out;
        assertEquals("", PlaygroundRunner.compile(program("throw new IllegalStateException(\"example\");"), LIBRARY));
        String result = PlaygroundRunner.run();
        assertTrue(result.startsWith("{\"ok\":false,"));
        assertTrue(result.contains("IllegalStateException: example"));
        assertSame(original, System.out);
    }

    @Test void boundsOutputAndSourceAndRequiresAnEntrypoint() throws Exception {
        assertEquals("", PlaygroundRunner.compile(program("System.out.print(\"x\".repeat(100000));"), LIBRARY));
        String result = PlaygroundRunner.run();
        assertTrue(result.contains("Output limited to 32 KiB"));
        assertTrue(result.length() < 33000);
        assertFalse(PlaygroundRunner.compile(" ".repeat(32769), LIBRARY).isEmpty());
        assertFalse(PlaygroundRunner.compile("public class Playground {}", LIBRARY).isEmpty());
    }

    private static String program(String body) {
        return "public class Playground { public static void main(String[] args) { " + body + " } }";
    }
}
