package io.transductive.presence.benchmarks;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Shared HTML renderer, confined to the benchmark dependency graph. */
final class ReportTemplate {
    private static final PebbleEngine ENGINE;

    static {
        var loader = new ClasspathLoader();
        loader.setPrefix("reports/");
        loader.setCharset(StandardCharsets.UTF_8.name());
        ENGINE = new PebbleEngine.Builder().loader(loader).strictVariables(true).autoEscaping(true).build();
    }

    private ReportTemplate() { }

    static void write(Path output, String template, Map<String, Object> context) throws IOException {
        // Finish rendering before touching an existing report if a template/data error occurs.
        var writer = new StringWriter();
        ENGINE.getTemplate(template).evaluate(writer, context);
        Files.writeString(output, writer.toString(), StandardCharsets.UTF_8);
    }
}
