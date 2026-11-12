import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteServerTest {
    @TempDir Path temporary;

    @Test void supportsJarRangesUnderAProjectPrefix() throws Exception {
        Files.writeString(temporary.resolve("sample.jar"), "0123456789");
        Files.writeString(temporary.resolve("index.html"), "Preview");
        var server = SiteServer.start(temporary, 0, "/presence/");
        try (var client = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/presence/";
            for (String[] example : new String[][] {
                    {"bytes=0-3", "0123", "bytes 0-3/10"},
                    {"bytes=-2", "89", "bytes 8-9/10"},
                    {"bytes=7-", "789", "bytes 7-9/10"},
                    {"bytes=8-99", "89", "bytes 8-9/10"}}) {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "sample.jar"))
                        .header("Range", example[0]).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(206, response.statusCode());
                assertEquals(example[1], response.body());
                assertEquals(example[2], response.headers().firstValue("Content-Range").orElseThrow());
            }
            for (String range : new String[]{"bytes=10-", "bytes=5-2", "bytes=-0", "bytes=9999999999999999999999-"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "sample.jar"))
                        .header("Range", range).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(416, response.statusCode(), range);
            }
            var page = client.send(HttpRequest.newBuilder(URI.create(base)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertEquals("Preview", page.body());
            var redirect = client.send(HttpRequest.newBuilder(URI.create(base.substring(0, base.length() - 1))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(301, redirect.statusCode());
            assertEquals("/presence/", redirect.headers().firstValue("Location").orElseThrow());
        } finally {
            server.stop(0);
        }
    }

    @Test void doesNotServeFilesAboveTheDocumentRoot() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("public"));
        Files.writeString(temporary.resolve("private.jar"), "outside-root-content");
        var server = SiteServer.start(root, 0, "/presence/");
        try (var client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/presence/%2e%2e/private.jar");
            var response = client.send(HttpRequest.newBuilder(uri).header("Range", "bytes=0-2").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
            assertFalse(response.body().contains("outside-root-content"));
        } finally {
            server.stop(0);
        }
    }
}
