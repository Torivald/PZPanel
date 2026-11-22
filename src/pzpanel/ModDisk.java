package pzpanel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Моды на диске сервера: список с размерами, удаление выбранных (Zomboid/mods и скачанные сервером предметы Мастерской)
 * и прежние версии из Zomboid/mods_replaced (их туда кладёт загрузка мода архивом): удалить, вернуть, автоочистка.
 */
final class ModDisk {
   private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

   private final Config cfg;
   private final Mods mods;
   private final ServerManager srv;

   ModDisk(Config cfg, Mods mods, ServerManager srv) {
      this.cfg = cfg;
      this.mods = mods;
      this.srv = srv;
   }

   private Path modsDir() {
      return this.cfg.zomboidDir().resolve("mods");
   }

   private Path replacedDir() {
      return this.cfg.zomboidDir().resolve("mods_replaced");
   }

   /** Мастерская самого сервера (её панель может чистить; Мастерскую Steam-клиента игры не трогаем). */
   private Path serverWorkshop() {
      return this.cfg.serverDir().resolve("steamapps").resolve("workshop").resolve("content").resolve("108600");
   }

   private int keepDays() {
      try {
         return Math.max(0, Integer.parseInt(this.cfg.get("modReplacedKeepDays", "30").trim()));
      } catch (NumberFormatException e) {
         return 30;
      }
   }

   private void requireStopped(String what) throws IOException {
      if (this.srv.isRunning()) {
         throw new IOException("Остановите сервер: " + what + " можно только при остановленном сервере");
      }
   }

   private static String safeName(String s) throws IOException {
      if (s == null || s.isBlank() || s.contains("/") || s.contains("\\") || s.contains("..") || s.startsWith(".")) {
         throw new IOException("Неверное имя: " + s);
      }
      return s;
   }

   // ------------------------------------------------------------------ список

   Map<String, Object> state() throws IOException {
      this.sweepReplaced();
      Map<String, List<Mods.ModInfo>> scan = this.mods.scan();
      Map<String, List<Mods.ModInfo>> localByFolder = new LinkedHashMap<>();
      Map<String, List<Mods.ModInfo>> wsById = new LinkedHashMap<>();
      for (List<Mods.ModInfo> l : scan.values()) {
         for (Mods.ModInfo m : l) {
            if (m.local()) {
               localByFolder.computeIfAbsent(m.folder(), k -> new ArrayList<>()).add(m);
            } else if (!m.workshopId().isEmpty()) {
               wsById.computeIfAbsent(m.workshopId(), k -> new ArrayList<>()).add(m);
            }
         }
      }
      Set<String> enabled = this.enabledIds();
      List<Map<String, Object>> local = new ArrayList<>();
      Path md = this.modsDir();
      if (Files.isDirectory(md)) {
         try (Stream<Path> s = Files.list(md)) {
            for (Path p : s.filter(Files::isDirectory).sorted(Comparator.comparing(x -> x.getFileName().toString().toLowerCase())).toList()) {
               String f = p.getFileName().toString();
               if (f.startsWith(".") || f.startsWith("_")) {
                  continue;
               }
               List<Mods.ModInfo> ml = localByFolder.getOrDefault(f, List.of());
               local.add(this.entry("folder", f, ml, p, enabled, ml.isEmpty() ? "" : ml.get(0).workshopId()));
            }
         }
      }
      List<Map<String, Object>> ws = new ArrayList<>();
      Path wd = this.serverWorkshop();
      if (Files.isDirectory(wd)) {
         try (Stream<Path> s = Files.list(wd)) {
            for (Path p : s.filter(Files::isDirectory).sorted().toList()) {
               String id = p.getFileName().toString();
               if (!id.matches("\\d+")) {
                  continue;
               }
               ws.add(this.entry("workshop", id, wsById.getOrDefault(id, List.of()), p, enabled, id));
            }
         }
      }
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("local", local);
      r.put("workshop", ws);
      r.put("workshopDir", wd.toString());
      r.put("replaced", this.replacedList());
      r.put("replacedDir", this.replacedDir().toString());
      r.put("keepDays", this.keepDays());
      r.put("serverRunning", this.srv.isRunning());
      return r;
   }

