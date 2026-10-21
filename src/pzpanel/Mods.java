package pzpanel;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/** Mod list in the server .ini, mod.info scanning in workshop folders, Steam Workshop lookups. */
final class Mods {
    private final Config cfg;
    private final Map<String, Map<String, Object>> steamCache = new ConcurrentHashMap<>();

    Mods(Config cfg) { this.cfg = cfg; }

    record ModInfo(String id, String name, String workshopId, String folder, String versionDir, List<String> require,
                   List<String> loadAfter, List<String> loadBefore, List<String> incompatible,
                   String versionMin, String versionMax, boolean java, String poster, boolean local) {}

    // ------------ scanning ------------
    Map<String, List<ModInfo>> scan() {
        Map<String, List<ModInfo>> byId = new LinkedHashMap<>();
        double gv = parseVer(cfg.get("gameVersion", "42.21"));
        // 1) local (frozen) mods: Zomboid/mods/<folder>; workshop id from _frozen_manifest.txt if present
        Path local = cfg.zomboidDir().resolve("mods");
        if (Files.isDirectory(local)) {
            Map<String, String> folderToWid = new HashMap<>();
            try {
                Path man = local.resolve("_frozen_manifest.txt");
                if (Files.exists(man)) for (String l : Util.readText(man).split("\r?\n")) {
                    if (l.startsWith("#")) continue;
                    String[] c = l.split("\t");
                    if (c.length >= 2) for (String f : c[1].split(",")) if (!f.isBlank()) folderToWid.put(f.trim(), c[0].trim());
                }
            } catch (IOException ignored) {}
            try (var mods = Files.list(local)) {
                for (Path mod : (Iterable<Path>) mods::iterator) {
                    if (!Files.isDirectory(mod)) continue;
                    ModInfo mi = readMod(mod, folderToWid.getOrDefault(mod.getFileName().toString(), ""), gv, true);
                    if (mi != null) byId.computeIfAbsent(mi.id(), k -> new ArrayList<>()).add(mi);
                }
            } catch (IOException ignored) {}
        }
        for (Path wd : cfg.workshopDirs()) {
            if (!Files.isDirectory(wd)) continue;
            boolean isLocalWorkshop = wd.getFileName().toString().equals("Workshop");
            try (var items = Files.list(wd)) {
                for (Path item : (Iterable<Path>) items::iterator) {
                    Path modsDir = isLocalWorkshop ? item.resolve("Contents").resolve("mods") : item.resolve("mods");
                    if (!Files.isDirectory(modsDir)) continue;
                    String wid = isLocalWorkshop ? "" : item.getFileName().toString();
                    try (var mods = Files.list(modsDir)) {
                        for (Path mod : (Iterable<Path>) mods::iterator) {
                            ModInfo mi = readMod(mod, wid, gv, false);
                            if (mi != null) byId.computeIfAbsent(mi.id(), k -> new ArrayList<>()).add(mi);
                        }
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }
        return byId;
    }

    private static ModInfo readMod(Path mod, String wid, double gv, boolean local) {
        Path best = null; double bestV = -1;
        try (var s = Files.list(mod)) {
            for (Path d : (Iterable<Path>) s::iterator) {
                String n = d.getFileName().toString();
                if (!Files.isDirectory(d) || !n.matches("\\d+(\\.\\d+)*")) continue;
                double v = parseVer(n);
                if (v <= gv + 1e-9 && v > bestV && Files.exists(d.resolve("mod.info"))) { best = d; bestV = v; }
            }
        } catch (IOException ignored) {}
        if (best == null) {
            if (Files.exists(mod.resolve("common").resolve("mod.info"))) best = mod.resolve("common");
            else if (Files.exists(mod.resolve("mod.info"))) best = mod;
            else return null;
        }
        Map<String, String> kv = new HashMap<>();
        Map<String, List<String>> multi = new HashMap<>();
        try {
            for (String l : Util.readText(best.resolve("mod.info")).split("\r?\n")) {
                int eq = l.indexOf('=');
                if (eq <= 0) continue;
                String k = l.substring(0, eq).trim(), v = l.substring(eq + 1).trim();
                kv.putIfAbsent(k, v);
                multi.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
        } catch (IOException e) { return null; }
        String id = kv.get("id");
        if (id == null || id.isBlank()) return null;
        boolean java = Files.isDirectory(best.resolve("media").resolve("java")) || Files.isDirectory(mod.resolve("common").resolve("media").resolve("java"));
        return new ModInfo(id.trim(), kv.getOrDefault("name", id), wid, mod.getFileName().toString(),
                best.getFileName().toString(), list(multi.get("require")), list(multi.get("loadModAfter")),
                list(multi.get("loadModBefore")), list(multi.get("incompatible")),
                kv.getOrDefault("versionMin", ""), kv.getOrDefault("versionMax", ""), java,
                Files.exists(best.resolve("poster.png")) ? best.resolve("poster.png").toString() : "", local);
    }

    private static List<String> list(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String r : raw) for (String x : r.split("[,;]")) {
            String t = x.trim().replaceFirst("^\\\\+", "");
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static double parseVer(String v) {
        try {
            String[] p = v.trim().split("\\.");
            double r = Double.parseDouble(p[0]);
            if (p.length > 1) r += Double.parseDouble("0." + String.format("%03d", Integer.parseInt(p[1].replaceAll("\\D", "0"))));
            return r;
        } catch (Exception e) { return 0; }
    }

    // ------------ current state ------------
    static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split(";")) { String t = x.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    Map<String, Object> state() {
        Path ini = cfg.serverIni();
        List<String> mods = splitList(Configs.iniValue(ini, "Mods"));
        List<String> items = splitList(Configs.iniValue(ini, "WorkshopItems"));
        Map<String, List<ModInfo>> scan = scan();
        Set<String> itemSet = new HashSet<>(items);
        double gv = parseVer(cfg.get("gameVersion", "42.21"));

        List<Map<String, Object>> enabled = new ArrayList<>();
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < mods.size(); i++) pos.put(strip(mods.get(i)), i);
        for (int i = 0; i < mods.size(); i++) {
            String raw = mods.get(i), id = strip(raw);
            ModInfo mi = pick(scan.get(id), itemSet);
            Map<String, Object> m = describe(id, mi);
            m.put("raw", raw);
            List<String> issues = new ArrayList<>();
            if (mi == null) issues.add(items.isEmpty() ? "Файлы не найдены в Zomboid/mods — моды заморожены, сервер его не скачает" : "Файлы не найдены — мод скачается при запуске сервера (если его предмет есть в списке Мастерской)");
            else {
                for (String r : mi.require()) {
                    Integer p = pos.get(r);
                    if (p == null) issues.add("Требует " + r + ", но он не включён");
                    else if (p > i) issues.add("Требует " + r + " — он должен стоять выше");
                }
                for (String a : mi.loadAfter()) { Integer p = pos.get(a); if (p != null && p > i) issues.add("Должен грузиться после " + a); }
                for (String b : mi.loadBefore()) { Integer p = pos.get(b); if (p != null && p < i) issues.add("Должен грузиться до " + b); }
                for (String x : mi.incompatible()) if (pos.containsKey(x)) issues.add("Несовместим с " + x);
                if (!mi.versionMax().isBlank() && parseVer(mi.versionMax()) + 1e-9 < gv) issues.add("versionMax " + mi.versionMax() + " ниже версии игры");
                if (!mi.local() && !mi.workshopId().isEmpty() && !itemSet.contains(mi.workshopId())) issues.add("Предмет Мастерской " + mi.workshopId() + " не в списке WorkshopItems — игроки его не скачают");
            }
            m.put("issues", issues);
            enabled.add(m);
        }
        List<Map<String, Object>> available = new ArrayList<>();
        for (var e : scan.entrySet()) {
            if (pos.containsKey(e.getKey())) continue;
            ModInfo mi = pick(e.getValue(), itemSet);
            Map<String, Object> m = describe(e.getKey(), mi);
            m.put("inWorkshopItems", mi != null && itemSet.contains(mi.workshopId()));
            available.add(m);
        }
        available.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));

        Set<String> usedItems = new HashSet<>();
        for (Map<String, Object> m : enabled) usedItems.add(String.valueOf(m.get("workshopId")));
        List<Map<String, Object>> itemList = new ArrayList<>();
        for (String it : items) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", it);
            x.put("downloaded", cfg.workshopDirs().stream().anyMatch(d -> Files.isDirectory(d.resolve(it))));
            x.put("usedByEnabledMod", usedItems.contains(it));
            Map<String, Object> c = steamCache.get(it);
            if (c != null) x.put("title", c.get("title"));
            itemList.add(x);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("enabled", enabled);
        r.put("available", available);
        r.put("workshopItems", itemList);
        r.put("map", Configs.iniValue(ini, "Map"));
        return r;
    }

    private static String strip(String s) { return s.replaceFirst("^\\\\+", ""); }

    private static ModInfo pick(List<ModInfo> l, Set<String> preferItems) {
        if (l == null || l.isEmpty()) return null;
        for (ModInfo m : l) if (preferItems.contains(m.workshopId())) return m;
        return l.get(0);
    }

    private static Map<String, Object> describe(String id, ModInfo mi) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", mi == null ? id : mi.name());
        m.put("workshopId", mi == null ? "" : mi.workshopId());
        m.put("installed", mi != null);
        if (mi != null) {
            m.put("require", mi.require());
            m.put("loadAfter", mi.loadAfter());
            m.put("versionMin", mi.versionMin());
            m.put("versionMax", mi.versionMax());
            m.put("versionDir", mi.versionDir());
            m.put("java", mi.java());
            m.put("local", mi.local());
            m.put("hasPoster", !mi.poster().isEmpty());
        }
        return m;
    }

    Path posterPath(String modId) {
        List<ModInfo> l = scan().get(modId);
        if (l == null) return null;
        for (ModInfo m : l) if (!m.poster().isEmpty()) return Paths.get(m.poster());
        return null;
    }

    /** Saves Mods= and WorkshopItems=; optionally mirrors the order into the client mods/default.txt. */
    void save(List<String> mods, List<String> items, boolean syncClient) throws IOException {
        Map<String, String> ch = new LinkedHashMap<>();
        ch.put("Mods", String.join(";", mods));
        ch.put("WorkshopItems", String.join(";", items));
        Configs.writeIni(cfg.serverIni(), ch, cfg.fileBackupDir());
        if (syncClient) writeClientMods(mods);
    }

    void writeClientMods(List<String> mods) throws IOException {
        Path f = cfg.clientModsFile();
        String old = Files.exists(f) ? Util.readText(f) : "VERSION = 1,\n\nmods\n{\n}\n\nmaps\n{\n}\n";
        String sep = Util.lineSep(old);
        StringBuilder body = new StringBuilder("mods").append(sep).append('{').append(sep);
        for (String m : mods) body.append("    mod = ").append(strip(m)).append(',').append(sep);
        body.append('}');
        Matcher mm = Pattern.compile("mods\\s*\\{[^}]*\\}", Pattern.DOTALL).matcher(old);
        String out = mm.find() ? old.substring(0, mm.start()) + body + old.substring(mm.end()) : old + sep + body + sep;
        Files.createDirectories(f.getParent());
        Util.writeTextWithBackup(f, out, cfg.fileBackupDir());
    }

    // ------------ Steam Workshop ------------
    List<Map<String, Object>> details(Collection<String> ids) throws IOException, InterruptedException {
        List<String> need = new ArrayList<>();
        for (String id : ids) if (!steamCache.containsKey(id)) need.add(id);
        for (int start = 0; start < need.size(); start += 100) {
            List<String> chunk = need.subList(start, Math.min(need.size(), start + 100));
            StringBuilder f = new StringBuilder("itemcount=" + chunk.size());
            for (int i = 0; i < chunk.size(); i++) f.append("&publishedfileids%5B").append(i).append("%5D=").append(chunk.get(i));
            JsonNode root = Util.JSON.readTree(Util.httpPostForm("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/", f.toString()));
            for (JsonNode d : root.path("response").path("publishedfiledetails")) {
                Map<String, Object> m = new LinkedHashMap<>();
                String id = d.path("publishedfileid").asText();
                m.put("id", id);
                m.put("ok", d.path("result").asInt() == 1);
                m.put("title", d.path("title").asText(""));
                m.put("preview", d.path("preview_url").asText(""));
                m.put("updated", d.path("time_updated").asLong(0));
                m.put("size", d.path("file_size").asLong(0));
                m.put("subs", d.path("lifetime_subscriptions").asLong(d.path("subscriptions").asLong(0)));
                List<String> tags = new ArrayList<>();
                for (JsonNode t : d.path("tags")) tags.add(t.path("tag").asText());
                m.put("tags", tags);
                String desc = d.path("description").asText("");
                m.put("modIds", parseModIds(desc));
                m.put("description", desc.length() > 1500 ? desc.substring(0, 1500) + "…" : desc);
                steamCache.put(id, m);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) { Map<String, Object> m = steamCache.get(id); if (m != null) out.add(m); }
        return out;
    }

    static List<String> parseModIds(String desc) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        desc = desc.replaceAll("\\[/?[a-zA-Z0-9*]+(=[^\\]]*)?\\]", "");
        Matcher m = Pattern.compile("(?im)Mod\\s*IDs?\\s*:\\s*([^\\r\\n\\[]+)").matcher(desc);
        while (m.find()) for (String x : m.group(1).split("[,;|]")) {
            String t = x.trim().replaceAll("\\(.*?\\)", "").replaceAll("[\\s*]+$", "").trim();
            if (t.matches("[A-Za-z0-9_\\-.\\\\ ']{1,80}") && !t.isEmpty() && !t.contains("Workshop")) s.add(t.replaceFirst("^\\\\", ""));
        }
        return new ArrayList<>(s);
    }

    /** Full item info including "Required items" parsed from the public page. */
    Map<String, Object> item(String id) throws IOException, InterruptedException {
        List<Map<String, Object>> d = details(List.of(id));
        if (d.isEmpty()) throw new IOException("Предмет " + id + " не найден");
        Map<String, Object> m = new LinkedHashMap<>(d.get(0));
        List<String> req = new ArrayList<>();
        try {
            String html = Util.httpGet("https://steamcommunity.com/sharedfiles/filedetails/?id=" + id);
            int a = html.indexOf("requiredItemsContainer");
            if (a >= 0) {
                int b = html.indexOf("workshopItemDescription", a);
                String part = html.substring(a, b > a ? Math.min(b, a + 12000) : Math.min(html.length(), a + 6000));
                Matcher mm = Pattern.compile("filedetails/\\?id=(\\d+)").matcher(part);
                while (mm.find()) if (!req.contains(mm.group(1)) && !mm.group(1).equals(id)) req.add(mm.group(1));
            }
            if (html.contains("collectionChildren") || html.contains("collectionItem")) m.put("maybeCollection", true);
        } catch (Exception ignored) {}
        List<Map<String, Object>> reqd = req.isEmpty() ? List.of() : details(req);
        m.put("required", reqd);
        return m;
    }

    List<Map<String, Object>> collection(String id) throws IOException, InterruptedException {
        JsonNode root = Util.JSON.readTree(Util.httpPostForm("https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/",
                "collectioncount=1&publishedfileids%5B0%5D=" + id));
        List<String> kids = new ArrayList<>();
        for (JsonNode c : root.path("response").path("collectiondetails").path(0).path("children")) kids.add(c.path("publishedfileid").asText());
        if (kids.isEmpty()) throw new IOException("Коллекция пуста или не найдена");
        return details(kids);
    }

    List<Map<String, Object>> search(String q, int page) throws IOException, InterruptedException {
        String url = "https://steamcommunity.com/workshop/browse/?appid=108600&searchtext=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&childpublishedfileid=0&browsesort=textsearch&section=readytouseitems&actualsort=textsearch&p=" + Math.max(1, page);
        String html = Util.httpGet(url);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher m = Pattern.compile("sharedfiles/filedetails/\\?id=(\\d+)").matcher(html);
        while (m.find()) ids.add(m.group(1));
        if (ids.isEmpty()) return List.of();
        return details(new ArrayList<>(ids).subList(0, Math.min(ids.size(), 30)));
    }
}
