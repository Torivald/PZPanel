package pzpanel;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Миры сервера. Мир — это имя сервера (-servername), под которым лежат:
 * Zomboid/Server/&lt;имя&gt;.ini, &lt;имя&gt;_SandboxVars.lua, &lt;имя&gt;_spawnpoints.lua, &lt;имя&gt;_spawnregions.lua,
 * сохранение Zomboid/Saves/Multiplayer/&lt;имя&gt;/ и база аккаунтов Zomboid/db/&lt;имя&gt;.db.
 * Активный мир — serverName в panel.properties; его же панель прописывает в -servername скрипта запуска.
 */
final class Worlds {
   private static final Pattern SERVERNAME = Pattern.compile("(-servername\\s+)(\"[^\"]*\"|\\S+)");
   private static final long SIZE_TTL = 10 * 60 * 1000L;

   private final Config cfg;
   private final ServerManager srv;
   private final Backups backups;
   private final Runnable onActiveChanged;
   private final Map<String, long[]> sizes = new ConcurrentHashMap<>();

   Worlds(Config cfg, ServerManager srv, Backups backups, Runnable onActiveChanged) {
      this.cfg = cfg;
      this.srv = srv;
      this.backups = backups;
      this.onActiveChanged = onActiveChanged;
   }

   // ------------------------------------------------------------------ список

   Map<String, Object> list() throws IOException {
      String active = this.cfg.serverName();
      String running = this.srv.runningWorld();
      TreeSet<String> withIni = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      Path conf = this.cfg.serverConfDir();
      if (Files.isDirectory(conf)) {
         try (DirectoryStream<Path> ds = Files.newDirectoryStream(conf, "*.ini")) {
            for (Path p : ds) {
               String n = p.getFileName().toString();
               withIni.add(n.substring(0, n.length() - 4));
            }
         }
      }
      TreeSet<String> saves = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      Path mp = this.cfg.zomboidDir().resolve("Saves").resolve("Multiplayer");
      if (Files.isDirectory(mp)) {
         try (DirectoryStream<Path> ds = Files.newDirectoryStream(mp, Files::isDirectory)) {
            for (Path p : ds) {
               saves.add(p.getFileName().toString());
            }
         }
      }
      Map<String, Integer> backupsCount = new LinkedHashMap<>();
      for (Map<String, Object> b : this.backups.list()) {
         backupsCount.merge(String.valueOf(b.get("world")), 1, Integer::sum);
      }

      List<Map<String, Object>> worlds = new ArrayList<>();
      TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      names.addAll(withIni);
      names.add(active);
      for (String n : names) {
         worlds.add(this.describe(n, active, running, backupsCount.getOrDefault(n, 0)));
      }
      // Сохранения без конфигов: переименованные вручную старые миры и клиентские сохранения (IP_порт_…)
      List<Map<String, Object>> orphans = new ArrayList<>();
      for (String n : saves) {
         if (names.contains(n)) {
            continue;
         }
         Map<String, Object> o = new LinkedHashMap<>();
         Path dir = this.cfg.worldSaveDir(n);
         o.put("name", n);
         o.put("client", n.matches(".*_\\d+_[0-9a-f]{16,}"));
         o.put("validName", Backups.NAME_RE.matcher(n).matches());
         o.put("size", this.size(n));
         o.put("modified", mtime(dir));
         orphans.add(o);
      }

      Map<String, Object> r = new LinkedHashMap<>();
      r.put("active", active);
      r.put("running", running);
      r.put("busy", this.backups.busy());
      r.put("progress", this.backups.progress());
      r.put("worlds", worlds);
      r.put("orphans", orphans);
      r.put("serverDir", this.cfg.zomboidDir().resolve("Server").toString());
      r.put("savesDir", mp.toString());
      r.put("startScript", this.cfg.serverDir().resolve(this.cfg.get("startScript")).toString());
      r.put("scriptWorld", this.scriptWorld());
      return r;
   }

