package pzpanel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class Util {
   static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
   static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15L))
      .followRedirects(Redirect.NORMAL)
      .proxy(ProxySelector.getDefault())
      .build();

   static String readText(Path var0) throws IOException {
      byte[] var1 = Files.readAllBytes(var0);
      int var2 = var1.length >= 3 && (var1[0] & 255) == 239 && (var1[1] & 255) == 187 && (var1[2] & 255) == 191 ? 3 : 0;
      return new String(var1, var2, var1.length - var2, StandardCharsets.UTF_8);
   }

   static void writeTextWithBackup(Path var0, String var1, Path var2) throws IOException {
      if (Files.exists(var0) && var2 != null) {
         Files.createDirectories(var2);
         String var3 = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
         Files.copy(var0, var2.resolve(var0.getFileName().toString() + "." + var3 + ".bak"), StandardCopyOption.REPLACE_EXISTING);
         pruneBackups(var2, var0.getFileName().toString(), 30);
      }

      Path var4 = var0.resolveSibling(var0.getFileName().toString() + ".pzpanel.tmp");
      Files.write(var4, var1.getBytes(StandardCharsets.UTF_8));
      Files.move(var4, var0, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
   }

   static void pruneBackups(Path var0, String var1, int var2) {
      try (Stream<Path> var3 = Files.list(var0)) {
         List var4 = var3.filter(var1x -> var1x.getFileName().toString().startsWith(var1 + ".")).sorted().toList();

         for (int var5 = 0; var5 < var4.size() - var2; var5++) {
            Files.deleteIfExists((Path)var4.get(var5));
         }
      } catch (IOException var8) {
      }
   }

   static String httpGet(String var0) throws IOException, InterruptedException {
      HttpRequest var1 = HttpRequest.newBuilder(URI.create(var0))
         .timeout(Duration.ofSeconds(25L))
         .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PZPanel/1.0")
         .header("Accept-Language", "ru,en;q=0.8")
         .GET()
         .build();
      HttpResponse var2 = HTTP.send(var1, BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (var2.statusCode() >= 400) {
         throw new IOException("HTTP " + var2.statusCode() + " " + var0);
      } else {
         return (String)var2.body();
      }
   }

   static String httpPostForm(String var0, String var1) throws IOException, InterruptedException {
      HttpRequest var2 = HttpRequest.newBuilder(URI.create(var0))
         .timeout(Duration.ofSeconds(25L))
         .header("Content-Type", "application/x-www-form-urlencoded")
         .header("User-Agent", "PZPanel/1.0")
         .POST(BodyPublishers.ofString(var1))
         .build();
      HttpResponse var3 = HTTP.send(var2, BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (var3.statusCode() >= 400) {
         throw new IOException("HTTP " + var3.statusCode() + " " + var0);
      } else {
         return (String)var3.body();
      }
   }

   static String nowStamp() {
      return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
   }

   static long dirSize(Path var0) {
      if (!Files.isDirectory(var0)) {
         return 0L;
      } else {
         final long[] var1 = new long[]{0L};

         try {
            Files.walkFileTree(var0, new SimpleFileVisitor<Path>() {
               public FileVisitResult visitFile(Path var1x, BasicFileAttributes var2) {
                  var1[0] = var1[0] + var2.size();
                  return FileVisitResult.CONTINUE;
               }

               public FileVisitResult visitFileFailed(Path var1x, IOException var2) {
                  return FileVisitResult.CONTINUE;
               }
            });
         } catch (IOException var3) {
         }

         return var1[0];
      }
   }

   /** Удаляет папку целиком (если есть). */
   static void deleteTree(Path dir) throws IOException {
      if (!Files.exists(dir)) {
         return;
      }
      Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
         @Override
         public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
            Files.delete(f);
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
            if (e != null) {
               throw e;
            }
            Files.delete(d);
            return FileVisitResult.CONTINUE;
         }
      });
   }

   /** Копирует папку целиком; progress получает относительный путь каждого файла. */
   static void copyTree(Path from, Path to, java.util.function.Consumer<String> progress) throws IOException {
      if (!Files.isDirectory(from)) {
         return;
      }
      Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
         @Override
         public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
            Files.createDirectories(to.resolve(from.relativize(d).toString()));
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
            if (!f.getFileName().toString().endsWith("-journal")) {
               String rel = from.relativize(f).toString();
               progress.accept(rel);
               Files.copy(f, to.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
            }
            return FileVisitResult.CONTINUE;
         }
      });
   }

   static String lineSep(String var0) {
      return var0.contains("\r\n") ? "\r\n" : "\n";
   }

   static boolean isWindows() {
      return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
   }
}
