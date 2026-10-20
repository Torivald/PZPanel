package pzpanel;

import com.sun.management.OperatingSystemMXBean;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Stats {
   static final int PERIOD_SEC = 5;
   static final int HISTORY = 360;
   private final Config cfg;
   private final Supplier<Optional<ProcessHandle>> server;
   private final ArrayDeque<double[]> hist = new ArrayDeque<>();
   private final Map<Long, Long> prevCpu = new HashMap<>();
   private long prevAt = 0L;
   private volatile Map<String, Object> last = Map.of();
   private final OperatingSystemMXBean os;
   private final int cores = Runtime.getRuntime().availableProcessors();
   private long peakRss = 0L;
   private long peakSince = 0L;
   private long rootPid = -1L;
   private static final Pattern XMX = Pattern.compile("-Xmx(\\d+)([kKmMgG]?)");

   Stats(Config var1, Supplier<Optional<ProcessHandle>> var2) {
      this.cfg = var1;
      this.server = var2;
      this.os = ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean var4 ? var4 : null;
      ScheduledExecutorService var5 = Executors.newSingleThreadScheduledExecutor(var0 -> {
         Thread var1x = new Thread(var0, "pz-stats");
         var1x.setDaemon(true);
         return var1x;
      });
      var5.scheduleWithFixedDelay(() -> {
         try {
            this.sample();
         } catch (Throwable var2x) {
         }
      }, 1L, 5L, TimeUnit.SECONDS);
   }

   private void sample() {
      long var1 = System.currentTimeMillis();
      Optional var3 = this.server.get();
      LinkedHashMap var4 = new LinkedHashMap();
      double var5 = -1.0;
      double var7 = -1.0;
      double var9 = -1.0;
      if (this.os != null) {
         var5 = this.os.getCpuLoad() * 100.0;
         var9 = this.os.getTotalMemorySize() / 1048576.0;
         var7 = var9 - this.os.getFreeMemorySize() / 1048576.0;
      }

      var4.put("cores", this.cores);
      var4.put("sysCpu", var5 < 0.0 ? null : round1(var5));
      var4.put("sysMemTotalMb", var9 < 0.0 ? null : Math.round(var9));
      var4.put("sysMemUsedMb", var7 < 0.0 ? null : Math.round(var7));
      if (!var3.isEmpty() && ((ProcessHandle)var3.get()).isAlive()) {
         ProcessHandle var11 = (ProcessHandle)var3.get();
         ArrayList<ProcessHandle> var12 = new ArrayList<>();
         var12.add(var11);
         var11.descendants().forEach(var12::add);
         HashMap<Long, Long> var13 = new HashMap<>();

         for (ProcessHandle var15 : var12) {
            var15.info().totalCpuDuration().map(Duration::toNanos).ifPresent(var2 -> var13.put(var15.pid(), var2));
         }

         Map<Long, Long> var28 = this.rssKb(var12);
         long var29 = 0L;

         for (ProcessHandle var18 : var12) {
            var29 += var28.getOrDefault(var18.pid(), 0L);
         }

         double var30 = Double.NaN;
         synchronized (this) {
            if (this.rootPid != var11.pid()) {
               this.prevCpu.clear();
               this.prevAt = 0L;
               this.rootPid = var11.pid();
               this.peakRss = 0L;
               this.peakSince = var1;
            }

            if (this.prevAt > 0L && var1 > this.prevAt) {
               long var20 = 0L;

               for (Entry<Long, Long> var23 : var13.entrySet()) {
                  Long var24 = this.prevCpu.get(var23.getKey());
                  var20 += Math.max(0L, (Long)var23.getValue() - (var24 == null ? 0L : var24));
               }

               var30 = var20 / 1000000.0 / (var1 - this.prevAt) * 100.0 / this.cores;
               var30 = Math.min(100.0, Math.max(0.0, var30));
            }

            this.prevCpu.clear();
            this.prevCpu.putAll(var13);
            this.prevAt = var1;
            this.peakRss = Math.max(this.peakRss, var29);
         }

         double var19 = var28.isEmpty() ? Double.NaN : var29 / 1024.0;
         var4.put("running", true);
         var4.put("pid", var11.pid());
         var4.put("processes", var12.size());
         var4.put("cpu", Double.isNaN(var30) ? null : round1(var30));
         var4.put("cpuCores", Double.isNaN(var30) ? null : round1(var30 * this.cores / 100.0));
         var4.put("rssMb", Double.isNaN(var19) ? null : Math.round(var19));
         var4.put("peakRssMb", this.peakRss / 1024L);
         var4.put("xmxMb", this.xmxMb(var12));
         var4.put("threads", this.threads(var12));
         this.last = var4;
         this.push(var1, var30, var19, var5, var7);
      } else {
         synchronized (this) {
            this.prevCpu.clear();
            this.prevAt = 0L;
            this.rootPid = -1L;
         }

         var4.put("running", false);
         this.last = var4;
         this.push(var1, Double.NaN, Double.NaN, var5, var7);
      }
   }

   private synchronized void push(long var1, double var3, double var5, double var7, double var9) {
      this.hist.addLast(new double[]{var1, var3, var5, var7, var9});

      while (this.hist.size() > 360) {
         this.hist.removeFirst();
      }
   }

   synchronized Map<String, Object> snapshot() {
      LinkedHashMap var1 = new LinkedHashMap<>(this.last);
      ArrayList var2 = new ArrayList();

      for (double[] var4 : this.hist) {
         var2.add(new Object[]{(long)var4[0], v1(var4[1]), v0(var4[2]), v1(var4[3]), v0(var4[4])});
      }

      var1.put("periodSec", 5);
      var1.put("history", var2);
      return var1;
   }

   private static Object v1(double var0) {
      return !Double.isNaN(var0) && !(var0 < 0.0) ? round1(var0) : null;
   }

   private static Object v0(double var0) {
      return !Double.isNaN(var0) && !(var0 < 0.0) ? Math.round(var0) : null;
   }

   private static double round1(double var0) {
      return Math.round(var0 * 10.0) / 10.0;
   }

   private Map<Long, Long> rssKb(List<ProcessHandle> var1) {
      HashMap var2 = new HashMap();
      if (!Util.isWindows()) {
         for (ProcessHandle var16 : var1) {
            Long var18 = procStatus(var16.pid(), "VmRSS");
            if (var18 != null) {
               var2.put(var16.pid(), var18);
            }
         }

         return var2;
      } else {
         try {
            Process var3 = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH").redirectErrorStream(true).start();
            HashSet var4 = new HashSet();

            for (ProcessHandle var6 : var1) {
               var4.add(var6.pid());
            }

            String var19;
            try (BufferedReader var17 = new BufferedReader(new InputStreamReader(var3.getInputStream(), StandardCharsets.ISO_8859_1))) {
               while ((var19 = var17.readLine()) != null) {
                  List var7 = csv(var19);
                  if (var7.size() >= 5) {
                     long var8;
                     try {
                        var8 = Long.parseLong(((String)var7.get(1)).trim());
                     } catch (NumberFormatException var12) {
                        continue;
                     }

                     if (var4.contains(var8)) {
                        String var10 = ((String)var7.get(4)).replaceAll("[^0-9]", "");
                        if (!var10.isEmpty()) {
                           var2.put(var8, Long.parseLong(var10));
                        }
                     }
                  }
               }
            }

            var3.waitFor(5L, TimeUnit.SECONDS);
         } catch (Exception var14) {
         }

         return var2;
      }
   }

   private static List<String> csv(String var0) {
      ArrayList var1 = new ArrayList();
      StringBuilder var2 = new StringBuilder();
      boolean var3 = false;

      for (int var4 = 0; var4 < var0.length(); var4++) {
         char var5 = var0.charAt(var4);
         if (var5 == '"') {
            var3 = !var3;
         } else if (var5 == ',' && !var3) {
            var1.add(var2.toString());
            var2.setLength(0);
         } else {
            var2.append(var5);
         }
      }

      var1.add(var2.toString());
      return var1;
   }

   private static Long procStatus(long var0, String var2) {
      try {
         for (String var4 : Files.readAllLines(Paths.get("/proc", String.valueOf(var0), "status"))) {
            if (var4.startsWith(var2 + ":")) {
               return Long.parseLong(var4.replaceAll("[^0-9]", ""));
            }
         }
      } catch (Exception var5) {
      }

      return null;
   }

   private Integer threads(List<ProcessHandle> var1) {
      if (Util.isWindows()) {
         return null;
      } else {
         int var2 = 0;

         for (ProcessHandle var4 : var1) {
            Long var5 = procStatus(var4.pid(), "Threads");
            if (var5 != null) {
               var2 = (int)(var2 + var5);
            }
         }

         return var2;
      }
   }

   private Long xmxMb(List<ProcessHandle> var1) {
      for (ProcessHandle var3 : var1) {
         String var4 = var3.info().commandLine().orElse("");
         if (var4.isEmpty() && !Util.isWindows()) {
            try {
               var4 = new String(Files.readAllBytes(Paths.get("/proc", String.valueOf(var3.pid()), "cmdline")), StandardCharsets.UTF_8).replace('\u0000', ' ');
            } catch (Exception var10) {
            }
         }

         Long var5 = parseXmx(var4);
         if (var5 != null) {
            return var5;
         }
      }

      Path var11 = this.cfg.serverDir();

      for (String var6 : new String[]{this.cfg.get("startScript"), "ProjectZomboid64.json", "StartServer64.bat", "start-server.sh"}) {
         try {
            Path var7 = var11.resolve(var6);
            if (Files.isRegularFile(var7)) {
               Long var8 = parseXmx(Files.readString(var7, StandardCharsets.ISO_8859_1));
               if (var8 != null) {
                  return var8;
               }
            }
         } catch (Exception var9) {
         }
      }

      return null;
   }

   private static Long parseXmx(String var0) {
      Matcher var1 = XMX.matcher(var0);
      if (!var1.find()) {
         return null;
      } else {
         long var2 = Long.parseLong(var1.group(1));
         String var4 = var1.group(2).toLowerCase(Locale.ROOT);

         return switch (var4) {
            case "g" -> var2 * 1024L;
            case "k" -> var2 / 1024L;
            case "m" -> var2;
            default -> var2 / 1048576L;
         };
      }
   }
}
