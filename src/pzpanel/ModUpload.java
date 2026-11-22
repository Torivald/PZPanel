package pzpanel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Установка мода из zip-архива, загруженного через панель (мод не из Мастерской или своя сборка).
 * Шаг 1 — receive(): архив сохраняется во временную папку и разбирается: где в нём папки модов (по mod.info).
 * Шаг 2 — install(): выбранные папки распаковываются в Zomboid/mods. Прежняя папка с тем же именем
 * не удаляется, а переносится в Zomboid/mods_replaced/&lt;время&gt;/. Сервер такой мод не обновляет (его нет в WorkshopItems).
 * Если для папки указан ID предмета Мастерской, он записывается в _frozen_manifest.txt (как у закреплённых модов).
 */
final class ModUpload {
   private static final Pattern VER_DIR = Pattern.compile("\\d+(\\.\\d+)*");
   private static final Pattern FOLDER_OK = Pattern.compile("[^/\\\\:*?\"<>|]{1,100}");
   private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
   private static final long TTL_MS = 60 * 60 * 1000L;

   /** Папка мода внутри архива. root — путь в архиве ("" — корень архива), folder — имя папки в Zomboid/mods. */
   private record Found(String root, String folder, List<String> ids, List<String> names, boolean java, long size, int files, String workshopId) {
   }

   private record Pending(Path zip, String fileName, List<Found> found, long created) {
   }

   private final Config cfg;
   private final Mods mods;
   private final ServerManager srv;
   private final Map<String, Pending> pending = new ConcurrentHashMap<>();

   ModUpload(Config cfg, Mods mods, ServerManager srv) {
      this.cfg = cfg;
      this.mods = mods;
      this.srv = srv;
   }

   private Path modsDir() {
      return this.cfg.zomboidDir().resolve("mods");
   }

   private Path uploadDir() {
      return this.cfg.panelDir.resolve("mod_uploads");
   }

   private long maxBytes() {
      long mb = 4096;
      try {
         mb = Long.parseLong(this.cfg.get("modUploadMaxMb", "4096").trim());
      } catch (NumberFormatException e) {
      }
      return Math.max(1, mb) * 1024L * 1024L;
   }

   /** Удаляет брошенные загрузки (старше часа). */
   private void sweep() {
      long now = System.currentTimeMillis();
      this.pending.entrySet().removeIf(e -> {
         if (now - e.getValue().created() > TTL_MS) {
            try {
               Files.deleteIfExists(e.getValue().zip());
            } catch (IOException ignored) {
            }
            return true;
         }
         return false;
      });
      Path d = this.uploadDir();
      if (Files.isDirectory(d)) {
         try (Stream<Path> s = Files.list(d)) {
            for (Path p : (Iterable<Path>) s::iterator) {
               boolean known = this.pending.values().stream().anyMatch(x -> x.zip().equals(p));
               if (!known && now - p.toFile().lastModified() > TTL_MS) {
                  Files.deleteIfExists(p);
               }
            }
         } catch (IOException ignored) {
         }
      }
   }

   // ------------------------------------------------------------------ шаг 1: приём и разбор

   Map<String, Object> receive(InputStream body, String fileName) throws IOException {
      this.sweep();
      String name = fileName == null ? "" : fileName.replaceAll(".*[/\\\\]", "").trim();
      if (!name.toLowerCase().endsWith(".zip")) {
         throw new IOException("Нужен архив .zip (7z и rar панель не распаковывает — перепакуйте в zip)");
      }
      Files.createDirectories(this.uploadDir());
      String token = UUID.randomUUID().toString().replace("-", "");
      Path zip = this.uploadDir().resolve(token + ".zip");
      long max = this.maxBytes();
      long n = 0;
      try (OutputStream os = Files.newOutputStream(zip)) {
         byte[] buf = new byte[1 << 16];
         int r;
         while ((r = body.read(buf)) > 0) {
            n += r;
            if (n > max) {
               throw new IOException("Архив больше " + max / 1048576L + " МБ (параметр modUploadMaxMb на вкладке «Панель»)");
            }
            os.write(buf, 0, r);
         }
      } catch (IOException e) {
         Files.deleteIfExists(zip);
         throw e;
      }
      if (n == 0) {
         Files.deleteIfExists(zip);
         throw new IOException("Пустой файл");
      }
      List<Found> found;
      try {
         found = this.analyze(zip, name);
      } catch (IOException e) {
         Files.deleteIfExists(zip);
         throw e;
      }
      if (found.isEmpty()) {
         Files.deleteIfExists(zip);
         throw new IOException("В архиве не найдено ни одного мода: нет файла mod.info (ожидается <Папка мода>/42/mod.info, <Папка мода>/mod.info или mods/<Папка мода>/…)");
      }
      this.pending.put(token, new Pending(zip, name, found, System.currentTimeMillis()));
      return this.describe(token);
   }

