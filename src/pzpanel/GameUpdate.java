package pzpanel;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Обновление выделенного сервера Project Zomboid (Steam app 380870) через SteamCMD.
 * Перед обновлением панель запоминает настройки запуска (StartServer64.bat, ProjectZomboid64.json): ZombieBuddy,
 * память Java (-Xmx/-Xms), сборщик мусора, -servername. SteamCMD может перезаписать эти файлы стоковыми —
 * после обновления панель возвращает настройки в новые файлы (прежние версии файлов — в PZPanel/file_backups).
 * serverMode=remote: сервер в другом контейнере, обновить его панель не может — только кладёт файл-запрос
 * updateRequestFile (ветка внутри), а обработчик снаружи обновляет контейнер.
 */
final class GameUpdate {
   static final String APP = "380870";
   private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
   private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
   private static final List<String> LAUNCH_FILES = List.of("StartServer64.bat", "StartServer64_nosteam.bat", "ProjectZomboid64.json", "start-server.sh");

   private final Config cfg;
   private final ServerManager srv;
   private final Backups jobs;
   private final ZbUpdater zb;
   /** Ветки сервера из последней проверки (app_info_print). */
   private volatile List<Map<String, Object>> branches = List.of();
   private volatile long checkedAt;

   GameUpdate(Config cfg, ServerManager srv, Backups jobs, ZbUpdater zb) {
      this.cfg = cfg;
      this.srv = srv;
      this.jobs = jobs;
      this.zb = zb;
   }

   private boolean remote() {
      return "remote".equals(this.cfg.get("serverMode", "local"));
   }

   /**
    * Обновлять файлы сервера самой панелью: обычный режим, а при serverMode=remote — если папка сервера смонтирована
    * в контейнер панели (есть java/projectzomboid.jar) и не задан updateRequestFile.
    */
   private boolean direct() {
      if (!this.remote()) {
         return true;
      }
      return this.cfg.get("updateRequestFile", "").isBlank()
         && Files.isRegularFile(this.cfg.serverDir().resolve("java").resolve("projectzomboid.jar"));
   }

   // ------------------------------------------------------------------ что установлено

   /** Манифест установки: от SteamCMD — внутри папки сервера, от Steam-клиента — в steamapps библиотеки. */
   private Path manifest() {
      Path own = this.cfg.serverDir().resolve("steamapps").resolve("appmanifest_" + APP + ".acf");
      if (Files.isRegularFile(own)) {
         return own;
      }
      return this.steamClientManifest();
   }

   /** Сервер поставлен Steam-клиентом: …/steamapps/common/<папка>, а манифест — в …/steamapps. */
   private Path steamClientManifest() {
      Path sd = this.cfg.serverDir().toAbsolutePath().normalize();
      Path common = sd.getParent();
      if (common != null && common.getFileName() != null && common.getFileName().toString().equalsIgnoreCase("common")) {
         Path m = common.getParent().resolve("appmanifest_" + APP + ".acf");
         if (Files.isRegularFile(m)) {
            return m;
         }
      }
      return null;
   }

