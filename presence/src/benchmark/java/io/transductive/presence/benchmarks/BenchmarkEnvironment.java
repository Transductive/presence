package io.transductive.presence.benchmarks;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class BenchmarkEnvironment {
    private BenchmarkEnvironment() { }

    static String describe() {
        return "Java: " + Runtime.version() + System.lineSeparator()
                + "JVM: " + System.getProperty("java.vm.name") + ", " + System.getProperty("java.vm.vendor")
                + System.lineSeparator() + "OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " / " + System.getProperty("os.arch") + System.lineSeparator()
                + "CPU: " + cpu() + " (" + Runtime.getRuntime().availableProcessors() + " logical processors)"
                + System.lineSeparator() + "Runner JVM arguments: "
                + ManagementFactory.getRuntimeMXBean().getInputArguments() + System.lineSeparator();
    }

    private static String cpu() {
        String os = System.getProperty("os.name");
        try {
            if (os.startsWith("Windows")) {
                return command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "(Get-CimInstance Win32_Processor | Select-Object -First 1).Name");
            }
            if (os.startsWith("Mac")) {
                return command("sysctl", "-n", "machdep.cpu.brand_string");
            }
            Path info = Path.of("/proc/cpuinfo");
            if (Files.isReadable(info)) {
                try (var lines = Files.lines(info)) {
                    return lines.filter(line -> line.startsWith("model name") || line.startsWith("Hardware"))
                            .map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst().orElse("unavailable");
                }
            }
        } catch (IOException exception) {
            return "unavailable (" + exception.getClass().getSimpleName() + ")";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "unavailable (interrupted)";
        }
        return "unavailable";
    }

    private static String command(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "unavailable (command timed out)";
        }
        String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        return process.exitValue() == 0 && !text.isEmpty() ? text : "unavailable";
    }
}
