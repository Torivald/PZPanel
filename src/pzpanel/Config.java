package pzpanel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Config {
   final Path panelDir;
   final Path file;
   final Properties p = new Properties();

   Config(Path var1) throws IOException {
      this.panelDir = var1;
      this.file = var1.resolve("panel.properties");
      if (Files.exists(this.file)) {
         try (BufferedReader var2 = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            this.p.load(var2);
         }
      }

      this.fillDefaults();
      this.save();
   }

   private void fillDefaults() {
      Path var1 = this.panelDir.getParent();
      if (var1 == null || !Files.exists(var1.resolve("StartServer64.bat")) && !Files.exists(var1.resolve("start-server.sh"))) {
         var1 = Paths.get("E:\\SteamLibrary\\steamapps\\common\\Project Zomboid Dedicated Server");
      }

      this.def("serverDir", var1.toString());
      this.def("startScript", Util.isWindows() ? "StartServer64.bat" : "start-server.sh");
      this.def("zomboidDir", Paths.get(System.getProperty("user.home"), "Zomboid").toString());
      this.def("serverName", this.detectServerName());
      this.def("bind", "127.0.0.1");
      this.def("port", "8766");
      this.def("panelPassword", "");
      this.def("openBrowser", "true");
      this.def("autoRestartOnCrash", "false");
      this.def("autoStartServer", "false");
      this.def("stopServerOnExit", "false");
      this.def("dailyRestart", "");
      this.def("restartWarnMinutes", "5");
      this.def("gameVersion", "42.21");
      Path var2 = this.serverDir().getParent() == null ? this.serverDir() : this.serverDir().getParent().resolve("ProjectZomboid");
      this.def("gameDir", var2.toString());
      this.def("pyramidZip", "");
      this.def("workshopDirs", "");
      this.def("zbConfigDir", Paths.get(System.getProperty("user.home"), ".zombie_buddy").toString());
      this.def("mapDir", "");
      this.def("topViewDir", "");
   }

   private String detectServerName() {
      try {
         Path var1 = Paths.get(this.get("serverDir", ""), this.get("startScript", "StartServer64.bat"));
         if (Files.exists(var1)) {
            Matcher var2 = Pattern.compile("-servername\\s+\"?([^\"\\s]+)").matcher(Util.readText(var1));
            if (var2.find()) {
               return var2.group(1);
            }
         }
      } catch (Exception var3) {
      }

      return "servertest";
   }

   private void def(String var1, String var2) {
      if (!this.p.containsKey(var1)) {
         this.p.setProperty(var1, var2);
      }
   }

   String get(String var1, String var2) {
      String var3 = this.p.getProperty(var1);
      return var3 == null ? var2 : var3.trim();
   }

   String get(String var1) {
      return this.get(var1, "");
   }

   int getInt(String var1, int var2) {
      try {
         return Integer.parseInt(this.get(var1));
      } catch (Exception var4) {
         return var2;
      }
   }

   boolean getBool(String var1) {
      return Boolean.parseBoolean(this.get(var1));
   }

   synchronized void save() throws IOException {
      try (BufferedWriter var1 = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8)) {
         this.p.store(var1, "PZ Panel settings. Restart the panel after changing bind/port.");
      }
   }

   Path serverDir() {
      return Paths.get(this.get("serverDir"));
   }

   Path zomboidDir() {
      return Paths.get(this.get("zomboidDir"));
   }

   String serverName() {
      return this.get("serverName", "servertest");
   }

   Path serverIni() {
      return this.zomboidDir().resolve("Server").resolve(this.serverName() + ".ini");
   }

   Path sandboxLua() {
      return this.zomboidDir().resolve("Server").resolve(this.serverName() + "_SandboxVars.lua");
   }

   Path spawnRegions() {
      return this.zomboidDir().resolve("Server").resolve(this.serverName() + "_spawnregions.lua");
   }

   Path spawnPoints() {
      return this.zomboidDir().resolve("Server").resolve(this.serverName() + "_spawnpoints.lua");
   }

   Path worldDir() {
      return this.zomboidDir().resolve("Saves").resolve("Multiplayer").resolve(this.serverName());
   }

   static final List<String> WORLD_SUFFIXES = List.of(".ini", "_SandboxVars.lua", "_spawnpoints.lua", "_spawnregions.lua");

   Path serverConfDir() {
      return this.zomboidDir().resolve("Server");
   }

   /** Конфиги мира: ini, песочница, точки и регионы появления. */
   List<Path> worldConfigFiles(String name) {
      List<Path> r = new ArrayList<>();
      for (String s : WORLD_SUFFIXES) {
         r.add(this.serverConfDir().resolve(name + s));
      }
      return r;
   }

   Path worldSaveDir(String name) {
      return this.zomboidDir().resolve("Saves").resolve("Multiplayer").resolve(name);
   }

   Path worldAccountsDb(String name) {
      return this.zomboidDir().resolve("db").resolve(name + ".db");
   }

   Path playersDb() {
      return this.worldDir().resolve("players.db");
   }

   Path accountsDb() {
      return this.zomboidDir().resolve("db").resolve(this.serverName() + ".db");
   }

   Path consoleLog() {
      return this.zomboidDir().resolve("server-console.txt");
   }

   Path clientModsFile() {
      return this.zomboidDir().resolve("mods").resolve("default.txt");
   }

   Path clientModOptions() {
      return this.zomboidDir().resolve("Lua").resolve("ModOptions.ini");
   }

   Path zbApprovals() {
      return Paths.get(this.get("zbConfigDir")).resolve("mod_approvals.json");
   }

   Path backupDir() {
      return this.zomboidDir().resolve("panel_backups");
   }

   Path fileBackupDir() {
      return this.panelDir.resolve("file_backups");
   }

   Path topViewDir() {
      String var1 = this.get("topViewDir");
      return var1.isBlank() ? this.panelDir.resolve("map").resolve("html").resolve("map_data").resolve("base_top") : Paths.get(var1);
   }

   List<Path> workshopDirs() {
      LinkedHashSet var1 = new LinkedHashSet();
      var1.add(this.serverDir().resolve("steamapps").resolve("workshop").resolve("content").resolve("108600"));
      Path var2 = Paths.get(this.get("gameDir"));
      if (var2.getParent() != null && var2.getParent().getParent() != null) {
         var1.add(var2.getParent().getParent().resolve("workshop").resolve("content").resolve("108600"));
      }

      for (String var6 : this.get("workshopDirs").split(";")) {
         if (!var6.isBlank()) {
            var1.add(Paths.get(var6.trim()));
         }
      }

      var1.add(this.zomboidDir().resolve("Workshop"));
      return new ArrayList<>(var1);
   }

   Path pyramidZip() {
      String var1 = this.get("pyramidZip");
      if (!var1.isBlank()) {
         return Paths.get(var1);
      } else {
         for (Path var3 : List.of(this.serverDir(), Paths.get(this.get("gameDir")))) {
            Path var4 = var3.resolve("media").resolve("maps").resolve("Muldraugh, KY").resolve("pyramid.zip");
            if (Files.exists(var4)) {
               return var4;
            }
         }

         return null;
      }
   }

   Map<String, Object> asMap() {
      TreeMap var1 = new TreeMap();

      for (String var3 : this.p.stringPropertyNames()) {
         var1.put(var3, var3.equals("panelPassword") && !this.get(var3).isEmpty() ? "********" : this.get(var3));
      }

      return var1;
   }
}
