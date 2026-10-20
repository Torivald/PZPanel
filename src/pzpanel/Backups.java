package pzpanel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip-бэкапы миров и общий исполнитель долгих операций (бэкап, восстановление, операции с мирами).
 * Одновременно выполняется только одна операция; её состояние — {@link #progress()}.
 */
final class Backups {
   interface Job {
      /** Выполняет работу, возвращает итоговое сообщение. */
      String run() throws Exception;
   }

   static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9_\\-]{1,48}");
   private static final Pattern BACKUP_NAME = Pattern.compile("(.+)_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})(_[a-z]+)?\\.zip");

   private final Config cfg;
   private final ServerManager srv;
   private volatile String progress = "";

   Backups(Config cfg, ServerManager srv) {
      this.cfg = cfg;
      this.srv = srv;
   }

   String progress() {
      return this.progress;
   }

   boolean busy() {
      return this.progress.startsWith("Идёт");
   }

   void step(String s) {
      this.progress = "Идёт: " + s;
   }

   /** Запускает операцию в фоне. Если уже что-то идёт — исключение. */
   synchronized String runJob(String title, Job job) throws IOException {
      if (this.busy()) {
         throw new IOException("Уже выполняется операция: " + this.progress.substring(6));
      }
      this.step(title);
      Thread t = new Thread(() -> {
         try {
            this.progress = "Готово: " + job.run();
         } catch (Exception e) {
            this.progress = "Ошибка: " + (e.getMessage() == null ? e.toString() : e.getMessage());
         }
      }, "pz-job");
      t.setDaemon(true);
      t.start();
      return "Запущено: " + title;
   }

   List<Map<String, Object>> list() throws IOException {
      List<Map<String, Object>> r = new ArrayList<>();
      Path dir = this.cfg.backupDir();
      if (!Files.isDirectory(dir)) {
         return r;
      }
      List<Path> files;
      try (Stream<Path> s = Files.list(dir)) {
         files = s.filter(p -> p.getFileName().toString().endsWith(".zip"))
            .sorted(Comparator.comparingLong(Backups::mtime).reversed())
            .toList();
      }
      for (Path p : files) {
         Map<String, Object> m = new LinkedHashMap<>();
         String n = p.getFileName().toString();
         m.put("name", n);
         m.put("size", Files.size(p));
         m.put("time", mtime(p));
         m.put("world", worldOfBackup(n));
         r.add(m);
      }
      return r;
   }

   private static long mtime(Path p) {
      try {
         return Files.getLastModifiedTime(p).toMillis();
      } catch (IOException e) {
         return 0L;
      }
   }

   /** Имя мира по имени файла бэкапа: Torivald_2026-10-20_12-00-00.zip → Torivald. */
   static String worldOfBackup(String fileName) {
      Matcher m = BACKUP_NAME.matcher(fileName);
      return m.matches() ? m.group(1) : "";
   }

   /** Бэкап активного мира (кнопка на вкладке «Бэкапы»). */
   String create() throws IOException {
      return this.create(this.cfg.serverName());
   }

   String create(String world) throws IOException {
      return this.runJob("бэкап мира " + world, () -> {
         Path zip = this.backupNow(world, "");
         return zip.getFileName() + " (" + Files.size(zip) / 1048576L + " МБ)";
      });
   }

   /**
    * Синхронный бэкап мира (вызывается из фоновой операции). Если этот мир сейчас работает — сначала save.
    * suffix — пометка в имени файла (например «_beforewipe»).
    */
   Path backupNow(String world, String suffix) throws IOException {
      if (world.equals(this.srv.runningWorld())) {
         this.step("save на сервере");
         try {
            this.srv.sendConsole("save");
            Thread.sleep(8000L);
         } catch (Exception e) {
         }
      }
      Path dir = this.cfg.backupDir();
      Files.createDirectories(dir);
      Path zip = dir.resolve(world + "_" + Util.nowStamp() + suffix + ".zip");
      Path base = this.cfg.zomboidDir();
      Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
      try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
         z.setLevel(1);
         this.addTree(z, base, this.cfg.worldSaveDir(world));
         Path db = this.cfg.worldAccountsDb(world);
         if (Files.exists(db)) {
            addFile(z, base, db);
         }
         for (Path f : this.cfg.worldConfigFiles(world)) {
            if (Files.exists(f)) {
               addFile(z, base, f);
            }
         }
      } catch (IOException e) {
         Files.deleteIfExists(tmp);
         throw e;
      }
      Files.move(tmp, zip);
      return zip;
   }

   private void addTree(ZipOutputStream z, Path base, Path dir) throws IOException {
      if (!Files.isDirectory(dir)) {
         return;
      }
      Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
         @Override
         public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
            if (!f.getFileName().toString().endsWith("-journal")) {
               Backups.this.step(base.relativize(f).toString());
               Backups.addFile(z, base, f);
            }
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFileFailed(Path f, IOException e) {
            return FileVisitResult.CONTINUE;
         }
      });
   }

   private static void addFile(ZipOutputStream z, Path base, Path f) throws IOException {
      z.putNextEntry(new ZipEntry(base.relativize(f).toString().replace('\\', '/')));
      try (InputStream in = Files.newInputStream(f)) {
         in.transferTo(z);
      } catch (IOException e) {
      }
      z.closeEntry();
   }

   private Path backupFile(String name) throws IOException {
      if (!name.matches("[^/\\\\:*?\"<>|]+\\.zip") || name.contains("..")) {
         throw new IOException("Неверное имя");
      }
      return this.cfg.backupDir().resolve(name);
   }

   void delete(String name) throws IOException {
      Files.deleteIfExists(this.backupFile(name));
   }

   /**
    * Восстановление мира из бэкапа: сохранение мира, база аккаунтов и конфиги заменяются содержимым архива.
    * Мир не должен работать. safety=true — перед этим бэкап текущего состояния (пометка _beforerestore).
    */
   String restore(String name, boolean safety) throws IOException {
      Path zip = this.backupFile(name);
      if (!Files.exists(zip)) {
         throw new IOException("Нет файла " + name);
      }
      String world = this.worldInZip(zip);
      if (world.equals(this.srv.runningWorld())) {
         throw new IOException("Мир " + world + " сейчас запущен — сначала остановите сервер");
      }
      return this.runJob("восстановление " + world + " из " + name, () -> {
         if (safety && (Files.exists(this.cfg.worldSaveDir(world)) || Files.exists(this.cfg.worldConfigFiles(world).get(0)))) {
            this.step("бэкап текущего состояния " + world);
            this.backupNow(world, "_beforerestore");
         }
         this.step("удаляю текущее сохранение " + world);
         Util.deleteTree(this.cfg.worldSaveDir(world));
         int n = this.extract(zip, world);
         return "мир " + world + " восстановлен из " + name + " (" + n + " файлов)";
      });
   }

   /** Определяет мир по содержимому архива (папка Saves/Multiplayer/<мир> или Server/<мир>.ini). */
   private String worldInZip(Path zip) throws IOException {
      String fromName = worldOfBackup(zip.getFileName().toString());
      String found = null;
      try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
         ZipEntry e;
         while ((e = z.getNextEntry()) != null) {
            String n = e.getName();
            Matcher m = Pattern.compile("^Saves/Multiplayer/([^/]+)/").matcher(n);
            if (m.find()) {
               found = m.group(1);
               break;
            }
            m = Pattern.compile("^Server/([^/]+)\\.ini$").matcher(n);
            if (m.find()) {
               found = m.group(1);
               break;
            }
         }
      }
      if (found == null) {
         found = fromName;
      }
      if (found.isEmpty() || !NAME_RE.matcher(found).matches()) {
         throw new IOException("Не удалось понять, какой мир в архиве " + zip.getFileName());
      }
      return found;
   }

   private int extract(Path zip, String world) throws IOException {
      Path base = this.cfg.zomboidDir().toAbsolutePath().normalize();
      List<String> allowedFiles = new ArrayList<>();
      for (String s : Config.WORLD_SUFFIXES) {
         allowedFiles.add("Server/" + world + s);
      }
      allowedFiles.add("db/" + world + ".db");
      String savePrefix = "Saves/Multiplayer/" + world + "/";
      int n = 0;
      try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
         ZipEntry e;
         byte[] buf = new byte[65536];
         while ((e = z.getNextEntry()) != null) {
            String name = e.getName().replace('\\', '/');
            if (e.isDirectory() || !(name.startsWith(savePrefix) || allowedFiles.contains(name))) {
               continue;
            }
            Path out = base.resolve(name).normalize();
            if (!out.startsWith(base)) {
               continue;
            }
            this.step("распаковка " + name);
            Files.createDirectories(out.getParent());
            try (OutputStream os = Files.newOutputStream(out)) {
               int r;
               while ((r = z.read(buf)) > 0) {
                  os.write(buf, 0, r);
               }
            }
            n++;
         }
      }
      return n;
   }
}
