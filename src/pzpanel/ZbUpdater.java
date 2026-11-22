package pzpanel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Обновление ZombieBuddy через панель.
 * ZombieBuddy состоит из двух частей: агента, который сервер грузит при запуске (ZombieBuddy.jar рядом с сервером,
 * на Windows ещё zbNative.dll и -agentlib:zbNative; на Linux — -javaagent в ProjectZomboid64.json), и мода
 * (предмет Мастерской 3619862853, папка ZombieBuddy с libs/ZombieBuddy.jar). Свежая версия берётся из Мастерской:
 * SteamCMD скачивает предмет анонимно в PZPanel/zb_download, или из уже скачанных копий.
 */
final class ZbUpdater {
   static final String WID = "3619862853";
   private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
   private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

   private final Config cfg;
   private final Mods mods;
   private final ServerManager srv;
   private final Backups jobs;

   ZbUpdater(Config cfg, Mods mods, ServerManager srv, Backups jobs) {
      this.cfg = cfg;
      this.mods = mods;
      this.srv = srv;
      this.jobs = jobs;
   }

   /** Куда SteamCMD качает предмет: на Windows — PZPanel\\zb_download, на Linux — steamcmdCache (как у «Закрепить»). */
   private Path downloadDir() {
      if (Util.isWindows()) {
         return this.cfg.panelDir.resolve("zb_download");
      }
      return Paths.get(this.cfg.get("steamcmdCache", "/tmp/pzws"));
   }

   private Path localMod() {
      return this.cfg.zomboidDir().resolve("mods").resolve("ZombieBuddy");
   }

   /** Копии мода, откуда можно взять новую версию: ключ → папка ZombieBuddy (с libs/ZombieBuddy.jar). */
   private Map<String, Path> sources() {
      Map<String, Path> r = new LinkedHashMap<>();
      r.put("download", this.downloadDir().resolve("steamapps/workshop/content/108600").resolve(WID).resolve("mods/ZombieBuddy"));
      r.put("local", this.localMod());
      r.put("serverWorkshop", this.cfg.serverDir().resolve("steamapps/workshop/content/108600").resolve(WID).resolve("mods/ZombieBuddy"));
      List<Path> ws = this.cfg.workshopDirs();
      if (ws.size() > 1) {
         r.put("clientWorkshop", ws.get(1).resolve(WID).resolve("mods/ZombieBuddy"));
      }
      return r;
   }

   private static String sourceTitle(String k) {
      return switch (k) {
         case "download" -> "Скачано панелью (SteamCMD)";
         case "local" -> "Мод в Zomboid/mods (замороженный)";
         case "serverWorkshop" -> "Мастерская сервера";
         case "clientWorkshop" -> "Мастерская Steam-клиента игры";
         default -> k;
      };
   }

   // ------------------------------------------------------------------ где агент сервера

   /** Путь к ZombieBuddy.jar, который грузит сервер (null — не нашли). */
   Path agentJar() {
      Path sd = this.cfg.serverDir();
      // -javaagent:<путь>[=опции] в ProjectZomboid64.json или в скрипте запуска
      for (Path f : List.of(sd.resolve("ProjectZomboid64.json"), sd.resolve(this.cfg.get("startScript")))) {
         try {
            if (Files.isRegularFile(f)) {
               Matcher m = Pattern.compile("-javaagent:\"?([^\"=,\\s]*ZombieBuddy[^\"=,\\s]*\\.jar)").matcher(Util.readText(f));
               if (m.find()) {
                  Path p = Paths.get(m.group(1));
                  return p.isAbsolute() ? p : sd.resolve(p).normalize();
               }
            }
         } catch (Exception e) {
         }
      }
      Path def = sd.resolve("ZombieBuddy.jar");
      if (Files.isRegularFile(def)) {
         return def;
      }
      // Windows: -agentlib:zbNative грузит ZombieBuddy.jar из папки сервера — ставим туда, даже если файла ещё нет
      for (Path f : List.of(sd.resolve("ProjectZomboid64.json"), sd.resolve(this.cfg.get("startScript")))) {
         try {
            if (Files.isRegularFile(f) && Util.readText(f).contains("-agentlib:zbNative")) {
               return def;
            }
         } catch (Exception e) {
         }
      }
      return null;
   }

