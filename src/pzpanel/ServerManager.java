package pzpanel;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.lang.ProcessHandle.Info;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class ServerManager {
   private final Config cfg;
   private volatile Process proc;
   private volatile Writer stdin;
   private volatile long startedAt;
   private volatile boolean stopRequested;
   private volatile String state = "stopped";
   private volatile String lastAction = "";
   private volatile long configChangedAt = 0L;
   private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
   private final ArrayDeque<String> lines = new ArrayDeque<>();
   private long firstSeq = 0L;
   private final StringBuilder partial = new StringBuilder();
   private static final int MAX_LINES = 6000;
   private volatile String lastDailyRun = "";
   private volatile String startedWorld;

   ServerManager(Config var1) {
      this.cfg = var1;
      this.sched.scheduleAtFixedRate(this::dailyRestartTick, 30L, 30L, TimeUnit.SECONDS);
   }

   boolean ownedAlive() {
      Process var1 = this.proc;
      return var1 != null && var1.isAlive();
   }

   /** serverMode=remote: сервер в другом контейнере. «Запущен» = открыт RCON-порт, запуск и перезапуск делает Docker (restart policy). */
   private boolean remote() {
      return "remote".equals(this.cfg.get("serverMode", "local"));
   }

   private String rconHost() {
      return this.cfg.get("rconHost", "127.0.0.1");
   }

   private boolean remoteUp() {
      int port = 27015;
      try {
         port = Integer.parseInt(Configs.iniValue(this.cfg.serverIni(), "RCONPort"));
      } catch (Exception e) {
      }
      try (Socket s = new Socket()) {
         s.connect(new InetSocketAddress(this.rconHost(), port), 1500);
         return true;
      } catch (IOException e) {
         return false;
      }
   }

   Optional<ProcessHandle> externalServer() {
      if (this.ownedAlive()) {
         return Optional.empty();
      } else {
         Path var1 = this.cfg.serverDir().resolve("jre64").toAbsolutePath().normalize();
         Path var2 = this.cfg.serverDir().toAbsolutePath().normalize();
         long var3 = ProcessHandle.current().pid();
         return ProcessHandle.allProcesses()
            .filter(var2x -> var2x.pid() != var3)
            .filter(
               var2x -> {
                  Info var3x = var2x.info();
                  String var4 = var3x.commandLine().orElse("");
                  if (var4.contains("zombie.network.GameServer")) {
                     return true;
                  } else {
                     String var5 = var3x.command().orElse("");
                     if (var5.isEmpty()) {
                        return false;
                     } else {
                        try {
                           Path var6 = Paths.get(var5).toAbsolutePath().normalize();
                           return var6.startsWith(var2) && var6.getFileName().toString().startsWith("ProjectZomboid64")
                              ? true
                              : var6.startsWith(var1) && (var4.isEmpty() || !var4.contains("pzpanel."));
                        } catch (Exception var7) {
                           return false;
                        }
                     }
                  }
               }
            )
            .findFirst();
      }
   }

   /** Имя мира работающего сервера или null, если сервер не запущен. */
   String runningWorld() {
      if (this.remote()) {
         return this.remoteUp() ? this.cfg.serverName() : null;
      }
      if (this.ownedAlive()) {
         return this.startedWorld != null ? this.startedWorld : this.cfg.serverName();
      }
      Optional<ProcessHandle> ext = this.externalServer();
      if (ext.isEmpty()) {
         return null;
      }
      String cmd = ext.get().info().commandLine().orElse("");
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("-servername\\s+\"?([^\"\\s]+)").matcher(cmd);
      return m.find() ? m.group(1) : this.cfg.serverName();
   }

   boolean isRunning() {
      return this.remote() ? this.remoteUp() : this.ownedAlive() || this.externalServer().isPresent();
   }

   Optional<ProcessHandle> serverHandle() {
      Process var1 = this.proc;
      return var1 != null && var1.isAlive() ? Optional.of(var1.toHandle()) : this.externalServer();
   }

   Map<String, Object> status() {
      LinkedHashMap var1 = new LinkedHashMap();
      boolean var2 = this.ownedAlive();
      Optional<ProcessHandle> var3 = var2 ? Optional.<ProcessHandle>empty() : this.externalServer();
      boolean var4 = this.remote() ? this.remoteUp() : var2 || var3.isPresent();
      if (!var4 && !this.state.equals("starting") && !this.state.equals("restarting")) {
         this.state = "stopped";
      }

      if (var4 && this.state.equals("stopped")) {
         this.state = "running";
      }

      var1.put("state", this.state);
      var1.put("running", var4);
      var1.put("owned", var2);
      var1.put("remote", this.remote());
      com.fasterxml.jackson.databind.JsonNode rm = this.remote() ? Stats.remoteMetrics(this.cfg) : null;
      long var5 = rm != null ? rm.path("pid").asLong(-1L) : var2 ? this.proc.pid() : var3.map(ProcessHandle::pid).orElse(-1L);
      var1.put("pid", var5);
      long var7 = rm != null ? rm.path("startEpoch").asLong() * 1000L : var2 ? this.startedAt : var3.flatMap(var0 -> var0.info().startInstant()).map(Instant::toEpochMilli).orElse(0L);
      var1.put("startedAt", var7);
      var1.put("uptimeSec", var7 > 0L && var4 ? (System.currentTimeMillis() - var7) / 1000L : 0L);
      var1.put("lastAction", this.lastAction);
      var1.put("needsRestart", var4 && this.configChangedAt > var7 && var7 > 0L);
      var1.put("waitingInput", var2 && this.promptPending());
      var1.put("serverName", this.cfg.serverName());
      var1.put("dailyRestart", this.cfg.get("dailyRestart"));
      var1.put("autoRestartOnCrash", this.cfg.getBool("autoRestartOnCrash"));
      return var1;
   }

   void markConfigChanged() {
      this.configChangedAt = System.currentTimeMillis();
   }

   private synchronized void addOutput(String var1) {
      for (int var2 = 0; var2 < var1.length(); var2++) {
         char var3 = var1.charAt(var2);
         if (var3 == '\n') {
            this.pushLine(this.partial.toString());
            this.partial.setLength(0);
         } else if (var3 != '\r') {
            this.partial.append(var3);
         }
      }
   }

   private synchronized void pushLine(String var1) {
      this.lines.addLast(var1);

      while (this.lines.size() > 6000) {
         this.lines.removeFirst();
         this.firstSeq++;
      }
   }

   synchronized boolean promptPending() {
      String var1 = this.partial.toString().trim();
      return var1.endsWith("[y/n]:") || var1.endsWith("[y/N]:") || var1.endsWith("(y/n)") || var1.toLowerCase(Locale.ROOT).endsWith("password:");
   }

   Map<String, Object> console(long var1) {
      LinkedHashMap var3 = new LinkedHashMap();
      if (this.proc != null) {
         synchronized (this) {
            long var18 = this.firstSeq + this.lines.size();
            long var7 = var1 < 0L ? Math.max(this.firstSeq, var18 - 400L) : Math.max(var1, this.firstSeq);
            ArrayList var9 = new ArrayList();
            int var19 = 0;

            for (String var21 : this.lines) {
               if (this.firstSeq + var19 >= var7) {
                  var9.add(var21);
               }

               var19++;
            }

            var3.put("mode", "process");
            var3.put("lines", var9);
            var3.put("partial", this.partial.toString());
            var3.put("cursor", var18);
            var3.put("reset", var1 >= 0L && var1 < this.firstSeq);
            return var3;
         }
      } else {
         Path var4 = this.cfg.consoleLog();
         var3.put("mode", "file");
         var3.put("partial", "");

         try (RandomAccessFile var5 = new RandomAccessFile(var4.toFile(), "r")) {
            long var6 = var5.length();
            long var8 = var1 >= 0L && var1 <= var6 ? var1 : Math.max(0L, var6 - 65536L);
            if (var6 - var8 > 524288L) {
               var8 = var6 - 524288L;
            }

            byte[] var10 = new byte[(int)(var6 - var8)];
            var5.seek(var8);
            var5.readFully(var10);
            String var11 = new String(var10, StandardCharsets.UTF_8);
            ArrayList var12 = new ArrayList<>(Arrays.asList(var11.split("\r?\n", -1)));
            if (var1 < 0L && !var12.isEmpty()) {
               var12.remove(0);
            }

            if (!var12.isEmpty() && ((String)var12.get(var12.size() - 1)).isEmpty()) {
               var12.remove(var12.size() - 1);
            }

            var3.put("lines", var12);
            var3.put("cursor", var6);
            var3.put("reset", var1 > var6);
         } catch (IOException var17) {
            var3.put("lines", List.of());
            var3.put("cursor", 0);
            var3.put("error", "Лог не найден: " + var4);
         }

         return var3;
      }
   }

   String sendConsole(String var1) throws IOException {
      if (this.ownedAlive() && this.stdin != null) {
         synchronized (this) {
            this.pushLine("> " + var1);
         }

         this.stdin.write(var1 + (Util.isWindows() ? "\r\n" : "\n"));
         this.stdin.flush();
         return "";
      } else {
         return this.rcon(var1);
      }
   }

   String rcon(String var1) throws IOException {
      Path var2 = this.cfg.serverIni();
      int var3 = 27015;

      try {
         var3 = Integer.parseInt(Configs.iniValue(var2, "RCONPort"));
      } catch (Exception var5) {
      }

      return Rcon.exec(this.rconHost(), var3, Configs.iniValue(var2, "RCONPassword"), var1, 5000);
   }

   synchronized String start() throws IOException {
      if (this.remote()) {
         String req = this.cfg.get("startRequestFile");
         if (req.isEmpty()) {
            throw new IOException("Сервер в отдельном контейнере (serverMode=remote): запускайте его средствами контейнера (docker start / compose) или задайте startRequestFile");
         }
         if (this.remoteUp()) {
            return "Сервер уже запущен";
         }
         // панель не управляет контейнером сама: она лишь кладёт файл-запрос, а снаружи (systemd path unit, скрипт) выполняют docker start
         Path f = Paths.get(req);
         if (f.getParent() != null) {
            Files.createDirectories(f.getParent());
         }
         Files.writeString(f, Instant.now().toString() + "\n");
         this.lastAction = "Запрос на запуск " + LocalTime.now().withNano(0);
         return "Запрос на запуск отправлен. Контейнер стартует за 1–3 минуты";
      }
      if (!this.ownedAlive() && !this.externalServer().isPresent()) {
         Path var1 = this.cfg.serverDir();
         Path var2 = var1.resolve(this.cfg.get("startScript"));
         if (!Files.exists(var2)) {
            throw new IOException("Не найден " + var2);
         } else {
            String world = this.cfg.serverName();
            boolean scriptHasName = Util.readText(var2).contains("-servername");
            List<String> var3 = new ArrayList<>(Util.isWindows()
               ? List.of("cmd.exe", "/c", var2.getFileName().toString())
               : List.of("bash", var2.getFileName().toString()));
            if (!Util.isWindows() || !scriptHasName) {
               var3.add("-servername");
               var3.add(world);
            }
            this.startedWorld = world;
            ProcessBuilder var4 = new ProcessBuilder(var3).directory(var1.toFile()).redirectErrorStream(true);
            synchronized (this) {
               this.lines.clear();
               this.partial.setLength(0);
               this.firstSeq = 0L;
            }

            this.proc = var4.start();
            this.startedAt = System.currentTimeMillis();
            this.stopRequested = false;
            this.state = "starting";
            this.lastAction = "Запуск " + LocalTime.now().withNano(0);
            this.stdin = new OutputStreamWriter(this.proc.getOutputStream(), StandardCharsets.UTF_8);
            Process var5 = this.proc;
            Thread var6 = new Thread(() -> {
               char[] var2x = new char[8192];

               int var4x;
               try (InputStreamReader var3x = new InputStreamReader(var5.getInputStream(), StandardCharsets.UTF_8)) {
                  while ((var4x = var3x.read(var2x)) > 0) {
                     String var5x = new String(var2x, 0, var4x);
                     this.addOutput(var5x);
                     if (var5x.contains("SERVER STARTED")) {
                        this.state = "running";
                     }

                     if (var5x.contains("Press any key to continue") || var5x.contains("Для продолжения нажмите любую клавишу")) {
                        try {
                           this.stdin.write("\r\n");
                           this.stdin.flush();
                        } catch (IOException var8) {
                        }
                     }
                  }
               } catch (IOException var10) {
               }

               this.onExit(var5);
            }, "pz-console");
            var6.setDaemon(true);
            var6.start();
            return "Запускаю сервер";
         }
      } else {
         return "Сервер уже запущен";
      }
   }

   private void onExit(Process var1) {
      try {
         var1.waitFor();
      } catch (InterruptedException var3) {
      }

      if (var1 == this.proc) {
         this.addOutput("\n[PZPanel] Процесс сервера завершился, код " + var1.exitValue() + "\n");
         boolean var2 = !this.stopRequested;
         if (!this.state.equals("restarting")) {
            this.state = "stopped";
         }

         if (var2 && this.cfg.getBool("autoRestartOnCrash")) {
            this.lastAction = "Сервер упал — автоперезапуск через 15 с";
            this.sched.schedule(() -> {
               try {
                  this.start();
               } catch (IOException var2x) {
                  this.lastAction = "Автоперезапуск не удался: " + var2x.getMessage();
               }
            }, 15L, TimeUnit.SECONDS);
         }
      }
   }

   String stop(boolean var1) throws IOException {
      if (this.remote()) {
         if (var1) {
            throw new IOException("Принудительное завершение недоступно: сервер в другом контейнере (docker stop / kill)");
         }
         if (!this.remoteUp()) {
            this.state = "stopped";
            return "Сервер не запущен";
         }
         this.state = "stopping";
         this.lastAction = "Остановка " + LocalTime.now().withNano(0);
         try {
            this.sendConsole("save");
            Thread.sleep(3000L);
            this.sendConsole("quit");
         } catch (Exception e) {
            throw new IOException("Не удалось отправить quit: " + e.getMessage());
         }
         return "Отправлены save и quit. Если у контейнера restart policy — сервер поднимется заново; остановить совсем — docker stop";
      }
      this.stopRequested = true;
      Optional var2 = this.ownedAlive() ? Optional.of(this.proc.toHandle()) : this.externalServer();
      if (var2.isEmpty()) {
         this.state = "stopped";
         return "Сервер не запущен";
      } else {
         this.state = "stopping";
         this.lastAction = (var1 ? "Принудительная остановка " : "Остановка ") + LocalTime.now().withNano(0);
         if (var1) {
            this.killTree((ProcessHandle)var2.get());
            return "Процесс сервера убит";
         } else {
            try {
               this.sendConsole("save");
               Thread.sleep(3000L);
               this.sendConsole("quit");
            } catch (Exception var4) {
               this.addOutput("[PZPanel] Не удалось отправить quit: " + var4.getMessage() + "\n");
            }

            ProcessHandle var3 = (ProcessHandle)var2.get();
            this.sched.schedule(() -> {
               if (var3.isAlive()) {
                  this.addOutput("[PZPanel] Сервер не закрылся за 3 минуты — завершаю принудительно\n");
                  this.killTree(var3);
               }
            }, 180L, TimeUnit.SECONDS);
            return "Отправлены save и quit, сервер сохраняется и выключается";
         }
      }
   }

   void stopAndWait(int var1) {
      if (this.ownedAlive()) {
         Process var2 = this.proc;
         this.stopRequested = true;
         this.state = "stopping";

         try {
            this.sendConsole("save");
            Thread.sleep(3000L);
            this.sendConsole("quit");
         } catch (Exception var5) {
         }

         try {
            if (!var2.waitFor(var1, TimeUnit.SECONDS)) {
               this.killTree(var2.toHandle());
            }
         } catch (InterruptedException var4) {
         }
      }
   }

   private void killTree(ProcessHandle var1) {
      var1.descendants().forEach(ProcessHandle::destroyForcibly);
      var1.destroyForcibly();
   }

   String restart(int var1) {
      if (this.state.equals("restarting")) {
         return "Перезапуск уже идёт";
      } else {
         this.state = "restarting";
         this.lastAction = "Перезапуск " + (var1 > 0 ? "через " + var1 + " мин" : "сейчас");
         this.sched.submit(() -> {
            try {
               boolean var2 = this.isRunning();
               if (var2 && var1 > 0) {
                  for (int var3 = var1; var3 > 0; var3--) {
                     this.broadcast("Перезапуск сервера через " + var3 + " мин.");
                     Thread.sleep(60000L);
                  }

                  this.broadcast("Перезапуск сервера! Сохраняемся...");
                  Thread.sleep(5000L);
               }

               if (var2) {
                  this.stop(false);
                  long var6 = System.currentTimeMillis() + 200000L;

                  while (this.isRunning() && System.currentTimeMillis() < var6) {
                     Thread.sleep(1000L);
                  }

                  Thread.sleep(3000L);
               }

               this.state = "stopped";
               if (!this.remote()) {
                  this.start();
               }
            } catch (Exception var5) {
               this.state = this.ownedAlive() ? "running" : "stopped";
               this.lastAction = "Перезапуск не удался: " + var5.getMessage();
            }
         });
         return this.lastAction;
      }
   }

   void broadcast(String var1) {
      try {
         this.sendConsole("servermsg \"" + var1.replace("\"", "'") + "\"");
      } catch (IOException var3) {
      }
   }

   private void dailyRestartTick() {
      String var1 = this.cfg.get("dailyRestart");
      if (!var1.isBlank()) {
         try {
            LocalTime var2 = LocalTime.parse(var1);
            int var3 = this.cfg.getInt("restartWarnMinutes", 5);
            LocalDateTime var4 = LocalDate.now().atTime(var2).minusMinutes(var3);
            String var5 = LocalDate.now() + " " + var1;
            LocalDateTime var6 = LocalDateTime.now();
            if (!var5.equals(this.lastDailyRun)
               && !var6.isBefore(var4)
               && var6.isBefore(var4.plusMinutes(2L))
               && this.isRunning()) {
               this.lastDailyRun = var5;
               this.restart(var3);
            }
         } catch (Exception var7) {
         }
      }
   }
}
