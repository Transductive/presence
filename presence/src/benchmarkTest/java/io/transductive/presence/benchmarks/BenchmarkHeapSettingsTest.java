package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkHeapSettingsTest {
    @Test
    void acceptsTheDefaultBoundaryAndLargerHeaps() {
        assertEquals("512m", BenchmarkRunner.maximumHeap("512m"));
        assertEquals("256m", BenchmarkRunner.maximumHeap("256M"));
        assertEquals("3g", BenchmarkRunner.maximumHeap("3g"));
        assertEquals("3g", BenchmarkRunner.maximumHeap("3G"));
    }

    @Test
    void rejectsUndersizedMalformedAndInjectedOptions() {
        for (String value : List.of("", "0m", "255m", "-1g", "+3g", "1.5g", "512", "512k", "3g ",
                "3g -XX:+UseSerialGC", "3g\n-Xmx4g", "-Xmx3g")) {
            assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.maximumHeap(value), value);
        }
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.maximumHeap(null));
    }

    @Test
    void rejectsIntegerAndByteConversionOverflow() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.maximumHeap("9223372036854775808g"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.maximumHeap("9223372036854775807m"));
    }
}
