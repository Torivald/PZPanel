package pzpanel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class Util {
    static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).build();

    static String readText(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        int off = (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) ? 3 : 0;
        return new String(b, off, b.length - off, StandardCharsets.UTF_8);
    }

    /** Writes text atomically; keeps a timestamped copy of the previous version in backupDir. */
    static void writeTextWithBackup(Path p, String text, Path backupDir) throws IOException {
        if (Files.exists(p) && backupDir != null) {
            Files.createDirectories(backupDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Files.copy(p, backupDir.resolve(p.getFileName().toString() + "." + ts + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            pruneBackups(backupDir, p.getFileName().toString(), 30);
        }
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".pzpanel.tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static void pruneBackups(Path dir, String prefix, int keep) {
        try (var s = Files.list(dir)) {
            List<Path> l = s.filter(f -> f.getFileName().toString().startsWith(prefix + ".")).sorted().toList();
            for (int i = 0; i < l.size() - keep; i++) Files.deleteIfExists(l.get(i));
        } catch (IOException ignored) {}
    }

    static String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PZPanel/1.0")
                .header("Accept-Language", "ru,en;q=0.8").GET().build();
        HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) throw new IOException("HTTP " + resp.statusCode() + " " + url);
        return resp.body();
    }

    static String httpPostForm(String url, String form) throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "PZPanel/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) throw new IOException("HTTP " + resp.statusCode() + " " + url);
        return resp.body();
    }

    static String nowStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    }

    static long dirSize(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        long[] total = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes a) {
                    total[0] += a.size(); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) {}
        return total[0];
    }

    static String lineSep(String text) { return text.contains("\r\n") ? "\r\n" : "\n"; }

    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
}