   private static String acfValue(String acf, String key) {
      Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s+\"([^\"]*)\"").matcher(acf);
      return m.find() ? m.group(1) : "";
   }

   /** Версия игры из projectzomboid.jar (статическое поле Core.gameVersion = new GameVersion(42, 22, "…")). */
   String installedVersion() {
      Path jar = this.cfg.serverDir().resolve("java").resolve("projectzomboid.jar");
      if (!Files.isRegularFile(jar)) {
         return "";
      }
      try (ZipFile z = new ZipFile(jar.toFile())) {
         ZipEntry e = z.getEntry("zombie/core/Core.class");
         if (e == null) {
            return "";
         }
         try (InputStream in = z.getInputStream(e)) {
            return versionFromCore(in.readAllBytes());
         }
      } catch (Exception ex) {
         return "";
      }
   }

   /** Ищет в байткоде: new GameVersion; dup; <major>; <minor>; ldc "<суффикс>"; invokespecial; putstatic. */
   static String versionFromCore(byte[] b) throws IOException {
      DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(b));
      in.readInt();
      in.readUnsignedShort();
      in.readUnsignedShort();
      int n = in.readUnsignedShort();
      Object[] cp = new Object[n];
      for (int i = 1; i < n; i++) {
         int tag = in.readUnsignedByte();
         switch (tag) {
            case 1 -> cp[i] = in.readUTF();
            case 3, 4 -> in.readInt();
            case 5, 6 -> {
               in.readLong();
               i++;
            }
            case 7, 16, 19, 20 -> cp[i] = new int[]{tag, in.readUnsignedShort()};
            case 8 -> cp[i] = new int[]{8, in.readUnsignedShort()};
            case 9, 10, 11, 12, 17, 18 -> {
               in.readUnsignedShort();
               in.readUnsignedShort();
            }
            case 15 -> {
               in.readUnsignedByte();
               in.readUnsignedShort();
            }
            default -> throw new IOException("constant pool tag " + tag);
         }
      }
      int gv = -1;
      for (int i = 1; i < n; i++) {
         if (cp[i] instanceof int[] a && a[0] == 7 && "zombie/core/GameVersion".equals(cp[a[1]])) {
            gv = i;
         }
      }
      if (gv < 0) {
         return "";
      }
      for (int i = 0; i + 12 < b.length; i++) {
         if ((b[i] & 0xff) != 0xBB || ((b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff)) != gv || (b[i + 3] & 0xff) != 0x59) {
            continue;
         }
         int p = i + 4;
         int[] v = new int[2];
         boolean ok = true;
         for (int k = 0; k < 2 && ok; k++) {
            int op = b[p] & 0xff;
            if (op == 0x10) {
               v[k] = b[p + 1];
               p += 2;
            } else if (op >= 0x03 && op <= 0x08) {
               v[k] = op - 0x03;
               p += 1;
            } else if (op == 0x11) {
               v[k] = (short) (((b[p + 1] & 0xff) << 8) | (b[p + 2] & 0xff));
               p += 3;
            } else {
               ok = false;
            }
         }
         if (!ok) {
            continue;
         }
         String suffix = "";
         int op = b[p] & 0xff;
         int idx = -1;
         if (op == 0x12) {
            idx = b[p + 1] & 0xff;
            p += 2;
         } else if (op == 0x13) {
            idx = ((b[p + 1] & 0xff) << 8) | (b[p + 2] & 0xff);
            p += 3;
         }
         if (idx > 0 && idx < n && cp[idx] instanceof int[] s && s[0] == 8 && cp[s[1]] instanceof String str) {
            suffix = str;
         }
         if ((b[p] & 0xff) != 0xB7 || (b[p + 3] & 0xff) != 0xB3) {
            continue; // нужен именно new GameVersion(...) → putstatic (поле Core.gameVersion)
         }
         if (v[0] < 40 || v[0] > 99) {
            continue;
         }
         return v[0] + "." + v[1] + (suffix.isEmpty() ? "" : (suffix.matches("\\d.*") ? "." : "") + suffix);
      }
      return "";
   }

   Map<String, Object> state() {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("remote", this.remote());
      r.put("direct", this.direct());
      r.put("updateRequestFile", this.cfg.get("updateRequestFile", ""));
      r.put("version", this.installedVersion());
      r.put("panelGameVersion", this.cfg.get("gameVersion", ""));
      Path m = this.manifest();
      String branch = "public";
      String build = "";
      long updated = 0;
      if (m != null) {
         try {
            String acf = Util.readText(m);
            build = acfValue(acf, "buildid");
            String bk = acfValue(acf, "BetaKey");
            if (bk.isEmpty()) {
               bk = acfValue(acf, "betakey");
            }
            if (!bk.isEmpty()) {
               branch = bk;
            }
            String lu = acfValue(acf, "LastUpdated");
            updated = lu.matches("\\d+") ? Long.parseLong(lu) * 1000L : 0;
         } catch (IOException e) {
         }
      }
      r.put("branch", branch);
      r.put("buildId", build);
      r.put("updated", updated);
      r.put("manifest", m == null ? "" : m.toString());
      r.put("steamClient", this.steamClientManifest() != null
         && !Files.isRegularFile(this.cfg.serverDir().resolve("steamapps").resolve("appmanifest_" + APP + ".acf")));
      r.put("branches", this.branches);
      r.put("checkedAt", this.checkedAt);
      r.put("serverRunning", this.srv.isRunning());
      r.put("busy", this.jobs.busy());
      r.put("progress", this.jobs.progress());
      r.put("launch", this.direct() ? this.launchSummary() : List.of());
      return r;
   }

   // ------------------------------------------------------------------ SteamCMD

   private Path steamcmd() throws IOException, InterruptedException {
      Path sc = this.zb.findSteamcmd();
      if (sc == null) {
         if (!Util.isWindows()) {
            throw new IOException("SteamCMD не найден: укажите путь к steamcmd.sh в параметре steamcmd на вкладке «Панель»");
         }
         sc = this.zb.installSteamcmd();
      }
      return sc;
   }

   /** Запускает SteamCMD, строки вывода — в onLine; возвращает весь вывод. */
   private String run(Path sc, List<String> args, int minutes, Consumer<String> onLine) throws IOException, InterruptedException {
      List<String> cmd = new ArrayList<>();
      if (!Util.isWindows() && "root".equals(System.getProperty("user.name"))) {
         cmd.addAll(List.of("runuser", "-u", "steam", "--"));
      }
      if (sc.toString().endsWith(".sh")) {
         cmd.add("bash");
      }
      cmd.add(sc.toString());
      cmd.addAll(args);
      Process p = new ProcessBuilder(cmd).directory(sc.getParent().toFile()).redirectErrorStream(true).start();
      p.getOutputStream().close();
      StringBuilder all = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
         String ln;
         while ((ln = r.readLine()) != null) {
            for (String part : ln.split("\r")) {
               String s = ANSI.matcher(part).replaceAll("");
               all.append(s).append('\n');
               if (!s.isBlank()) {
                  onLine.accept(s.trim());
               }
            }
         }
      }
      if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
         p.destroyForcibly();
         throw new IOException("SteamCMD не уложился в " + minutes + " минут");
      }
      return all.toString();
   }

   /** Проверить, какие ветки и сборки есть в Steam (анонимно, app_info_print). */
   String check() throws IOException {
      return this.jobs.runJob("проверка версий сервера в Steam", () -> {
         Path sc = this.steamcmd();
         String out = "";
         for (int attempt = 1; attempt <= 2; attempt++) {
            out = this.run(sc, List.of("+login", "anonymous", "+app_info_update", "1", "+app_info_print", APP, "+quit"), 10,
               s -> this.jobs.step("SteamCMD: " + cut(s)));
            if (out.contains("\"branches\"")) {
               break;
            }
         }
         List<Map<String, Object>> list = parseBranches(out);
         if (list.isEmpty()) {
            throw new IOException("SteamCMD не вернул список веток сервера");
         }
         this.branches = list;
         this.checkedAt = System.currentTimeMillis();
         return "веток в Steam: " + list.size();
      });
   }

   /** Разбор секции "branches" из app_info_print (формат VDF). */
   static List<Map<String, Object>> parseBranches(String out) {
      List<Map<String, Object>> r = new ArrayList<>();
      int i = out.indexOf("\"branches\"");
      if (i < 0) {
         return r;
      }
      int open = out.indexOf('{', i);
      int depth = 0;
      int end = open;
      for (int k = open; k < out.length(); k++) {
         char c = out.charAt(k);
         if (c == '{') {
            depth++;
         } else if (c == '}') {
            if (--depth == 0) {
               end = k;
               break;
            }
         }
      }
      String sec = out.substring(open + 1, end);
      Matcher m = Pattern.compile("\"([^\"]+)\"\\s*\\{([^{}]*)\\}").matcher(sec);
      while (m.find()) {
         Map<String, Object> b = new LinkedHashMap<>();
         String body = m.group(2);
         b.put("name", m.group(1));
         b.put("buildId", acfValue(body, "buildid"));
         b.put("description", acfValue(body, "description"));
         String t = acfValue(body, "timeupdated");
         b.put("updated", t.matches("\\d+") ? Long.parseLong(t) * 1000L : 0L);
         b.put("password", "1".equals(acfValue(body, "pwdrequired")));
         r.add(b);
      }
      return r;
   }

   private static String cut(String s) {
      return s.length() > 150 ? s.substring(0, 150) + "…" : s;
   }

   // ------------------------------------------------------------------ обновление

   /**
    * Обновить сервер до сборки ветки branch (public — основная). validate — проверить все файлы (дольше,
    * восстанавливает испорченные, но и стоковые скрипты запуска — их панель потом поправит).
    */
   String update(String branch, boolean validate) throws IOException {
      String b = branch == null || branch.isBlank() ? "public" : branch.trim();
      if (!b.matches("[A-Za-z0-9_.\\-]{1,64}")) {
         throw new IOException("Неверное имя ветки: " + b);
      }
      if (!this.direct()) {
         return this.requestRemote(b);
      }
      if (this.srv.isRunning()) {
         throw new IOException("Остановите сервер: обновлять игру можно только при остановленном сервере");
      }
      return this.jobs.runJob("обновление сервера Project Zomboid (" + b + ")", () -> {
         String before = this.installedVersion();
         Map<String, String> snap = this.snapshotLaunch();
         String stamp = LocalDateTime.now().format(STAMP);
         Path bk = this.cfg.fileBackupDir().resolve("before_update_" + stamp);
         Files.createDirectories(bk);
         for (Map.Entry<String, String> e : snap.entrySet()) {
            Files.writeString(bk.resolve(e.getKey()), e.getValue());
         }
         Path sc = this.steamcmd();
         List<String> args = new ArrayList<>(List.of("+force_install_dir", this.cfg.serverDir().toAbsolutePath().toString(), "+login", "anonymous",
            "+app_update", APP, "-beta", b));
         if (validate) {
            args.add("validate");
         }
         args.add("+quit");
         String out = "";
         boolean ok = false;
         for (int attempt = 1; attempt <= 2 && !ok; attempt++) {
            out = this.run(sc, args, 90, s -> this.jobs.step("SteamCMD: " + cut(progressLine(s))));
            ok = out.contains("Success! App '" + APP + "'") || out.contains("already up to date");
         }
         if (!ok) {
            String err = "";
            for (String l : out.split("\n")) {
               if (l.contains("ERROR") || l.contains("Error!")) {
                  err = l.trim();
               }
            }
            throw new IOException("SteamCMD не обновил сервер" + (err.isEmpty() ? "" : ": " + err) + ". Копии файлов запуска — в " + bk);
         }
         List<String> fixed = this.reapplyLaunch(snap);
         String after = this.installedVersion();
         String gv = "";
         if (!after.isEmpty()) {
            String mm = after.replaceFirst("^(\\d+\\.\\d+).*", "$1");
            if (!mm.equals(this.cfg.get("gameVersion", ""))) {
               this.cfg.p.setProperty("gameVersion", mm);
               this.cfg.save();
               gv = " Параметр gameVersion панели: " + mm + ".";
            }
         }
         this.srv.markConfigChanged();
         return "сервер обновлён: " + (before.isEmpty() ? "?" : before) + " → " + (after.isEmpty() ? "?" : after) + " (ветка " + b + ")."
            + (fixed.isEmpty() ? " Файлы запуска не изменились." : " Возвращены настройки запуска: " + String.join(", ", fixed) + ".")
            + gv + " Прежние файлы запуска — в file_backups/" + bk.getFileName() + ". Игрокам нужна та же версия игры.";
      });
   }

   /** «Update state (0x61) downloading, progress: 45.12 (123 / 456)» → «скачивание: 45.12 %». */
   private static String progressLine(String s) {
      Matcher m = Pattern.compile("Update state \\(0x[0-9a-f]+\\) ([a-z ]+), progress: ([0-9.]+)").matcher(s);
      if (m.find()) {
         String st = m.group(1).trim();
         String ru = switch (st) {
            case "downloading" -> "скачивание";
            case "verifying install", "verifying update" -> "проверка файлов";
            case "preallocating" -> "подготовка места";
            case "committing" -> "запись файлов";
            default -> st;
         };
         return ru + ": " + m.group(2) + " %";
      }
      return s;
   }

   private String requestRemote(String branch) throws IOException {
      String req = this.cfg.get("updateRequestFile", "");
      if (req.isBlank()) {
         throw new IOException("Сервер в отдельном контейнере (serverMode=remote): задайте updateRequestFile — панель положит туда запрос, а обработчик на хосте обновит сервер");
      }
      if (this.srv.isRunning()) {
         throw new IOException("Остановите сервер перед обновлением");
      }
      Path f = Paths.get(req);
      if (f.getParent() != null) {
         Files.createDirectories(f.getParent());
      }
      Files.writeString(f, "branch=" + branch + "\nrequested=" + Instant.now() + "\n");
      return "Запрос на обновление сервера (ветка " + branch + ") отправлен: " + f + ". Его выполнит обработчик на хосте";
   }

   // ------------------------------------------------------------------ настройки запуска

   private Map<String, String> snapshotLaunch() throws IOException {
      Map<String, String> r = new LinkedHashMap<>();
      Path sd = this.cfg.serverDir();
      List<String> names = new ArrayList<>(LAUNCH_FILES);
      String ss = this.cfg.get("startScript", "");
      if (!ss.isBlank() && !names.contains(ss) && !ss.contains("/") && !ss.contains("\\")) {
         names.add(ss);
      }
      for (String n : names) {
         Path f = sd.resolve(n);
         if (Files.isRegularFile(f)) {
            r.put(n, Util.readText(f));
         }
      }
      return r;
   }

   /** Важные для нас аргументы JVM: агент ZombieBuddy, память, сборщик мусора. */
   private static boolean keepArg(String a) {
      return a.startsWith("-agentlib:zbNative") || a.matches("-javaagent:.*ZombieBuddy.*") || a.startsWith("-Xmx") || a.startsWith("-Xms")
         || a.matches("-XX:[+-]Use\\w+GC");
   }

   private static List<String> tokens(String line) {
      List<String> t = new ArrayList<>();
      Matcher m = Pattern.compile("\"[^\"]*\"|\\S+").matcher(line);
      while (m.find()) {
         t.add(m.group());
      }
      return t;
   }

   private List<Map<String, Object>> launchSummary() {
      List<Map<String, Object>> r = new ArrayList<>();
      try {
         for (Map.Entry<String, String> e : this.snapshotLaunch().entrySet()) {
            List<String> keep = new ArrayList<>();
            if (e.getKey().endsWith(".json")) {
               for (com.fasterxml.jackson.databind.JsonNode a : Util.JSON.readTree(e.getValue()).path("vmArgs")) {
                  if (keepArg(a.asText())) {
                     keep.add(a.asText());
                  }
               }
            } else {
               for (String l : e.getValue().split("\r?\n")) {
                  if (l.contains("GameServer")) {
                     for (String t : tokens(l)) {
                        if (keepArg(t)) {
                           keep.add(t);
                        }
                     }
                     Matcher m = Pattern.compile("-servername\\s+(\\S+)").matcher(l);
                     if (m.find()) {
                        keep.add("-servername " + m.group(1));
                     }
                  }
               }
            }
            if (!keep.isEmpty()) {
               Map<String, Object> m = new LinkedHashMap<>();
               m.put("file", e.getKey());
               m.put("args", keep);
               r.add(m);
            }
         }
      } catch (Exception e) {
      }
      return r;
   }

   /** Возвращает настройки из снимка в обновлённые файлы. Список — что поправлено. */
   private List<String> reapplyLaunch(Map<String, String> snap) throws IOException {
      List<String> fixed = new ArrayList<>();
      Path sd = this.cfg.serverDir();
      for (Map.Entry<String, String> e : snap.entrySet()) {
         Path f = sd.resolve(e.getKey());
         if (!Files.isRegularFile(f)) {
            continue;
         }
         String now = Util.readText(f);
         if (now.equals(e.getValue())) {
            continue;
         }
         String res = e.getKey().endsWith(".json") ? fixJson(e.getValue(), now) : fixScript(e.getValue(), now);
         if (res != null && !res.equals(now)) {
            Util.writeTextWithBackup(f, res, this.cfg.fileBackupDir());
            fixed.add(e.getKey());
         }
      }
      return fixed;
   }

   /** ProjectZomboid64.json: в новые vmArgs — агент ZombieBuddy (первым), память и сборщик мусора из прежних. */
   static String fixJson(String old, String now) throws IOException {
      com.fasterxml.jackson.databind.JsonNode o = Util.JSON.readTree(old);
      com.fasterxml.jackson.databind.node.ObjectNode n = (com.fasterxml.jackson.databind.node.ObjectNode) Util.JSON.readTree(now);
      List<String> oldArgs = new ArrayList<>();
      o.path("vmArgs").forEach(a -> oldArgs.add(a.asText()));
      List<String> args = new ArrayList<>();
      n.path("vmArgs").forEach(a -> args.add(a.asText()));
      List<String> before = new ArrayList<>(args);
      mergeArgs(oldArgs, args);
      if (before.equals(args)) {
         return now;
      }
      com.fasterxml.jackson.databind.node.ArrayNode arr = n.putArray("vmArgs");
      args.forEach(arr::add);
      StringBuilder sb = new StringBuilder();
      pretty(n, 0, sb);
      return sb.append('\n').toString();
   }

   /** JSON с табами, как у стоковых файлов игры. */
   private static void pretty(com.fasterxml.jackson.databind.JsonNode n, int d, StringBuilder sb) throws IOException {
      String in = "\t".repeat(d + 1);
      if (n.isObject()) {
         sb.append("{\n");
         var it = n.fields();
         while (it.hasNext()) {
            var e = it.next();
            sb.append(in).append(Util.JSON.writeValueAsString(e.getKey())).append(": ");
            pretty(e.getValue(), d + 1, sb);
            sb.append(it.hasNext() ? ",\n" : "\n");
         }
         sb.append("\t".repeat(d)).append('}');
      } else if (n.isArray()) {
         sb.append("[\n");
         for (int i = 0; i < n.size(); i++) {
            sb.append(in);
            pretty(n.get(i), d + 1, sb);
            sb.append(i + 1 < n.size() ? ",\n" : "\n");
         }
         sb.append("\t".repeat(d)).append(']');
      } else {
         sb.append(Util.JSON.writeValueAsString(n));
      }
   }

   /** Переносит важные аргументы из старого списка в новый (заменяя одноимённые). */
   static void mergeArgs(List<String> oldArgs, List<String> args) {
      int at = 0;
      for (String a : oldArgs) {
         if (!keepArg(a)) {
            continue;
         }
         String key = a.startsWith("-Xmx") ? "-Xmx" : a.startsWith("-Xms") ? "-Xms" : a.startsWith("-XX:") ? "-XX:GC"
            : a.startsWith("-agentlib:zbNative") ? "-agentlib:zbNative" : "-javaagent:ZB";
         args.removeIf(x -> key.equals("-XX:GC") ? x.matches("-XX:[+-]Use\\w+GC") : key.equals("-javaagent:ZB") ? x.matches("-javaagent:.*ZombieBuddy.*") : x.startsWith(key));
         if (a.startsWith("-agentlib") || a.startsWith("-javaagent")) {
            args.add(Math.min(at++, args.size()), a);
         } else {
            args.add(a);
         }
      }
   }

   /** Скрипт запуска (.bat/.sh): в строке запуска Java — агент, память, GC и -servername из прежней версии. */
   static String fixScript(String old, String now) {
      String oldLine = null;
      for (String l : old.split("\r?\n")) {
         if (l.contains("GameServer")) {
            oldLine = l;
         }
      }
      if (oldLine == null) {
         return now;
      }
      List<String> oldTok = tokens(oldLine);
      String nl = now.contains("\r\n") ? "\r\n" : "\n";
      String[] lines = now.split("\r?\n", -1);
      boolean changed = false;
      for (int i = 0; i < lines.length; i++) {
         String l = lines[i];
         if (!l.contains("GameServer")) {
            continue;
         }
         List<String> t = tokens(l);
         int gs = -1;
         for (int k = 0; k < t.size(); k++) {
            if (t.get(k).contains("GameServer")) {
               gs = k;
            }
         }
         int javaIdx = 0;
         for (int k = 0; k < t.size(); k++) {
            if (t.get(k).toLowerCase().contains("java")) {
               javaIdx = k;
               break;
            }
         }
         if (gs <= javaIdx) {
            continue;
         }
         List<String> jvm = new ArrayList<>(t.subList(javaIdx + 1, gs));
         List<String> before = new ArrayList<>(jvm);
         // -cp <путь> держим в конце, новые опции — перед ним
         List<String> cp = new ArrayList<>();
         int ci = jvm.indexOf("-cp") >= 0 ? jvm.indexOf("-cp") : jvm.indexOf("-classpath");
         if (ci >= 0 && ci + 1 < jvm.size()) {
            cp.add(jvm.remove(ci));
            cp.add(jvm.remove(ci));
         }
         mergeArgs(oldTok, jvm);
         jvm.addAll(cp);
         List<String> app = new ArrayList<>(t.subList(gs + 1, t.size()));
         List<String> appBefore = new ArrayList<>(app);
         int on = oldTok.indexOf("-servername");
         if (on >= 0 && on + 1 < oldTok.size() && !app.contains("-servername")) {
            app.add(0, oldTok.get(on + 1));
            app.add(0, "-servername");
         }
         if (before.equals(jvm) && app.equals(appBefore)) {
            continue;
         }
         List<String> out = new ArrayList<>(t.subList(0, javaIdx + 1));
         out.addAll(jvm);
         out.add(t.get(gs));
         out.addAll(app);
         String indent = l.replaceFirst("^(\\s*).*", "$1");
         lines[i] = indent + String.join(" ", out);
         changed = true;
      }
      return changed ? String.join(nl, lines) : now;
   }

   /** Кнопка «Вернуть настройки запуска» — после обновления через Steam-клиент: из последнего снимка before_update_*. */
   Map<String, Object> reapplyFromBackup() throws IOException {
      if (!this.direct()) {
         throw new IOException("serverMode=remote: файлы сервера не видны панели (папка сервера не смонтирована) — обновляет обработчик на хосте");
      }
      Path dir = this.cfg.fileBackupDir();
      Path last = null;
      if (Files.isDirectory(dir)) {
         try (var s = Files.list(dir)) {
            last = s.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("before_update_")).max(Path::compareTo).orElse(null);
         }
      }
      if (last == null) {
         throw new IOException("Нет сохранённых настроек запуска — сначала нажмите «Запомнить настройки запуска»");
      }
      Map<String, String> snap = new LinkedHashMap<>();
      try (var s = Files.list(last)) {
         for (Path p : s.toList()) {
            snap.put(p.getFileName().toString(), Util.readText(p));
         }
      }
      List<String> fixed = this.reapplyLaunch(snap);
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("message", fixed.isEmpty() ? "Настройки запуска на месте — ничего не менял (снимок " + last.getFileName() + ")."
         : "Возвращены настройки запуска из " + last.getFileName() + ": " + String.join(", ", fixed) + ".");
      return r;
   }

   /** Запомнить текущие настройки запуска (перед обновлением через Steam-клиент). */
   Map<String, Object> snapshotNow() throws IOException {
      if (!this.direct()) {
         throw new IOException("serverMode=remote: файлы сервера не видны панели (папка сервера не смонтирована) — обновляет обработчик на хосте");
      }
      Path bk = this.cfg.fileBackupDir().resolve("before_update_" + LocalDateTime.now().format(STAMP));
      Files.createDirectories(bk);
      Map<String, String> snap = this.snapshotLaunch();
      for (Map.Entry<String, String> e : snap.entrySet()) {
         Files.writeString(bk.resolve(e.getKey()), e.getValue());
      }
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("message", "Настройки запуска запомнены (" + String.join(", ", snap.keySet()) + ") в " + bk.getFileName()
         + ". После обновления через Steam нажмите «Вернуть настройки запуска».");
      return r;
   }
}
