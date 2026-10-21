package pzpanel;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.*;

/**
 * CPU / RAM of the dedicated server: the server process plus all its descendants
 * (cmd.exe → java.exe on Windows, bash → ProjectZomboid64 on Linux), sampled every 5 s, 30 min of history.
 * CPU — ProcessHandle total CPU time; RAM — resident memory (Linux /proc/<pid>/status VmRSS, Windows tasklist "Mem Usage").
 */
final class Stats {
    static final int PERIOD_SEC = 5, HISTORY = 360;
    private final Config cfg;
    private final Supplier<Optional<ProcessHandle>> server;
    private final ArrayDeque<double[]> hist = new ArrayDeque<>();   // {epochMs, cpu% of machine, rss MB, sys cpu %, sys used MB}
    private final Map<Long, Long> prevCpu = new HashMap<>();        // pid -> cpu nanos
    private long prevAt = 0;
    private volatile Map<String, Object> last = Map.of();
    private final com.sun.management.OperatingSystemMXBean os;
    private final int cores = Runtime.getRuntime().availableProcessors();
    private long peakRss = 0, peakSince = 0;
    private long rootPid = -1;

    Stats(Config cfg, Supplier<Optional<ProcessHandle>> server) {
        this.cfg = cfg;
        this.server = server;
        java.lang.management.OperatingSystemMXBean b = ManagementFactory.getOperatingSystemMXBean();
        os = b instanceof com.sun.management.OperatingSystemMXBean x ? x : null;
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "pz-stats"); t.setDaemon(true); return t; });
        s.scheduleWithFixedDelay(() -> { try { sample(); } catch (Throwable ignored) {} }, 1, PERIOD_SEC, TimeUnit.SECONDS);
    }

    private void sample() {
        long now = System.currentTimeMillis();
        Optional<ProcessHandle> root = server.get();
        Map<String, Object> m = new LinkedHashMap<>();
        double sysCpu = -1, sysUsedMb = -1, sysTotalMb = -1;
        if (os != null) {
            sysCpu = os.getCpuLoad() * 100;
            sysTotalMb = os.getTotalMemorySize() / 1048576.0;
            sysUsedMb = sysTotalMb - os.getFreeMemorySize() / 1048576.0;
        }
        m.put("cores", cores);
        m.put("sysCpu", sysCpu < 0 ? null : round1(sysCpu));
        m.put("sysMemTotalMb", sysTotalMb < 0 ? null : Math.round(sysTotalMb));
        m.put("sysMemUsedMb", sysUsedMb < 0 ? null : Math.round(sysUsedMb));
        if (root.isEmpty() || !root.get().isAlive()) {
            synchronized (this) { prevCpu.clear(); prevAt = 0; rootPid = -1; }
            m.put("running", false);
            last = m;
            push(now, Double.NaN, Double.NaN, sysCpu, sysUsedMb);
            return;
        }
        ProcessHandle r = root.get();
        List<ProcessHandle> tree = new ArrayList<>();
        tree.add(r);
        r.descendants().forEach(tree::add);
        Map<Long, Long> cpuNow = new HashMap<>();
        for (ProcessHandle h : tree) h.info().totalCpuDuration().map(Duration::toNanos).ifPresent(n -> cpuNow.put(h.pid(), n));
        Map<Long, Long> rss = rssKb(tree);
        long rssKb = 0;
        for (ProcessHandle h : tree) rssKb += rss.getOrDefault(h.pid(), 0L);
        double cpu = Double.NaN;
        synchronized (this) {
            if (rootPid != r.pid()) { prevCpu.clear(); prevAt = 0; rootPid = r.pid(); peakRss = 0; peakSince = now; }
            if (prevAt > 0 && now > prevAt) {
                long d = 0;
                for (Map.Entry<Long, Long> e : cpuNow.entrySet()) {
                    Long p = prevCpu.get(e.getKey());
                    d += Math.max(0, e.getValue() - (p == null ? 0 : p));   // a new child counts from 0
                }
                cpu = d / 1e6 / (now - prevAt) * 100.0 / cores;            // % of the whole machine
                cpu = Math.min(100, Math.max(0, cpu));
            }
            prevCpu.clear(); prevCpu.putAll(cpuNow); prevAt = now;
            peakRss = Math.max(peakRss, rssKb);
        }
        double rssMb = rss.isEmpty() ? Double.NaN : rssKb / 1024.0;
        m.put("running", true);
        m.put("pid", r.pid());
        m.put("processes", tree.size());
        m.put("cpu", Double.isNaN(cpu) ? null : round1(cpu));
        m.put("cpuCores", Double.isNaN(cpu) ? null : round1(cpu * cores / 100));
        m.put("rssMb", Double.isNaN(rssMb) ? null : Math.round(rssMb));
        m.put("peakRssMb", peakRss / 1024);
        m.put("xmxMb", xmxMb(tree));
        m.put("threads", threads(tree));
        last = m;
        push(now, cpu, rssMb, sysCpu, sysUsedMb);
    }

    private synchronized void push(long t, double cpu, double rssMb, double sysCpu, double sysUsed) {
        hist.addLast(new double[]{t, cpu, rssMb, sysCpu, sysUsed});
        while (hist.size() > HISTORY) hist.removeFirst();
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>(last);
        List<Object[]> h = new ArrayList<>();
        for (double[] p : hist) h.add(new Object[]{(long) p[0], v1(p[1]), v0(p[2]), v1(p[3]), v0(p[4])});
        m.put("periodSec", PERIOD_SEC);
        m.put("history", h);
        return m;
    }

    private static Object v1(double v) { return Double.isNaN(v) || v < 0 ? null : round1(v); }
    private static Object v0(double v) { return Double.isNaN(v) || v < 0 ? null : (Object) Math.round(v); }
    private static double round1(double v) { return Math.round(v * 10) / 10.0; }

    // ---------------- resident memory ----------------
    private Map<Long, Long> rssKb(List<ProcessHandle> tree) {
        Map<Long, Long> out = new HashMap<>();
        if (!Util.isWindows()) {
            for (ProcessHandle h : tree) {
                Long v = procStatus(h.pid(), "VmRSS");
                if (v != null) out.put(h.pid(), v);
            }
            return out;
        }
        // Windows: one tasklist call for all processes; "Mem Usage" = working set, e.g. "1 234 567 K" (separators depend on locale)
        try {
            Process p = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH").redirectErrorStream(true).start();
            Set<Long> want = new HashSet<>();
            for (ProcessHandle h : tree) want.add(h.pid());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = r.readLine()) != null) {
                    List<String> c = csv(line);
                    if (c.size() < 5) continue;
                    long pid;
                    try { pid = Long.parseLong(c.get(1).trim()); } catch (NumberFormatException e) { continue; }
                    if (!want.contains(pid)) continue;
                    String digits = c.get(4).replaceAll("[^0-9]", "");
                    if (!digits.isEmpty()) out.put(pid, Long.parseLong(digits));
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return out;
    }

    private static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static Long procStatus(long pid, String key) {
        try {
            for (String l : Files.readAllLines(Paths.get("/proc", String.valueOf(pid), "status"))) {
                if (l.startsWith(key + ":")) return Long.parseLong(l.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Integer threads(List<ProcessHandle> tree) {
        if (Util.isWindows()) return null;
        int n = 0;
        for (ProcessHandle h : tree) { Long v = procStatus(h.pid(), "Threads"); if (v != null) n += v; }
        return n;
    }

    // ---------------- Java heap limit (-Xmx) ----------------
    private static final Pattern XMX = Pattern.compile("-Xmx(\\d+)([kKmMgG]?)");

    private Long xmxMb(List<ProcessHandle> tree) {
        for (ProcessHandle h : tree) {
            String cl = h.info().commandLine().orElse("");
            if (cl.isEmpty() && !Util.isWindows()) {
                try { cl = new String(Files.readAllBytes(Paths.get("/proc", String.valueOf(h.pid()), "cmdline")), StandardCharsets.UTF_8).replace('\0', ' '); } catch (Exception ignored) {}
            }
            Long v = parseXmx(cl);
            if (v != null) return v;
        }
        // The Windows JDK does not expose other processes' arguments: read the launch files instead
        Path dir = cfg.serverDir();
        for (String f : new String[]{cfg.get("startScript"), "ProjectZomboid64.json", "StartServer64.bat", "start-server.sh"}) {
            try {
                Path p = dir.resolve(f);
                if (Files.isRegularFile(p)) {
                    Long v = parseXmx(Files.readString(p, StandardCharsets.ISO_8859_1));
                    if (v != null) return v;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Long parseXmx(String s) {
        Matcher m = XMX.matcher(s);
        if (!m.find()) return null;
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "g" -> n * 1024;
            case "k" -> n / 1024;
            case "m" -> n;
            default -> n / 1048576;
        };
    }
}
