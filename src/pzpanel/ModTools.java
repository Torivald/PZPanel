package pzpanel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Инструменты вкладки «Моды», не затрагивающие запуск сервера:
 * 1) автосортировщик порядка загрузки (по require / loadModAfter / loadModBefore, по желанию — по категориям);
 * 2) архивы замороженных модов для скачивания игроками (один мод или все) с политикой удаления старых архивов.
 */
final class ModTools {
   static final Pattern ARCHIVE_NAME = Pattern.compile("^PZ_mod[A-Za-z0-9_.\\-]*\\.zip$");
   private static final String FULL_PREFIX = "PZ_mods_all_";
   private static final String ONE_PREFIX = "PZ_mod_";
   private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

   private final Config cfg;
   private final Mods mods;
   private final Map<String, Job> jobs = new ConcurrentHashMap<>();
   private final Object packLock = new Object();

   ModTools(Config cfg, Mods mods) {
      this.cfg = cfg;
      this.mods = mods;
      ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
         Thread t = new Thread(r, "pz-modarchive-sweep");
         t.setDaemon(true);
         return t;
      });
      s.scheduleWithFixedDelay(() -> {
         try {
            sweep();
         } catch (Throwable ignored) {
         }
      }, 5, 600, TimeUnit.SECONDS);
   }

   // =====================================================================================================
   // Автосортировщик
   // =====================================================================================================

   private static String strip(String s) {
      return s.replaceFirst("^\\\\+", "");
   }

   private Map<String, Mods.ModInfo> infoById() {
      Set<String> ws = new HashSet<>(Mods.splitList(Configs.iniValue(this.cfg.serverIni(), "WorkshopItems")));
      Map<String, Mods.ModInfo> res = new HashMap<>();
      for (Map.Entry<String, List<Mods.ModInfo>> e : this.mods.scan().entrySet()) {
         Mods.ModInfo pick = e.getValue().get(0);
         for (Mods.ModInfo m : e.getValue()) {
            if (ws.contains(m.workshopId())) {
               pick = m;
               break;
            }
         }
         res.put(e.getKey(), pick);
      }
      return res;
   }

   /**
    * Предлагает новый порядок для списка raw (как в Mods=). Ничего не пишет на диск.
    * mode=fix — стабильная топологическая сортировка: порядок сохраняется, меняются только те моды, которые нарушают
    * require / loadModAfter / loadModBefore. mode=categories — как «Sort» в моде Mod Load Order Sorter: сначала по
    * категориям (библиотеки → ресурсы → карты → транспорт → код → одежда → интерфейс → прочее → переводы) и по алфавиту
    * внутри категории, затем та же топологическая правка.
    */
   Map<String, Object> sort(List<String> raw, String mode) {
      boolean cats = "categories".equals(mode);
      Map<String, Mods.ModInfo> info = infoById();
      int n = raw.size();
      List<String> ids = new ArrayList<>();
      for (String r : raw) {
         ids.add(strip(r));
      }
      Map<String, Integer> idx = new HashMap<>();
      for (int i = 0; i < n; i++) {
         idx.putIfAbsent(ids.get(i), i);
      }

      // начальный порядок
      Integer[] start = new Integer[n];
      for (int i = 0; i < n; i++) {
         start[i] = i;
      }
      Map<String, Object> catOf = new LinkedHashMap<>();
      if (cats) {
         Set<String> required = new HashSet<>();
         for (String id : ids) {
            Mods.ModInfo m = info.get(id);
            if (m != null) {
               required.addAll(m.require());
            }
         }
         int[] rank = new int[n];
         String[] names = new String[n];
         for (int i = 0; i < n; i++) {
            Mods.ModInfo m = info.get(ids.get(i));
            rank[i] = category(ids.get(i), m, required.contains(ids.get(i)));
            names[i] = (m == null ? ids.get(i) : m.name()).toLowerCase(Locale.ROOT);
            catOf.put(ids.get(i), CATEGORY_NAMES[rank[i]]);
         }
         java.util.Arrays.sort(start, Comparator.<Integer>comparingInt(i -> rank[i]).thenComparing(i -> names[i]).thenComparingInt(i -> i));
      }
      int[] pos = new int[n]; // позиция в начальном порядке
      for (int p = 0; p < n; p++) {
         pos[start[p]] = p;
      }

      // рёбра «a раньше b»
      List<Set<Integer>> out = new ArrayList<>();
      int[] indeg = new int[n];
      for (int i = 0; i < n; i++) {
         out.add(new LinkedHashSet<>());
      }
      List<String> warnings = new ArrayList<>();
      for (int i = 0; i < n; i++) {
         Mods.ModInfo m = info.get(ids.get(i));
         if (m == null) {
            continue;
         }
         for (String r : m.require()) {
            Integer j = idx.get(r);
            if (j == null) {
               warnings.add(ids.get(i) + " требует " + r + ", но он не включён");
            } else if (!j.equals(i) && out.get(j).add(i)) {
               indeg[i]++;
            }
         }
         for (String r : m.loadAfter()) {
            Integer j = idx.get(r);
            if (j != null && !j.equals(i) && out.get(j).add(i)) {
               indeg[i]++;
            }
         }
         for (String r : m.loadBefore()) {
            Integer j = idx.get(r);
            if (j != null && !j.equals(i) && out.get(i).add(j)) {
               indeg[j]++;
            }
         }
         for (String r : m.incompatible()) {
            if (idx.containsKey(r)) {
               warnings.add(ids.get(i) + " несовместим с " + r);
            }
         }
      }

      // стабильный Kahn: из готовых берём того, кто раньше стоял в начальном порядке
      PriorityQueue<Integer> ready = new PriorityQueue<>(Comparator.comparingInt(i -> pos[i]));
      for (int i = 0; i < n; i++) {
         if (indeg[i] == 0) {
            ready.add(i);
         }
      }
      List<Integer> order = new ArrayList<>();
      while (!ready.isEmpty()) {
         int i = ready.poll();
         order.add(i);
         for (int j : out.get(i)) {
            if (--indeg[j] == 0) {
               ready.add(j);
            }
         }
      }
      List<String> cycles = new ArrayList<>();
      if (order.size() < n) { // цикл зависимостей: хвост оставляем как есть и сообщаем
         Set<Integer> done = new HashSet<>(order);
         List<Integer> rest = new ArrayList<>();
         for (int p = 0; p < n; p++) {
            if (!done.contains(start[p])) {
               rest.add(start[p]);
               cycles.add(ids.get(start[p]));
            }
         }
         order.addAll(rest);
      }

      List<String> result = new ArrayList<>();
      List<Map<String, Object>> moves = new ArrayList<>();
      for (int p = 0; p < n; p++) {
         int i = order.get(p);
         result.add(raw.get(i));
         if (p != i) {
            Map<String, Object> mv = new LinkedHashMap<>();
            mv.put("id", ids.get(i));
            mv.put("name", info.get(ids.get(i)) == null ? ids.get(i) : info.get(ids.get(i)).name());
            mv.put("from", i + 1);
            mv.put("to", p + 1);
            mv.put("delta", Math.abs(p - i));
            moves.add(mv);
         }
      }
      moves.sort((a, b) -> Integer.compare((Integer) b.get("delta"), (Integer) a.get("delta")));
      Map<String, Object> res = new LinkedHashMap<>();
      res.put("mode", cats ? "categories" : "fix");
      res.put("order", result);
      res.put("changed", moves.size());
      res.put("moves", moves.size() > 60 ? moves.subList(0, 60) : moves);
      res.put("warnings", warnings);
      res.put("cycles", cycles);
      if (cats) {
         res.put("categories", catOf);
      }
      return res;
   }

   static final String[] CATEGORY_NAMES = {
      "Библиотеки / API", "Ресурсы / текстуры", "Карты", "Транспорт", "Только код", "Одежда", "Интерфейс", "Прочее", "Переводы"
   };
   private static final Pattern RE_FRAMEWORK = Pattern.compile("(?i)(lib|framework|api|core|tweak|sorter|manager|buddy|utils?)");
   private static final Pattern RE_LOCAL = Pattern.compile("(?i)(translat|locali[sz]|russian|locale|lang)");
   private static final Pattern RE_VEHICLE = Pattern.compile("(?i)(vehicle|truck|car)");
   private static final Pattern RE_CLOTH = Pattern.compile("(?i)(cloth|outfit|wear|backpack|armou?r)");
   private static final Pattern RE_UI = Pattern.compile("(?i)(\\bui\\b|ui$|^ui|hud|interface|menu|minimap|legend|craft)");

   private Path modRoot(Mods.ModInfo m) {
      if (m.local()) {
         return this.cfg.zomboidDir().resolve("mods").resolve(m.folder());
      }
      for (Path wd : this.cfg.workshopDirs()) {
         Path p = wd.resolve(m.workshopId()).resolve("mods").resolve(m.folder());
         if (Files.isDirectory(p)) {
            return p;
         }
      }
      return null;
   }

   private static boolean any(Path root, String ver, String... subs) {
      for (String base : new String[]{ver, "common"}) {
         if (base == null || base.isEmpty()) {
            continue;
         }
         for (String s : subs) {
            if (Files.exists(root.resolve(base).resolve(s))) {
               return true;
            }
         }
      }
      return false;
   }

   private int category(String id, Mods.ModInfo m, boolean requiredByOthers) {
      String text = id + " " + (m == null ? "" : m.name());
      Path root = m == null ? null : modRoot(m);
      String ver = m == null ? "" : m.versionDir();
      boolean media = root != null && any(root, ver, "media");
      boolean lua = root != null && any(root, ver, "media/lua");
      boolean maps = root != null && any(root, ver, "media/maps");
      boolean vehicles = root != null && any(root, ver, "media/scripts/vehicles", "media/models_X/Vehicles", "media/textures/Vehicles");
      boolean clothing = root != null && any(root, ver, "media/clothing", "media/scripts/clothing", "media/textures/Clothes", "media/textures/Clothing");
      boolean resources = root != null && any(root, ver, "media/textures", "media/texturepacks", "media/models_X", "media/sound", "media/ui");
      boolean translateOnly = lua && !any(root, ver, "media/lua/client", "media/lua/server") && any(root, ver, "media/lua/shared/Translate");
      if (requiredByOthers || RE_FRAMEWORK.matcher(text).find()) {
         return 0;
      }
      if (RE_LOCAL.matcher(text).find() || translateOnly) {
         return 8;
      }
      if (maps) {
         return 2;
      }
      if (vehicles || RE_VEHICLE.matcher(id).find()) {
         return 3;
      }
      if (clothing || RE_CLOTH.matcher(id).find()) {
         return 5;
      }
      if (RE_UI.matcher(text).find()) {
         return 6;
      }
      if (resources && !lua) {
         return 1;
      }
      if (lua && !resources) {
         return 4;
      }
      return 7;
   }

   // =====================================================================================================
   // Архивы замороженных модов
   // =====================================================================================================

   Path archiveDir() {
      return this.cfg.panelDir.resolve("mod_archives");
   }

   private int ttlHours() {
      try {
         return Math.max(1, Integer.parseInt(this.cfg.get("modArchiveTtlHours", "24").trim()));
      } catch (NumberFormatException e) {
         return 24;
      }
   }

   private Path modsDir() {
      return this.cfg.zomboidDir().resolve("mods");
   }

   /** Замороженные моды: из _frozen_manifest.txt (предмет → папки) + любые другие папки модов в Zomboid/mods. */
   private List<Map<String, Object>> frozenItems() throws IOException {
      Path md = modsDir();
      List<Map<String, Object>> items = new ArrayList<>();
      if (!Files.isDirectory(md)) {
         return items;
      }
      Map<String, List<Mods.ModInfo>> scan = this.mods.scan();
      Map<String, List<Mods.ModInfo>> byFolder = new HashMap<>();
      for (List<Mods.ModInfo> l : scan.values()) {
         for (Mods.ModInfo m : l) {
            if (m.local()) {
               byFolder.computeIfAbsent(m.folder(), k -> new ArrayList<>()).add(m);
            }
         }
      }
      Set<String> used = new HashSet<>();
      Path mf = md.resolve("_frozen_manifest.txt");
      if (Files.exists(mf)) {
         for (String l : Util.readText(mf).split("\r?\n")) {
            if (l.startsWith("#")) {
               continue;
            }
            String[] c = l.split("\t");
            if (c.length < 2 || c[0].isBlank()) {
               continue;
            }
            List<String> folders = new ArrayList<>();
            for (String f : c[1].split(",")) {
               if (!f.isBlank() && Files.isDirectory(md.resolve(f.trim())) && used.add(f.trim())) {
                  folders.add(f.trim());
               }
            }
            if (!folders.isEmpty()) {
               items.add(item(c[0].trim(), folders, byFolder));
            }
         }
      }
      try (Stream<Path> s = Files.list(md)) {
         for (Path p : (Iterable<Path>) s.sorted()::iterator) {
            String f = p.getFileName().toString();
            if (Files.isDirectory(p) && !f.startsWith("_") && !f.startsWith(".") && !used.contains(f) && byFolder.containsKey(f)) {
               items.add(item("", List.of(f), byFolder));
            }
         }
      }
      return items;
   }

   private Map<String, Object> item(String wid, List<String> folders, Map<String, List<Mods.ModInfo>> byFolder) throws IOException {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", wid.isEmpty() ? "folder:" + folders.get(0) : wid);
      m.put("workshopId", wid);
      m.put("folders", folders);
      List<Map<String, Object>> list = new ArrayList<>();
      long size = 0;
      boolean java = false;
      for (String f : folders) {
         for (Mods.ModInfo mi : byFolder.getOrDefault(f, List.of())) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", mi.id());
            x.put("name", mi.name());
            list.add(x);
            java |= mi.java();
         }
         size += dirSize(modsDir().resolve(f));
      }
      m.put("mods", list);
      m.put("title", list.isEmpty() ? folders.get(0) : String.valueOf(list.get(0).get("name")));
      m.put("size", size);
      m.put("java", java);
      return m;
   }

   private static long dirSize(Path d) throws IOException {
      AtomicLong n = new AtomicLong();
      try (Stream<Path> s = Files.walk(d)) {
         s.filter(Files::isRegularFile).forEach(p -> {
            try {
               n.addAndGet(Files.size(p));
            } catch (IOException ignored) {
            }
         });
      }
      return n.get();
   }

   Map<String, Object> archivesState() throws IOException {
      sweep();
      Map<String, Object> res = new LinkedHashMap<>();
      res.put("items", frozenItems());
      res.put("archives", archiveList());
      res.put("singleTtlHours", ttlHours());
      res.put("running", jobs.values().stream().anyMatch(j -> "running".equals(j.state)));
      return res;
   }

   private List<Map<String, Object>> archiveList() throws IOException {
      List<Map<String, Object>> list = new ArrayList<>();
      Path d = archiveDir();
      if (!Files.isDirectory(d)) {
         return list;
      }
      long ttl = ttlHours() * 3600_000L;
      try (Stream<Path> s = Files.list(d)) {
         for (Path p : (Iterable<Path>) s.sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())::iterator) {
            String n = p.getFileName().toString();
            if (!ARCHIVE_NAME.matcher(n).matches()) {
               continue;
            }
            Map<String, Object> a = new LinkedHashMap<>();
            boolean full = n.startsWith(FULL_PREFIX);
            a.put("name", n);
            a.put("kind", full ? "full" : "single");
            a.put("size", Files.size(p));
            long t = p.toFile().lastModified();
            a.put("time", t);
            a.put("expires", full ? 0 : t + ttl);
            list.add(a);
         }
      }
      return list;
   }

   Path archiveFile(String name) {
      if (name == null || !ARCHIVE_NAME.matcher(name).matches()) {
         return null;
      }
      Path p = archiveDir().resolve(name);
      return Files.isRegularFile(p) ? p : null;
   }

   void deleteArchive(String name) throws IOException {
      if (name == null || !ARCHIVE_NAME.matcher(name).matches()) {
         throw new IOException("Недопустимое имя архива");
      }
      Files.deleteIfExists(archiveDir().resolve(name));
   }

   /** Политика хранения: полный архив ровно один (самый свежий), архивы одного мода живут modArchiveTtlHours (24 ч), «битые» .part — час. */
   void sweep() throws IOException {
      Path d = archiveDir();
      if (!Files.isDirectory(d)) {
         return;
      }
      long now = System.currentTimeMillis(), ttl = ttlHours() * 3600_000L;
      List<Path> fulls = new ArrayList<>();
      try (Stream<Path> s = Files.list(d)) {
         for (Path p : (Iterable<Path>) s::iterator) {
            String n = p.getFileName().toString();
            long age = now - p.toFile().lastModified();
            try {
               if (n.endsWith(".part")) {
                  if (age > 3600_000L && jobs.values().stream().noneMatch(j -> "running".equals(j.state))) {
                     Files.deleteIfExists(p);
                  }
               } else if (ARCHIVE_NAME.matcher(n).matches()) {
                  if (n.startsWith(FULL_PREFIX)) {
                     fulls.add(p);
                  } else if (age > ttl) {
                     Files.deleteIfExists(p);
                  }
               }
            } catch (IOException ignored) {
               // на Windows файл может быть занят скачиванием — удалим в следующий раз
            }
         }
      }
      fulls.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
      for (int i = 1; i < fulls.size(); i++) {
         try {
            Files.deleteIfExists(fulls.get(i));
         } catch (IOException ignored) {
         }
      }
   }

   static final class Job {
      volatile String state = "running";
      volatile long done, total;
      volatile String name = "", error = "", message = "";
      volatile long size;
   }

   /** scope=one + key (ID предмета или folder:Имя) либо scope=all. Возвращает id задачи. */
   String startPack(String scope, String key) throws IOException {
      if (jobs.values().stream().anyMatch(j -> "running".equals(j.state))) {
         throw new IOException("Архив уже собирается — дождитесь окончания");
      }
      List<Map<String, Object>> items = frozenItems();
      List<String> folders = new ArrayList<>();
      String label;
      boolean full = "all".equals(scope);
      if (full) {
         try (Stream<Path> s = Files.list(modsDir())) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
               String f = p.getFileName().toString();
               if (Files.isDirectory(p) && !f.startsWith("_") && !f.startsWith(".")) {
                  folders.add(f);
               }
            }
         }
         if (folders.isEmpty()) {
            throw new IOException("В Zomboid/mods нет модов для архива");
         }
         label = "all";
      } else {
         Map<String, Object> it = null;
         for (Map<String, Object> x : items) {
            if (String.valueOf(x.get("key")).equals(key)) {
               it = x;
            }
         }
         if (it == null) {
            throw new IOException("Замороженный мод не найден: " + key);
         }
         @SuppressWarnings("unchecked")
         List<String> f = (List<String>) it.get("folders");
         folders.addAll(f);
         label = safe(String.valueOf(it.get("title")));
      }
      Job job = new Job();
      String id = Long.toHexString(System.nanoTime());
      jobs.put(id, job);
      while (jobs.size() > 20) {
         jobs.keySet().stream().filter(k -> !"running".equals(jobs.get(k).state)).findFirst().ifPresentOrElse(jobs::remove, () -> jobs.remove(jobs.keySet().iterator().next()));
      }
      Thread t = new Thread(() -> {
         try {
            synchronized (packLock) {
               pack(job, full, label, folders);
            }
         } catch (Throwable e) {
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            job.state = "error";
         }
      }, "pz-modpack");
      t.setDaemon(true);
      t.start();
      return id;
   }

   Map<String, Object> job(String id) {
      Job j = jobs.get(id);
      Map<String, Object> m = new LinkedHashMap<>();
      if (j == null) {
         m.put("state", "unknown");
         return m;
      }
      m.put("state", j.state);
      m.put("done", j.done);
      m.put("total", j.total);
      m.put("name", j.name);
      m.put("size", j.size);
      m.put("error", j.error);
      return m;
   }

   private static String safe(String s) {
      String r = s.replaceAll("[^A-Za-z0-9_\\-]+", "_").replaceAll("^_+|_+$", "");
      if (r.length() > 40) {
         r = r.substring(0, 40);
      }
      return r.isEmpty() ? "mod" : r;
   }

   private void pack(Job job, boolean full, String label, List<String> folders) throws IOException {
      Path dir = archiveDir();
      Files.createDirectories(dir);
      sweep();
      Path md = modsDir();
      Path newestSrc = null;
      long newest = 0;
      long total = 0;
      for (String f : folders) {
         total += dirSize(md.resolve(f));
         try (Stream<Path> s = Files.walk(md.resolve(f))) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
               long t = p.toFile().lastModified();
               if (t > newest) {
                  newest = t;
                  newestSrc = p;
               }
            }
         }
      }
      job.total = total;
      String stamp = LocalDateTime.now().format(STAMP);
      String name = (full ? FULL_PREFIX : ONE_PREFIX + label + "_") + stamp + ".zip";
      if (!full) { // свежий архив того же мода уже есть — отдаём его
         try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
               String n = p.getFileName().toString();
               if (n.startsWith(ONE_PREFIX + label + "_") && ARCHIVE_NAME.matcher(n).matches() && p.toFile().lastModified() > newest) {
                  job.name = n;
                  job.size = Files.size(p);
                  job.done = total;
                  job.state = "done";
                  return;
               }
            }
         }
      }
      job.name = name;
      Path part = dir.resolve(name + ".part");
      Path target = dir.resolve(name);
      try (OutputStream os = Files.newOutputStream(part); ZipOutputStream z = new ZipOutputStream(os)) {
         z.setLevel(Deflater.BEST_SPEED); // текстуры и jar почти не сжимаются — скорость важнее
         String ini = Configs.iniValue(this.cfg.serverIni(), "Mods");
         putText(z, "_README_install.txt", readme(full, folders));
         if (full) {
            Path mf = md.resolve("_frozen_manifest.txt");
            if (Files.exists(mf)) {
               putFile(z, mf, "_frozen_manifest.txt", job);
            }
            Path fw = md.resolve("_frozen_workshop_items.txt");
            if (Files.exists(fw)) {
               putFile(z, fw, "_frozen_workshop_items.txt", job);
            }
            if (!ini.isBlank()) {
               StringBuilder sb = new StringBuilder("VERSION = 1,\r\n\r\nmods\r\n{\r\n");
               for (String m : Mods.splitList(ini)) {
                  sb.append("    mod = ").append(strip(m)).append(",\r\n");
               }
               sb.append("}\r\n\r\nmaps\r\n{\r\n}\r\n");
               putText(z, "default.txt", sb.toString());
            }
         }
         for (String f : folders) {
            Path root = md.resolve(f);
            try (Stream<Path> s = Files.walk(root)) {
               for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                  putFile(z, p, f + "/" + root.relativize(p).toString().replace('\\', '/'), job);
               }
            }
         }
      } catch (IOException | RuntimeException e) {
         Files.deleteIfExists(part);
         throw e;
      }
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
      job.size = Files.size(target);
      job.done = job.total;
      job.state = "done";
      sweep(); // у полного архива предыдущий удаляется, одиночные — по сроку
   }

   private String readme(boolean full, List<String> folders) {
      StringBuilder sb = new StringBuilder();
      sb.append(full ? "МОДЫ СЕРВЕРА — все замороженные моды (" + folders.size() + " папок)\r\n\r\n" : "МОД С СЕРВЕРА (замороженная версия)\r\n\r\n");
      sb.append("Установка:\r\n");
      sb.append("1. Закройте игру.\r\n");
      sb.append("2. Откройте папку %USERPROFILE%\\Zomboid\\mods (Win+R, вставить путь, Enter).\r\n");
      sb.append(full
         ? "3. Распакуйте архив сюда так, чтобы получилось Zomboid\\mods\\<папка мода> (не вложенная папка с названием архива).\r\n   Старые папки модов из прошлых архивов лучше удалить. default.txt — список включённых модов как на сервере (свой сохраните заранее).\r\n"
         : "3. Распакуйте архив сюда: должно получиться Zomboid\\mods\\" + folders.get(0) + " (не вложенная папка с названием архива).\r\n4. Включите мод в главном меню (Mods) и добавьте в список так, как он стоит на сервере.\r\n");
      sb.append("5. Параметры запуска Steam: -modfolders mods\r\n");
      sb.append("6. Для Java-модов нужен ZombieBuddy (Install → Both); при первом запуске на каждый Java-мод: «Да» + «Запомнить».\r\n\r\n");
      sb.append("Папки в архиве: ").append(String.join(", ", folders)).append("\r\n");
      sb.append("Собрано: ").append(Instant.now()).append("\r\n");
      return sb.toString();
   }

   private static void putText(ZipOutputStream z, String name, String text) throws IOException {
      z.putNextEntry(new ZipEntry(name));
      z.write(("﻿" + text).getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
   }

   private static void putFile(ZipOutputStream z, Path p, String name, Job job) throws IOException {
      ZipEntry e = new ZipEntry(name);
      e.setTime(p.toFile().lastModified());
      z.putNextEntry(e);
      byte[] buf = new byte[1 << 16];
      try (InputStream in = Files.newInputStream(p)) {
         int r;
         while ((r = in.read(buf)) > 0) {
            z.write(buf, 0, r);
            job.done += r;
         }
      }
      z.closeEntry();
   }
}
