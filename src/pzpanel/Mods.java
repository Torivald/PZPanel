package pzpanel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class Mods {
   private final Config cfg;
   private final Map<String, Map<String, Object>> steamCache = new ConcurrentHashMap<>();

   Mods(Config var1) {
      this.cfg = var1;
   }

   Map<String, List<Mods.ModInfo>> scan() {
      LinkedHashMap<String, List<Mods.ModInfo>> var1 = new LinkedHashMap<>();
      double var2 = parseVer(this.cfg.get("gameVersion", "42.21"));
      Path var4 = this.cfg.zomboidDir().resolve("mods");
      if (Files.isDirectory(var4)) {
         HashMap<String, String> var5 = new HashMap<>();

         try {
            Path var6 = var4.resolve("_frozen_manifest.txt");
            if (Files.exists(var6)) {
               for (String var10 : Util.readText(var6).split("\r?\n")) {
                  if (!var10.startsWith("#")) {
                     String[] var11 = var10.split("\t");
                     if (var11.length >= 2) {
                        for (String var15 : var11[1].split(",")) {
                           if (!var15.isBlank()) {
                              var5.put(var15.trim(), var11[0].trim());
                           }
                        }
                     }
                  }
               }
            }
         } catch (IOException var26) {
         }

         try (Stream<Path> var28 = Files.list(var4)) {
            for (Path var32 : (Iterable<Path>) var28::iterator) {
               if (Files.isDirectory(var32)) {
                  Mods.ModInfo var34 = readMod(var32, var5.getOrDefault(var32.getFileName().toString(), ""), var2, true);
                  if (var34 != null) {
                     var1.computeIfAbsent(var34.id(), var0 -> new ArrayList<>()).add(var34);
                  }
               }
            }
         } catch (IOException var25) {
         }
      }

      for (Path var29 : this.cfg.workshopDirs()) {
         if (Files.isDirectory(var29)) {
            boolean var31 = var29.getFileName().toString().equals("Workshop");

            try (Stream<Path> var33 = Files.list(var29)) {
               for (Path var36 : (Iterable<Path>) var33::iterator) {
                  Path var37 = var31 ? var36.resolve("Contents").resolve("mods") : var36.resolve("mods");
                  if (Files.isDirectory(var37)) {
                     String var38 = var31 ? "" : var36.getFileName().toString();

                     try (Stream<Path> var39 = Files.list(var37)) {
                        for (Path var41 : (Iterable<Path>) var39::iterator) {
                           Mods.ModInfo var16 = readMod(var41, var38, var2, false);
                           if (var16 != null) {
                              var1.computeIfAbsent(var16.id(), var0 -> new ArrayList<>()).add(var16);
                           }
                        }
                     } catch (IOException var21) {
                     }
                  }
               }
            } catch (IOException var23) {
            }
         }
      }

      return var1;
   }

   private static Mods.ModInfo readMod(Path var0, String var1, double var2, boolean var4) {
      Path var5 = null;
      double var6 = -1.0;

      try (Stream<Path> var8 = Files.list(var0)) {
         for (Path var10 : (Iterable<Path>) var8::iterator) {
            String var11 = var10.getFileName().toString();
            if (Files.isDirectory(var10) && var11.matches("\\d+(\\.\\d+)*")) {
               double var12 = parseVer(var11);
               if (var12 <= var2 + 1.0E-9 && var12 > var6 && Files.exists(var10.resolve("mod.info"))) {
                  var5 = var10;
                  var6 = var12;
               }
            }
         }
      } catch (IOException var20) {
      }

      if (var5 == null) {
         if (Files.exists(var0.resolve("common").resolve("mod.info"))) {
            var5 = var0.resolve("common");
         } else {
            if (!Files.exists(var0.resolve("mod.info"))) {
               return null;
            }

            var5 = var0;
         }
      }

      HashMap<String, String> var21 = new HashMap<>();
      HashMap<String, List<String>> var22 = new HashMap<>();

      try {
         for (String var13 : Util.readText(var5.resolve("mod.info")).split("\r?\n")) {
            int var14 = var13.indexOf(61);
            if (var14 > 0) {
               String var15 = var13.substring(0, var14).trim();
               String var16 = var13.substring(var14 + 1).trim();
               var21.putIfAbsent(var15, var16);
               var22.computeIfAbsent(var15, var0x -> new ArrayList<>()).add(var16);
            }
         }
      } catch (IOException var18) {
         return null;
      }

      String var24 = (String)var21.get("id");
      if (var24 != null && !var24.isBlank()) {
         boolean var26 = Files.isDirectory(var5.resolve("media").resolve("java")) || Files.isDirectory(var0.resolve("common").resolve("media").resolve("java"));
         return new Mods.ModInfo(
            var24.trim(),
            var21.getOrDefault("name", var24),
            var1,
            var0.getFileName().toString(),
            var5.getFileName().toString(),
            list((List<String>)var22.get("require")),
            list((List<String>)var22.get("loadModAfter")),
            list((List<String>)var22.get("loadModBefore")),
            list((List<String>)var22.get("incompatible")),
            var21.getOrDefault("versionMin", ""),
            var21.getOrDefault("versionMax", ""),
            var26,
            Files.exists(var5.resolve("poster.png")) ? var5.resolve("poster.png").toString() : "",
            var4
         );
      } else {
         return null;
      }
   }

   private static List<String> list(List<String> var0) {
      ArrayList var1 = new ArrayList();
      if (var0 == null) {
         return var1;
      } else {
         for (String var3 : var0) {
            for (String var7 : var3.split("[,;]")) {
               String var8 = var7.trim().replaceFirst("^\\\\+", "");
               if (!var8.isEmpty()) {
                  var1.add(var8);
               }
            }
         }

         return var1;
      }
   }

   static double parseVer(String var0) {
      try {
         String[] var1 = var0.trim().split("\\.");
         double var2 = Double.parseDouble(var1[0]);
         if (var1.length > 1) {
            var2 += Double.parseDouble("0." + String.format("%03d", Integer.parseInt(var1[1].replaceAll("\\D", "0"))));
         }

         return var2;
      } catch (Exception var4) {
         return 0.0;
      }
   }

   static List<String> splitList(String var0) {
      ArrayList var1 = new ArrayList();

      for (String var5 : var0.split(";")) {
         String var6 = var5.trim();
         if (!var6.isEmpty()) {
            var1.add(var6);
         }
      }

      return var1;
   }

   Map<String, Object> state() {
      Path var1 = this.cfg.serverIni();
      List var2 = splitList(Configs.iniValue(var1, "Mods"));
      List<String> var3 = splitList(Configs.iniValue(var1, "WorkshopItems"));
      Map<String, List<Mods.ModInfo>> var4 = this.scan();
      HashSet<String> var5 = new HashSet<>(var3);
      double var6 = parseVer(this.cfg.get("gameVersion", "42.21"));
      ArrayList<Map<String, Object>> var8 = new ArrayList<>();
      HashMap var9 = new HashMap();

      for (int var10 = 0; var10 < var2.size(); var10++) {
         var9.put(strip((String)var2.get(var10)), var10);
      }

      for (int var19 = 0; var19 < var2.size(); var19++) {
         String var11 = (String)var2.get(var19);
         String var12 = strip(var11);
         Mods.ModInfo var13 = pick((List<Mods.ModInfo>)var4.get(var12), var5);
         Map var14 = describe(var12, var13);
         var14.put("raw", var11);
         ArrayList var15 = new ArrayList();
         if (var13 == null) {
            var15.add(
               var3.isEmpty()
                  ? "Файлы не найдены в Zomboid/mods — моды заморожены, сервер его не скачает"
                  : "Файлы не найдены — мод скачается при запуске сервера (если его предмет есть в списке Мастерской)"
            );
         } else {
            for (String var17 : var13.require()) {
               Integer var18 = (Integer)var9.get(var17);
               if (var18 == null) {
                  var15.add("Требует " + var17 + ", но он не включён");
               } else if (var18 > var19) {
                  var15.add("Требует " + var17 + " — он должен стоять выше");
               }
            }

            for (String var37 : var13.loadAfter()) {
               Integer var40 = (Integer)var9.get(var37);
               if (var40 != null && var40 > var19) {
                  var15.add("Должен грузиться после " + var37);
               }
            }

            for (String var38 : var13.loadBefore()) {
               Integer var41 = (Integer)var9.get(var38);
               if (var41 != null && var41 < var19) {
                  var15.add("Должен грузиться до " + var38);
               }
            }

            for (String var39 : var13.incompatible()) {
               if (var9.containsKey(var39)) {
                  var15.add("Несовместим с " + var39);
               }
            }

            if (!var13.versionMax().isBlank() && parseVer(var13.versionMax()) + 1.0E-9 < var6) {
               var15.add("versionMax " + var13.versionMax() + " ниже версии игры");
            }

            if (!var13.local() && !var13.workshopId().isEmpty() && !var5.contains(var13.workshopId())) {
               var15.add("Предмет Мастерской " + var13.workshopId() + " не в списке WorkshopItems — игроки его не скачают");
            }
         }

         var14.put("issues", var15);
         var8.add(var14);
      }

      ArrayList<Map<String, Object>> var20 = new ArrayList<>();

      for (Entry<String, List<Mods.ModInfo>> var23 : var4.entrySet()) {
         if (!var9.containsKey(var23.getKey())) {
            Mods.ModInfo var26 = pick((List<Mods.ModInfo>)var23.getValue(), var5);
            Map var30 = describe((String)var23.getKey(), var26);
            var30.put("inWorkshopItems", var26 != null && var5.contains(var26.workshopId()));
            var20.add(var30);
         }
      }

      var20.sort(Comparator.comparing(var0 -> String.valueOf(var0.get("name")).toLowerCase(Locale.ROOT)));
      HashSet var22 = new HashSet();

      for (Map var27 : var8) {
         var22.add(String.valueOf(var27.get("workshopId")));
      }

      ArrayList var25 = new ArrayList();

      for (String var31 : var3) {
         LinkedHashMap var32 = new LinkedHashMap();
         var32.put("id", var31);
         var32.put("downloaded", this.cfg.workshopDirs().stream().anyMatch(var1x -> Files.isDirectory(var1x.resolve(var31))));
         var32.put("usedByEnabledMod", var22.contains(var31));
         Map var36 = this.steamCache.get(var31);
         if (var36 != null) {
            var32.put("title", var36.get("title"));
         }

         var25.add(var32);
      }

      LinkedHashMap var29 = new LinkedHashMap();
      var29.put("enabled", var8);
      var29.put("available", var20);
      var29.put("workshopItems", var25);
      var29.put("map", Configs.iniValue(var1, "Map"));
      return var29;
   }

   // ---------------- закрепление версии мода из Мастерской ----------------
   // Копия мода в Zomboid/mods + строка в _frozen_manifest.txt + удаление из WorkshopItems=: сервер перестаёт обновлять мод
   // и скачивать его при запуске, архив для игроков берёт именно эту версию (как scripts/04_freeze_mods.sh).

   private Path pinSource(String wid) throws IOException, InterruptedException {
      Path own = this.cfg.serverDir().resolve("steamapps/workshop/content/108600").resolve(wid).resolve("mods");
      if (Files.isDirectory(own)) {
         return own;
      }
      String cmd = this.cfg.get("steamcmd");
      if (cmd.isEmpty()) {
         throw new IOException("Предмет " + wid + " ещё не скачан сервером, а steamcmd не настроен (steamcmd= в panel.properties)");
      }
      Path cache = Paths.get(this.cfg.get("steamcmdCache", "/tmp/pzws"));
      Files.createDirectories(cache);
      List<String> c = new ArrayList<>();
      if ("root".equals(System.getProperty("user.name"))) {
         c.addAll(List.of("runuser", "-u", "steam", "--"));
      }
      c.addAll(List.of(cmd, "+force_install_dir", cache.toString(), "+login", "anonymous", "+workshop_download_item", "108600", wid, "validate", "+quit"));
      Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
      StringBuilder out = new StringBuilder();
      Thread t = new Thread(() -> {
         try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) {
               synchronized (out) {
                  out.append(l).append('\n');
                  if (out.length() > 20000) {
                     out.delete(0, 10000);
                  }
               }
            }
         } catch (IOException e) {
         }
      });
      t.setDaemon(true);
      t.start();
      if (!p.waitFor(20, TimeUnit.MINUTES)) {
         p.destroyForcibly();
         throw new IOException("steamcmd не уложился в 20 минут");
      }
      t.join(2000);
      Path src = cache.resolve("steamapps/workshop/content/108600").resolve(wid).resolve("mods");
      if (!Files.isDirectory(src)) {
         String log;
         synchronized (out) {
            log = out.length() > 600 ? out.substring(out.length() - 600) : out.toString();
         }
         throw new IOException("steamcmd не скачал предмет " + wid + ": " + log.trim());
      }
      return src;
   }

   private static void copyTree(Path from, Path to) throws IOException {
      try (Stream<Path> s = Files.walk(from)) {
         for (Path p : (Iterable<Path>) s::iterator) {
            Path t = to.resolve(from.relativize(p).toString());
            if (Files.isDirectory(p)) {
               Files.createDirectories(t);
            } else {
               Files.createDirectories(t.getParent());
               Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
         }
      }
   }

   private static void deleteTree(Path d) throws IOException {
      if (!Files.exists(d)) {
         return;
      }
      try (Stream<Path> s = Files.walk(d)) {
         for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
            Files.delete(p);
         }
      }
   }

   private static String sha256(Path f) throws IOException {
      try (InputStream in = Files.newInputStream(f)) {
         MessageDigest md = MessageDigest.getInstance("SHA-256");
         byte[] b = new byte[65536];
         int n;
         while ((n = in.read(b)) > 0) {
            md.update(b, 0, n);
         }
         StringBuilder sb = new StringBuilder();
         for (byte x : md.digest()) {
            sb.append(String.format("%02x", x));
         }
         return sb.toString();
      } catch (java.security.NoSuchAlgorithmException e) {
         throw new IOException(e);
      }
   }

   /** Папки мода, закреплённого за предметом wid (из _frozen_manifest.txt). */
   private List<String> pinnedFolders(String wid) throws IOException {
      Path mf = this.cfg.zomboidDir().resolve("mods").resolve("_frozen_manifest.txt");
      List<String> r = new ArrayList<>();
      if (Files.exists(mf)) {
         for (String l : Util.readText(mf).split("\r?\n")) {
            String[] c = l.split("\t");
            if (c.length >= 2 && c[0].trim().equals(wid)) {
               for (String f : c[1].split(",")) {
                  if (!f.isBlank()) {
                     r.add(f.trim());
                  }
               }
            }
         }
      }
      return r;
   }

   /** Java-часть закреплённого мода: файл, mod id и sha256. approve=true — записать их в ZombieBuddy как разрешённые. */
   private List<Map<String, Object>> javaParts(String wid, List<String> folders, boolean approve) throws IOException {
      Path mods = this.cfg.zomboidDir().resolve("mods");
      List<Map<String, Object>> res = new ArrayList<>();
      Path apr = this.cfg.zbApprovals();
      ObjectNode root = null;
      ArrayNode arr = null;
      if (approve) {
         root = Files.exists(apr) ? (ObjectNode) Util.JSON.readTree(Util.readText(apr)) : Util.JSON.createObjectNode();
         if (!root.path("mods").isArray()) {
            root.putArray("mods");
         }
         arr = (ArrayNode) root.path("mods");
      }
      boolean changed = false;
      for (String f : folders) {
         Path dir = mods.resolve(f);
         if (!Files.isDirectory(dir)) {
            continue;
         }
         String fallbackId = f;
         try (Stream<Path> s = Files.walk(dir)) {
            for (Path info : (Iterable<Path>) s.filter(x -> x.getFileName().toString().equals("mod.info"))::iterator) {
               Matcher m = Pattern.compile("(?m)^id=(.+)$").matcher(Util.readText(info));
               if (m.find()) {
                  fallbackId = m.group(1).trim();
                  break;
               }
            }
         }
         List<Path> jars;
         try (Stream<Path> s = Files.walk(dir)) {
            jars = s.filter(x -> x.getFileName().toString().endsWith(".jar")).toList();
         }
         for (Path jar : jars) {
            String id = fallbackId;
            for (Path d = jar.getParent(); d != null && d.startsWith(dir); d = d.getParent()) {
               Path info = d.resolve("mod.info");
               if (Files.exists(info)) {
                  Matcher m = Pattern.compile("(?m)^id=(.+)$").matcher(Util.readText(info));
                  if (m.find()) {
                     id = m.group(1).trim();
                  }
                  break;
               }
            }
            String h = sha256(jar);
            boolean ok = false;
            if (approve) {
               for (JsonNode e : arr) {
                  if (e.path("jar_hash").asText().equals(h)) {
                     ((ObjectNode) e).put("decision", true);
                     ok = true;
                  }
               }
               if (!ok) {
                  ObjectNode e = arr.addObject();
                  e.put("id", id);
                  e.put("workshop_id", Long.parseLong(wid));
                  e.put("jar_hash", h);
                  e.put("decision", true);
               }
               changed = true;
            }
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("mod", id);
            j.put("file", jar.getFileName().toString());
            j.put("sha256", h);
            j.put("approved", approve);
            res.add(j);
         }
      }
      if (changed) {
         Files.createDirectories(apr.getParent());
         Util.writeTextWithBackup(apr, Util.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), this.cfg.fileBackupDir());
      }
      return res;
   }

   Map<String, Object> pin(String wid) throws IOException, InterruptedException {
      if (wid.isEmpty()) {
         throw new IOException("Не указан ID предмета");
      }
      Path src = this.pinSource(wid);
      Path dst = this.cfg.zomboidDir().resolve("mods");
      Files.createDirectories(dst);
      List<String> folders = new ArrayList<>();
      try (Stream<Path> s = Files.list(src)) {
         for (Path p : (Iterable<Path>) s.filter(Files::isDirectory)::iterator) {
            folders.add(p.getFileName().toString());
         }
      }
      if (folders.isEmpty()) {
         throw new IOException("В предмете " + wid + " нет папки mods/<мод>");
      }
      for (String f : folders) {
         deleteTree(dst.resolve(f));
         copyTree(src.resolve(f), dst.resolve(f));
      }
      if (!src.startsWith(this.cfg.serverDir())) {
         deleteTree(src.getParent()); // кэш steamcmd больше не нужен
      }
      long upd = 0L;
      try {
         List<Map<String, Object>> d = this.details(List.of(wid));
         if (!d.isEmpty() && d.get(0).get("updated") instanceof Number n) {
            upd = n.longValue();
         }
      } catch (Exception e) {
      }
      String stamp = (upd > 0 ? LocalDateTime.ofInstant(Instant.ofEpochSecond(upd), ZoneOffset.UTC) : LocalDateTime.now(ZoneOffset.UTC))
         .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
      Path mf = dst.resolve("_frozen_manifest.txt");
      List<String> keep = new ArrayList<>();
      if (Files.exists(mf)) {
         for (String l : Util.readText(mf).split("\r?\n")) {
            if (!l.isEmpty() && !l.startsWith(wid + "\t")) {
               keep.add(l);
            }
         }
      }
      keep.add(wid + "\t" + String.join(",", folders) + "\tpanel\t" + stamp);
      Util.writeTextWithBackup(mf, String.join("\n", keep) + "\n", this.cfg.fileBackupDir());
      List<String> ws = new ArrayList<>(splitList(Configs.iniValue(this.cfg.serverIni(), "WorkshopItems")));
      boolean wasWs = ws.remove(wid);
      if (wasWs) {
         LinkedHashMap<String, String> m = new LinkedHashMap<>();
         m.put("WorkshopItems", String.join(";", ws));
         Configs.writeIni(this.cfg.serverIni(), m, this.cfg.fileBackupDir());
      }
      List<Map<String, Object>> java = this.javaParts(wid, folders, false);
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("message", "Закреплено: " + String.join(", ", folders) + (wasWs ? " (убрано из WorkshopItems — сервер больше не обновляет мод)" : "") + ". Нужен перезапуск сервера.");
      r.put("folders", folders);
      r.put("java", java);
      return r;
   }

   Map<String, Object> approveJava(String wid) throws IOException {
      List<String> folders = this.pinnedFolders(wid);
      if (folders.isEmpty()) {
         throw new IOException("Предмет " + wid + " не закреплён");
      }
      List<Map<String, Object>> java = this.javaParts(wid, folders, true);
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("message", "Java-часть разрешена в ZombieBuddy: " + java.size() + " jar. Нужен перезапуск сервера.");
      r.put("java", java);
      return r;
   }

   private static String strip(String var0) {
      return var0.replaceFirst("^\\\\+", "");
   }

   private static Mods.ModInfo pick(List<Mods.ModInfo> var0, Set<String> var1) {
      if (var0 != null && !var0.isEmpty()) {
         for (Mods.ModInfo var3 : var0) {
            if (var1.contains(var3.workshopId())) {
               return var3;
            }
         }

         return (Mods.ModInfo)var0.get(0);
      } else {
         return null;
      }
   }

   private static Map<String, Object> describe(String var0, Mods.ModInfo var1) {
      LinkedHashMap var2 = new LinkedHashMap();
      var2.put("id", var0);
      var2.put("name", var1 == null ? var0 : var1.name());
      var2.put("workshopId", var1 == null ? "" : var1.workshopId());
      var2.put("installed", var1 != null);
      if (var1 != null) {
         var2.put("require", var1.require());
         var2.put("loadAfter", var1.loadAfter());
         var2.put("versionMin", var1.versionMin());
         var2.put("versionMax", var1.versionMax());
         var2.put("versionDir", var1.versionDir());
         var2.put("java", var1.java());
         var2.put("local", var1.local());
         var2.put("hasPoster", !var1.poster().isEmpty());
      }

      return var2;
   }

   Path posterPath(String var1) {
      List<Mods.ModInfo> var2 = this.scan().get(var1);
      if (var2 == null) {
         return null;
      } else {
         for (Mods.ModInfo var4 : var2) {
            if (!var4.poster().isEmpty()) {
               return Paths.get(var4.poster());
            }
         }

         return null;
      }
   }

   void save(List<String> var1, List<String> var2, boolean var3) throws IOException {
      LinkedHashMap var4 = new LinkedHashMap();
      var4.put("Mods", String.join(";", var1));
      var4.put("WorkshopItems", String.join(";", var2));
      Configs.writeIni(this.cfg.serverIni(), var4, this.cfg.fileBackupDir());
      if (var3) {
         this.writeClientMods(var1);
      }
   }

   void writeClientMods(List<String> var1) throws IOException {
      Path var2 = this.cfg.clientModsFile();
      String var3 = Files.exists(var2) ? Util.readText(var2) : "VERSION = 1,\n\nmods\n{\n}\n\nmaps\n{\n}\n";
      String var4 = Util.lineSep(var3);
      StringBuilder var5 = new StringBuilder("mods").append(var4).append('{').append(var4);

      for (String var7 : var1) {
         var5.append("    mod = ").append(strip(var7)).append(',').append(var4);
      }

      var5.append('}');
      Matcher var8 = Pattern.compile("mods\\s*\\{[^}]*\\}", 32).matcher(var3);
      String var9 = var8.find() ? var3.substring(0, var8.start()) + var5 + var3.substring(var8.end()) : var3 + var4 + var5 + var4;
      Files.createDirectories(var2.getParent());
      Util.writeTextWithBackup(var2, var9, this.cfg.fileBackupDir());
   }

   List<Map<String, Object>> details(Collection<String> var1) throws IOException, InterruptedException {
      ArrayList var2 = new ArrayList();

      for (String var4 : var1) {
         if (!this.steamCache.containsKey(var4)) {
            var2.add(var4);
         }
      }

      for (byte var14 = 0; var14 < var2.size(); var14 += 100) {
         List var16 = var2.subList(var14, Math.min(var2.size(), var14 + 100));
         StringBuilder var5 = new StringBuilder("itemcount=" + var16.size());

         for (int var6 = 0; var6 < var16.size(); var6++) {
            var5.append("&publishedfileids%5B").append(var6).append("%5D=").append((String)var16.get(var6));
         }

         JsonNode var19 = Util.JSON
            .readTree(Util.httpPostForm("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/", var5.toString()));

         for (JsonNode var8 : var19.path("response").path("publishedfiledetails")) {
            LinkedHashMap var9 = new LinkedHashMap();
            String var10 = var8.path("publishedfileid").asText();
            var9.put("id", var10);
            var9.put("ok", var8.path("result").asInt() == 1);
            var9.put("title", var8.path("title").asText(""));
            var9.put("preview", var8.path("preview_url").asText(""));
            var9.put("updated", var8.path("time_updated").asLong(0L));
            var9.put("size", var8.path("file_size").asLong(0L));
            var9.put("subs", var8.path("lifetime_subscriptions").asLong(var8.path("subscriptions").asLong(0L)));
            ArrayList var11 = new ArrayList();

            for (JsonNode var13 : var8.path("tags")) {
               var11.add(var13.path("tag").asText());
            }

            var9.put("tags", var11);
            String var21 = var8.path("description").asText("");
            var9.put("modIds", parseModIds(var21));
            var9.put("description", var21.length() > 1500 ? var21.substring(0, 1500) + "…" : var21);
            this.steamCache.put(var10, var9);
         }
      }

      ArrayList var15 = new ArrayList();

      for (String var18 : var1) {
         Map var20 = this.steamCache.get(var18);
         if (var20 != null) {
            var15.add(var20);
         }
      }

      return var15;
   }

   static List<String> parseModIds(String var0) {
      LinkedHashSet var1 = new LinkedHashSet();
      var0 = var0.replaceAll("\\[/?[a-zA-Z0-9*]+(=[^\\]]*)?\\]", "");
      Matcher var2 = Pattern.compile("(?im)Mod\\s*IDs?\\s*:\\s*([^\\r\\n\\[]+)").matcher(var0);

      while (var2.find()) {
         for (String var6 : var2.group(1).split("[,;|]")) {
            String var7 = var6.trim().replaceAll("\\(.*?\\)", "").replaceAll("[\\s*]+$", "").trim();
            if (var7.matches("[A-Za-z0-9_\\-.\\\\ ']{1,80}") && !var7.isEmpty() && !var7.contains("Workshop")) {
               var1.add(var7.replaceFirst("^\\\\", ""));
            }
         }
      }

      return new ArrayList<>(var1);
   }

   Map<String, Object> item(String var1) throws IOException, InterruptedException {
      List var2 = this.details(List.of(var1));
      if (var2.isEmpty()) {
         throw new IOException("Предмет " + var1 + " не найден");
      } else {
         LinkedHashMap var3 = new LinkedHashMap((Map)var2.get(0));
         ArrayList var4 = new ArrayList();

         try {
            String var5 = Util.httpGet("https://steamcommunity.com/sharedfiles/filedetails/?id=" + var1);
            int var6 = var5.indexOf("requiredItemsContainer");
            if (var6 >= 0) {
               int var7 = var5.indexOf("workshopItemDescription", var6);
               String var8 = var5.substring(var6, var7 > var6 ? Math.min(var7, var6 + 12000) : Math.min(var5.length(), var6 + 6000));
               Matcher var9 = Pattern.compile("filedetails/\\?id=(\\d+)").matcher(var8);

               while (var9.find()) {
                  if (!var4.contains(var9.group(1)) && !var9.group(1).equals(var1)) {
                     var4.add(var9.group(1));
                  }
               }
            }

            if (var5.contains("collectionChildren") || var5.contains("collectionItem")) {
               var3.put("maybeCollection", true);
            }
         } catch (Exception var10) {
         }

         List var11 = var4.isEmpty() ? List.of() : this.details(var4);
         var3.put("required", var11);
         return var3;
      }
   }

   List<Map<String, Object>> collection(String var1) throws IOException, InterruptedException {
      JsonNode var2 = Util.JSON
         .readTree(
            Util.httpPostForm("https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/", "collectioncount=1&publishedfileids%5B0%5D=" + var1)
         );
      ArrayList var3 = new ArrayList();

      for (JsonNode var5 : var2.path("response").path("collectiondetails").path(0).path("children")) {
         var3.add(var5.path("publishedfileid").asText());
      }

      if (var3.isEmpty()) {
         throw new IOException("Коллекция пуста или не найдена");
      } else {
         return this.details(var3);
      }
   }

   List<Map<String, Object>> search(String var1, int var2) throws IOException, InterruptedException {
      String var3 = "https://steamcommunity.com/workshop/browse/?appid=108600&searchtext="
         + URLEncoder.encode(var1, StandardCharsets.UTF_8)
         + "&childpublishedfileid=0&browsesort=textsearch&section=readytouseitems&actualsort=textsearch&p="
         + Math.max(1, var2);
      String var4 = Util.httpGet(var3);
      LinkedHashSet var5 = new LinkedHashSet();
      Matcher var6 = Pattern.compile("sharedfiles/filedetails/\\?id=(\\d+)").matcher(var4);

      while (var6.find()) {
         var5.add(var6.group(1));
      }

      return var5.isEmpty() ? List.of() : this.details(new ArrayList<>(var5).subList(0, Math.min(var5.size(), 30)));
   }

   record ModInfo(
      String id,
      String name,
      String workshopId,
      String folder,
      String versionDir,
      List<String> require,
      List<String> loadAfter,
      List<String> loadBefore,
      List<String> incompatible,
      String versionMin,
      String versionMax,
      boolean java,
      String poster,
      boolean local
   ) {
   }
}
