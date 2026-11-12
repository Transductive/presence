import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Build-time check for assembled site links. Run with the Java 21 source launcher. */
class SiteCheck {
    private static final Pattern LINKS = Pattern.compile("(?:href|src)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDS = Pattern.compile("(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_URLS = Pattern.compile("url\\([\"']?([^\\s)\"']+)[\"']?\\)");

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]).toRealPath();
        URI base = URI.create(args[1].endsWith("/") ? args[1] : args[1] + "/");
        List<String> errors = new ArrayList<>();
        Map<Path, Set<String>> anchors = new HashMap<>();
        List<Path> pages;
        try (var files = Files.walk(root)) {
            pages = files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".html")
                    || p.toString().endsWith(".css")).toList();
        }
        int checked = 0;
        for (Path page : pages) {
            String source = Files.readString(page);
            URI document = base.resolve(root.relativize(page).toString().replace('\\', '/'));
            var links = (page.toString().endsWith(".css") ? CSS_URLS : LINKS).matcher(source);
            while (links.find()) {
                String value = links.group(1).replace("&amp;", "&");
                if (value.isBlank() || value.startsWith("data:") || value.startsWith("javascript:")) continue;
                URI target;
                try { target = document.resolve(value); }
                catch (IllegalArgumentException e) { errors.add(page + ": invalid URL " + value); continue; }
                if (!base.getScheme().equals(target.getScheme()) || !base.getAuthority().equals(target.getAuthority())) continue;
                String targetPath = target.getPath();
                if ((targetPath + "/").equals(base.getPath())) targetPath += "/";
                if (!targetPath.startsWith(base.getPath())) {
                    errors.add(page + ": link escapes deployment prefix: " + value); continue;
                }
                Path local = root.resolve(targetPath.substring(base.getPath().length())).normalize();
                if (!local.startsWith(root)) { errors.add(page + ": link escapes site: " + value); continue; }
                if (Files.isDirectory(local)) local = local.resolve("index.html");
                if (!Files.isRegularFile(local)) { errors.add(page + ": missing " + value); continue; }
                checked++;
                String fragment = target.getFragment();
                if (fragment != null && !fragment.isEmpty() && local.toString().endsWith(".html")) {
                    Set<String> ids = anchors.get(local);
                    if (ids == null) {
                        ids = new HashSet<>();
                        var matches = IDS.matcher(Files.readString(local));
                        while (matches.find()) ids.add(matches.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"));
                        anchors.put(local, ids);
                    }
                    if (!ids.contains(fragment)) errors.add(page + ": missing fragment " + value);
                }
            }
        }
        if (!errors.isEmpty()) throw new IllegalStateException(String.join(System.lineSeparator(), errors));
        System.out.printf("Site verified: %,d HTML/CSS files, %,d local links and assets. Base: %s%n", pages.size(), checked, base);
    }
}
