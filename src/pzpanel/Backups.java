package pzpanel;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.*;

/** World backups: Saves/Multiplayer/<server> + db/<server>.db + Server/<server>* into Zomboid/panel_backups/*.zip */
final class Backups {
    private final Config cfg;
    private final ServerManager srv;
    private volatile String progress = "";

    Backups(Config cfg, ServerManager srv) { this.cfg = cfg; this.srv = srv; }

    String progress() { return progress; }

    List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        Path d = cfg.backupDir();
        if (!Files.isDirectory(d)) return out;
        try (var s = Files.list(d)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                if (!p.toString().endsWith(".zip")) continue;
                out.add(Map.of("name", p.getFileName().toString(), "size", Files.size(p), "time", Files.getLastModifiedTime(p).toMillis()));
            }
        }
        return out;
    }

    synchronized String create() {
        if (progress.startsWith("Идёт")) return progress;
        progress = "Идёт: подготовка";
        Thread t = new Thread(() -> {
            try {
                if (srv.ownedAlive() || srv.externalServer().isPresent()) {
                    progress = "Идёт: save на сервере";
                    try { srv.sendConsole("save"); Thread.sleep(8000); } catch (Exception ignored) {}
                }
                Path dir = cfg.backupDir();
                Files.createDirectories(dir);
                Path zip = dir.resolve(cfg.serverName() + "_" + Util.nowStamp() + ".zip");
                Path z = cfg.zomboidDir();
                try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)))) {
                    zos.setLevel(Deflater.BEST_SPEED);
                    addTree(zos, z, cfg.worldDir());
                    if (Files.exists(cfg.accountsDb())) addFile(zos, z, cfg.accountsDb());
                    try (var s = Files.list(z.resolve("Server"))) {
                        for (Path p : (Iterable<Path>) s::iterator)
                            if (p.getFileName().toString().startsWith(cfg.serverName())) addFile(zos, z, p);
                    }
                }
                progress = "Готово: " + zip.getFileName() + " (" + (Files.size(zip) / 1048576) + " МБ)";
            } catch (Exception e) {
                progress = "Ошибка: " + e.getMessage();
            }
        }, "pz-backup");
        t.setDaemon(true);
        t.start();
        return progress;
    }

    private void addTree(ZipOutputStream zos, Path base, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (!f.getFileName().toString().endsWith("-journal")) { progress = "Идёт: " + base.relativize(f); addFile(zos, base, f); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
    }

    private static void addFile(ZipOutputStream zos, Path base, Path f) throws IOException {
        zos.putNextEntry(new ZipEntry(base.relativize(f).toString().replace('\\', '/')));
        try (InputStream in = Files.newInputStream(f)) { in.transferTo(zos); }
        catch (IOException e) { /* file locked: skip */ }
        zos.closeEntry();
    }

    void delete(String name) throws IOException {
        if (!name.matches("[^/\\\\:*?\"<>|]+\\.zip") || name.contains("..")) throw new IOException("Неверное имя");
        Files.deleteIfExists(cfg.backupDir().resolve(name));
    }
}