   private Map<String, Object> describe(String n, String active, String running, int backupsCount) {
      Map<String, Object> w = new LinkedHashMap<>();
      Path ini = this.cfg.serverConfDir().resolve(n + ".ini");
      Path save = this.cfg.worldSaveDir(n);
      w.put("name", n);
      w.put("active", n.equals(active));
      w.put("running", n.equals(running));
      w.put("validName", Backups.NAME_RE.matcher(n).matches());
      w.put("hasIni", Files.exists(ini));
      w.put("hasSandbox", Files.exists(this.cfg.serverConfDir().resolve(n + "_SandboxVars.lua")));
      w.put("hasSave", Files.isDirectory(save));
      w.put("hasAccounts", Files.exists(this.cfg.worldAccountsDb(n)));
      w.put("size", Files.isDirectory(save) ? this.size(n) : 0L);
      w.put("lastSave", mtime(save.resolve("players.db")));
      w.put("modified", Files.isDirectory(save) ? mtime(save) : mtime(ini));
      w.put("backups", backupsCount);
      if (Files.exists(ini)) {
         w.put("publicName", Configs.iniValue(ini, "PublicName"));
         w.put("map", Configs.iniValue(ini, "Map"));
         w.put("mods", Mods.splitList(Configs.iniValue(ini, "Mods")).size());
         w.put("maxPlayers", Configs.iniValue(ini, "MaxPlayers"));
         w.put("pvp", Configs.iniValue(ini, "PVP"));
         w.put("port", Configs.iniValue(ini, "DefaultPort"));
         w.put("isPublic", Configs.iniValue(ini, "Public"));
         w.put("password", !Configs.iniValue(ini, "Password").isEmpty());
      }
      return w;
   }

   private static long mtime(Path p) {
      try {
         return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
      } catch (IOException e) {
         return 0L;
      }
   }

   /** Размер сохранения: считается в фоне и кэшируется на 10 минут; пока не посчитан — -1. */
   private long size(String n) {
      long[] c = this.sizes.get(n);
      long now = System.currentTimeMillis();
      if (c == null || now - c[1] > SIZE_TTL) {
         long[] fresh = new long[]{c == null ? -1L : c[0], now};
         this.sizes.put(n, fresh);
         CompletableFuture.runAsync(() -> fresh[0] = Util.dirSize(this.cfg.worldSaveDir(n)));
         return fresh[0];
      }
      return c[0];
   }

   // ------------------------------------------------------------------ проверки

   private static String checkName(String n) throws IOException {
      n = n == null ? "" : n.trim();
      if (!Backups.NAME_RE.matcher(n).matches()) {
         throw new IOException("Имя мира: латиница, цифры, _ и -, до 48 символов");
      }
      return n;
   }

   /** Мир существует, если у него есть ini, сохранение или это активный мир. */
   private boolean exists(String n) {
      return Files.exists(this.cfg.serverConfDir().resolve(n + ".ini")) || Files.isDirectory(this.cfg.worldSaveDir(n));
   }

   private void requireNotRunning(String n, String what) throws IOException {
      String r = this.srv.runningWorld();
      if (r != null && r.equalsIgnoreCase(n)) {
         throw new IOException("Мир " + n + " сейчас запущен — " + what + " можно только при остановленном сервере");
      }
   }

   private void requireIdle() throws IOException {
      if (this.backups.busy()) {
         throw new IOException("Подождите: " + this.backups.progress().substring(6));
      }
   }

   // ------------------------------------------------------------------ скрипт запуска

   private Path startScript() {
      return this.cfg.serverDir().resolve(this.cfg.get("startScript"));
   }

   /** Какое имя прописано в -servername скрипта запуска (пусто — не прописано). */
   String scriptWorld() {
      try {
         Path s = this.startScript();
         if (Files.exists(s)) {
            Matcher m = SERVERNAME.matcher(Util.readText(s));
            if (m.find()) {
               return m.group(2).replace("\"", "");
            }
         }
      } catch (IOException e) {
      }
      return "";
   }

