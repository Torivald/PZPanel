package pzpanel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Main {
    static final String VERSION = "1.0";
    private final Config cfg;
    private final ServerManager srv;
    private final Players players;
    private final Mods mods;
    private final Backups backups;
    private final MapData mapData;
    private final Stats stats;
    private volatile byte[] mapDataJson;
    private final String token = randomHex(24);
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private volatile ZipFile pyramid;
    private volatile long worldSize = -1, worldSizeAt = 0;

    Main(Path panelDir) throws IOException {
        cfg = new Config(panelDir);
        srv = new ServerManager(cfg);
        stats = new Stats(cfg, srv::serverHandle);
        players = new Players(cfg, srv);
        mods = new Mods(cfg);
        backups = new Backups(cfg, srv);
        mapData = new MapData(cfg);
        mapData.loadAsync();
    }

    public static void main(String[] args) throws Exception {
        try { Class.forName("org.sqlite.JDBC"); } catch (Throwable t) { System.err.println("SQLite JDBC не найден: " + t); }
        Path dir = args.length > 0 ? Paths.get(args[0]) : jarDir();
        new Main(dir).run();
    }

    static Path jarDir() {
        try { return Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent(); }
        catch (Exception e) { return Paths.get("").toAbsolutePath(); }
    }

    void run() throws IOException {
        String bind = cfg.get("bind", "127.0.0.1");
        boolean loopback = InetAddress.getByName(bind).isLoopbackAddress();
        if (!loopback && cfg.get("panelPassword").isBlank()) {
            System.err.println("Панель слушает не только localhost (bind=" + bind + "), задайте panelPassword в panel.properties. Переключаюсь на 127.0.0.1.");
            bind = "127.0.0.1"; loopback = true;
        }
        int port = cfg.getInt("port", 8766);
        HttpServer hs = HttpServer.create(new InetSocketAddress(bind, port), 64);
        hs.setExecutor(Executors.newFixedThreadPool(12));
        final boolean lb = loopback;
        hs.createContext("/", ex -> handle(ex, lb));
        hs.start();
        String url = "http://" + (loopback ? "127.0.0.1" : bind) + ":" + port + "/";
        System.out.println("PZ Panel " + VERSION + " — " + url);
        System.out.println("Сервер: " + cfg.serverDir() + "  |  мир: " + cfg.serverName() + "  |  Zomboid: " + cfg.zomboidDir());
        System.out.println("Не закрывайте это окно, пока пользуетесь панелью (сервер, запущенный из панели, продолжит работать).");
        if (cfg.getBool("stopServerOnExit")) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Панель закрывается — сохраняю мир и останавливаю сервер...");
                srv.stopAndWait(cfg.getInt("stopTimeoutSec", 170));
            }, "pzp-shutdown"));
        }
        if (cfg.getBool("autoStartServer")) {
            try { System.out.println("autoStartServer: " + srv.start()); } catch (Exception e) { System.err.println("autoStartServer: " + e.getMessage()); }
        }
        if (cfg.getBool("openBrowser") && Util.isWindows()) {
            try { new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start(); } catch (IOException ignored) {}
        }
    }

    // ---------------- HTTP ----------------
    private void handle(HttpExchange ex, boolean loopback) throws IOException {
        try {
            String host = Optional.ofNullable(ex.getRequestHeaders().getFirst("Host")).orElse("").replaceFirst(":\\d+$", "").toLowerCase(Locale.ROOT);
            if (loopback && !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"))) {
                send(ex, 403, "text/plain", "Forbidden host".getBytes()); return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            boolean needLogin = !cfg.get("panelPassword").isBlank();
            boolean loggedIn = !needLogin || sessions.contains(cookie(ex, "pzp_session"));

            if (path.equals("/api/login") && method.equals("POST")) { login(ex); return; }
            if (path.startsWith("/api/")) {
                if (!token.equals(ex.getRequestHeaders().getFirst("X-PZP"))) { json(ex, 403, Map.of("error", "bad token")); return; }
                if (!loggedIn) { json(ex, 401, Map.of("error", "login")); return; }
                api(ex, path, method);
                return;
            }
            if (path.startsWith("/tiles/")) { if (!loggedIn) { send(ex, 401, "text/plain", new byte[0]); return; } tile(ex, path); return; }
            if (path.startsWith("/topview/")) { if (!loggedIn) { send(ex, 401, "text/plain", new byte[0]); return; } topTile(ex, path); return; }
            if (path.equals("/mapimg/zombies.png")) {
                if (!loggedIn) { send(ex, 401, "text/plain", new byte[0]); return; }
                byte[] h = mapData.heatPng();
                if (h == null) { send(ex, 404, "text/plain", new byte[0]); return; }
                ex.getResponseHeaders().add("Cache-Control", "max-age=3600");
                send(ex, 200, "image/png", h); return;
            }
            if (path.startsWith("/poster/")) { if (!loggedIn) { send(ex, 401, "text/plain", new byte[0]); return; } poster(ex, path.substring(8)); return; }
            if (path.startsWith("/backup/")) { if (!loggedIn) { send(ex, 401, "text/plain", new byte[0]); return; } download(ex, path.substring(8)); return; }
            staticFile(ex, path.equals("/") ? "/index.html" : path, needLogin && !loggedIn);
        } catch (Throwable e) {
            try { json(ex, 500, Map.of("error", String.valueOf(e.getMessage() == null ? e.toString() : e.getMessage()))); } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    private void login(HttpExchange ex) throws IOException {
        JsonNode b = body(ex);
        if (!cfg.get("panelPassword").isBlank() && cfg.get("panelPassword").equals(b.path("password").asText())) {
            String s = randomHex(24);
            sessions.add(s);
            ex.getResponseHeaders().add("Set-Cookie", "pzp_session=" + s + "; HttpOnly; SameSite=Strict; Path=/");
            json(ex, 200, Map.of("ok", true));
        } else {
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            json(ex, 403, Map.of("error", "Неверный пароль"));
        }
    }

    private void api(HttpExchange ex, String path, String method) throws Exception {
        Map<String, String> q = query(ex);
        boolean post = method.equals("POST");
        switch (path) {
            case "/api/status" -> json(ex, 200, statusFull());
            case "/api/stats" -> json(ex, 200, stats.snapshot());
            case "/api/server/start" -> { requirePost(post); json(ex, 200, Map.of("message", srv.start())); }
            case "/api/server/stop" -> { requirePost(post); json(ex, 200, Map.of("message", srv.stop(body(ex).path("force").asBoolean(false)))); }
            case "/api/server/restart" -> { requirePost(post); json(ex, 200, Map.of("message", srv.restart(body(ex).path("warnMinutes").asInt(0)))); }
            case "/api/server/save" -> { requirePost(post); srv.sendConsole("save"); json(ex, 200, Map.of("message", "Команда save отправлена")); }
            case "/api/console" -> {
                if (post) json(ex, 200, Map.of("output", srv.sendConsole(body(ex).path("cmd").asText())));
                else json(ex, 200, srv.console(parseLong(q.get("cursor"), -1)));
            }
            case "/api/rcon" -> { requirePost(post); json(ex, 200, Map.of("output", srv.rcon(body(ex).path("cmd").asText()))); }
            case "/api/players" -> json(ex, 200, players.all());
            case "/api/map/info" -> json(ex, 200, mapInfo());
            case "/api/map/data" -> {
                if (!"ready".equals(mapData.status)) { json(ex, 200, mapData.summary()); return; }
                if (mapDataJson == null) mapDataJson = Util.JSON.writeValueAsBytes(mapData.summary());
                sendMaybeGzip(ex, "application/json; charset=utf-8", mapDataJson);
            }
            case "/api/map/buildings" -> json(ex, 200, mapData.buildingsIn((int) parseLong(q.get("x0"), 0), (int) parseLong(q.get("y0"), 0),
                    (int) parseLong(q.get("x1"), 0), (int) parseLong(q.get("y1"), 0), 6000));
            case "/api/map/building" -> json(ex, 200, mapData.building((int) parseLong(q.get("id"), -1)));
            case "/api/mods" -> {
                if (post) {
                    JsonNode b = body(ex);
                    mods.save(strings(b.path("mods")), strings(b.path("workshopItems")), b.path("syncClient").asBoolean(false));
                    srv.markConfigChanged();
                    json(ex, 200, Map.of("message", "Сохранено. Изменения вступят в силу после перезапуска сервера."));
                } else json(ex, 200, mods.state());
            }
            case "/api/workshop/search" -> json(ex, 200, mods.search(q.getOrDefault("q", ""), (int) parseLong(q.get("page"), 1)));
            case "/api/workshop/item" -> json(ex, 200, mods.item(digits(q.get("id"))));
            case "/api/workshop/details" -> {
                List<String> ids = new ArrayList<>();
                for (String s : q.getOrDefault("ids", "").split(",")) if (s.matches("\\d+")) ids.add(s);
                json(ex, 200, mods.details(ids));
            }
            case "/api/workshop/collection" -> json(ex, 200, mods.collection(digits(q.get("id"))));
            case "/api/config/ini" -> {
                if (post) {
                    Map<String, String> ch = Util.JSON.convertValue(body(ex).path("changes"), new TypeReference<Map<String, String>>() {});
                    Configs.writeIni(cfg.serverIni(), ch, cfg.fileBackupDir());
                    srv.markConfigChanged();
                    json(ex, 200, Map.of("message", "Сохранено " + ch.size() + " параметров. Нужен перезапуск сервера."));
                } else json(ex, 200, Map.of("path", cfg.serverIni().toString(), "entries", Configs.readIni(cfg.serverIni())));
            }
            case "/api/config/sandbox" -> {
                if (post) {
                    List<Map<String, Object>> ch = Util.JSON.convertValue(body(ex).path("changes"), new TypeReference<List<Map<String, Object>>>() {});
                    int n = Configs.writeSandbox(cfg.sandboxLua(), ch, cfg.fileBackupDir());
                    srv.markConfigChanged();
                    json(ex, 200, Map.of("message", "Изменено параметров: " + n + ". Нужен перезапуск сервера."));
                } else json(ex, 200, Map.of("path", cfg.sandboxLua().toString(), "entries", Configs.readSandbox(cfg.sandboxLua())));
            }
            case "/api/file" -> {
                Path f = namedFile(q.getOrDefault("name", ""));
                if (post) {
                    Util.writeTextWithBackup(f, body(ex).path("text").asText(), cfg.fileBackupDir());
                    srv.markConfigChanged();
                    json(ex, 200, Map.of("message", "Файл сохранён (копия прежней версии — в file_backups)"));
                } else json(ex, 200, Map.of("path", f.toString(), "exists", Files.exists(f), "text", Files.exists(f) ? Util.readText(f) : ""));
            }
            case "/api/zb" -> {
                Path f = cfg.zbApprovals();
                if (post) {
                    JsonNode b = body(ex);
                    ObjectNode root = (ObjectNode) Util.JSON.readTree(Util.readText(f));
                    ArrayNode arr = (ArrayNode) root.path("mods");
                    String id = b.path("id").asText(), hash = b.path("jar_hash").asText(), action = b.path("action").asText();
                    for (int i = arr.size() - 1; i >= 0; i--) {
                        JsonNode e = arr.get(i);
                        if (e.path("id").asText().equals(id) && e.path("jar_hash").asText().equals(hash)) {
                            if (action.equals("delete")) arr.remove(i);
                            else ((ObjectNode) e).put("decision", action.equals("allow"));
                        }
                    }
                    Util.writeTextWithBackup(f, Util.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), cfg.fileBackupDir());
                    json(ex, 200, Map.of("message", "Сохранено. ZombieBuddy прочитает это при следующем запуске игры/сервера."));
                } else json(ex, 200, Map.of("path", f.toString(), "data", Files.exists(f) ? Util.JSON.readTree(Util.readText(f)) : Map.of()));
            }
            case "/api/backups" -> json(ex, 200, Map.of("list", backups.list(), "progress", backups.progress(), "dir", cfg.backupDir().toString()));
            case "/api/backups/create" -> { requirePost(post); json(ex, 200, Map.of("message", backups.create())); }
            case "/api/backups/delete" -> { requirePost(post); backups.delete(body(ex).path("name").asText()); json(ex, 200, Map.of("message", "Удалено")); }
            case "/api/settings" -> {
                if (post) {
                    Map<String, String> ch = Util.JSON.convertValue(body(ex).path("changes"), new TypeReference<Map<String, String>>() {});
                    for (var e : ch.entrySet()) {
                        if (e.getKey().equals("panelPassword") && e.getValue().equals("********")) continue;
                        cfg.p.setProperty(e.getKey(), e.getValue() == null ? "" : e.getValue().trim());
                    }
                    cfg.save();
                    pyramid = null;
                    json(ex, 200, Map.of("message", "Настройки сохранены. bind/port применятся после перезапуска панели."));
                } else json(ex, 200, Map.of("settings", cfg.asMap(), "file", cfg.file.toString()));
            }
            default -> json(ex, 404, Map.of("error", "not found"));
        }
    }

    private Map<String, Object> statusFull() {
        Map<String, Object> m = new LinkedHashMap<>(srv.status());
        m.put("panelVersion", VERSION);
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("serverDir", cfg.serverDir().toString());
        paths.put("ini", cfg.serverIni().toString());
        paths.put("world", cfg.worldDir().toString());
        paths.put("iniExists", Files.exists(cfg.serverIni()));
        m.put("paths", paths);
        try { m.put("lastSave", Files.getLastModifiedTime(cfg.playersDb()).toMillis()); } catch (IOException ignored) {}
        if (System.currentTimeMillis() - worldSizeAt > 10 * 60_000) {
            worldSizeAt = System.currentTimeMillis();
            CompletableFuture.runAsync(() -> worldSize = Util.dirSize(cfg.worldDir()));
        }
        m.put("worldSize", worldSize);
        Path ini = cfg.serverIni();
        m.put("modsCount", Mods.splitList(Configs.iniValue(ini, "Mods")).size());
        m.put("workshopCount", Mods.splitList(Configs.iniValue(ini, "WorkshopItems")).size());
        m.put("maxPlayers", Configs.iniValue(ini, "MaxPlayers"));
        m.put("publicName", Configs.iniValue(ini, "PublicName"));
        m.put("gamePort", Configs.iniValue(ini, "DefaultPort"));
        m.put("rconPort", Configs.iniValue(ini, "RCONPort"));
        m.put("rconEnabled", !Configs.iniValue(ini, "RCONPassword").isEmpty());
        try { m.put("diskFreeGb", Files.getFileStore(cfg.zomboidDir()).getUsableSpace() / 1_073_741_824L); } catch (Exception ignored) {}
        return m;
    }

    private Path namedFile(String n) throws IOException {
        return switch (n) {
            case "ini" -> cfg.serverIni();
            case "sandbox" -> cfg.sandboxLua();
            case "spawnregions" -> cfg.spawnRegions();
            case "spawnpoints" -> cfg.spawnPoints();
            case "clientmods" -> cfg.clientModsFile();
            case "modoptions" -> cfg.clientModOptions();
            case "startscript" -> cfg.serverDir().resolve(cfg.get("startScript"));
            default -> throw new IOException("Неизвестный файл");
        };
    }

    // ---------------- map tiles ----------------
    private ZipFile pyramid() throws IOException {
        if (pyramid == null) {
            Path z = cfg.pyramidZip();
            if (z == null || !Files.exists(z)) throw new IOException("pyramid.zip не найден (укажите pyramidZip в настройках)");
            pyramid = new ZipFile(z.toFile());
        }
        return pyramid;
    }

    private Map<String, Object> mapInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            ZipFile z = pyramid();
            ZipEntry e = z.getEntry("pyramid.txt");
            String txt = e == null ? "" : new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            int[] bounds = {0, 0, 19968, 16128};
            for (String l : txt.split("\r?\n")) if (l.startsWith("bounds=")) {
                String[] p = l.substring(7).trim().split("\\s+");
                for (int i = 0; i < 4 && i < p.length; i++) bounds[i] = Integer.parseInt(p[i]);
            }
            int maxLevel = 0;
            while (z.getEntry((maxLevel + 1) + "/") != null || z.getEntry((maxLevel + 1) + "/tile0x0.png") != null) maxLevel++;
            m.put("ok", true);
            m.put("bounds", bounds);
            m.put("levels", maxLevel);
            m.put("tileSize", 256);
            Map<String, Object> top = topInfo();
            if (top != null) m.put("top", top);
        } catch (Exception ex) { m.put("ok", false); m.put("error", ex.getMessage()); }
        return m;
    }

    // ---------------- top view (pzmap2dzi base_top output) ----------------
    private volatile Map<String, Object> topInfoCache;
    private Map<String, Object> topInfo() {
        Map<String, Object> c = topInfoCache;
        if (c != null) return c;
        try {
            Path d = cfg.topViewDir();
            if (d == null || !Files.exists(d.resolve("map_info.json")) || !Files.exists(d.resolve("layer0.dzi"))) return null;
            JsonNode mi = Util.JSON.readTree(Files.readAllBytes(d.resolve("map_info.json")));
            String dzi = Util.readText(d.resolve("layer0.dzi"));
            java.util.regex.Matcher ts = java.util.regex.Pattern.compile("TileSize=\"(\\d+)\"").matcher(dzi);
            java.util.regex.Matcher fm = java.util.regex.Pattern.compile("Format=\"(\\w+)\"").matcher(dzi);
            int maxLevel = -1;
            try (var ds = Files.newDirectoryStream(d.resolve("layer0_files"))) {
                for (Path l : ds) { String n = l.getFileName().toString(); if (n.matches("\\d+")) maxLevel = Math.max(maxLevel, Integer.parseInt(n)); }
            }
            if (maxLevel < 0) return null;
            List<Integer> layers = new ArrayList<>();
            try (var ds = Files.newDirectoryStream(d, "layer*_files")) {
                for (Path l : ds) {
                    java.util.regex.Matcher lm = java.util.regex.Pattern.compile("layer(-?\\d+)_files").matcher(l.getFileName().toString());
                    if (!lm.matches()) continue;
                    int li = Integer.parseInt(lm.group(1));
                    // a floor counts only if it has at least one real tile
                    boolean any = false;
                    try (var w = Files.walk(l, 2)) { any = w.anyMatch(f -> Files.isRegularFile(f) && f.getFileName().toString().matches("\\d+_\\d+\\.\\w+")); }
                    if (any) layers.add(li);
                }
            }
            Collections.sort(layers);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("w", mi.path("w").asInt()); m.put("h", mi.path("h").asInt());
            m.put("x0", mi.path("x0").asDouble()); m.put("y0", mi.path("y0").asDouble());
            m.put("sqr", mi.path("sqr").asDouble(1)); m.put("skip", mi.path("skip").asInt(0));
            m.put("tileSize", ts.find() ? Integer.parseInt(ts.group(1)) : 256);
            m.put("ext", fm.find() ? fm.group(1) : "webp");
            m.put("maxLevel", maxLevel);
            m.put("layers", layers);
            topInfoCache = m;
            return m;
        } catch (Exception e) { return null; }
    }

    private void topTile(HttpExchange ex, String path) throws IOException {
        String[] p = path.split("/"); // /topview/layer/L/X_Y.ext
        Map<String, Object> ti = topInfo();
        if (p.length != 5 || ti == null || !p[2].matches("-?\\d+") || !p[3].matches("\\d+") || !p[4].matches("\\d+_\\d+\\.(webp|png|jpg|jpeg)")) { send(ex, 404, "text/plain", new byte[0]); return; }
        Path f = cfg.topViewDir().resolve("layer" + Integer.parseInt(p[2]) + "_files").resolve(p[3]).resolve(p[4]);
        if (!Files.exists(f)) { send(ex, 404, "text/plain", new byte[0]); return; }
        String ext = p[4].substring(p[4].lastIndexOf('.') + 1);
        ex.getResponseHeaders().add("Cache-Control", "max-age=86400");
        send(ex, 200, ext.equals("webp") ? "image/webp" : ext.equals("png") ? "image/png" : "image/jpeg", Files.readAllBytes(f));
    }

    private void tile(HttpExchange ex, String path) throws IOException {
        String[] p = path.split("/"); // /tiles/L/X/Y.png
        if (p.length != 5) { send(ex, 404, "text/plain", new byte[0]); return; }
        String name = Integer.parseInt(p[2]) + "/tile" + Integer.parseInt(p[3]) + "x" + Integer.parseInt(p[4].replace(".png", "")) + ".png";
        ZipFile z = pyramid();
        ZipEntry e = z.getEntry(name);
        if (e == null) { send(ex, 404, "text/plain", new byte[0]); return; }
        byte[] b;
        synchronized (z) { b = z.getInputStream(e).readAllBytes(); }
        ex.getResponseHeaders().add("Cache-Control", "max-age=86400");
        send(ex, 200, "image/png", b);
    }

    private void poster(HttpExchange ex, String modId) throws IOException {
        Path p = mods.posterPath(URLDecoder.decode(modId, StandardCharsets.UTF_8));
        if (p == null || !Files.exists(p)) { send(ex, 404, "text/plain", new byte[0]); return; }
        ex.getResponseHeaders().add("Cache-Control", "max-age=3600");
        send(ex, 200, "image/png", Files.readAllBytes(p));
    }

    private void download(HttpExchange ex, String name) throws IOException {
        name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        if (name.contains("..") || name.contains("/") || name.contains("\\")) { send(ex, 400, "text/plain", new byte[0]); return; }
        Path f = cfg.backupDir().resolve(name);
        if (!Files.exists(f)) { send(ex, 404, "text/plain", new byte[0]); return; }
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + name.replaceAll("[^A-Za-z0-9_.\\-]", "_") + "\"");
        ex.getResponseHeaders().add("Content-Type", "application/zip");
        ex.sendResponseHeaders(200, Files.size(f));
        try (OutputStream os = ex.getResponseBody()) { Files.copy(f, os); }
    }

    // ---------------- static ----------------
    private void staticFile(HttpExchange ex, String path, boolean loginPage) throws IOException {
        if (path.contains("..")) { send(ex, 400, "text/plain", new byte[0]); return; }
        String res = "/web" + path;
        InputStream in = Main.class.getResourceAsStream(res);
        if (in == null) { send(ex, 404, "text/plain", "Not found".getBytes()); return; }
        byte[] b = in.readAllBytes();
        String ct = path.endsWith(".html") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                : path.endsWith(".css") ? "text/css; charset=utf-8" : path.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
        if (path.endsWith(".html")) {
            b = new String(b, StandardCharsets.UTF_8).replace("%%TOKEN%%", token).replace("%%LOGIN%%", String.valueOf(loginPage)).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Cache-Control", "no-store");
        }
        ex.getResponseHeaders().add("X-Frame-Options", "DENY");
        send(ex, 200, ct, b);
    }

    // ---------------- helpers ----------------
    private static void requirePost(boolean post) throws IOException { if (!post) throw new IOException("POST required"); }

    private static JsonNode body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        return b.length == 0 ? Util.JSON.createObjectNode() : Util.JSON.readTree(b);
    }

    private static List<String> strings(JsonNode n) {
        List<String> l = new ArrayList<>();
        for (JsonNode x : n) { String s = x.asText().trim(); if (!s.isEmpty() && !s.contains(";")) l.add(s); }
        return l;
    }

    private static String digits(String s) throws IOException {
        if (s == null) throw new IOException("Нет id");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{6,})").matcher(s);
        if (!m.find()) throw new IOException("Не найден ID предмета Мастерской");
        return m.group(1);
    }

    private static long parseLong(String s, long d) { try { return Long.parseLong(s); } catch (Exception e) { return d; } }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String cookie(HttpExchange ex, String name) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c == null) return "";
        for (String part : c.split(";")) { String t = part.trim(); if (t.startsWith(name + "=")) return t.substring(name.length() + 1); }
        return "";
    }

    private static void sendMaybeGzip(HttpExchange ex, String ct, byte[] b) throws IOException {
        String ae = Optional.ofNullable(ex.getRequestHeaders().getFirst("Accept-Encoding")).orElse("");
        if (!ae.contains("gzip") || b.length < 4096) { send(ex, 200, ct, b); return; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) { gz.write(b); }
        ex.getResponseHeaders().set("Content-Encoding", "gzip");
        send(ex, 200, ct, bo.toByteArray());
    }

    private static void json(HttpExchange ex, int code, Object o) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Util.JSON.writeValueAsBytes(o));
    }

    private static void send(HttpExchange ex, int code, String ct, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String randomHex(int n) {
        byte[] b = new byte[n]; new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString();
    }
}
