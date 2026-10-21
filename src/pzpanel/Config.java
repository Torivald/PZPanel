package pzpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** panel.properties next to the jar. Every path has an auto-detected default. */
final class Config {
    final Path panelDir;
    final Path file;
    final Properties p = new Properties();

    Config(Path panelDir) throws IOException {
        this.panelDir = panelDir;
        this.file = panelDir.resolve("panel.properties");
        if (Files.exists(file)) try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        fillDefaults();
        save();
    }

    private void fillDefaults() {
        Path guessServer = panelDir.getParent();
        if (guessServer == null || !Files.exists(guessServer.resolve("StartServer64.bat")) && !Files.exists(guessServer.resolve("start-server.sh")))
            guessServer = Paths.get("E:\\SteamLibrary\\steamapps\\common\\Project Zomboid Dedicated Server");
        def("serverDir", guessServer.toString());
        def("startScript", Util.isWindows() ? "StartServer64.bat" : "start-server.sh");
        def("zomboidDir", Paths.get(System.getProperty("user.home"), "Zomboid").toString());
        def("serverName", detectServerName());
        def("bind", "127.0.0.1");
        def("port", "8766");
        def("panelPassword", "");
        def("openBrowser", "true");
        def("autoRestartOnCrash", "false");
        def("autoStartServer", "false");
        def("stopServerOnExit", "false"); // save+quit the panel-started server when the panel itself exits  // start the server when the panel starts (for a panel run as a service)
        def("dailyRestart", "");          // HH:mm, empty = off
        def("restartWarnMinutes", "5");
        def("gameVersion", "42.21");
        Path game = serverDir().getParent() == null ? serverDir() : serverDir().getParent().resolve("ProjectZomboid");
        def("gameDir", game.toString());
        def("pyramidZip", "");            // empty = auto
        def("workshopDirs", "");          // extra dirs separated by ;  (server and client dirs are auto)
        def("zbConfigDir", Paths.get(System.getProperty("user.home"), ".zombie_buddy").toString());
        def("mapDir", "");                // empty = <serverDir>/media/maps/Muldraugh, KY
        def("topViewDir", "");            // empty = <panel>/map/html/map_data/base_top (pzmap2dzi output)
    }

    private String detectServerName() {
        try {
            Path bat = Paths.get(get("serverDir", ""), get("startScript", "StartServer64.bat"));
            if (Files.exists(bat)) {
                Matcher m = Pattern.compile("-servername\\s+\"?([^\"\\s]+)").matcher(Util.readText(bat));
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return "servertest";
    }

    private void def(String k, String v) { if (!p.containsKey(k)) p.setProperty(k, v); }

    String get(String k, String d) { String v = p.getProperty(k); return v == null ? d : v.trim(); }
    String get(String k) { return get(k, ""); }
    int getInt(String k, int d) { try { return Integer.parseInt(get(k)); } catch (Exception e) { return d; } }
    boolean getBool(String k) { return Boolean.parseBoolean(get(k)); }

    synchronized void save() throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "PZ Panel settings. Restart the panel after changing bind/port.");
        }
    }

    Path serverDir() { return Paths.get(get("serverDir")); }
    Path zomboidDir() { return Paths.get(get("zomboidDir")); }
    String serverName() { return get("serverName", "servertest"); }
    Path serverIni() { return zomboidDir().resolve("Server").resolve(serverName() + ".ini"); }
    Path sandboxLua() { return zomboidDir().resolve("Server").resolve(serverName() + "_SandboxVars.lua"); }
    Path spawnRegions() { return zomboidDir().resolve("Server").resolve(serverName() + "_spawnregions.lua"); }
    Path spawnPoints() { return zomboidDir().resolve("Server").resolve(serverName() + "_spawnpoints.lua"); }
    Path worldDir() { return zomboidDir().resolve("Saves").resolve("Multiplayer").resolve(serverName()); }
    Path playersDb() { return worldDir().resolve("players.db"); }
    Path accountsDb() { return zomboidDir().resolve("db").resolve(serverName() + ".db"); }
    Path consoleLog() { return zomboidDir().resolve("server-console.txt"); }
    Path clientModsFile() { return zomboidDir().resolve("mods").resolve("default.txt"); }
    Path clientModOptions() { return zomboidDir().resolve("Lua").resolve("ModOptions.ini"); }
    Path zbApprovals() { return Paths.get(get("zbConfigDir")).resolve("mod_approvals.json"); }
    Path backupDir() { return zomboidDir().resolve("panel_backups"); }
    Path fileBackupDir() { return panelDir.resolve("file_backups"); }
    Path topViewDir() {
        String t = get("topViewDir");
        return t.isBlank() ? panelDir.resolve("map").resolve("html").resolve("map_data").resolve("base_top") : Paths.get(t);
    }

    List<Path> workshopDirs() {
        LinkedHashSet<Path> s = new LinkedHashSet<>();
        s.add(serverDir().resolve("steamapps").resolve("workshop").resolve("content").resolve("108600"));
        Path g = Paths.get(get("gameDir"));
        if (g.getParent() != null && g.getParent().getParent() != null)
            s.add(g.getParent().getParent().resolve("workshop").resolve("content").resolve("108600"));
        for (String x : get("workshopDirs").split(";")) if (!x.isBlank()) s.add(Paths.get(x.trim()));
        s.add(zomboidDir().resolve("Workshop"));
        return new ArrayList<>(s);
    }

    Path pyramidZip() {
        String explicit = get("pyramidZip");
        if (!explicit.isBlank()) return Paths.get(explicit);
        for (Path base : List.of(serverDir(), Paths.get(get("gameDir")))) {
            Path z = base.resolve("media").resolve("maps").resolve("Muldraugh, KY").resolve("pyramid.zip");
            if (Files.exists(z)) return z;
        }
        return null;
    }

    Map<String, Object> asMap() {
        Map<String, Object> m = new TreeMap<>();
        for (String k : p.stringPropertyNames()) m.put(k, k.equals("panelPassword") && !get(k).isEmpty() ? "********" : get(k));
        return m;
    }
}