   private List<Found> analyze(Path zip, String zipName) throws IOException {
      // все mod.info → корень мода: папка с mod.info, а если это папка версии (42, 42.13) или common — на уровень выше
      Map<String, List<String>> infoByRoot = new LinkedHashMap<>();
      List<String> names = new ArrayList<>();
      Map<String, String> manifest = new HashMap<>();
      String workshopTxtId = "";
      long total = 0;
      try (ZipFile zf = new ZipFile(zip.toFile())) {
         for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
            ZipEntry e = en.nextElement();
            String n = norm(e.getName());
            if (n == null) {
               throw new IOException("В архиве недопустимый путь: " + e.getName());
            }
            if (e.isDirectory() || n.startsWith("__MACOSX/") || n.contains("/__MACOSX/")) {
               continue;
            }
            names.add(n);
            total += Math.max(0, e.getSize());
            String base = n.substring(n.lastIndexOf('/') + 1);
            String dir = n.contains("/") ? n.substring(0, n.lastIndexOf('/')) : "";
            if (base.equalsIgnoreCase("mod.info")) {
               String root = dir;
               String last = root.substring(root.lastIndexOf('/') + 1);
               if (!root.isEmpty() && (VER_DIR.matcher(last).matches() || last.equalsIgnoreCase("common"))) {
                  root = root.contains("/") ? root.substring(0, root.lastIndexOf('/')) : "";
               }
               infoByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(n);
            } else if (base.equals("_frozen_manifest.txt") && dir.isEmpty()) {
               // архив модов для игроков: папка → предмет Мастерской
               for (String l : read(zf, e).split("\r?\n")) {
                  String[] c = l.split("\t");
                  if (c.length >= 2 && c[0].trim().matches("\\d+")) {
                     for (String f : c[1].split(",")) {
                        if (!f.isBlank()) {
                           manifest.put(f.trim(), c[0].trim());
                        }
                     }
                  }
               }
            } else if (base.equalsIgnoreCase("workshop.txt")) {
               Matcher m = Pattern.compile("(?m)^id=(\\d+)\\s*$").matcher(read(zf, e));
               if (m.find()) {
                  workshopTxtId = m.group(1);
               }
            }
         }
         if (total > this.maxBytes() * 20) {
            throw new IOException("Распакованный архив слишком большой (" + total / 1048576L + " МБ)");
         }
         // вложенные корни (мод внутри мода) отбрасываем — берём внешний
         List<String> roots = new ArrayList<>(infoByRoot.keySet());
         roots.sort(Comparator.comparingInt(String::length));
         List<String> keep = new ArrayList<>();
         for (String r : roots) {
            boolean nested = false;
            for (String k : keep) {
               if (k.isEmpty() || r.startsWith(k + "/")) {
                  nested = true;
                  break;
               }
            }
            if (!nested) {
               keep.add(r);
            }
         }
         List<Found> res = new ArrayList<>();
         Set<String> folders = new LinkedHashSet<>();
         for (String root : keep) {
            List<String> ids = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : infoByRoot.entrySet()) {
               if (!(e.getKey().equals(root) || root.isEmpty() || e.getKey().startsWith(root + "/"))) {
                  continue;
               }
               for (String info : e.getValue()) {
                  String text = read(zf, zf.getEntry(rawName(zf, info)));
                  String id = field(text, "id");
                  String title = field(text, "name");
                  if (!id.isEmpty() && !ids.contains(id)) {
                     ids.add(id);
                     titles.add(title.isEmpty() ? id : title);
                  }
               }
            }
            String folder = root.isEmpty() ? "" : root.substring(root.lastIndexOf('/') + 1);
            if (folder.isEmpty()) {
               // mod.info лежит прямо в корне архива: имя папки — по id мода или по имени архива
               folder = !ids.isEmpty() ? ids.get(0) : zipName.replaceFirst("(?i)\\.zip$", "");
               folder = folder.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
            }
            if (!FOLDER_OK.matcher(folder).matches() || folder.startsWith(".") || folder.startsWith("_") || !folders.add(folder)) {
               throw new IOException("Неподходящее или повторяющееся имя папки мода в архиве: «" + folder + "»");
            }
            boolean java = false;
            long size = 0;
            int files = 0;
            String prefix = root.isEmpty() ? "" : root + "/";
            for (String n : names) {
               if (n.startsWith(prefix)) {
                  files++;
                  java |= n.toLowerCase().endsWith(".jar");
                  ZipEntry ze = zf.getEntry(rawName(zf, n));
                  size += ze == null ? 0 : Math.max(0, ze.getSize());
               }
            }
            // ID предмета Мастерской: из манифеста архива, из пути …/<ID>/mods/<папка>, из workshop.txt
            String wid = manifest.getOrDefault(folder, "");
            if (wid.isEmpty()) {
               Matcher m = Pattern.compile("(?:^|/)(\\d{6,})/mods/[^/]+$").matcher(root);
               if (m.find()) {
                  wid = m.group(1);
               }
            }
            if (wid.isEmpty() && keep.size() == 1) {
               wid = workshopTxtId;
            }
            res.add(new Found(root, folder, ids, titles, java, size, files, wid));
         }
         res.sort(Comparator.comparing(f -> f.folder().toLowerCase()));
         return res;
      } catch (ZipException e) {
         throw new IOException("Файл не открывается как zip-архив: " + e.getMessage());
      }
   }

   /** Нормализует путь из архива; null — путь опасный (абсолютный или с ..). */
   private static String norm(String n) {
      String s = n.replace('\\', '/');
      while (s.startsWith("./")) {
         s = s.substring(2);
      }
      if (s.startsWith("/") || s.matches("^[A-Za-z]:.*")) {
         return null;
      }
      for (String part : s.split("/")) {
         if (part.equals("..")) {
            return null;
         }
      }
      return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
   }

   /** Имя записи в архиве как оно там записано (с обратными слешами, если архив сделан на Windows). */
   private final Map<ZipFile, Map<String, String>> rawNames = new java.util.WeakHashMap<>();

   private String rawName(ZipFile zf, String normalized) {
      Map<String, String> m = this.rawNames.computeIfAbsent(zf, z -> {
         Map<String, String> x = new HashMap<>();
         for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
            ZipEntry e = en.nextElement();
            String n = norm(e.getName());
            if (n != null) {
               x.put(n, e.getName());
            }
         }
         return x;
      });
      return m.getOrDefault(normalized, normalized);
   }

   private static String read(ZipFile zf, ZipEntry e) throws IOException {
      if (e == null || e.getSize() > 1_000_000) {
         return "";
      }
      try (InputStream in = zf.getInputStream(e)) {
         byte[] b = in.readNBytes(1_000_000);
         String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
         return s.startsWith("﻿") ? s.substring(1) : s;
      }
   }

   private static String field(String text, String key) {
      Matcher m = Pattern.compile("(?mi)^\\s*" + key + "\\s*=\\s*(.*?)\\s*$").matcher(text);
      return m.find() ? m.group(1) : "";
   }

   private Map<String, Object> describe(String token) {
      Pending p = this.pending.get(token);
      Map<String, Map<String, String>> existing = this.existingIds();
      Map<String, String> manifest = this.manifestFolders();
      List<Map<String, Object>> list = new ArrayList<>();
      for (Found f : p.found()) {
         Map<String, Object> m = new LinkedHashMap<>();
         m.put("folder", f.folder());
         m.put("root", f.root().isEmpty() ? "(корень архива)" : f.root());
         m.put("ids", f.ids());
         m.put("names", f.names());
         m.put("java", f.java());
         m.put("size", f.size());
         m.put("files", f.files());
         String wid = f.workshopId().isEmpty() ? manifest.getOrDefault(f.folder(), "") : f.workshopId();
         m.put("workshopId", wid);
         m.put("exists", Files.isDirectory(this.modsDir().resolve(f.folder())));
         // тот же id мода уже есть в другом месте (Мастерская или другая папка) — сервер может взять не ту копию
         List<String> dup = new ArrayList<>();
         for (String id : f.ids()) {
            Map<String, String> where = existing.get(id);
            if (where != null) {
               for (Map.Entry<String, String> w : where.entrySet()) {
                  if (!w.getKey().equals("local:" + f.folder())) {
                     dup.add(id + " — " + w.getValue());
                  }
               }
            }
         }
         m.put("duplicates", dup);
         list.add(m);
      }
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("token", token);
      r.put("file", p.fileName());
      r.put("mods", list);
      r.put("serverRunning", this.srv.isRunning());
      return r;
   }

   /** id мода → {ключ места → описание}: где этот id уже установлен. */
   private Map<String, Map<String, String>> existingIds() {
      Map<String, Map<String, String>> r = new HashMap<>();
      for (Map.Entry<String, List<Mods.ModInfo>> e : this.mods.scan().entrySet()) {
         for (Mods.ModInfo mi : e.getValue()) {
            String key = mi.local() ? "local:" + mi.folder() : "ws:" + mi.workshopId();
            String desc = mi.local() ? "Zomboid/mods/" + mi.folder() : "предмет Мастерской " + mi.workshopId();
            r.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).put(key, desc);
         }
      }
      return r;
   }

   private Path manifestFile() {
      return this.modsDir().resolve("_frozen_manifest.txt");
   }

   /** Папка → ID предмета из _frozen_manifest.txt. */
   private Map<String, String> manifestFolders() {
      Map<String, String> r = new HashMap<>();
      try {
         if (Files.exists(this.manifestFile())) {
            for (String l : Util.readText(this.manifestFile()).split("\r?\n")) {
               String[] c = l.split("\t");
               if (!l.startsWith("#") && c.length >= 2) {
                  for (String f : c[1].split(",")) {
                     if (!f.isBlank()) {
                        r.put(f.trim(), c[0].trim());
                     }
                  }
               }
            }
         }
      } catch (IOException ignored) {
      }
      return r;
   }

   void cancel(String token) throws IOException {
      Pending p = this.pending.remove(token == null ? "" : token);
      if (p != null) {
         Files.deleteIfExists(p.zip());
      }
   }

   // ------------------------------------------------------------------ шаг 2: установка

   /**
    * folders — какие папки из архива ставить; workshopIds — папка → ID предмета Мастерской (необязательно).
    * Возвращает установленные папки с id модов (их включает в Mods= уже страница), Java-части и куда ушли прежние версии.
    */
   Map<String, Object> install(String token, List<String> folders, Map<String, String> workshopIds) throws IOException {
      Pending p = this.pending.get(token == null ? "" : token);
      if (p == null) {
         throw new IOException("Загрузка устарела или уже установлена — загрузите архив ещё раз");
      }
      List<Found> chosen = new ArrayList<>();
      for (Found f : p.found()) {
         if (folders.contains(f.folder())) {
            chosen.add(f);
         }
      }
      if (chosen.isEmpty()) {
         throw new IOException("Не выбрано ни одной папки мода");
      }
      Path md = this.modsDir();
      boolean running = this.srv.isRunning();
      for (Found f : chosen) {
         if (running && Files.exists(md.resolve(f.folder()))) {
            throw new IOException("Папка " + f.folder() + " уже есть в Zomboid/mods — заменять её можно только при остановленном сервере (файлы мода заняты)");
         }
         String wid = workshopIds.getOrDefault(f.folder(), "").trim();
         if (!wid.isEmpty() && !wid.matches("\\d{4,}")) {
            throw new IOException("ID предмета Мастерской для " + f.folder() + " — только цифры");
         }
      }
      Files.createDirectories(md);
      // 1) распаковка во временную папку рядом (та же файловая система — потом быстрый перенос)
      Path tmp = md.resolve(".pzpanel-upload-" + token);
      Util.deleteTree(tmp);
      Files.createDirectories(tmp);
      try (ZipFile zf = new ZipFile(p.zip().toFile())) {
         for (Found f : chosen) {
            String prefix = f.root().isEmpty() ? "" : f.root() + "/";
            Path dst = tmp.resolve(f.folder()).normalize();
            Files.createDirectories(dst);
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
               ZipEntry e = en.nextElement();
               String n = norm(e.getName());
               if (n == null || e.isDirectory() || !n.startsWith(prefix) || n.startsWith("__MACOSX/") || n.contains("/__MACOSX/")) {
                  continue;
               }
               Path out = dst.resolve(n.substring(prefix.length())).normalize();
               if (!out.startsWith(dst)) {
                  continue;
               }
               Files.createDirectories(out.getParent());
               try (InputStream in = zf.getInputStream(e)) {
                  Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
               }
            }
         }
      } catch (IOException e) {
         Util.deleteTree(tmp);
         throw e;
      }
      // 2) прежние папки — в Zomboid/mods_replaced/<время>/, новые — на их место
      String stamp = LocalDateTime.now().format(STAMP);
      Path replaced = this.cfg.zomboidDir().resolve("mods_replaced").resolve(stamp);
      List<String> moved = new ArrayList<>();
      try {
         for (Found f : chosen) {
            Path target = md.resolve(f.folder());
            if (Files.exists(target)) {
               Files.createDirectories(replaced);
               move(target, replaced.resolve(f.folder()));
               moved.add(f.folder());
            }
            move(tmp.resolve(f.folder()), target);
         }
      } catch (IOException e) {
         throw new IOException("Не удалось поставить папку мода (файлы заняты игрой или сервером?): " + e.getMessage()
            + (moved.isEmpty() ? "" : ". Прежние версии лежат в " + replaced));
      } finally {
         Util.deleteTree(tmp);
      }
      // 3) _frozen_manifest.txt: папки с известным ID предмета — как закреплённые моды
      Map<String, String> wids = new LinkedHashMap<>();
      Map<String, String> known = this.manifestFolders();
      for (Found f : chosen) {
         String wid = workshopIds.getOrDefault(f.folder(), "").trim();
         if (wid.isEmpty()) {
            wid = known.getOrDefault(f.folder(), "");
         }
         if (!wid.isEmpty()) {
            wids.put(f.folder(), wid);
         }
      }
      if (!wids.isEmpty()) {
         this.updateManifest(wids);
      }
      this.pending.remove(token);
      Files.deleteIfExists(p.zip());
      this.srv.markConfigChanged();

      List<Map<String, Object>> installed = new ArrayList<>();
      boolean java = false;
      for (Found f : chosen) {
         Map<String, Object> m = new LinkedHashMap<>();
         m.put("folder", f.folder());
         m.put("ids", f.ids());
         m.put("java", f.java());
         m.put("workshopId", wids.getOrDefault(f.folder(), ""));
         installed.add(m);
         java |= f.java();
      }
      Map<String, Object> r = new LinkedHashMap<>();
      StringBuilder msg = new StringBuilder("Установлено в Zomboid/mods: ");
      msg.append(String.join(", ", chosen.stream().map(Found::folder).toList())).append('.');
      if (!moved.isEmpty()) {
         msg.append(" Прежние версии (").append(String.join(", ", moved)).append(") перенесены в Zomboid/mods_replaced/").append(stamp).append('.');
      }
      if (java) {
         msg.append(" В моде есть Java-код — ZombieBuddy спросит о нём при запуске сервера.");
      }
      msg.append(" Нужен перезапуск сервера; игрокам — новый архив модов.");
      r.put("message", msg.toString());
      r.put("installed", installed);
      r.put("replacedDir", moved.isEmpty() ? "" : replaced.toString());
      return r;
   }

   private static void move(Path from, Path to) throws IOException {
      try {
         Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
         Files.move(from, to); // другая файловая система или ATOMIC_MOVE не поддерживается
      }
   }

   /** Папка → ID предмета: строки манифеста для этих папок заменяются, из чужих строк папки убираются. */
   private void updateManifest(Map<String, String> wids) throws IOException {
      Path mf = this.manifestFile();
      String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
      Map<String, List<String>> byWid = new LinkedHashMap<>();
      for (Map.Entry<String, String> e : wids.entrySet()) {
         byWid.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
      }
      List<String> out = new ArrayList<>();
      if (Files.exists(mf)) {
         for (String l : Util.readText(mf).split("\r?\n")) {
            if (l.isEmpty()) {
               continue;
            }
            String[] c = l.split("\t", -1);
            if (l.startsWith("#") || c.length < 2) {
               out.add(l);
               continue;
            }
            String wid = c[0].trim();
            List<String> rest = new ArrayList<>();
            for (String f : c[1].split(",")) {
               if (!f.isBlank() && !wids.containsKey(f.trim())) {
                  rest.add(f.trim());
               }
            }
            if (byWid.containsKey(wid)) {
               for (String f : rest) { // остальные папки того же предмета остаются за ним
                  if (!byWid.get(wid).contains(f)) {
                     byWid.get(wid).add(f);
                  }
               }
               continue;
            }
            if (!rest.isEmpty()) {
               c[1] = String.join(",", rest);
               out.add(String.join("\t", c));
            }
         }
      }
      for (Map.Entry<String, List<String>> e : byWid.entrySet()) {
         out.add(e.getKey() + "\t" + String.join(",", e.getValue()) + "\tupload\t" + stamp);
      }
      Files.createDirectories(mf.getParent());
      Util.writeTextWithBackup(mf, String.join("\n", out) + "\n", this.cfg.fileBackupDir());
      // список предметов для freeze_mods.bat / pack_mods.bat
      Path fl = this.modsDir().resolve("_frozen_workshop_items.txt");
      LinkedHashSet<String> items = new LinkedHashSet<>();
      if (Files.exists(fl)) {
         for (String l : Util.readText(fl).split("\r?\n")) {
            if (l.trim().matches("\\d+")) {
               items.add(l.trim());
            }
         }
      }
      if (items.addAll(byWid.keySet())) {
         Files.writeString(fl, String.join("\n", items) + "\n");
      }
   }
}