   private Map<String, Object> entry(String kind, String key, List<Mods.ModInfo> ml, Path dir, Set<String> enabled, String wid) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("kind", kind);
      m.put("key", key);
      List<String> ids = new ArrayList<>();
      List<String> names = new ArrayList<>();
      boolean on = false;
      for (Mods.ModInfo mi : ml) {
         if (!ids.contains(mi.id())) {
            ids.add(mi.id());
            names.add(mi.name());
            on |= enabled.contains(mi.id());
         }
      }
      m.put("ids", ids);
      m.put("names", names);
      m.put("enabled", on);
      m.put("workshopId", wid);
      m.put("size", Util.dirSize(dir));
      return m;
   }

   private Set<String> enabledIds() {
      Set<String> r = new HashSet<>();
      try {
         for (String m : Mods.splitList(Configs.iniValue(this.cfg.serverIni(), "Mods"))) {
            r.add(m.replaceFirst("^\\\\+", ""));
         }
      } catch (Exception e) {
      }
      return r;
   }

   // ------------------------------------------------------------------ удаление модов с диска

   /**
    * Удаляет выбранные папки Zomboid/mods и скачанные сервером предметы Мастерской. cleanIni — убрать их моды из Mods=
    * (если у мода не осталось другой копии) и предметы из WorkshopItems= активного мира.
    */
   Map<String, Object> delete(List<String> folders, List<String> workshopIds, boolean cleanIni) throws IOException {
      this.requireStopped("удалять моды");
      if (folders.isEmpty() && workshopIds.isEmpty()) {
         throw new IOException("Ничего не выбрано");
      }
      Map<String, List<Mods.ModInfo>> scan = this.mods.scan();
      Set<String> goneIds = new LinkedHashSet<>();
      Set<String> delFolders = new LinkedHashSet<>();
      for (String f : folders) {
         delFolders.add(safeName(f));
      }
      Set<String> delWs = new LinkedHashSet<>();
      for (String w : workshopIds) {
         if (!w.matches("\\d+")) {
            throw new IOException("Неверный ID предмета: " + w);
         }
         delWs.add(w);
      }
      // какие id модов пропадут совсем: все их копии удаляются
      for (Map.Entry<String, List<Mods.ModInfo>> e : scan.entrySet()) {
         boolean anyLeft = false;
         boolean anyGone = false;
         for (Mods.ModInfo mi : e.getValue()) {
            boolean gone = mi.local() ? delFolders.contains(mi.folder()) : delWs.contains(mi.workshopId());
            anyGone |= gone;
            anyLeft |= !gone;
         }
         if (anyGone && !anyLeft) {
            goneIds.add(e.getKey());
         }
      }
      long freed = 0;
      List<String> done = new ArrayList<>();
      List<String> failed = new ArrayList<>();
      for (String f : delFolders) {
         Path p = this.modsDir().resolve(f);
         if (!Files.isDirectory(p)) {
            continue;
         }
         long sz = Util.dirSize(p);
         try {
            Util.deleteTree(p);
            freed += sz;
            done.add(f);
         } catch (IOException ex) {
            failed.add(f + " (" + ex.getMessage() + ")");
         }
      }
      for (String w : delWs) {
         Path p = this.serverWorkshop().resolve(w);
         if (!Files.isDirectory(p)) {
            continue;
         }
         long sz = Util.dirSize(p);
         try {
            Util.deleteTree(p);
            freed += sz;
            done.add(w);
         } catch (IOException ex) {
            failed.add(w + " (" + ex.getMessage() + ")");
         }
      }
      this.cleanManifest(delFolders);
      String iniNote = "";
      if (cleanIni) {
         Path ini = this.cfg.serverIni();
         List<String> modsList = new ArrayList<>(Mods.splitList(Configs.iniValue(ini, "Mods")));
         List<String> ws = new ArrayList<>(Mods.splitList(Configs.iniValue(ini, "WorkshopItems")));
         int before = modsList.size() + ws.size();
         modsList.removeIf(m -> goneIds.contains(m.replaceFirst("^\\\\+", "")));
         ws.removeIf(delWs::contains);
         int removed = before - modsList.size() - ws.size();
         if (removed > 0) {
            this.mods.save(modsList, ws, false);
            iniNote = " Из настроек мира убрано записей: " + removed + ".";
         }
      }
      this.srv.markConfigChanged();
      Map<String, Object> r = new LinkedHashMap<>();
      String msg = "Удалено: " + done.size() + " (" + (freed / 1048576L) + " МБ)." + iniNote;
      if (!failed.isEmpty()) {
         msg += " Не удалось удалить: " + String.join("; ", failed) + ".";
      }
      r.put("message", msg);
      r.put("deleted", done);
      r.put("failed", failed);
      return r;
   }

   /** Убирает удалённые папки из _frozen_manifest.txt и пропавшие предметы из _frozen_workshop_items.txt. */
   private void cleanManifest(Set<String> folders) throws IOException {
      Path mf = this.modsDir().resolve("_frozen_manifest.txt");
      if (folders.isEmpty() || !Files.exists(mf)) {
         return;
      }
      List<String> out = new ArrayList<>();
      Set<String> keptWids = new HashSet<>();
      boolean changed = false;
      for (String l : Util.readText(mf).split("\r?\n")) {
         if (l.isEmpty()) {
            continue;
         }
         String[] c = l.split("\t", -1);
         if (l.startsWith("#") || c.length < 2) {
            out.add(l);
            continue;
         }
         List<String> rest = new ArrayList<>();
         for (String f : c[1].split(",")) {
            if (!f.isBlank() && !folders.contains(f.trim())) {
               rest.add(f.trim());
            }
         }
         if (rest.size() != c[1].split(",").length) {
            changed = true;
         }
         if (!rest.isEmpty()) {
            c[1] = String.join(",", rest);
            out.add(String.join("\t", c));
            keptWids.add(c[0].trim());
         }
      }
      if (!changed) {
         return;
      }
      Util.writeTextWithBackup(mf, String.join("\n", out) + "\n", this.cfg.fileBackupDir());
      Path fl = this.modsDir().resolve("_frozen_workshop_items.txt");
      if (Files.exists(fl)) {
         List<String> keep = new ArrayList<>();
         for (String l : Util.readText(fl).split("\r?\n")) {
            if (l.trim().matches("\\d+") && keptWids.contains(l.trim())) {
               keep.add(l.trim());
            }
         }
         Files.writeString(fl, String.join("\n", keep) + (keep.isEmpty() ? "" : "\n"));
      }
   }

   // ------------------------------------------------------------------ прежние версии (mods_replaced)

   private List<Map<String, Object>> replacedList() throws IOException {
      List<Map<String, Object>> r = new ArrayList<>();
      Path rd = this.replacedDir();
      if (!Files.isDirectory(rd)) {
         return r;
      }
      try (Stream<Path> s = Files.list(rd)) {
         for (Path st : s.filter(Files::isDirectory).sorted(Comparator.comparing((Path x) -> x.getFileName().toString()).reversed()).toList()) {
            try (Stream<Path> f = Files.list(st)) {
               for (Path p : f.filter(Files::isDirectory).sorted().toList()) {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("stamp", st.getFileName().toString());
                  m.put("folder", p.getFileName().toString());
                  m.put("size", Util.dirSize(p));
                  m.put("time", st.toFile().lastModified());
                  m.put("current", Files.isDirectory(this.modsDir().resolve(p.getFileName().toString())));
                  r.add(m);
               }
            }
         }
      }
      return r;
   }

   /** Автоочистка: прежние версии старше modReplacedKeepDays дней (0 — не удалять). */
   void sweepReplaced() {
      int days = this.keepDays();
      Path rd = this.replacedDir();
      if (days <= 0 || !Files.isDirectory(rd)) {
         return;
      }
      long limit = System.currentTimeMillis() - days * 86_400_000L;
      try (Stream<Path> s = Files.list(rd)) {
         for (Path st : s.filter(Files::isDirectory).toList()) {
            if (st.toFile().lastModified() < limit) {
               Util.deleteTree(st);
            }
         }
      } catch (IOException ignored) {
      }
   }

   /** Удалить прежнюю версию (folder пуст — все папки этой даты). */
   String deleteReplaced(String stamp, String folder) throws IOException {
      Path st = this.replacedDir().resolve(safeName(stamp));
      Path p = folder == null || folder.isBlank() ? st : st.resolve(safeName(folder));
      if (!Files.exists(p)) {
         throw new IOException("Уже удалено");
      }
      long sz = Util.dirSize(p);
      Util.deleteTree(p);
      removeIfEmpty(st);
      return "Прежняя версия удалена (" + sz / 1048576L + " МБ)";
   }

   /** Вернуть прежнюю версию: текущая папка уходит в mods_replaced (новая дата), прежняя — на её место. */
   String restoreReplaced(String stamp, String folder) throws IOException {
      this.requireStopped("возвращать прежнюю версию мода");
      Path st = this.replacedDir().resolve(safeName(stamp));
      Path old = st.resolve(safeName(folder));
      if (!Files.isDirectory(old)) {
         throw new IOException("Нет прежней версии " + folder + " от " + stamp);
      }
      Path cur = this.modsDir().resolve(folder);
      String note = "";
      if (Files.exists(cur)) {
         String now = LocalDateTime.now().format(STAMP);
         Path dst = this.replacedDir().resolve(now);
         Files.createDirectories(dst);
         move(cur, dst.resolve(folder));
         note = " Текущая версия перенесена в mods_replaced/" + now + ".";
      }
      Files.createDirectories(this.modsDir());
      move(old, cur);
      removeIfEmpty(st);
      this.srv.markConfigChanged();
      return "Возвращена версия " + folder + " от " + stamp + "." + note + " Нужен перезапуск сервера; игрокам — новый архив модов.";
   }

   private static void removeIfEmpty(Path d) throws IOException {
      if (Files.isDirectory(d)) {
         try (Stream<Path> s = Files.list(d)) {
            if (s.findAny().isEmpty()) {
               Files.delete(d);
            }
         }
      }
   }

   private static void move(Path from, Path to) throws IOException {
      try {
         Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
         Files.move(from, to);
      }
   }
}
