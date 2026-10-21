package pzpanel;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Line-preserving editors for the server .ini and SandboxVars.lua. */
final class Configs {

    // ---------------- server .ini ----------------
    static List<Map<String, Object>> readIni(Path ini) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(ini)) return out;
        String[] lines = Util.readText(ini).split("\r?\n", -1);
        List<String> comment = new ArrayList<>();
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("#")) { comment.add(t.replaceFirst("^#\\s?", "")); continue; }
            int eq = l.indexOf('=');
            if (eq > 0 && !t.isEmpty()) {
                Map<String, Object> e = new LinkedHashMap<>();
                String k = l.substring(0, eq).trim();
                e.put("key", k);
                e.put("value", l.substring(eq + 1));
                e.put("comment", String.join("\n", comment));
                e.put("secret", k.toLowerCase(Locale.ROOT).contains("password") || k.equalsIgnoreCase("DiscordToken"));
                out.add(e);
            }
            comment.clear();
        }
        return out;
    }

    static String iniValue(Path ini, String key) {
        try {
            for (String l : Util.readText(ini).split("\r?\n")) {
                int eq = l.indexOf('=');
                if (eq > 0 && l.substring(0, eq).trim().equals(key)) return l.substring(eq + 1).trim();
            }
        } catch (IOException ignored) {}
        return "";
    }

    /** Replaces values of existing keys; appends unknown keys at the end. */
    static void writeIni(Path ini, Map<String, String> changes, Path backupDir) throws IOException {
        String text = Util.readText(ini);
        String sep = Util.lineSep(text);
        String[] lines = text.split("\r?\n", -1);
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            String k = l.substring(0, eq).trim();
            if (changes.containsKey(k)) { lines[i] = k + "=" + clean(changes.get(k)); done.add(k); }
        }
        StringBuilder sb = new StringBuilder(String.join(sep, lines));
        for (var e : changes.entrySet()) if (!done.contains(e.getKey())) {
            if (sb.length() > 0 && !sb.toString().endsWith(sep)) sb.append(sep);
            sb.append(e.getKey()).append('=').append(clean(e.getValue())).append(sep);
        }
        Util.writeTextWithBackup(ini, sb.toString(), backupDir);
    }

    private static String clean(String v) { return v == null ? "" : v.replace("\r", "").replace("\n", " "); }

    // ---------------- SandboxVars.lua ----------------
    private static final Pattern OPEN = Pattern.compile("^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\{\\s*$");
    private static final Pattern KV = Pattern.compile("^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\"(?:[^\"\\\\]++|\\\\.)*+\"|[^,{}]+?)\\s*,\\s*(--.*)?$");
    private static final Pattern CLOSE = Pattern.compile("^\\s*\\}\\s*,?\\s*$");

    static List<Map<String, Object>> readSandbox(Path lua) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(lua)) return out;
        String[] lines = Util.readText(lua).split("\r?\n", -1);
        Deque<String> path = new ArrayDeque<>();
        List<String> comment = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String t = l.trim();
            if (t.startsWith("--")) { comment.add(t.replaceFirst("^--\\s?", "")); continue; }
            Matcher m;
            if ((m = OPEN.matcher(l)).matches()) { path.addLast(m.group(2)); comment.clear(); continue; }
            if (CLOSE.matcher(l).matches()) { if (!path.isEmpty()) path.removeLast(); comment.clear(); continue; }
            if ((m = KV.matcher(l)).matches()) {
                List<String> parts = new ArrayList<>(path);
                if (!parts.isEmpty() && parts.get(0).equals("SandboxVars")) parts.remove(0);
                String raw = m.group(3);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("line", i);
                e.put("group", parts.isEmpty() ? "Основные" : String.join(".", parts));
                e.put("key", m.group(2));
                e.put("value", raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2 ? raw.substring(1, raw.length() - 1) : raw);
                e.put("type", raw.equals("true") || raw.equals("false") ? "bool" : raw.startsWith("\"") ? "string" : raw.matches("-?[0-9.]+") ? "number" : "raw");
                e.put("comment", String.join("\n", comment));
                out.add(e);
            }
            comment.clear();
        }
        return out;
    }

    /** changes: list of {line, key, value, type}. Only the value part of that exact line is rewritten. */
    static int writeSandbox(Path lua, List<Map<String, Object>> changes, Path backupDir) throws IOException {
        String text = Util.readText(lua);
        String sep = Util.lineSep(text);
        String[] lines = text.split("\r?\n", -1);
        int n = 0;
        for (Map<String, Object> c : changes) {
            int idx = ((Number) c.get("line")).intValue();
            if (idx < 0 || idx >= lines.length) continue;
            Matcher m = KV.matcher(lines[idx]);
            if (!m.matches() || !m.group(2).equals(String.valueOf(c.get("key")))) continue;
            String type = String.valueOf(c.get("type"));
            String v = String.valueOf(c.get("value"));
            String lit = switch (type) {
                case "bool" -> Boolean.parseBoolean(v) ? "true" : "false";
                case "number" -> v.matches("-?[0-9]+(\\.[0-9]+)?") ? v : null;
                case "string" -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                default -> v;
            };
            if (lit == null) continue;
            lines[idx] = m.group(1) + m.group(2) + " = " + lit + "," + (m.group(4) != null ? " " + m.group(4) : "");
            n++;
        }
        Util.writeTextWithBackup(lua, String.join(sep, lines), backupDir);
        return n;
    }
}