   /** Меняет -servername в скрипте запуска (если он там есть). Прежняя версия — в file_backups. */
   private boolean updateScript(String name) throws IOException {
      Path s = this.startScript();
      if (!Files.exists(s)) {
         return false;
      }
      String text = Util.readText(s);
      Matcher m = SERVERNAME.matcher(text);
      if (!m.find()) {
         return false;
      }
      if (m.group(2).replace("\"", "").equals(name)) {
         return true;
      }
      String updated = m.replaceFirst(Matcher.quoteReplacement(m.group(1) + name));
      Util.writeTextWithBackup(s, updated, this.cfg.fileBackupDir());
      return true;
   }

   /** После ручной смены serverName на вкладке «Панель»: прописать его и в скрипт запуска. */
   void syncScript() throws IOException {
      this.updateScript(this.cfg.serverName());
      this.onActiveChanged.run();
   }

   private String setActive(String name) throws IOException {
      this.cfg.p.setProperty("serverName", name);
      this.cfg.save();
      boolean script = this.updateScript(name);
      this.srv.markConfigChanged();
      this.onActiveChanged.run();
      return script ? " (имя прописано и в " + this.startScript().getFileName() + ")" : "";
   }

   // ------------------------------------------------------------------ операции

   /** Сделать мир активным: панель и скрипт запуска переключаются на него. Сервер должен быть остановлен. */
   String activate(String name) throws IOException {
      name = checkName(name);
      if (this.srv.isRunning()) {
         throw new IOException("Остановите сервер, затем переключайте мир");
      }
      if (!this.exists(name)) {
         throw new IOException("Нет мира " + name);
      }
      this.requireIdle();
      return "Активный мир: " + name + this.setActive(name) + ". Запустите сервер.";
   }

   /**
    * Новый мир.
    * mode=settings — копия конфигов мира from (ini, песочница, точки появления); мир и аккаунты — с нуля;
    * mode=full     — полная копия мира from (конфиги, сохранение, аккаунты);
    * mode=default  — без файлов: при первом запуске сервер создаст настройки по умолчанию.
    * Если у имени уже есть сохранение без конфигов (например, переименованный вручную старый мир),
    * mode=settings просто добавит ему конфиги — так старое сохранение снова становится миром.
    */
   String create(String name, String from, String mode, boolean activate) throws IOException {
      String n = checkName(name);
      this.requireIdle();
      if (Files.exists(this.cfg.serverConfDir().resolve(n + ".ini"))) {
         throw new IOException("Мир " + n + " уже есть");
      }
      for (String w : this.allNames()) {
         if (w.equalsIgnoreCase(n) && !w.equals(n)) {
            throw new IOException("Уже есть мир " + w + " — имена не должны отличаться только регистром");
         }
      }
      boolean saveExists = Files.isDirectory(this.cfg.worldSaveDir(n));
      if (mode.equals("default")) {
         if (!activate) {
            throw new IOException("Мир с настройками по умолчанию появится только после первого запуска — включите «сразу сделать активным»");
         }
         if (this.srv.isRunning()) {
            throw new IOException("Остановите сервер: новый мир станет активным");
         }
         return "Активный мир: " + n + this.setActive(n) + ". При первом запуске сервер создаст настройки по умолчанию.";
      }
      String src = checkName(from);
      if (!Files.exists(this.cfg.serverConfDir().resolve(src + ".ini"))) {
         throw new IOException("Нет конфигов мира " + src);
      }
      if (mode.equals("full")) {
         if (saveExists) {
            throw new IOException("Сохранение " + n + " уже есть — выберите «только настройки» или другое имя");
         }
         this.requireNotRunning(src, "полное копирование");
      } else if (!mode.equals("settings")) {
         throw new IOException("Неизвестный режим " + mode);
      }
      if (activate && this.srv.isRunning()) {
         throw new IOException("Остановите сервер или снимите галочку «сразу сделать активным»");
      }
      boolean full = mode.equals("full");
      return this.backups.runJob("создание мира " + n, () -> {
         this.copyConfigs(src, n, true);
         if (full) {
            Util.copyTree(this.cfg.worldSaveDir(src), this.cfg.worldSaveDir(n), rel -> this.backups.step("копирую " + rel));
            Path db = this.cfg.worldAccountsDb(src);
            if (Files.exists(db)) {
               Files.createDirectories(this.cfg.worldAccountsDb(n).getParent());
               Files.copy(db, this.cfg.worldAccountsDb(n), StandardCopyOption.REPLACE_EXISTING);
            }
         }
         this.sizes.remove(n);
         String act = activate ? "; активный мир: " + n + this.setActive(n) : "";
         String what = full ? "полная копия " + src : saveExists ? "к сохранению " + n + " добавлены настройки из " + src : "настройки из " + src + ", мир с нуля";
         return "мир " + n + " создан (" + what + ")" + act;
      });
   }

