package io.transductive.presence.benchmarks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** JSON and number formatting for local report artifacts. */
final class ReportJson {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();

    private ReportJson() { }

    static JsonElement read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            JsonElement value = JSON.fromJson(reader, JsonElement.class);
            if (value == null) {
                throw new IllegalArgumentException("Empty report input: " + path);
            }
            return value;
        }
    }

    static void write(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.toJson(value) + System.lineSeparator());
    }

    static String text(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing report field: " + key);
        }
        return object.get(key).getAsString();
    }

    static long count(JsonObject object, String key) {
        long value = object.get(key).getAsBigDecimal().longValueExact();
        if (value < 0) {
            throw new IllegalArgumentException("Negative report count: " + key);
        }
        return value;
    }

    static double finite(JsonObject object, String key) {
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite report field: " + key);
        }
        return value;
    }

    static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%,.3f", value) : "n/a";
    }

    static String integer(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
