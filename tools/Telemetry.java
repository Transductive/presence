import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/** Cross-platform, repository-local telemetry commands; invoked through mise. */
class Telemetry {
    static final String CLUSTER = "presence-telemetry";
    static final String NAMESPACE = "presence-telemetry";
    static final String OWNER_LABEL = "io.transductive.presence.telemetry";
    private static final String SERVER = "k3d-" + CLUSTER + "-server-0";
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path STATE = ROOT.resolve(".local/telemetry");
    private static final Path KUBECONFIG = STATE.resolve("kubeconfig.yaml");
    private static final Path CONFIG = ROOT.resolve("infra/telemetry");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        try {
            String action = args.length == 0 ? "help" : args[0];
            // Validate arguments before any infrastructure changes.
            boolean smoke = List.of("smoke", "flink-smoke", "flink-rocksdb").contains(action);
            double minutes = smoke ? smokeMinutes(Arrays.copyOfRange(args, 1, args.length)) : 1;
            if (!smoke && args.length > 1) throw new IllegalArgumentException("Unexpected arguments.");
            switch (action) {
                case "up" -> up();
                case "smoke" -> smoke(minutes, false, false);
                case "flink-smoke" -> smoke(minutes, true, false);
                case "flink-rocksdb" -> smoke(minutes, true, true);
                case "status" -> status();
                case "down" -> stop();
                case "reset" -> reset();
                case "help" -> System.out.println("mise run telemetry:{up|smoke|flink|flink:rocksdb|status|down|reset}\n"
                        + "smoke and flink accept --minutes N (default 1). down preserves data; reset deletes this cluster and its data.");
                default -> throw new IllegalArgumentException("Unknown telemetry command: " + action);
            }
        } catch (Exception error) {
            System.err.println("Presence telemetry: " + error.getMessage());
            System.err.println("Inspect with: mise run telemetry:status");
            System.exit(1);
        }
    }

    static double smokeMinutes(String[] args) {
        if (args.length == 0) return 1;
        if (args.length != 2 || !args[0].equals("--minutes")) {
            throw new IllegalArgumentException("Usage: mise run telemetry:{smoke|flink|flink:rocksdb} -- --minutes N");
        }
        double value = Double.parseDouble(args[1]);
        if (!Double.isFinite(value) || value * Duration.ofMinutes(1).toNanos() < 1 || value > 1_440) {
            throw new IllegalArgumentException("minutes must be greater than zero and at most 1440");
        }
        return value;
    }

    private static void up() throws Exception {
        if (!Files.isRegularFile(CONFIG.resolve("versions.properties"))) {
            throw new IOException("Run this command from the Presence repository root.");
        }
        if (!capture("docker", "info", "--format", "{{.OSType}}").trim().equals("linux")) {
            throw new IOException("Start Docker with Linux containers enabled.");
        }
        Files.createDirectories(STATE);
        var versions = new Properties();
        try (var input = Files.newInputStream(CONFIG.resolve("versions.properties"))) { versions.load(input); }
        if (!exists()) {
            checkPorts();
            run("k3d", "cluster", "create", CLUSTER,
                    "--image", versions.getProperty("k3s.image"), "--servers", "1", "--agents", "0",
                    "--servers-memory", "4g", "--api-port", "127.0.0.1:6447",
                    "--kubeconfig-update-default=false", "--kubeconfig-switch-context=false",
                    "--runtime-label", OWNER_LABEL + "=true@server:0",
                    "--port", "127.0.0.1:3000:30300@server:0",
                    "--port", "127.0.0.1:4317:30417@server:0",
                    "--port", "127.0.0.1:4318:30418@server:0",
                    "--port", "127.0.0.1:8428:30428@server:0",
                    "--k3s-arg", "--disable=traefik,servicelb,metrics-server@server:0", "--timeout", "180s");
        } else {
            requireOwned();
            if (!capture("docker", "inspect", SERVER, "--format", "{{.State.Running}}").trim().equals("true")) {
                checkPorts();
                run("k3d", "cluster", "start", CLUSTER, "--wait", "--timeout", "180s");
            }
        }
        // Capture credentials without printing them; never merge into the user's default kubeconfig.
        Files.writeString(KUBECONFIG, capture("k3d", "kubeconfig", "get", CLUSTER));
        kube("wait", "--for=condition=Ready", "nodes", "--all", "--timeout=180s");
        String namespace = kubeCapture("create", "namespace", NAMESPACE, "--dry-run=client", "-o", "yaml");
        kubeApply(namespace);
        String dashboard = kubeCapture("create", "configmap", "presence-dashboard", "-n", NAMESPACE,
                "--from-file=presence-smoke.json=" + CONFIG.resolve("dashboard.json"),
                "--from-file=presence-flink-smoke.json=" + CONFIG.resolve("flink-dashboard.json"),
                "--dry-run=client", "-o", "yaml");
        kubeApply(dashboard);
        install("presence-vm", "victoria-metrics-single", "https://victoriametrics.github.io/helm-charts/",
                versions.getProperty("victoriametrics.chart"), "victoriametrics.yaml");
        install("presence-collector", "opentelemetry-collector", "https://open-telemetry.github.io/opentelemetry-helm-charts",
                versions.getProperty("collector.chart"), "collector.yaml");
        install("presence-grafana", "grafana", "https://grafana-community.github.io/helm-charts",
                versions.getProperty("grafana.chart"), "grafana.yaml");
        awaitReady("http://127.0.0.1:8428/health", null);
        awaitReady("http://127.0.0.1:3000/api/health", null);
        System.out.println("\nGrafana: http://localhost:3000/d/presence-smoke/presence-smoke?theme=light\n"
                + "Flink dashboard: http://localhost:3000/d/presence-flink-smoke/presence-flink?theme=light\n"
                + "OTLP/HTTP metrics: http://localhost:4318/v1/metrics\n"
                + "OTLP/gRPC: localhost:4317\nVictoriaMetrics: http://localhost:8428/vmui/\n"
                + "All published ports bind to 127.0.0.1. Kubernetes context: k3d-" + CLUSTER);
    }

    private static void smoke(double minutes, boolean flink, boolean rocksdb) throws Exception {
        String runId = UUID.randomUUID().toString();
        Path report = ROOT.resolve("build/reports/telemetry/" + runId);
        Files.createDirectories(report);
        Path result = report.resolve("result.properties");
        String liveUrl = dashboardUrl(flink, runId, "now-5m", "now");
        System.out.println("\nWatch this run live (data appears after the Java workload starts):\n" + liveUrl + "\n");
        up();
        var command = new ArrayList<String>();
        if (isWindows()) command.addAll(List.of("cmd.exe", "/d", "/c", "gradlew.bat"));
        else command.addAll(List.of("sh", "./gradlew"));
        command.addAll(List.of(rocksdb ? ":presence-flink:telemetrySmokeBundle"
                : flink ? ":presence-flink:telemetrySmoke" : ":presence-otel:telemetrySmoke", "-PsmokeMinutes=" + minutes,
                "-PsmokeRunId=" + runId, "-PsmokeResult=" + result));
        run(Duration.ofMinutes((long) Math.ceil(minutes) + 10), command.toArray(String[]::new));
        if (rocksdb) rocksDbSmoke(minutes, runId, report);
        var counts = new Properties();
        try (var input = Files.newInputStream(result)) { counts.load(input); }
        String url = dashboardUrl(flink, runId, counts.getProperty("startedMillis"), counts.getProperty("finishedMillis"));
        Files.writeString(report.resolve("dashboard-url.txt"), url + "\n");
        String summary = flink ? counts.getProperty("cycles") + " source cycles; Flink job " + counts.getProperty("jobId")
                : counts.getProperty("puts") + " puts and " + counts.getProperty("lookups") + " lookups";
        System.out.println("\nSmoke complete: " + summary + ". Review the charts in your browser:\n" + url
                + "\nWorkload summary: " + report + "\nThe cluster stays running for review. Stop with: mise run telemetry:down");
    }

    private static void rocksDbSmoke(double minutes, String runId, Path report) throws Exception {
        var versions = new Properties();
        try (var input = Files.newInputStream(CONFIG.resolve("versions.properties"))) { versions.load(input); }
        String image = versions.getProperty("flink.smoke.image");
        run("docker", "pull", image);
        String user = isWindows() ? "1000:1000" : capture("id", "-u").trim() + ":" + capture("id", "-g").trim();
        String container = capture("docker", "create", "--name", "presence-flink-smoke-" + runId,
                "--label", OWNER_LABEL + "=true", "--network", "k3d-" + CLUSTER,
                "--memory", "2g", "--cpus", "4", "--user", user, "--read-only",
                "--tmpfs", "/tmp:rw,exec,nosuid,size=536870912",
                "--mount", "type=bind,src=" + ROOT.resolve("presence-flink/build/telemetry-smoke/lib") + ",dst=/app,readonly",
                "--mount", "type=bind,src=" + report + ",dst=/results",
                "--entrypoint", "java", image, "-Xmx1g", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
                "-cp", "/app/*", "io.transductive.presence.flink.smoke.FlinkTelemetrySmoke",
                Double.toString(minutes), runId, "/results/result.properties", "rocksdb",
                "http://" + SERVER + ":30417").trim();
        if (!container.matches("[a-f0-9]{64}")) throw new IOException("Docker did not return a container ID");
        try {
            run(Duration.ofMinutes((long) Math.ceil(minutes) + 5), "docker", "start", "--attach", container);
            if (!capture("docker", "inspect", "--format", "{{.State.ExitCode}}", container).trim().equals("0")) {
                throw new IOException("RocksDB smoke container failed; inspect the preceding job output");
            }
        } finally {
            // Only the container ID returned by our create command is eligible for cleanup.
            run("docker", "rm", "--force", container);
        }
    }

    static String dashboardUrl(String runId, String started, String finished) {
        return dashboardUrl(false, runId, started, finished);
    }

    static String dashboardUrl(boolean flink, String runId, String started, String finished) {
        String path = flink ? "presence-flink-smoke/presence-flink" : "presence-smoke/presence-smoke";
        return "http://localhost:3000/d/" + path + "?theme=light&var-run="
                + URLEncoder.encode(runId, StandardCharsets.UTF_8) + "&from=" + started + "&to=" + finished + "&refresh=1s";
    }

    private static void status() throws Exception {
        run("k3d", "cluster", "list");
        if (exists()) {
            requireOwned();
            if (Files.exists(KUBECONFIG)) kube("get", "pods,services,pvc", "-n", NAMESPACE);
        }
    }

    private static void stop() throws Exception {
        if (!exists()) { System.out.println("Presence telemetry cluster does not exist."); return; }
        requireOwned();
        run("k3d", "cluster", "stop", CLUSTER);
        System.out.println("Stopped. Metrics remain on the cluster's local volume; telemetry:up resumes it.");
    }

    private static void reset() throws Exception {
        if (exists()) {
            requireOwned();
            run("k3d", "cluster", "delete", CLUSTER);
        }
        Files.deleteIfExists(KUBECONFIG);
        System.out.println("Deleted Presence telemetry cluster and its local metrics data. Source configuration is unchanged.");
    }

    private static boolean exists() throws Exception {
        String clusters = capture("k3d", "cluster", "list", "--no-headers");
        return clusters.lines().anyMatch(line -> line.trim().split("\\s+", 2)[0].equals(CLUSTER));
    }

    private static void requireOwned() throws Exception {
        // Go raw-string quotes survive Windows native argument parsing.
        String owner = capture("docker", "inspect", SERVER, "--format", "{{index .Config.Labels `" + OWNER_LABEL + "`}}");
        if (!owner.trim().equals("true")) throw new IOException("Refusing to modify existing cluster " + CLUSTER + ": Presence ownership label is missing.");
    }

    private static void checkPorts() throws IOException {
        var sockets = new ArrayList<ServerSocket>();
        try {
            for (int port : new int[] {3000, 4317, 4318, 6447, 8428}) {
                var socket = new ServerSocket();
                sockets.add(socket);
                try { socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)); }
                catch (IOException error) { throw new IOException("Local port " + port + " is occupied; stop its owner before telemetry:up.", error); }
            }
        } finally {
            for (var socket : sockets) socket.close();
        }
    }

    private static void install(String release, String chart, String repository, String version, String values) throws Exception {
        var command = new ArrayList<>(List.of("helm", "upgrade", "--install", release, chart, "--repo", repository, "--version", version,
                "--kubeconfig", KUBECONFIG.toString(), "--kube-context", "k3d-" + CLUSTER,
                "--namespace", NAMESPACE, "--values", CONFIG.resolve(values).toString(), "--wait", "--timeout", "5m", "--hide-notes"));
        if (release.equals("presence-grafana")) {
            // The external dashboard ConfigMap is not included in the chart's own
            // rollout checksum. Load changed dashboards before starting the workload.
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(CONFIG.resolve("dashboard.json")));
            digest.update(Files.readAllBytes(CONFIG.resolve("flink-dashboard.json")));
            command.addAll(List.of("--set-string", "podAnnotations.presence-dashboard-sha256="
                    + java.util.HexFormat.of().formatHex(digest.digest())));
        }
        run(command.toArray(String[]::new));
    }

    private static String[] kubectl(String... args) {
        var command = new ArrayList<>(List.of("kubectl", "--kubeconfig", KUBECONFIG.toString(), "--context", "k3d-" + CLUSTER));
        command.addAll(List.of(args));
        return command.toArray(String[]::new);
    }

    private static void kube(String... args) throws Exception { run(kubectl(args)); }
    private static String kubeCapture(String... args) throws Exception { return capture(kubectl(args)); }

    private static void kubeApply(String yaml) throws Exception {
        // Dashboard JSON exceeds the client-side last-applied annotation budget.
        // Server-side apply stores ownership without copying the ConfigMap into an annotation.
        // These generated resources belong to this tool inside its ownership-checked cluster;
        // take over fields previously written by its client-side apply implementation.
        var command = kubectl("apply", "--server-side", "--field-manager=presence-telemetry", "--force-conflicts", "-f", "-");
        var process = builder(command).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var input = process.getOutputStream()) { input.write(yaml.getBytes(StandardCharsets.UTF_8)); }
        finish(process, command, Duration.ofMinutes(10));
    }

    private static void awaitReady(String url, String contains) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var response = get(url);
                if (response.statusCode() == 200 && (contains == null || response.body().contains(contains))) return;
            } catch (IOException ignored) { /* Services may still be starting after Helm readiness. */ }
            Thread.sleep(1_000);
        }
        throw new IOException("Timed out waiting for " + url);
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ProcessBuilder builder(String... command) {
        if (isWindows() && !command[0].endsWith(".exe")) command[0] += ".exe";
        var builder = new ProcessBuilder(command).directory(ROOT.toFile());
        builder.environment().put("HELM_CONFIG_HOME", STATE.resolve("helm/config").toString());
        builder.environment().put("HELM_CACHE_HOME", STATE.resolve("helm/cache").toString());
        builder.environment().put("HELM_DATA_HOME", STATE.resolve("helm/data").toString());
        return builder;
    }

    private static void run(String... command) throws Exception {
        run(Duration.ofMinutes(10), command);
    }

    private static void run(Duration timeout, String... command) throws Exception {
        System.out.println("$ " + String.join(" ", command));
        finish(builder(command).inheritIO().start(), command, timeout);
    }

    private static String capture(String... command) throws Exception {
        var process = builder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = executor.submit(() -> {
                try (var input = process.getInputStream()) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            });
            finish(process, command, Duration.ofMinutes(2));
            return output.get(5, TimeUnit.SECONDS);
        }
    }

    private static void finish(Process process, String[] command, Duration timeout) throws Exception {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("Command timed out: " + command[0]);
        }
        if (process.exitValue() != 0) throw new IOException(command[0] + " exited with " + process.exitValue());
    }

    private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }
}
