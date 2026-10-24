package pzpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Журнал самой панели: всё, что она пишет в stdout/stderr, дублируется в кольцевой буфер (для окна «Логи панели») и в файл pzpanel.log. */
final class PanelLog {
   private static final int CAP = 3000;
   private static final long FILE_MAX = 2L * 1024 * 1024;
   private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
   private static final Deque<String> lines = new ArrayDeque<>();
   private static long seq = 0; // номер последней добавленной строки
   private static Path file;

   private PanelLog() {
   }

   static synchronized void install(Path dir) {
      if (file != null) {
         return;
      }
      try {
         file = dir.resolve("pzpanel.log");
         if (Files.exists(file) && Files.size(file) > FILE_MAX) {
            Files.move(file, dir.resolve("pzpanel.log.1"), StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (Exception e) {
         file = null;
      }
      System.setOut(new PrintStream(new Tee(System.out, "INFO"), true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(new Tee(System.err, "ERROR"), true, StandardCharsets.UTF_8));
      add("INFO", "---- PZ Panel запущена ----");
   }

   static synchronized void add(String level, String text) {
      String line = LocalDateTime.now().format(TS) + " " + level + " " + text;
      lines.addLast(line);
      seq++;
      while (lines.size() > CAP) {
         lines.removeFirst();
      }
      if (file != null) {
         try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(line);
            w.write('\n');
         } catch (Exception e) {
         }
      }
   }

   /** Строки после курсора (cursor < 0 — все имеющиеся). */
   static synchronized Map<String, Object> read(long cursor) {
      long first = seq - lines.size(); // номер строки перед самой старой в буфере
      boolean reset = cursor < 0 || cursor < first || cursor > seq;
      long from = reset ? first : cursor;
      List<String> out = new ArrayList<>();
      long i = first;
      for (String l : lines) {
         i++;
         if (i > from) {
            out.add(l);
         }
      }
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("lines", out);
      r.put("cursor", seq);
      r.put("reset", reset);
      return r;
   }

   static synchronized void clear() {
      lines.clear();
      seq = 0;
      if (file != null) {
         try {
            Files.writeString(file, "");
         } catch (Exception e) {
         }
      }
   }

   private static final class Tee extends OutputStream {
      private final PrintStream orig;
      private final String level;
      private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

      Tee(PrintStream orig, String level) {
         this.orig = orig;
         this.level = level;
      }

      @Override
      public void write(int b) {
         orig.write(b);
         if (b == '\n') {
            flushLine();
         } else if (b != '\r') {
            buf.write(b);
         }
      }

      @Override
      public void write(byte[] b, int off, int len) {
         for (int i = 0; i < len; i++) {
            write(b[off + i]);
         }
      }

      @Override
      public void flush() {
         orig.flush();
      }

      private void flushLine() {
         String s = buf.toString(StandardCharsets.UTF_8);
         buf.reset();
         if (!s.isBlank()) {
            add(level, s);
         }
      }
   }
}
