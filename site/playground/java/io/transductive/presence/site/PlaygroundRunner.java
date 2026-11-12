package io.transductive.presence.site;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Website-only compiler bridge. Never included in the Presence library JAR. */
public final class PlaygroundRunner {
    private static final int SOURCE_LIMIT = 32_768;
    private static final int OUTPUT_LIMIT = 32_768;
    private static Method entryPoint;

    private PlaygroundRunner() { }

    /** Returns an empty string on success, or compiler diagnostics with source line numbers. */
    public static String compile(String source, String libraryPath) throws IOException {
        entryPoint = null;
        if (source == null || source.length() > SOURCE_LIMIT) {
            return "Keep the example below 32,768 characters.";
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return "The Java compiler is unavailable in this runtime.";
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var messages = new StringWriter();
        try (var manager = new MemoryFiles(compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8))) {
            var input = new SimpleJavaFileObject(URI.create("string:///Playground.java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
            };
            boolean success = compiler.getTask(messages, manager, diagnostics,
                    List.of("-classpath", libraryPath, "-proc:none", "-source", "21", "-target", "21", "-Xmaxerrs", "20"),
                    null, List.of(input)).call();
            if (!success) {
                var text = new StringBuilder(messages.toString());
                diagnostics.getDiagnostics().stream().limit(20).forEach(d -> text.append("Line ")
                        .append(d.getLineNumber()).append(": ").append(d.getMessage(java.util.Locale.ROOT)).append('\n'));
                return text.isEmpty() ? "Compilation failed." : text.toString();
            }
            ClassLoader loader = new ClassLoader(PlaygroundRunner.class.getClassLoader()) {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    var bytes = manager.classes.get(name);
                    if (bytes == null) throw new ClassNotFoundException(name);
                    byte[] code = bytes.toByteArray();
                    return defineClass(name, code, 0, code.length);
                }
            };
            try {
                // Loading without initialization lets Run capture static-initializer output/errors too.
                Method main = loader.loadClass("Playground").getMethod("main", String[].class);
                if (!Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class) {
                    return "Playground needs public static void main(String[] args).";
                }
                entryPoint = main;
                return "";
            } catch (ReflectiveOperationException e) {
                return "Use a public class named Playground with public static void main(String[] args).";
            }
        }
    }

    /** Executes the compiled program and returns JSON containing its result and bounded output. */
    public static String run() {
        if (entryPoint == null) return result(false, "Compile a valid Playground class first.");
        var bytes = new BoundedOutput();
        var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        boolean success = true;
        try {
            System.setOut(output);
            System.setErr(output);
            entryPoint.invoke(null, (Object) new String[0]);
        } catch (Throwable failure) {
            success = false;
            Throwable cause = failure instanceof InvocationTargetException e ? e.getTargetException() : failure;
            cause.printStackTrace(output);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            output.flush();
            entryPoint = null;
        }
        return result(success, bytes.text());
    }

    private static String result(boolean success, String text) {
        var json = new StringBuilder("{\"ok\":").append(success).append(",\"output\":\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 32) json.append(String.format("\\u%04x", (int) c));
                    else json.append(c);
                }
            }
        }
        return json.append("\"}").toString();
    }

    private static final class MemoryFiles extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> classes = new HashMap<>();
        MemoryFiles(StandardJavaFileManager delegate) { super(delegate); }

        @Override public JavaFileObject getJavaFileForOutput(Location location, String name,
                JavaFileObject.Kind kind, FileObject sibling) {
            return new SimpleJavaFileObject(URI.create("memory:///" + name.replace('.', '/') + kind.extension), kind) {
                @Override public OutputStream openOutputStream() {
                    var bytes = new ByteArrayOutputStream();
                    classes.put(name, bytes);
                    return bytes;
                }
            };
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;
        @Override public synchronized void write(int value) {
            if (bytes.size() < OUTPUT_LIMIT) bytes.write(value);
            else truncated = true;
        }
        @Override public synchronized void write(byte[] source, int offset, int length) {
            int accepted = Math.min(length, OUTPUT_LIMIT - bytes.size());
            bytes.write(source, offset, accepted);
            truncated |= accepted < length;
        }
        synchronized String text() {
            return bytes.toString(StandardCharsets.UTF_8) + (truncated ? "\n[Output limited to 32 KiB.]" : "");
        }
    }
}
