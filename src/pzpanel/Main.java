package pzpanel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Main {
   static final String VERSION = "1.4";
   private final Config cfg;
   private final ServerManager srv;
   private final Players players;
   private final Mods mods;
   private final ModTools modTools;
   private final Updater updater;
   private final Backups backups;
   private final Worlds worlds;
   private final MapData mapData;
   private final Stats stats;
   private volatile byte[] mapDataJson;
   private final String token = randomHex(24);
   private final Set<String> sessions = ConcurrentHashMap.newKeySet();
   private volatile ZipFile pyramid;
   private volatile long worldSize = -1L;
   private volatile long worldSizeAt = 0L;
   private volatile Map<String, Object> topInfoCache;

   Main(Path var1) throws IOException {
      this.cfg = new Config(var1);
      this.srv = new ServerManager(this.cfg);
      this.stats = new Stats(this.cfg, this.srv::serverHandle);
      this.players = new Players(this.cfg, this.srv);
      this.mods = new Mods(this.cfg);
      this.updater = new Updater(this.cfg);
      this.modTools = new ModTools(this.cfg, this.mods);
      this.backups = new Backups(this.cfg, this.srv);
      this.worlds = new Worlds(this.cfg, this.srv, this.backups, () -> {
         this.worldSize = -1L;
         this.worldSizeAt = 0L;
      });
      this.mapData = new MapData(this.cfg);
      this.mapData.loadAsync();
   }

   public static void main(String[] var0) throws Exception {
      try {
         Class.forName("org.sqlite.JDBC");
      } catch (Throwable var2) {
         System.err.println("SQLite JDBC не найден: " + var2);
      }

      Path var1 = var0.length > 0 ? Paths.get(var0[0]) : jarDir();
      new Main(var1).run();
   }

   static Path jarDir() {
      try {
         return Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
      } catch (Exception var1) {
         return Paths.get("").toAbsolutePath();
      }
   }

   void run() throws IOException {
      String var1 = this.cfg.get("bind", "127.0.0.1");
      boolean var2 = InetAddress.getByName(var1).isLoopbackAddress();
      if (!var2 && this.cfg.get("panelPassword").isBlank()) {
         System.err.println("Панель слушает не только localhost (bind=" + var1 + "), задайте panelPassword в panel.properties. Переключаюсь на 127.0.0.1.");
         var1 = "127.0.0.1";
         var2 = true;
      }

      int var3 = this.cfg.getInt("port", 8766);
      HttpServer var4 = HttpServer.create(new InetSocketAddress(var1, var3), 64);
      var4.setExecutor(Executors.newFixedThreadPool(12));
      boolean var5 = var2;
      var4.createContext("/", var2x -> this.handle(var2x, var5));
      var4.start();
      String var6 = "http://" + (var2 ? "127.0.0.1" : var1) + ":" + var3 + "/";
      System.out.println("PZ Panel " + VERSION + " — " + var6);
      System.out.println("Сервер: " + this.cfg.serverDir() + "  |  мир: " + this.cfg.serverName() + "  |  Zomboid: " + this.cfg.zomboidDir());
      System.out.println("Не закрывайте это окно, пока пользуетесь панелью (сервер, запущенный из панели, продолжит работать).");
      if (this.cfg.getBool("stopServerOnExit")) {
         Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Панель закрывается — сохраняю мир и останавливаю сервер...");
            this.srv.stopAndWait(this.cfg.getInt("stopTimeoutSec", 170));
         }, "pzp-shutdown"));
      }

      if (this.cfg.getBool("autoStartServer")) {
         try {
            System.out.println("autoStartServer: " + this.srv.start());
         } catch (Exception var9) {
            System.err.println("autoStartServer: " + var9.getMessage());
         }
      }

      if (this.cfg.getBool("openBrowser") && Util.isWindows()) {
         try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", var6).start();
         } catch (IOException var8) {
         }
      }
   }

   private void handle(HttpExchange var1, boolean var2) throws IOException {
      try {
         String var16 = Optional.ofNullable(var1.getRequestHeaders().getFirst("Host")).orElse("").replaceFirst(":\\d+$", "").toLowerCase(Locale.ROOT);
         if (!var2 || var16.equals("127.0.0.1") || var16.equals("localhost") || var16.equals("[::1]")) {
            String var4 = var1.getRequestURI().getPath();
            String var5 = var1.getRequestMethod();
            boolean var6 = !this.cfg.get("panelPassword").isBlank();
            boolean var7 = !var6 || this.sessions.contains(cookie(var1, "pzp_session"));
            if (var4.equals("/api/login") && var5.equals("POST")) {
               this.login(var1);
               return;
            }

            if (var4.startsWith("/api/")) {
               if (!this.token.equals(var1.getRequestHeaders().getFirst("X-PZP"))) {
                  json(var1, 403, Map.of("error", "bad token"));
                  return;
               }

               if (!var7) {
                  json(var1, 401, Map.of("error", "login"));
                  return;
               }

               this.api(var1, var4, var5);
               return;
            }

            if (var4.startsWith("/tiles/")) {
               if (!var7) {
                  send(var1, 401, "text/plain", new byte[0]);
                  return;
               }

               this.tile(var1, var4);
               return;
            }

            if (var4.startsWith("/topview/")) {
               if (!var7) {
                  send(var1, 401, "text/plain", new byte[0]);
                  return;
               }

               this.topTile(var1, var4);
               return;
            }

            if (var4.equals("/mapimg/zombies.png")) {
               if (!var7) {
                  send(var1, 401, "text/plain", new byte[0]);
                  return;
               }

               byte[] var8 = this.mapData.heatPng();
               if (var8 == null) {
                  send(var1, 404, "text/plain", new byte[0]);
                  return;
               }

               var1.getResponseHeaders().add("Cache-Control", "max-age=3600");
               send(var1, 200, "image/png", var8);
               return;
            }

            if (var4.startsWith("/poster/")) {
               if (!var7) {
                  send(var1, 401, "text/plain", new byte[0]);
                  return;
               }

               this.poster(var1, var4.substring(8));
               return;
            }

            if (var4.startsWith("/modarchive/")) {
               if (!var7) {
                  send(var1, 401, "text/plain", new byte[0]);
                  return;
               }

               this.downloadModArchive(var1, var4.substring(12));
               return;
            }

            if (!var4.startsWith("/backup/")) {
               this.staticFile(var1, var4.equals("/") ? "/index.html" : var4, var6 && !var7);
               return;
            }

            if (!var7) {
               send(var1, 401, "text/plain", new byte[0]);
               return;
            }

            this.download(var1, var4.substring(8));
            return;
         }

         send(var1, 403, "text/plain", "Forbidden host".getBytes());
      } catch (Throwable var14) {
         Throwable var3 = var14;

         try {
            json(var1, 500, Map.of("error", String.valueOf(var3.getMessage() == null ? var3.toString() : var3.getMessage())));
         } catch (Exception var13) {
         }

         return;
      } finally {
         var1.close();
      }
   }

   private void login(HttpExchange var1) throws IOException {
      JsonNode var2 = body(var1);
      if (!this.cfg.get("panelPassword").isBlank() && this.cfg.get("panelPassword").equals(var2.path("password").asText())) {
         String var3 = randomHex(24);
         this.sessions.add(var3);
         var1.getResponseHeaders().add("Set-Cookie", "pzp_session=" + var3 + "; HttpOnly; SameSite=Strict; Path=/");
         json(var1, 200, Map.of("ok", true));
      } else {
         try {
            Thread.sleep(800L);
         } catch (InterruptedException var4) {
         }

         json(var1, 403, Map.of("error", "Неверный пароль"));
      }
   }

   private void api(HttpExchange var1, String var2, String var3) throws Exception {
      Map<String, String> var4 = query(var1);
      boolean var5 = var3.equals("POST");
      switch (var2) {
         case "/api/status":
            json(var1, 200, this.statusFull());
            break;
         case "/api/stats":
            json(var1, 200, this.stats.snapshot());
            break;
         case "/api/server/start":
            requirePost(var5);
            json(var1, 200, Map.of("message", this.srv.start()));
            break;
         case "/api/server/stop":
            requirePost(var5);
            json(var1, 200, Map.of("message", this.srv.stop(body(var1).path("force").asBoolean(false), true)));
            break;
         case "/api/server/restart":
            requirePost(var5);
            json(var1, 200, Map.of("message", this.srv.restart(body(var1).path("warnMinutes").asInt(0))));
            break;
         case "/api/server/save":
            requirePost(var5);
            this.srv.sendConsole("save");
            json(var1, 200, Map.of("message", "Команда save отправлена"));
            break;
         case "/api/console":
            if (var5) {
               json(var1, 200, Map.of("output", this.srv.sendConsole(body(var1).path("cmd").asText())));
            } else {
               json(var1, 200, this.srv.console(parseLong((String)var4.get("cursor"), -1L)));
            }
            break;
         case "/api/rcon":
            requirePost(var5);
            json(var1, 200, Map.of("output", this.srv.rcon(body(var1).path("cmd").asText())));
            break;
         case "/api/players":
            json(var1, 200, this.players.all());
            break;
         case "/api/map/info":
            json(var1, 200, this.mapInfo());
            break;
         case "/api/map/data":
            if (!"ready".equals(this.mapData.status)) {
               json(var1, 200, this.mapData.summary());
               return;
            }

            if (this.mapDataJson == null) {
               this.mapDataJson = Util.JSON.writeValueAsBytes(this.mapData.summary());
            }

            sendMaybeGzip(var1, "application/json; charset=utf-8", this.mapDataJson);
            break;
         case "/api/map/buildings":
            json(
               var1,
               200,
               this.mapData
                  .buildingsIn(
                     (int)parseLong((String)var4.get("x0"), 0L),
                     (int)parseLong((String)var4.get("y0"), 0L),
                     (int)parseLong((String)var4.get("x1"), 0L),
                     (int)parseLong((String)var4.get("y1"), 0L),
                     6000
                  )
            );
            break;
         case "/api/map/building":
            json(var1, 200, this.mapData.building((int)parseLong((String)var4.get("id"), -1L)));
            break;
         case "/api/mods":
            if (var5) {
               JsonNode var22 = body(var1);
               this.mods.save(strings(var22.path("mods")), strings(var22.path("workshopItems")), var22.path("syncClient").asBoolean(false));
               this.srv.markConfigChanged();
               json(var1, 200, Map.of("message", "Сохранено. Изменения вступят в силу после перезапуска сервера."));
            } else {
               json(var1, 200, this.mods.state());
            }
            break;
         case "/api/mods/sort":
            requirePost(var5);
            JsonNode sortBody = body(var1);
            json(var1, 200, this.modTools.sort(strings(sortBody.path("mods")), sortBody.path("mode").asText("fix")));
            break;
         case "/api/update/check":
            json(var1, 200, this.updater.check("1".equals(query(var1).get("force"))));
            break;
         case "/api/update/install":
            requirePost(var5);
            json(var1, 200, Map.of("message", this.updater.install()));
            break;
         case "/api/mods/frozen":
            json(var1, 200, this.modTools.archivesState());
            break;
         case "/api/mods/pack":
            if (var5) {
               JsonNode packBody = body(var1);
               String scope = packBody.path("scope").asText("one");
               json(var1, 200, Map.of("job", this.modTools.startPack(scope, packBody.path("key").asText(""))));
            } else {
               json(var1, 200, this.modTools.job(var4.getOrDefault("job", "")));
            }
            break;
         case "/api/mods/archive/delete":
            requirePost(var5);
            this.modTools.deleteArchive(body(var1).path("name").asText());
            json(var1, 200, Map.of("message", "Архив удалён"));
            break;
         case "/api/workshop/search":
            json(var1, 200, this.mods.search(var4.getOrDefault("q", ""), (int)parseLong((String)var4.get("page"), 1L)));
            break;
         case "/api/workshop/item":
            json(var1, 200, this.mods.item(digits((String)var4.get("id"))));
            break;
         case "/api/workshop/details":
            ArrayList var21 = new ArrayList();

            for (String var29 : var4.getOrDefault("ids", "").split(",")) {
               if (var29.matches("\\d+")) {
                  var21.add(var29);
               }
            }

            json(var1, 200, this.mods.details(var21));
            break;
         case "/api/workshop/pin":
            JsonNode pinBody = body(var1);
            String pinId = digits(pinBody.path("id").asText());
            if (Util.isWindows() && !pinBody.path("approve").asBoolean(false) && this.srv.isRunning()) {
               // на Windows файлы модов (jar) заняты работающим сервером — замена папки сорвётся на середине
               throw new IOException("Остановите сервер: на Windows файлы модов заняты, пока он работает");
            }
            Map<String, Object> pinRes = pinBody.path("approve").asBoolean(false) ? this.mods.approveJava(pinId) : this.mods.pin(pinId);
            this.srv.markConfigChanged();
            json(var1, 200, pinRes);
            break;
         case "/api/workshop/collection":
            json(var1, 200, this.mods.collection(digits((String)var4.get("id"))));
            break;
         case "/api/config/ini":
            if (var5) {
               Map var20 = Util.JSON.convertValue(body(var1).path("changes"), new TypeReference<Map<String, String>>() {});
               Configs.writeIni(this.cfg.serverIni(), var20, this.cfg.fileBackupDir());
               this.srv.markConfigChanged();
               json(var1, 200, Map.of("message", "Сохранено " + var20.size() + " параметров. Нужен перезапуск сервера."));
            } else {
               json(var1, 200, Map.of("path", this.cfg.serverIni().toString(), "entries", Configs.readIni(this.cfg.serverIni())));
            }
            break;
         case "/api/config/sandbox":
            if (var5) {
               List var19 = Util.JSON.convertValue(body(var1).path("changes"), new TypeReference<List<Map<String, Object>>>() {});
               int var24 = Configs.writeSandbox(this.cfg.sandboxLua(), var19, this.cfg.fileBackupDir());
               this.srv.markConfigChanged();
               json(var1, 200, Map.of("message", "Изменено параметров: " + var24 + ". Нужен перезапуск сервера."));
            } else {
               json(var1, 200, Map.of("path", this.cfg.sandboxLua().toString(), "entries", Configs.readSandbox(this.cfg.sandboxLua())));
            }
            break;
         case "/api/file":
            Path var18 = this.namedFile(var4.getOrDefault("name", ""));
            if (var5) {
               Util.writeTextWithBackup(var18, body(var1).path("text").asText(), this.cfg.fileBackupDir());
               this.srv.markConfigChanged();
               json(var1, 200, Map.of("message", "Файл сохранён (копия прежней версии — в file_backups)"));
            } else {
               json(var1, 200, Map.of("path", var18.toString(), "exists", Files.exists(var18), "text", Files.exists(var18) ? Util.readText(var18) : ""));
            }
            break;
         case "/api/zb":
            Path var17 = this.cfg.zbApprovals();
            if (var5) {
               JsonNode var23 = body(var1);
               ObjectNode var26 = (ObjectNode)Util.JSON.readTree(Util.readText(var17));
               ArrayNode var11 = (ArrayNode)var26.path("mods");
               String var12 = var23.path("id").asText();
               String var13 = var23.path("jar_hash").asText();
               String var14 = var23.path("action").asText();

               for (int var15 = var11.size() - 1; var15 >= 0; var15--) {
                  JsonNode var16 = var11.get(var15);
                  if (var16.path("id").asText().equals(var12) && var16.path("jar_hash").asText().equals(var13)) {
                     if (var14.equals("delete")) {
                        var11.remove(var15);
                     } else {
                        ((ObjectNode)var16).put("decision", var14.equals("allow"));
                     }
                  }
               }

               Util.writeTextWithBackup(var17, Util.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(var26), this.cfg.fileBackupDir());
               json(var1, 200, Map.of("message", "Сохранено. ZombieBuddy прочитает это при следующем запуске игры/сервера."));
            } else {
               json(var1, 200, Map.of("path", var17.toString(), "data", (Object)(Files.exists(var17) ? Util.JSON.readTree(Util.readText(var17)) : Map.of())));
            }
            break;
         case "/api/backups":
            json(var1, 200, Map.of("list", this.backups.list(), "progress", this.backups.progress(), "dir", this.cfg.backupDir().toString()));
            break;
         case "/api/backups/create":
            requirePost(var5);
            {
               String w = body(var1).path("world").asText("");
               json(var1, 200, Map.of("message", (w.isBlank() ? this.backups.create() : this.backups.create(w))));
            }
            break;
         case "/api/backups/restore":
            requirePost(var5);
            {
               JsonNode b = body(var1);
               json(var1, 200, Map.of("message", this.backups.restore(b.path("name").asText(), b.path("safety").asBoolean(true))));
            }
            break;
         case "/api/worlds":
            json(var1, 200, this.worlds.list());
            break;
         case "/api/worlds/activate":
            requirePost(var5);
            json(var1, 200, Map.of("message", this.worlds.activate(body(var1).path("name").asText())));
            break;
         case "/api/worlds/create":
            requirePost(var5);
            {
               JsonNode b = body(var1);
               json(var1, 200, Map.of("message", this.worlds.create(b.path("name").asText(), b.path("from").asText(), b.path("mode").asText("settings"), b.path("activate").asBoolean(false))));
            }
            break;
         case "/api/worlds/wipe":
            requirePost(var5);
            {
               JsonNode b = body(var1);
               json(var1, 200, Map.of("message", this.worlds.wipe(b.path("name").asText(), b.path("keepAccounts").asBoolean(true), b.path("backup").asBoolean(true))));
            }
            break;
         case "/api/worlds/rename":
            requirePost(var5);
            {
               JsonNode b = body(var1);
               json(var1, 200, Map.of("message", this.worlds.rename(b.path("name").asText(), b.path("newName").asText())));
            }
            break;
         case "/api/worlds/delete":
            requirePost(var5);
            {
               JsonNode b = body(var1);
               json(var1, 200, Map.of("message", this.worlds.delete(b.path("name").asText(), b.path("backup").asBoolean(true))));
            }
            break;
         case "/api/worlds/deleteOrphan":
            requirePost(var5);
            {
               JsonNode b = body(var1);
               json(var1, 200, Map.of("message", this.worlds.deleteOrphan(b.path("name").asText(), b.path("backup").asBoolean(false))));
            }
            break;
         case "/api/backups/delete":
            requirePost(var5);
            this.backups.delete(body(var1).path("name").asText());
            json(var1, 200, Map.of("message", "Удалено"));
            break;
         case "/api/settings":
            if (var5) {
               Map<String, String> var8 = Util.JSON.convertValue(body(var1).path("changes"), new TypeReference<Map<String, String>>() {});

               for (Entry<String, String> var10 : var8.entrySet()) {
                  if (!((String)var10.getKey()).equals("panelPassword") || !((String)var10.getValue()).equals("********")) {
                     this.cfg.p.setProperty((String)var10.getKey(), var10.getValue() == null ? "" : ((String)var10.getValue()).trim());
                  }
               }

               this.cfg.save();
               this.pyramid = null;
               if (var8.containsKey("serverName")) {
                  this.worlds.syncScript();
               }
               json(var1, 200, Map.of("message", "Настройки сохранены. bind/port применятся после перезапуска панели."));
            } else {
               json(var1, 200, Map.of("settings", this.cfg.asMap(), "file", this.cfg.file.toString()));
            }
            break;
         default:
            json(var1, 404, Map.of("error", "not found"));
      }
   }

   private Map<String, Object> statusFull() {
      LinkedHashMap var1 = new LinkedHashMap<>(this.srv.status());
      var1.put("panelVersion", VERSION);
      var1.put("runningWorld", this.srv.runningWorld());
      LinkedHashMap var2 = new LinkedHashMap();
      var2.put("serverDir", this.cfg.serverDir().toString());
      var2.put("ini", this.cfg.serverIni().toString());
      var2.put("world", this.cfg.worldDir().toString());
      var2.put("iniExists", Files.exists(this.cfg.serverIni()));
      var1.put("paths", var2);

      try {
         var1.put("lastSave", Files.getLastModifiedTime(this.cfg.playersDb()).toMillis());
      } catch (IOException var6) {
      }

      if (System.currentTimeMillis() - this.worldSizeAt > 600000L) {
         this.worldSizeAt = System.currentTimeMillis();
         CompletableFuture.runAsync(() -> this.worldSize = Util.dirSize(this.cfg.worldDir()));
      }

      var1.put("worldSize", this.worldSize);
      Path var3 = this.cfg.serverIni();
      var1.put("modsCount", Mods.splitList(Configs.iniValue(var3, "Mods")).size());
      var1.put("workshopCount", Mods.splitList(Configs.iniValue(var3, "WorkshopItems")).size());
      var1.put("maxPlayers", Configs.iniValue(var3, "MaxPlayers"));
      var1.put("publicName", Configs.iniValue(var3, "PublicName"));
      var1.put("gamePort", Configs.iniValue(var3, "DefaultPort"));
      var1.put("rconPort", Configs.iniValue(var3, "RCONPort"));
      var1.put("rconEnabled", !Configs.iniValue(var3, "RCONPassword").isEmpty());

      try {
         var1.put("diskFreeGb", Files.getFileStore(this.cfg.zomboidDir()).getUsableSpace() / 1073741824L);
      } catch (Exception var5) {
      }

      return var1;
   }

   private Path namedFile(String var1) throws IOException {
      return switch (var1) {
         case "ini" -> this.cfg.serverIni();
         case "sandbox" -> this.cfg.sandboxLua();
         case "spawnregions" -> this.cfg.spawnRegions();
         case "spawnpoints" -> this.cfg.spawnPoints();
         case "clientmods" -> this.cfg.clientModsFile();
         case "modoptions" -> this.cfg.clientModOptions();
         case "startscript" -> this.cfg.serverDir().resolve(this.cfg.get("startScript"));
         default -> throw new IOException("Неизвестный файл");
      };
   }

   private ZipFile pyramid() throws IOException {
      if (this.pyramid == null) {
         Path var1 = this.cfg.pyramidZip();
         if (var1 == null || !Files.exists(var1)) {
            throw new IOException("pyramid.zip не найден (укажите pyramidZip в настройках)");
         }

         this.pyramid = new ZipFile(var1.toFile());
      }

      return this.pyramid;
   }

   private Map<String, Object> mapInfo() {
      LinkedHashMap var1 = new LinkedHashMap();

      try {
         ZipFile var2 = this.pyramid();
         ZipEntry var3 = var2.getEntry("pyramid.txt");
         String var4 = var3 == null ? "" : new String(var2.getInputStream(var3).readAllBytes(), StandardCharsets.UTF_8);
         int[] var5 = new int[]{0, 0, 19968, 16128};

         for (String var9 : var4.split("\r?\n")) {
            if (var9.startsWith("bounds=")) {
               String[] var10 = var9.substring(7).trim().split("\\s+");

               for (int var11 = 0; var11 < 4 && var11 < var10.length; var11++) {
                  var5[var11] = Integer.parseInt(var10[var11]);
               }
            }
         }

         int var13 = 0;

         while (var2.getEntry(var13 + 1 + "/") != null || var2.getEntry(var13 + 1 + "/tile0x0.png") != null) {
            var13++;
         }

         var1.put("ok", true);
         var1.put("bounds", var5);
         var1.put("levels", var13);
         var1.put("tileSize", 256);
         Map var14 = this.topInfo();
         if (var14 != null) {
            var1.put("top", var14);
         }
      } catch (Exception var12) {
         var1.put("ok", false);
         var1.put("error", var12.getMessage());
      }

      return var1;
   }

   private Map<String, Object> topInfo() {
      Map var1 = this.topInfoCache;
      if (var1 != null) {
         return var1;
      } else {
         try {
            Path var2 = this.cfg.topViewDir();
            if (var2 != null && Files.exists(var2.resolve("map_info.json")) && Files.exists(var2.resolve("layer0.dzi"))) {
               JsonNode var3 = Util.JSON.readTree(Files.readAllBytes(var2.resolve("map_info.json")));
               String var4 = Util.readText(var2.resolve("layer0.dzi"));
               Matcher var5 = Pattern.compile("TileSize=\"(\\d+)\"").matcher(var4);
               Matcher var6 = Pattern.compile("Format=\"(\\w+)\"").matcher(var4);
               int var7 = -1;

               try (DirectoryStream<Path> var8 = Files.newDirectoryStream(var2.resolve("layer0_files"))) {
                  for (Path var10 : var8) {
                     String var11 = var10.getFileName().toString();
                     if (var11.matches("\\d+")) {
                        var7 = Math.max(var7, Integer.parseInt(var11));
                     }
                  }
               }

               if (var7 < 0) {
                  return null;
               } else {
                  ArrayList var25 = new ArrayList();

                  try (DirectoryStream<Path> var26 = Files.newDirectoryStream(var2, "layer*_files")) {
                     for (Path var29 : var26) {
                        Matcher var12 = Pattern.compile("layer(-?\\d+)_files").matcher(var29.getFileName().toString());
                        if (var12.matches()) {
                           int var13 = Integer.parseInt(var12.group(1));
                           boolean var14 = false;

                           try (Stream<Path> var15 = Files.walk(var29, 2)) {
                              var14 = var15.anyMatch(var0 -> Files.isRegularFile(var0) && var0.getFileName().toString().matches("\\d+_\\d+\\.\\w+"));
                           }

                           if (var14) {
                              var25.add(var13);
                           }
                        }
                     }
                  }

                  Collections.sort(var25);
                  LinkedHashMap var27 = new LinkedHashMap();
                  var27.put("w", var3.path("w").asInt());
                  var27.put("h", var3.path("h").asInt());
                  var27.put("x0", var3.path("x0").asDouble());
                  var27.put("y0", var3.path("y0").asDouble());
                  var27.put("sqr", var3.path("sqr").asDouble(1.0));
                  var27.put("skip", var3.path("skip").asInt(0));
                  var27.put("tileSize", var5.find() ? Integer.parseInt(var5.group(1)) : 256);
                  var27.put("ext", var6.find() ? var6.group(1) : "webp");
                  var27.put("maxLevel", var7);
                  var27.put("layers", var25);
                  this.topInfoCache = var27;
                  return var27;
               }
            } else {
               return null;
            }
         } catch (Exception var24) {
            return null;
         }
      }
   }

   private void topTile(HttpExchange var1, String var2) throws IOException {
      String[] var3 = var2.split("/");
      Map var4 = this.topInfo();
      if (var3.length == 5 && var4 != null && var3[2].matches("-?\\d+") && var3[3].matches("\\d+") && var3[4].matches("\\d+_\\d+\\.(webp|png|jpg|jpeg)")) {
         Path var5 = this.cfg.topViewDir().resolve("layer" + Integer.parseInt(var3[2]) + "_files").resolve(var3[3]).resolve(var3[4]);
         if (!Files.exists(var5)) {
            send(var1, 404, "text/plain", new byte[0]);
         } else {
            String var6 = var3[4].substring(var3[4].lastIndexOf(46) + 1);
            var1.getResponseHeaders().add("Cache-Control", "max-age=86400");
            send(var1, 200, var6.equals("webp") ? "image/webp" : (var6.equals("png") ? "image/png" : "image/jpeg"), Files.readAllBytes(var5));
         }
      } else {
         send(var1, 404, "text/plain", new byte[0]);
      }
   }

   private void tile(HttpExchange var1, String var2) throws IOException {
      String[] var3 = var2.split("/");
      if (var3.length != 5) {
         send(var1, 404, "text/plain", new byte[0]);
      } else {
         String var4 = Integer.parseInt(var3[2]) + "/tile" + Integer.parseInt(var3[3]) + "x" + Integer.parseInt(var3[4].replace(".png", "")) + ".png";
         ZipFile var5 = this.pyramid();
         ZipEntry var6 = var5.getEntry(var4);
         if (var6 == null) {
            send(var1, 404, "text/plain", new byte[0]);
         } else {
            byte[] var7;
            synchronized (var5) {
               var7 = var5.getInputStream(var6).readAllBytes();
            }

            var1.getResponseHeaders().add("Cache-Control", "max-age=86400");
            send(var1, 200, "image/png", var7);
         }
      }
   }

   private void poster(HttpExchange var1, String var2) throws IOException {
      Path var3 = this.mods.posterPath(URLDecoder.decode(var2, StandardCharsets.UTF_8));
      if (var3 != null && Files.exists(var3)) {
         var1.getResponseHeaders().add("Cache-Control", "max-age=3600");
         send(var1, 200, "image/png", Files.readAllBytes(var3));
      } else {
         send(var1, 404, "text/plain", new byte[0]);
      }
   }

   private void download(HttpExchange var1, String var2) throws IOException {
      var2 = URLDecoder.decode(var2, StandardCharsets.UTF_8);
      if (!var2.contains("..") && !var2.contains("/") && !var2.contains("\\")) {
         Path var3 = this.cfg.backupDir().resolve(var2);
         if (!Files.exists(var3)) {
            send(var1, 404, "text/plain", new byte[0]);
         } else {
            var1.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + var2.replaceAll("[^A-Za-z0-9_.\\-]", "_") + "\"");
            var1.getResponseHeaders().add("Content-Type", "application/zip");
            var1.sendResponseHeaders(200, Files.size(var3));

            try (OutputStream var4 = var1.getResponseBody()) {
               Files.copy(var3, var4);
            }
         }
      } else {
         send(var1, 400, "text/plain", new byte[0]);
      }
   }

   private void downloadModArchive(HttpExchange var1, String var2) throws IOException {
      var2 = URLDecoder.decode(var2, StandardCharsets.UTF_8);
      Path var3 = this.modTools.archiveFile(var2);
      if (var3 == null) {
         send(var1, 404, "text/plain", new byte[0]);
         return;
      }
      var1.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + var2 + "\"");
      var1.getResponseHeaders().add("Content-Type", "application/zip");
      var1.sendResponseHeaders(200, Files.size(var3));
      try (OutputStream var4 = var1.getResponseBody()) {
         Files.copy(var3, var4);
      }
   }

   private void staticFile(HttpExchange var1, String var2, boolean var3) throws IOException {
      if (var2.contains("..")) {
         send(var1, 400, "text/plain", new byte[0]);
      } else {
         String var4 = "/web" + var2;
         InputStream var5 = Main.class.getResourceAsStream(var4);
         if (var5 == null) {
            send(var1, 404, "text/plain", "Not found".getBytes());
         } else {
            byte[] var6 = var5.readAllBytes();
            String var7 = var2.endsWith(".html")
               ? "text/html; charset=utf-8"
               : (
                  var2.endsWith(".js")
                     ? "application/javascript; charset=utf-8"
                     : (var2.endsWith(".css") ? "text/css; charset=utf-8" : (var2.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream"))
               );
            if (var2.endsWith(".html")) {
               var6 = new String(var6, StandardCharsets.UTF_8)
                  .replace("%%TOKEN%%", this.token)
                  .replace("%%LOGIN%%", String.valueOf(var3))
                  .getBytes(StandardCharsets.UTF_8);
               var1.getResponseHeaders().add("Cache-Control", "no-store");
            }

            var1.getResponseHeaders().add("X-Frame-Options", "DENY");
            send(var1, 200, var7, var6);
         }
      }
   }

   private static void requirePost(boolean var0) throws IOException {
      if (!var0) {
         throw new IOException("POST required");
      }
   }

   private static JsonNode body(HttpExchange var0) throws IOException {
      byte[] var1 = var0.getRequestBody().readAllBytes();
      return (JsonNode)(var1.length == 0 ? Util.JSON.createObjectNode() : Util.JSON.readTree(var1));
   }

   private static List<String> strings(JsonNode var0) {
      ArrayList var1 = new ArrayList();

      for (JsonNode var3 : var0) {
         String var4 = var3.asText().trim();
         if (!var4.isEmpty() && !var4.contains(";")) {
            var1.add(var4);
         }
      }

      return var1;
   }

   private static String digits(String var0) throws IOException {
      if (var0 == null) {
         throw new IOException("Нет id");
      } else {
         Matcher var1 = Pattern.compile("(\\d{6,})").matcher(var0);
         if (!var1.find()) {
            throw new IOException("Не найден ID предмета Мастерской");
         } else {
            return var1.group(1);
         }
      }
   }

   private static long parseLong(String var0, long var1) {
      try {
         return Long.parseLong(var0);
      } catch (Exception var4) {
         return var1;
      }
   }

   private static Map<String, String> query(HttpExchange var0) {
      HashMap var1 = new HashMap();
      String var2 = var0.getRequestURI().getRawQuery();
      if (var2 == null) {
         return var1;
      } else {
         for (String var6 : var2.split("&")) {
            int var7 = var6.indexOf(61);
            if (var7 > 0) {
               var1.put(URLDecoder.decode(var6.substring(0, var7), StandardCharsets.UTF_8), URLDecoder.decode(var6.substring(var7 + 1), StandardCharsets.UTF_8));
            }
         }

         return var1;
      }
   }

   private static String cookie(HttpExchange var0, String var1) {
      String var2 = var0.getRequestHeaders().getFirst("Cookie");
      if (var2 == null) {
         return "";
      } else {
         for (String var6 : var2.split(";")) {
            String var7 = var6.trim();
            if (var7.startsWith(var1 + "=")) {
               return var7.substring(var1.length() + 1);
            }
         }

         return "";
      }
   }

   private static void sendMaybeGzip(HttpExchange var0, String var1, byte[] var2) throws IOException {
      String var3 = Optional.ofNullable(var0.getRequestHeaders().getFirst("Accept-Encoding")).orElse("");
      if (var3.contains("gzip") && var2.length >= 4096) {
         ByteArrayOutputStream var4 = new ByteArrayOutputStream();

         try (GZIPOutputStream var5 = new GZIPOutputStream(var4)) {
            var5.write(var2);
         }

         var0.getResponseHeaders().set("Content-Encoding", "gzip");
         send(var0, 200, var1, var4.toByteArray());
      } else {
         send(var0, 200, var1, var2);
      }
   }

   private static void json(HttpExchange var0, int var1, Object var2) throws IOException {
      send(var0, var1, "application/json; charset=utf-8", Util.JSON.writeValueAsBytes(var2));
   }

   private static void send(HttpExchange var0, int var1, String var2, byte[] var3) throws IOException {
      var0.getResponseHeaders().set("Content-Type", var2);
      var0.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      var0.sendResponseHeaders(var1, var3.length == 0 ? -1L : var3.length);
      if (var3.length > 0) {
         try (OutputStream var4 = var0.getResponseBody()) {
            var4.write(var3);
         }
      }
   }

   private static String randomHex(int var0) {
      byte[] var1 = new byte[var0];
      new SecureRandom().nextBytes(var1);
      StringBuilder var2 = new StringBuilder();

      for (byte var6 : var1) {
         var2.append(String.format("%02x", var6));
      }

      return var2.toString();
   }
}
