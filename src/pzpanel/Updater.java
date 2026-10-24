package pzpanel;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Проверка и установка новой версии панели из GitHub Releases (только pzpanel.jar; скрипты запуска не трогаются). */
final class Updater {
   private static final String API = "https://api.github.com/repos/Torivald/PZPanel/releases/latest";
   private static final long CACHE_MS = 5 * 60 * 1000L;
   private final Config cfg;
   private JsonNode cached;
   private long cachedAt;

   Updater(Config cfg) {
      this.cfg = cfg;
   }

   private synchronized JsonNode latestRelease(boolean force) throws IOException {
      if (!force && cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
         return cached;
      }
      try {
         HttpRequest rq = HttpRequest.newBuilder(URI.create(API))
            .timeout(Duration.ofSeconds(20L))
            .header("User-Agent", "PZPanel/" + Main.VERSION)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
         HttpResponse<String> rs = Util.HTTP.send(rq, BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
         if (rs.statusCode() >= 400) {
            throw new IOException("GitHub ответил HTTP " + rs.statusCode());
         }
         cached = Util.JSON.readTree(rs.body());
         cachedAt = System.currentTimeMillis();
         return cached;
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException("Проверка прервана");
      } catch (IOException e) {
         throw e;
      } catch (Exception e) {
         throw new IOException("Не удалось связаться с GitHub: " + e.getMessage());
      }
   }

   /** Сравнение версий вида 1.4 / 1.4.1: >0, если a новее b. */
   static int compare(String a, String b) {
      String[] x = a.split("\\."), y = b.split("\\.");
      for (int i = 0; i < Math.max(x.length, y.length); i++) {
         int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
         if (p != q) {
            return Integer.compare(p, q);
         }
      }
      return 0;
   }

   private static int num(String s) {
      String d = s.replaceAll("[^0-9]", "");
      return d.isEmpty() ? 0 : Integer.parseInt(d.length() > 6 ? d.substring(0, 6) : d);
   }

   private static String assetName(String version) {
      return "PZPanel_" + version + "_" + (Util.isWindows() ? "Windows" : "Linux") + ".zip";
   }

   Map<String, Object> check(boolean force) throws IOException {
      JsonNode r = latestRelease(force);
      String tag = r.path("tag_name").asText("").replaceFirst("^[vV]", "");
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("current", Main.VERSION);
      m.put("latest", tag);
      m.put("newer", !tag.isEmpty() && compare(tag, Main.VERSION) > 0);
      m.put("name", r.path("name").asText(""));
      m.put("url", r.path("html_url").asText(""));
      m.put("published", r.path("published_at").asText(""));
      m.put("notes", r.path("body").asText(""));
      String want = assetName(tag);
      JsonNode asset = null;
      for (JsonNode a : r.path("assets")) {
         if (want.equals(a.path("name").asText())) {
            asset = a;
         }
      }
      m.put("installable", asset != null);
      m.put("asset", want);
      m.put("assetSize", asset == null ? 0 : asset.path("size").asLong());
      m.put("pending", Files.exists(jarNew()));
      return m;
   }

   private Path jar() {
      return cfg.panelDir.resolve("pzpanel.jar");
   }

   private Path jarNew() {
      return cfg.panelDir.resolve("pzpanel.jar.new");
   }

   private static boolean trustedHost(URI u) {
      String h = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
      return "https".equals(u.getScheme())
         && (h.equals("github.com") || h.equals("api.github.com") || h.endsWith(".githubusercontent.com"));
   }

   /** Скачивает архив релиза, достаёт из него pzpanel.jar и подменяет им текущий. Перезапуск панели — отдельно, вручную. */
   synchronized String install() throws IOException {
      Map<String, Object> c = check(true);
      if (!Boolean.TRUE.equals(c.get("newer"))) {
         return "Установлена последняя версия (" + Main.VERSION + ")";
      }
      if (!Boolean.TRUE.equals(c.get("installable"))) {
         throw new IOException("К релизу " + c.get("latest") + " не приложен архив " + c.get("asset") + " — скачайте обновление вручную со страницы релиза");
      }
      String url = null;
      for (JsonNode a : cached.path("assets")) {
         if (c.get("asset").equals(a.path("name").asText())) {
            url = a.path("browser_download_url").asText();
         }
      }
      URI uri = URI.create(url);
      if (!trustedHost(uri)) {
         throw new IOException("Недоверенный адрес загрузки: " + uri.getHost());
      }
      Path dir = cfg.panelDir.resolve("update");
      Files.createDirectories(dir);
      Path zip = dir.resolve("release.zip");
      try {
         HttpRequest rq = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).header("User-Agent", "PZPanel/" + Main.VERSION).GET().build();
         HttpResponse<Path> rs = Util.HTTP.send(rq, BodyHandlers.ofFile(zip));
         if (rs.statusCode() >= 400) {
            throw new IOException("Загрузка не удалась: HTTP " + rs.statusCode());
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException("Загрузка прервана");
      }
      Path tmp = dir.resolve("pzpanel.jar");
      try (ZipFile zf = new ZipFile(zip.toFile())) {
         ZipEntry found = null;
         for (java.util.Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (!e.isDirectory() && (n.equals("pzpanel.jar") || n.equals("PZPanel/pzpanel.jar"))) {
               found = e;
            }
         }
         if (found == null) {
            throw new IOException("В архиве нет pzpanel.jar");
         }
         try (InputStream in = zf.getInputStream(found)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
         }
      }
      try (ZipFile jz = new ZipFile(tmp.toFile())) {
         if (jz.getEntry("pzpanel/Main.class") == null || Files.size(tmp) < 50_000) {
            throw new IOException("Скачанный файл не похож на pzpanel.jar — установка отменена");
         }
      } catch (java.util.zip.ZipException e) {
         throw new IOException("Скачанный pzpanel.jar повреждён — установка отменена");
      }
      Files.move(tmp, jarNew(), StandardCopyOption.REPLACE_EXISTING);
      Files.deleteIfExists(zip);
      Path cur = jar();
      if (Files.exists(cur)) {
         Files.copy(cur, cfg.panelDir.resolve("pzpanel.jar.bak"), StandardCopyOption.REPLACE_EXISTING);
      }
      try {
         Files.move(jarNew(), cur, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
         // Windows держит запущенный jar открытым: файл останется как pzpanel.jar.new и подменится при следующем запуске PZPanel.bat
         return "Версия " + c.get("latest") + " скачана. Закройте панель и запустите PZPanel.bat — обновление применится при запуске";
      }
      return "Версия " + c.get("latest") + " установлена. Перезапустите панель, чтобы начать её использовать";
   }
}
