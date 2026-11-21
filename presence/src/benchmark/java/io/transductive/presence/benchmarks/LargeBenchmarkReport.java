package io.transductive.presence.benchmarks;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;

/** Uses the same template for automatic single-run and saved multi-run reviews. */
final class LargeBenchmarkReport {
    private LargeBenchmarkReport() { }

    static void write(Path directory, String mode, Collection<RunResult> results,
            List<BenchmarkReport.Profile> profiles) throws IOException {
        ResultFormatFactory.getInstance(ResultFormatType.JSON, directory.resolve("results.json").toString())
                .writeOut(results);
        BenchmarkReview.writeSingle(directory, mode);
    }
}
