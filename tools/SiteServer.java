import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Local-only static preview. CheerpJ needs byte ranges when loading JARs repeatedly. */
class SiteServer {
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[1]);
        String prefix = args[2];
        HttpServer server = start(Path.of(args[0]), port, prefix);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        System.out.println("Presence: http://127.0.0.1:" + port + prefix + " (Ctrl+C to stop; restart after edits)");
    }

    static HttpServer start(Path directory, int port, String prefix) throws IOException {
        Path root = directory.toRealPath();
        if (!prefix.startsWith("/") || !prefix.endsWith("/")) throw new IllegalArgumentException("Expected a base path ending in /.");
        var files = SimpleFileServer.createFileHandler(root);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        if (!"/".equals(prefix)) {
            String withoutSlash = prefix.substring(0, prefix.length() - 1);
            server.createContext(withoutSlash, exchange -> {
                boolean redirect = exchange.getRequestURI().getPath().equals(withoutSlash);
                if (redirect) exchange.getResponseHeaders().set("Location", prefix);
                exchange.sendResponseHeaders(redirect ? 301 : 404, -1);
                exchange.close();
            });
        }
        server.createContext(prefix, exchange -> {
            try {
                if (!serveJarRange(exchange, root, prefix)) files.handle(exchange);
            } catch (IOException e) {
                exchange.close(); // A browser may abort an outstanding request when Stop terminates its worker.
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static boolean serveJarRange(HttpExchange exchange, Path root, String prefix) throws IOException {
        String value = exchange.getRequestHeaders().getFirst("Range");
        String path = exchange.getRequestURI().getPath();
        if (!"GET".equals(exchange.getRequestMethod()) || value == null || !path.endsWith(".jar")) return false;
        var range = RANGE.matcher(value);
        if (!range.matches()) return false; // Unsupported range syntax falls back to a complete response.
        Path file = root.resolve(path.substring(prefix.length())).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root)) return false;
        long size = Files.size(file);
        long start;
        long end;
        try {
            if (range.group(1).isEmpty()) {
                long suffix = Long.parseLong(range.group(2));
                if (suffix <= 0) throw new IllegalArgumentException();
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(range.group(1));
                end = range.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(range.group(2)), size - 1);
            }
            if (start >= size || end < start) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return true;
        }
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
        exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        long remaining = end - start + 1;
        exchange.sendResponseHeaders(206, remaining);
        try (var input = new RandomAccessFile(file.toFile(), "r"); var output = exchange.getResponseBody()) {
            input.seek(start);
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) throw new IOException("JAR changed during a range request.");
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
        return true;
    }
}
