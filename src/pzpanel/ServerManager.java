package pzpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Starts/stops the dedicated server, keeps its console, talks RCON, schedules restarts. */
final class ServerManager {
    private final Config cfg;
    private volatile Process proc;              // started by the panel
    private volatile Writer stdin;
    private volatile long startedAt;
    private volatile boolean stopRequested;
    private volatile String state = "stopped";  // stopped | starting | running | stopping | restarting
    private volatile String lastAction = "";
    private volatile long configChangedAt = 0;
    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);

    // console ring buffer (only for a panel-owned process)
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private long firstSeq = 0;
    private final StringBuilder partial = new StringBuilder();
    private static final int MAX_LINES = 6000;

    ServerManager(Config cfg) {
        this.cfg = cfg;
        sched.scheduleAtFixedRate(this::dailyRestartTick, 30, 30, TimeUnit.SECONDS);
    }

    // ---------------- status ----------------
    boolean ownedAlive() { Process p = proc; return p != null && p.isAlive(); }

    /** A java.exe from the server's own jre64 that isn't us = a server started outside the panel. */
    Optional<ProcessHandle> externalServer() {
        if (ownedAlive()) return Optional.empty();
        Path jre = cfg.serverDir().resolve("jre64").toAbsolutePath().normalize();
        Path srvDir = cfg.serverDir().toAbsolutePath().normalize();
        long me = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses().filter(ph -> ph.pid() != me).filter(ph -> {
            ProcessHandle.Info i = ph.info();
            String cmdline = i.commandLine().orElse("");
            if (cmdline.contains("zombie.network.GameServer")) return true;
            String c = i.command().orElse("");
            if (c.isEmpty()) return false;
            try {
                Path cp = Paths.get(c).toAbsolutePath().normalize();
                // Linux: the native launcher ProjectZomboid64 hosts the JVM in-process
                if (cp.startsWith(srvDir) && cp.getFileName().toString().startsWith("ProjectZomboid64")) return true;
                return cp.startsWith(jre) && (cmdline.isEmpty() || !cmdline.contains("pzpanel."));
            } catch (Exception e) { return false; }
        }).findFirst();
    }

    /** The server's root process (panel-owned launcher or an external server), for CPU/RAM stats. */
    Optional<ProcessHandle> serverHandle() {
        Process p = proc;
        if (p != null && p.isAlive()) return Optional.of(p.toHandle());
        return externalServer();
    }

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean owned = ownedAlive();
        Optional<ProcessHandle> ext = owned ? Optional.empty() : externalServer();
        boolean running = owned || ext.isPresent();
        if (!running && !state.equals("starting") && !state.equals("restarting")) state = "stopped";
        if (running && state.equals("stopped")) state = "running";
        m.put("state", state);
        m.put("running", running);
        m.put("owned", owned);
        long pid = owned ? proc.pid() : ext.map(ProcessHandle::pid).orElse(-1L);
        m.put("pid", pid);
        long st = owned ? startedAt : ext.flatMap(h -> h.info().startInstant()).map(Instant::toEpochMilli).orElse(0L);
        m.put("startedAt", st);
        m.put("uptimeSec", st > 0 && running ? (System.currentTimeMillis() - st) / 1000 : 0);
        m.put("lastAction", lastAction);
        m.put("needsRestart", running && configChangedAt > st && st > 0);
        m.put("waitingInput", owned && promptPending());
        m.put("serverName", cfg.serverName());
        m.put("dailyRestart", cfg.get("dailyRestart"));
        m.put("autoRestartOnCrash", cfg.getBool("autoRestartOnCrash"));
        return m;
    }

    void markConfigChanged() { configChangedAt = System.currentTimeMillis(); }

    // ---------------- console ----------------
    private synchronized void addOutput(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\n') { pushLine(partial.toString()); partial.setLength(0); }
            else if (c != '\r') partial.append(c);
        }
    }

    private synchronized void pushLine(String l) {
        lines.addLast(l);
        while (lines.size() > MAX_LINES) { lines.removeFirst(); firstSeq++; }
    }

    synchronized boolean promptPending() {
        String p = partial.toString().trim();
        return p.endsWith("[y/n]:") || p.endsWith("[y/N]:") || p.endsWith("(y/n)") || p.toLowerCase(Locale.ROOT).endsWith("password:");
    }

    /** cursor: for the owned process = line sequence number; otherwise byte offset in server-console.txt. */
    Map<String, Object> console(long cursor) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (proc != null) {
            synchronized (this) {
                long last = firstSeq + lines.size();
                long from = cursor < 0 ? Math.max(firstSeq, last - 400) : Math.max(cursor, firstSeq);
                List<String> out = new ArrayList<>();
                int idx = 0;
                for (String l : lines) { if (firstSeq + idx >= from) out.add(l); idx++; }
                m.put("mode", "process");
                m.put("lines", out);
                m.put("partial", partial.toString());
                m.put("cursor", last);
                m.put("reset", cursor >= 0 && cursor < firstSeq);
            }
            return m;
        }
        Path f = cfg.consoleLog();
        m.put("mode", "file");
        m.put("partial", "");
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length();
            long from = cursor < 0 || cursor > len ? Math.max(0, len - 64 * 1024) : cursor;
            if (len - from > 512 * 1024) from = len - 512 * 1024;
            byte[] b = new byte[(int) (len - from)];
            raf.seek(from); raf.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(Arrays.asList(s.split("\r?\n", -1)));
            if (cursor < 0 && !out.isEmpty()) out.remove(0); // probably cut in the middle
            if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
            m.put("lines", out);
            m.put("cursor", len);
            m.put("reset", cursor > len);
        } catch (IOException e) {
            m.put("lines", List.of());
            m.put("cursor", 0);
            m.put("error", "Лог не найден: " + f);
        }
        return m;
    }

    /** Typed into the console: stdin if we own the process, RCON otherwise. */
    String sendConsole(String cmd) throws IOException {
        if (ownedAlive() && stdin != null) {
            synchronized (this) { pushLine("> " + cmd); }
            stdin.write(cmd + (Util.isWindows() ? "\r\n" : "\n")); stdin.flush();
            return "";
        }
        return rcon(cmd);
    }

    String rcon(String cmd) throws IOException {
        Path ini = cfg.serverIni();
        int port = 27015;
        try { port = Integer.parseInt(Configs.iniValue(ini, "RCONPort")); } catch (Exception ignored) {}
        return Rcon.exec("127.0.0.1", port, Configs.iniValue(ini, "RCONPassword"), cmd, 5000);
    }

    // ---------------- lifecycle ----------------
    synchronized String start() throws IOException {
        if (ownedAlive() || externalServer().isPresent()) return "Сервер уже запущен";
        Path dir = cfg.serverDir();
        Path script = dir.resolve(cfg.get("startScript"));
        if (!Files.exists(script)) throw new IOException("Не найден " + script);
        List<String> cmd = Util.isWindows()
                ? List.of("cmd.exe", "/c", script.getFileName().toString())
                : List.of("bash", script.getFileName().toString(), "-servername", cfg.serverName());
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        synchronized (this) { lines.clear(); partial.setLength(0); firstSeq = 0; }
        proc = pb.start();
        startedAt = System.currentTimeMillis();
        stopRequested = false;
        state = "starting";
        lastAction = "Запуск " + LocalTime.now().withNano(0);
        stdin = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
        Process p = proc;
        Thread reader = new Thread(() -> {
            char[] buf = new char[8192];
            try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                int n;
                while ((n = r.read(buf)) > 0) {
                    String s = new String(buf, 0, n);
                    addOutput(s);
                    if (s.contains("SERVER STARTED")) state = "running";
                    if (s.contains("Press any key to continue") || s.contains("Для продолжения нажмите любую клавишу")) {
                        try { stdin.write("\r\n"); stdin.flush(); } catch (IOException ignored) {}
                    }
                }
            } catch (IOException ignored) {}
            onExit(p);
        }, "pz-console");
        reader.setDaemon(true);
        reader.start();
        return "Запускаю сервер";
    }

    private void onExit(Process p) {
        try { p.waitFor(); } catch (InterruptedException ignored) {}
        if (p != proc) return;
        addOutput("\n[PZPanel] Процесс сервера завершился, код " + p.exitValue() + "\n");
        boolean crashed = !stopRequested;
        if (!state.equals("restarting")) state = "stopped";
        if (crashed && cfg.getBool("autoRestartOnCrash")) {
            lastAction = "Сервер упал — автоперезапуск через 15 с";
            sched.schedule(() -> { try { start(); } catch (IOException e) { lastAction = "Автоперезапуск не удался: " + e.getMessage(); } }, 15, TimeUnit.SECONDS);
        }
    }

    /** Graceful stop: save + quit (stdin or RCON), then wait; force-kill the whole tree on timeout or when force=true. */
    String stop(boolean force) throws IOException {
        stopRequested = true;
        Optional<ProcessHandle> h = ownedAlive() ? Optional.of(proc.toHandle()) : externalServer();
        if (h.isEmpty()) { state = "stopped"; return "Сервер не запущен"; }
        state = "stopping";
        lastAction = (force ? "Принудительная остановка " : "Остановка ") + LocalTime.now().withNano(0);
        if (force) { killTree(h.get()); return "Процесс сервера убит"; }
        try { sendConsole("save"); Thread.sleep(3000); sendConsole("quit"); }
        catch (Exception e) { addOutput("[PZPanel] Не удалось отправить quit: " + e.getMessage() + "\n"); }
        ProcessHandle ph = h.get();
        sched.schedule(() -> { if (ph.isAlive()) { addOutput("[PZPanel] Сервер не закрылся за 3 минуты — завершаю принудительно\n"); killTree(ph); } }, 180, TimeUnit.SECONDS);
        return "Отправлены save и quit, сервер сохраняется и выключается";
    }

    /** For the panel's own shutdown (service stop / reboot): save + quit the panel-owned server and wait. */
    void stopAndWait(int seconds) {
        if (!ownedAlive()) return;
        Process p = proc;
        stopRequested = true;
        state = "stopping";
        try { sendConsole("save"); Thread.sleep(3000); sendConsole("quit"); } catch (Exception ignored) {}
        try { if (!p.waitFor(seconds, TimeUnit.SECONDS)) killTree(p.toHandle()); } catch (InterruptedException ignored) {}
    }

    private void killTree(ProcessHandle h) {
        h.descendants().forEach(ProcessHandle::destroyForcibly);
        h.destroyForcibly();
    }

    /** Restart with optional countdown broadcast to players. */
    String restart(int warnMinutes) {
        if (state.equals("restarting")) return "Перезапуск уже идёт";
        state = "restarting";
        lastAction = "Перезапуск " + (warnMinutes > 0 ? "через " + warnMinutes + " мин" : "сейчас");
        sched.submit(() -> {
            try {
                boolean running = ownedAlive() || externalServer().isPresent();
                if (running && warnMinutes > 0) {
                    for (int m = warnMinutes; m > 0; m--) {
                        broadcast("Перезапуск сервера через " + m + " мин.");
                        Thread.sleep(60_000);
                    }
                    broadcast("Перезапуск сервера! Сохраняемся...");
                    Thread.sleep(5000);
                }
                if (running) {
                    stop(false);
                    long until = System.currentTimeMillis() + 200_000;
                    while ((ownedAlive() || externalServer().isPresent()) && System.currentTimeMillis() < until) Thread.sleep(1000);
                    Thread.sleep(3000);
                }
                state = "stopped";
                start();
            } catch (Exception e) {
                state = ownedAlive() ? "running" : "stopped";
                lastAction = "Перезапуск не удался: " + e.getMessage();
            }
        });
        return lastAction;
    }

    void broadcast(String msg) {
        try { sendConsole("servermsg \"" + msg.replace("\"", "'") + "\""); } catch (IOException ignored) {}
    }

    private volatile String lastDailyRun = "";
    private void dailyRestartTick() {
        String t = cfg.get("dailyRestart");
        if (t.isBlank()) return;
        try {
            LocalTime target = LocalTime.parse(t);
            int warn = cfg.getInt("restartWarnMinutes", 5);
            LocalDateTime fireAt = LocalDate.now().atTime(target).minusMinutes(warn);
            String key = LocalDate.now() + " " + t;
            LocalDateTime now = LocalDateTime.now();
            if (!key.equals(lastDailyRun) && !now.isBefore(fireAt) && now.isBefore(fireAt.plusMinutes(2))
                    && (ownedAlive() || externalServer().isPresent())) {
                lastDailyRun = key;
                restart(warn);
            }
        } catch (Exception ignored) {}
    }
}