   /** zbNative.dll на Windows — рядом с сервером (его грузит -agentlib:zbNative, а он — ZombieBuddy.jar рядом с собой). */
   private Path nativeDll() {
      return this.cfg.serverDir().resolve("zbNative.dll");
   }

   // ------------------------------------------------------------------ состояние

   Map<String, Object> state() {
      Map<String, Object> r = new LinkedHashMap<>();
      Path agent = this.agentJar();
      r.put("agent", agent == null ? null : jarInfo(agent));
      r.put("agentPath", agent == null ? "" : agent.toString());
      r.put("windows", Util.isWindows());
      r.put("native", Util.isWindows() && Files.isRegularFile(this.nativeDll()) ? fileInfo(this.nativeDll()) : null);
      List<Map<String, Object>> src = new ArrayList<>();
      for (Map.Entry<String, Path> e : this.sources().entrySet()) {
         Path jar = e.getValue().resolve("libs").resolve("ZombieBuddy.jar");
         if (!Files.isRegularFile(jar)) {
            continue;
         }
         Map<String, Object> m = jarInfo(jar);
         m.put("key", e.getKey());
         m.put("title", sourceTitle(e.getKey()));
         m.put("dir", e.getValue().toString());
         src.add(m);
      }
      r.put("sources", src);
      r.put("localMod", Files.isDirectory(this.localMod()));
      long updated = 0;
      try {
         for (Map<String, Object> d : this.mods.details(List.of(WID))) {
            if (WID.equals(d.get("id"))) {
               updated = ((Number) d.getOrDefault("updated", 0L)).longValue();
            }
         }
      } catch (Exception e) {
      }
      r.put("workshopUpdated", updated * 1000L);
      r.put("authors", authorsInfo(this.authorsFile()));
      r.put("authorsPath", this.authorsFile().toString());
      r.put("serverRunning", this.srv.isRunning());
      r.put("progress", this.jobs.progress());
      r.put("busy", this.jobs.busy());
      Path sc = this.findSteamcmd();
      r.put("steamcmd", sc == null ? "" : sc.toString());
      return r;
   }