   /** Копирует конфиги мира; ссылки на файлы мира внутри (точки появления, PublicName) переводит на новое имя. */
   private void copyConfigs(String src, String dst, boolean inJob) throws IOException {
      Path conf = this.cfg.serverConfDir();
      for (String s : Config.WORLD_SUFFIXES) {
         Path f = conf.resolve(src + s);
         if (!Files.exists(f)) {
            continue;
         }
         if (inJob) {
            this.backups.step("копирую " + f.getFileName());
         }
         String text = Util.readText(f);
         text = text.replace("\"" + src + "_spawnpoints.lua\"", "\"" + dst + "_spawnpoints.lua\"");
         if (s.equals(".ini")) {
            text = text.replaceAll("(?m)^PublicName=" + Pattern.quote(src) + "\\s*$", Matcher.quoteReplacement("PublicName=" + dst));
         }
         Path out = conf.resolve(dst + s);
         Util.writeTextWithBackup(out, text, this.cfg.fileBackupDir());
      }
   }

   private List<String> allNames() throws IOException {
      List<String> r = new ArrayList<>();
      Map<String, Object> l = this.list();
      for (Object o : (List<?>) l.get("worlds")) {
         r.add(String.valueOf(((Map<?, ?>) o).get("name")));
      }
      for (Object o : (List<?>) l.get("orphans")) {
         r.add(String.valueOf(((Map<?, ?>) o).get("name")));
      }
      return r;
   }

   /**
    * Сброс мира (вайп): сохранение удаляется, настройки остаются. keepAccounts — оставить базу аккаунтов
    * (игроки зайдут со старыми паролями и ролями, но персонажей создадут заново). backup — сначала zip-бэкап.
    */
   String wipe(String name, boolean keepAccounts, boolean backup) throws IOException {
      String n = checkName(name);
      this.requireNotRunning(n, "сброс");
      this.requireIdle();
      if (!this.exists(n)) {
         throw new IOException("Нет мира " + n);
      }
      return this.backups.runJob("сброс мира " + n, () -> {
         String bk = "";
         if (backup) {
            bk = ", бэкап: " + this.backups.backupNow(n, "_beforewipe").getFileName();
         }
         this.backups.step("удаляю сохранение " + n);
         Util.deleteTree(this.cfg.worldSaveDir(n));
         if (!keepAccounts) {
            Files.deleteIfExists(this.cfg.worldAccountsDb(n));
         }
         this.sizes.remove(n);
         return "мир " + n + " сброшен" + (keepAccounts ? " (аккаунты сохранены)" : " (вместе с аккаунтами)") + bk
            + ". При следующем запуске сервер создаст его заново с текущими настройками.";
      });
   }