   private static Map<String, Object> fileInfo(Path f) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("path", f.toString());
      try {
         m.put("time", Files.getLastModifiedTime(f).toMillis());
         m.put("size", Files.size(f));
         m.put("sha256", sha256(f));
      } catch (IOException e) {
      }
      return m;
   }

   private static Map<String, Object> jarInfo(Path jar) {
      Map<String, Object> m = fileInfo(jar);
      String ver = "";
      try (JarFile jf = new JarFile(jar.toFile(), false)) {
         Manifest mf = jf.getManifest();
         if (mf != null) {
            ver = String.valueOf(mf.getMainAttributes().getValue("Implementation-Version"));
         }
      } catch (IOException e) {
      }
      m.put("version", "null".equals(ver) ? "" : ver);
      return m;
   }

   private static String sha256(Path f) throws IOException {
      try (InputStream in = Files.newInputStream(f)) {
         MessageDigest md = MessageDigest.getInstance("SHA-256");
         byte[] b = new byte[1 << 16];
         int n;
         while ((n = in.read(b)) > 0) {
            md.update(b, 0, n);
         }
         return HexFormat.of().formatHex(md.digest());
      } catch (java.security.NoSuchAlgorithmException e) {
         throw new IOException(e);
      }
   }

   // ------------------------------------------------------------------ скачивание через SteamCMD

   Path findSteamcmd() {
      List<Path> c = new ArrayList<>();
      String conf = this.cfg.get("steamcmd", "");
      if (!conf.isBlank()) {
         c.add(Paths.get(conf.trim()));
      }
      Path home = Paths.get(System.getProperty("user.home"));
      String exe = Util.isWindows() ? "steamcmd.exe" : "steamcmd.sh";
      c.add(this.cfg.panelDir.resolve("steamcmd").resolve(exe));
      c.add(home.resolve("steamcmd").resolve(exe));
      if (Util.isWindows()) {
         c.add(Paths.get("C:\\steamcmd\\steamcmd.exe"));
         c.add(home.resolve("scoop/apps/steamcmd/current/steamcmd.exe"));
      } else {
         c.add(Paths.get("/usr/games/steamcmd"));
         c.add(Paths.get("/usr/bin/steamcmd"));
      }
      for (Path p : c) {
         if (Files.isRegularFile(p)) {
            return p;
         }
      }
      return null;
   }

   /** SteamCMD не найден — скачать с серверов Valve в PZPanel/steamcmd. */
   private Path installSteamcmd() throws IOException, InterruptedException {
      boolean win = Util.isWindows();
      Path dir = this.cfg.panelDir.resolve("steamcmd");
      Files.createDirectories(dir);
      String url = "https://steamcdn-a.akamaihd.net/client/installer/" + (win ? "steamcmd.zip" : "steamcmd_linux.tar.gz");
      this.jobs.step("скачиваю SteamCMD с серверов Valve");
      HttpResponse<Path> rs = Util.HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
         HttpResponse.BodyHandlers.ofFile(dir.resolve(win ? "steamcmd.zip" : "steamcmd_linux.tar.gz")));
      if (rs.statusCode() != 200) {
         throw new IOException("Не удалось скачать SteamCMD: HTTP " + rs.statusCode());
      }
      if (win) {
         try (ZipInputStream z = new ZipInputStream(Files.newInputStream(rs.body()))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
               if (!e.isDirectory() && !e.getName().contains("..")) {
                  Files.copy(z, dir.resolve(e.getName()), StandardCopyOption.REPLACE_EXISTING);
               }
            }
         }
      } else {
         Process p = new ProcessBuilder("tar", "xzf", rs.body().toString(), "-C", dir.toString()).redirectErrorStream(true).start();
         String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
         if (p.waitFor() != 0) {
            throw new IOException("Не удалось распаковать SteamCMD: " + out.trim());
         }
      }
      Files.deleteIfExists(rs.body());
      Path exe = dir.resolve(win ? "steamcmd.exe" : "steamcmd.sh");
      if (!Files.isRegularFile(exe)) {
         throw new IOException("SteamCMD скачан, но " + exe + " не найден");
      }
      return exe;
   }

   /** Скачать свежий ZombieBuddy из Мастерской (фоновая операция). */
   String download() throws IOException {
      return this.jobs.runJob("скачивание ZombieBuddy из Мастерской", () -> {
         Path sc = this.findSteamcmd();
         if (sc == null) {
            if (!Util.isWindows()) {
               throw new IOException("SteamCMD не найден: укажите путь к steamcmd.sh в параметре steamcmd на вкладке «Панель»");
            }
            sc = this.installSteamcmd();
         }
         Path dl = this.downloadDir();
         Path item = dl.resolve("steamapps/workshop/content/108600").resolve(WID);
         Util.deleteTree(item);
         Files.createDirectories(dl);
         StringBuilder tail = new StringBuilder();
         for (int attempt = 1; attempt <= 2 && !Files.isRegularFile(item.resolve("mods/ZombieBuddy/libs/ZombieBuddy.jar")); attempt++) {
            List<String> cmd = new ArrayList<>();
            if (!Util.isWindows() && "root".equals(System.getProperty("user.name"))) {
               cmd.addAll(List.of("runuser", "-u", "steam", "--")); // как у «Закрепить»: SteamCMD не запускается от root
            }
            if (sc.toString().endsWith(".sh")) {
               cmd.add("bash");
            }
            cmd.add(sc.toString());
            cmd.addAll(List.of("+force_install_dir", dl.toAbsolutePath().toString(), "+login", "anonymous",
               "+workshop_download_item", "108600", WID, "validate", "+quit"));
            this.jobs.step("SteamCMD: запуск" + (attempt > 1 ? " (повтор)" : ""));
            Process p = new ProcessBuilder(cmd).directory(sc.getParent().toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
               String ln;
               while ((ln = r.readLine()) != null) {
                  for (String part : ln.split("\r")) {
                     String s = ANSI.matcher(part).replaceAll("").trim();
                     if (!s.isEmpty()) {
                        this.jobs.step("SteamCMD: " + (s.length() > 150 ? s.substring(0, 150) + "…" : s));
                        tail.append(s).append('\n');
                        if (tail.length() > 4000) {
                           tail.delete(0, tail.length() - 4000);
                        }
                     }
                  }
               }
            }
            if (!p.waitFor(20, TimeUnit.MINUTES)) {
               p.destroyForcibly();
               throw new IOException("SteamCMD не уложился в 20 минут");
            }
         }
         Path jar = item.resolve("mods/ZombieBuddy/libs/ZombieBuddy.jar");
         if (!Files.isRegularFile(jar)) {
            String err = "";
            for (String l : tail.toString().split("\n")) {
               if (l.contains("ERROR") || l.toLowerCase().contains("fail")) {
                  err = l;
               }
            }
            throw new IOException("SteamCMD не скачал ZombieBuddy" + (err.isEmpty() ? "" : ": " + err)
               + (Util.isWindows() ? "" : ". На Linux SteamCMD нужны 32-битные библиотеки: sudo apt install -y lib32gcc-s1"));
         }
         String v = String.valueOf(jarInfo(jar).get("version"));
         return "скачан ZombieBuddy " + (v.isEmpty() ? "" : v + " ") + "— теперь его можно установить";
      });
   }

   // ------------------------------------------------------------------ установка

   /**
    * Ставит ZombieBuddy из копии source: agent — ZombieBuddy.jar сервера (+ zbNative.dll на Windows),
    * mod — папка Zomboid/mods/ZombieBuddy (если мод заморожен там). Прежние версии сохраняются.
    */
   Map<String, Object> install(String source, boolean agent, boolean mod) throws IOException {
      if (this.srv.isRunning()) {
         throw new IOException("Остановите сервер: обновлять ZombieBuddy можно только при остановленном сервере");
      }
      if (this.jobs.busy()) {
         throw new IOException("Подождите: " + this.jobs.progress());
      }
      Path src = this.sources().get(source);
      if (src == null || !Files.isRegularFile(src.resolve("libs/ZombieBuddy.jar"))) {
         throw new IOException("Нет такой копии ZombieBuddy");
      }
      if (!agent && !mod) {
         throw new IOException("Ничего не выбрано");
      }
      String ver = String.valueOf(jarInfo(src.resolve("libs/ZombieBuddy.jar")).get("version"));
      String stamp = LocalDateTime.now().format(STAMP);
      List<String> done = new ArrayList<>();
      if (agent) {
         Path a = this.agentJar();
         if (a == null) {
            throw new IOException("Не найден ZombieBuddy.jar сервера (ни -javaagent в ProjectZomboid64.json, ни файла рядом с сервером) — снимите галочку «агент сервера»");
         }
         Path bk = this.cfg.fileBackupDir();
         Files.createDirectories(bk);
         if (Files.exists(a)) {
            String old = String.valueOf(jarInfo(a).get("version"));
            Files.copy(a, bk.resolve("ZombieBuddy_" + (old.isEmpty() ? "old" : old) + "_" + stamp + ".jar"), StandardCopyOption.REPLACE_EXISTING);
         }
         replaceFile(src.resolve("libs/ZombieBuddy.jar"), a);
         Files.deleteIfExists(a.resolveSibling("ZombieBuddy.jar.new"));
         done.add("агент сервера (" + a + ")");
         if (Util.isWindows() && Files.isRegularFile(src.resolve("libs/zbNative.dll"))) {
            Path dll = this.nativeDll();
            if (Files.exists(dll)) {
               Files.copy(dll, bk.resolve("zbNative_" + stamp + ".dll"), StandardCopyOption.REPLACE_EXISTING);
            }
            replaceFile(src.resolve("libs/zbNative.dll"), dll);
            done.add("zbNative.dll");
         }
      }
      if (mod && !source.equals("local")) {
         Path lm = this.localMod();
         if (Files.isDirectory(lm)) {
            Path rep = this.cfg.zomboidDir().resolve("mods_replaced").resolve(stamp);
            Files.createDirectories(rep);
            Files.move(lm, rep.resolve("ZombieBuddy"));
            Util.copyTree(src, lm, rel -> {
            });
            this.touchManifest();
            done.add("мод в Zomboid/mods (прежний — в mods_replaced/" + stamp + ")");
         }
      }
      try {
         if (this.copyAuthorsIfNewer(src.resolve("authors.json"))) {
            done.add("authors.json");
         }
      } catch (IOException e) {
      }
      if (done.isEmpty()) {
         throw new IOException("Обновлять нечего: мода ZombieBuddy в Zomboid/mods нет, а агент не выбран");
      }
      String approve = "";
      try {
         if (Files.isDirectory(this.localMod())) {
            this.mods.approveJava(WID);
            approve = " Новая версия сразу разрешена в ZombieBuddy.";
         }
      } catch (Exception e) {
      }
      this.srv.markConfigChanged();
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("message", "ZombieBuddy " + (ver.isEmpty() ? "" : ver + " ") + "установлен: " + String.join(", ", done) + "." + approve
         + " Запустите сервер; игрокам нужна та же версия (новый архив модов или обновление через установщик ZombieBuddy).");
      return r;
   }

   // ------------------------------------------------------------------ authors.json

   static final String AUTHORS_URL = "https://raw.githubusercontent.com/zed-0xff/ZombieBuddy/refs/heads/master/authors.json";

   /** Кэш списка авторов ZombieBuddy: рядом с mod_approvals.json (~/.zombie_buddy/authors.json). */
   Path authorsFile() {
      return this.cfg.zbApprovals().resolveSibling("authors.json");
   }

   private static Map<String, Object> authorsInfo(Path f) {
      if (!Files.isRegularFile(f)) {
         return null;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      try {
         com.fasterxml.jackson.databind.JsonNode j = Util.JSON.readTree(Util.readText(f));
         m.put("updatedAt", j.path("updated_at").asText(""));
         com.fasterxml.jackson.databind.JsonNode a = j.isArray() ? j : j.path("authors");
         m.put("count", a.size());
         m.put("signed", !j.path("signature").asText("").isEmpty());
         m.put("time", Files.getLastModifiedTime(f).toMillis());
      } catch (Exception e) {
         m.put("error", "не читается: " + e.getMessage());
      }
      return m;
   }

   private static String updatedAt(String json) {
      try {
         return Util.JSON.readTree(json).path("updated_at").asText("");
      } catch (Exception e) {
         return "";
      }
   }

   /** Пишет authors.json в кэш ZombieBuddy, если присланный новее текущего (или текущего нет). */
   private boolean writeAuthorsIfNewer(String json, boolean force) throws IOException {
      com.fasterxml.jackson.databind.JsonNode j;
      try {
         j = Util.JSON.readTree(json);
      } catch (Exception e) {
         throw new IOException("authors.json не читается как JSON");
      }
      if (j.path("signature").asText("").isEmpty() || !(j.isArray() || j.path("authors").isArray())) {
         throw new IOException("Это не authors.json ZombieBuddy (нет списка авторов или подписи)");
      }
      Path f = this.authorsFile();
      String cur = Files.isRegularFile(f) ? updatedAt(Util.readText(f)) : "";
      String neu = j.path("updated_at").asText("");
      if (!force && !cur.isEmpty() && neu.compareTo(cur) <= 0) {
         return false;
      }
      Files.createDirectories(f.getParent());
      Util.writeTextWithBackup(f, json, this.cfg.fileBackupDir());
      return true;
   }

   private boolean copyAuthorsIfNewer(Path src) throws IOException {
      return Files.isRegularFile(src) && this.writeAuthorsIfNewer(Util.readText(src), false);
   }

   /**
    * Обновить authors.json: свежий с GitHub ZombieBuddy (там же его берёт сам ZombieBuddy), а если GitHub недоступен —
    * самый новый из копий мода. Подпись файла проверяет ZombieBuddy: неподписанный или испорченный он проигнорирует.
    */
   Map<String, Object> updateAuthors(boolean force) throws IOException {
      String json = null;
      String from = "";
      String netErr = "";
      try {
         HttpResponse<String> rs = Util.HTTP.send(HttpRequest.newBuilder(URI.create(AUTHORS_URL)).timeout(Duration.ofSeconds(30))
            .header("User-Agent", "PZPanel").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
         if (rs.statusCode() == 200) {
            json = rs.body();
            from = "GitHub ZombieBuddy";
         } else {
            netErr = "HTTP " + rs.statusCode();
         }
      } catch (Exception e) {
         netErr = e.getMessage() == null ? e.toString() : e.getMessage();
      }
      if (json == null) {
         String best = "";
         for (Map.Entry<String, Path> e : this.sources().entrySet()) {
            Path a = e.getValue().resolve("authors.json");
            if (Files.isRegularFile(a)) {
               String t = Util.readText(a);
               if (json == null || updatedAt(t).compareTo(best) > 0) {
                  json = t;
                  best = updatedAt(t);
                  from = sourceTitle(e.getKey());
               }
            }
         }
      }
      if (json == null) {
         throw new IOException("Не удалось получить authors.json: GitHub недоступен (" + netErr + "), в копиях мода его тоже нет");
      }
      boolean wrote = this.writeAuthorsIfNewer(json, force);
      Map<String, Object> r = new LinkedHashMap<>();
      String upd = updatedAt(json);
      r.put("message", wrote
         ? "authors.json обновлён (" + from + (upd.isEmpty() ? "" : ", от " + upd) + "): " + this.authorsFile() + ". ZombieBuddy прочитает его при следующем запуске."
         : "authors.json уже свежий" + (upd.isEmpty() ? "" : " (" + upd + ")") + " — ничего не менял.");
      return r;
   }

   private static void replaceFile(Path from, Path to) throws IOException {
      Files.createDirectories(to.getParent());
      Path tmp = to.resolveSibling(to.getFileName() + ".pzpanel-tmp");
      Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
      try {
         Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
         Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   /** Строка ZombieBuddy в _frozen_manifest.txt: новая дата версии. */
   private void touchManifest() throws IOException {
      Path mf = this.cfg.zomboidDir().resolve("mods").resolve("_frozen_manifest.txt");
      if (!Files.exists(mf)) {
         return;
      }
      String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
      List<String> out = new ArrayList<>();
      boolean found = false;
      for (String l : Util.readText(mf).split("\r?\n")) {
         if (l.isEmpty()) {
            continue;
         }
         String[] c = l.split("\t", -1);
         if (c.length >= 2 && c[0].trim().equals(WID)) {
            found = true;
            l = WID + "\t" + c[1] + "\tzb-update\t" + when;
         }
         out.add(l);
      }
      if (!found) {
         out.add(WID + "\tZombieBuddy\tzb-update\t" + when);
      }
      Util.writeTextWithBackup(mf, String.join("\n", out) + "\n", this.cfg.fileBackupDir());
   }
}