   /** Переименование мира: конфиги, сохранение и база аккаунтов. Мир не должен работать. */
   String rename(String name, String newName) throws IOException {
      String n = checkName(name);
      String nn = checkName(newName);
      if (n.equals(nn)) {
         throw new IOException("Имя не изменилось");
      }
      this.requireNotRunning(n, "переименование");
      this.requireIdle();
      if (!this.exists(n)) {
         throw new IOException("Нет мира " + n);
      }
      for (String w : this.allNames()) {
         if (w.equalsIgnoreCase(nn) && !w.equalsIgnoreCase(n)) {
            throw new IOException("Имя " + nn + " уже занято (" + w + ")");
         }
      }
      boolean wasActive = n.equals(this.cfg.serverName());
      if (wasActive && this.srv.isRunning()) {
         throw new IOException("Остановите сервер: переименовывается активный мир");
      }
      Path conf = this.cfg.serverConfDir();
      this.copyConfigs(n, nn, false);
      for (String s : Config.WORLD_SUFFIXES) {
         Files.deleteIfExists(conf.resolve(n + s));
      }
      moveCaseSafe(this.cfg.worldSaveDir(n), this.cfg.worldSaveDir(nn));
      moveCaseSafe(this.cfg.worldAccountsDb(n), this.cfg.worldAccountsDb(nn));
      this.sizes.remove(n);
      String act = wasActive ? "; активный мир теперь " + nn + this.setActive(nn) : "";
      return "Мир " + n + " переименован в " + nn + act + ". Бэкапы остались под старым именем.";
   }

   /** Перемещение, которое работает и при смене только регистра на Windows. */
   private static void moveCaseSafe(Path from, Path to) throws IOException {
      if (!Files.exists(from)) {
         return;
      }
      Path tmp = from.resolveSibling(from.getFileName() + ".pzpanel-rename");
      Files.move(from, tmp);
      Files.move(tmp, to);
   }

   /** Удаление мира целиком (конфиги, сохранение, аккаунты). Активный мир удалить нельзя. */
   String delete(String name, boolean backup) throws IOException {
      String n = checkName(name);
      if (n.equals(this.cfg.serverName())) {
         throw new IOException("Это активный мир — сначала сделайте активным другой");
      }
      this.requireNotRunning(n, "удаление");
      this.requireIdle();
      if (!this.exists(n)) {
         throw new IOException("Нет мира " + n);
      }
      return this.backups.runJob("удаление мира " + n, () -> {
         String bk = "";
         if (backup) {
            bk = ", бэкап: " + this.backups.backupNow(n, "_beforedelete").getFileName();
         }
         this.backups.step("удаляю мир " + n);
         Util.deleteTree(this.cfg.worldSaveDir(n));
         Files.deleteIfExists(this.cfg.worldAccountsDb(n));
         for (Path f : this.cfg.worldConfigFiles(n)) {
            if (Files.exists(f)) {
               Util.writeTextWithBackup(f, "", this.cfg.fileBackupDir());
               Files.deleteIfExists(f);
            }
         }
         this.sizes.remove(n);
         return "мир " + n + " удалён" + bk;
      });
   }

   /** Удаление сохранения без конфигов (вкладка «Миры», раздел «Сохранения без настроек»). */
   String deleteOrphan(String dirName, boolean backup) throws IOException {
      if (dirName == null || dirName.isBlank() || dirName.contains("/") || dirName.contains("\\") || dirName.contains("..")) {
         throw new IOException("Неверное имя");
      }
      Path dir = this.cfg.worldSaveDir(dirName);
      if (!Files.isDirectory(dir)) {
         throw new IOException("Нет папки " + dirName);
      }
      if (Files.exists(this.cfg.serverConfDir().resolve(dirName + ".ini")) || dirName.equals(this.cfg.serverName())) {
         throw new IOException("Это мир, а не одиночное сохранение — удаляйте его в списке миров");
      }
      this.requireIdle();
      boolean bk = backup && Backups.NAME_RE.matcher(dirName).matches();
      return this.backups.runJob("удаление сохранения " + dirName, () -> {
         String b = "";
         if (bk) {
            b = ", бэкап: " + this.backups.backupNow(dirName, "_beforedelete").getFileName();
         }
         this.backups.step("удаляю " + dirName);
         Util.deleteTree(dir);
         this.sizes.remove(dirName);
         return "сохранение " + dirName + " удалено" + b;
      });
   }
}
