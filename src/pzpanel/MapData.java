package pzpanel;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Map data read straight from the game's map files (no rendering):
 * buildings and room types (*.lotheader), streets (streets.xml), zones (objects.lua),
 * towns (../<Town>, KY/map.info) and the map's zombie density (zpop in *.lotheader).
 * lotheader layout follows pzmap2dzi (MIT, (c) 2022 Min Xiang).
 */
final class MapData {
    // ---------------- categories ----------------
    record Cat(String id, String label, String color, String icon, String[] patterns) {}
    static final List<Cat> CATS = List.of(
        new Cat("medical", "Медицина и аптеки", "#ff5c5c", "+", new String[]{"pharmacy*", "medical*", "medicine", "hospital*", "dentist*", "oldmedical", "morgue", "medclinic", "clinic", "optometrist", "vet", "coroneroffice"}),
        new Cat("police", "Полиция", "#4f8cff", "P", new String[]{"police*", "evidenceroom", "interrogationroom", "detectiveoffice", "swat*", "ww_sherrif"}),
        new Cat("guns", "Оружие и охота", "#ff8a00", "G", new String[]{"gunstore*", "gunstorage", "hunting", "hunterstorage", "armysurplus*", "knifestore", "knifeshipping", "knifefactory"}),
        new Cat("military", "Военные", "#7da33a", "A", new String[]{"army*", "military*", "oldarmy"}),
        new Cat("prison", "Тюрьма", "#a1887f", "Т", new String[]{"prison*"}),
        new Cat("fuel", "Заправки", "#ffd400", "F", new String[]{"gasstore*", "gasstorage", "fossoil", "gas2go"}),
        new Cat("food", "Продукты", "#5ccc5e", "Е", new String[]{"grocer*", "gigamart*", "conveniencestore*", "cornerstore*", "generalstore*", "ww_generalstore", "producestorage", "butcher", "knoxbutcher", "bakery*", "liquorstore", "zippee*", "candy*", "potatostorage"}),
        new Cat("fire", "Пожарные", "#ff4d2e", "О", new String[]{"firestorage", "firegarage"}),
        new Cat("cars", "Автосервис", "#b0b6bb", "М", new String[]{"mechanic", "carsupply*", "cardealershipoffice"}),
        new Cat("tools", "Инструменты, склады, заводы", "#c98a4b", "И", new String[]{"toolstore*", "ww_toolstore", "toolstorage", "garagestorage", "warehouse*", "construction", "metal*", "welding*", "carpentryworkshop", "masonrystore", "factory*", "*factory", "logging*", "storageunit", "paintershop", "gardenstore", "machinery", "workshop", "blacksmith", "ww_blacksmith"}),
        new Cat("clothing", "Одежда, сумки, спорт", "#d773da", "О", new String[]{"clothesstore*", "clothesstorage", "clothesestorage", "clothingstore", "clothingstorage", "leatherclothesstore", "shoestore", "shoestorage", "bagstore", "tailoringstore", "tailorworkshop", "sewingstore", "sewingstorage", "departmentstore*", "departmentstorage", "weddingstore*", "sportstore", "sportstorage", "outdoorsupply*", "camping*", "jewelrystore", "jewelrystorage", "walletshop"}),
        new Cat("electronics", "Электроника", "#33c9dc", "Э", new String[]{"electronic*", "radio*", "cdstore", "camerastore", "cybercafe", "movierental", "communications", "broadcasting", "serverroom", "batterystorage", "batteryfactory"}),
        new Cat("books", "Книги и библиотеки", "#c5a07a", "К", new String[]{"bookstore*", "*library", "comic*", "newsroom", "newspaper*"}),
        new Cat("eat", "Кафе, бары, рестораны", "#ff6f9a", "Р", new String[]{"restaurant*", "diner*", "pizza*", "burger*", "spiffo*", "cafe", "cafekitchen", "cafeteria*", "bar", "barkitchen", "barstorage", "barcountertwiggy", "ww_bar", "sushi*", "chinese*", "italian*", "mexican*", "western*", "donut*", "icecream*", "jayschicken*", "catfish*", "fishchips*", "seafood*", "taco*", "foodcourt", "hotdogstand", "beergarden", "brewery*", "stripclub*", "arenakitchen"}),
        new Cat("gym", "Спортзалы", "#a5d46a", "С", new String[]{"gym", "gymstorage", "boxing", "fitness"}),
        new Cat("school", "Школы и университет", "#ffbb5c", "Ш", new String[]{"*classroom*", "elementary*", "secondary*", "school*", "daycare", "university*", "musicschool"}),
        new Cat("church", "Церкви", "#eeeeee", "Ц", new String[]{"church", "officechurch", "lobbychurch", "storagechurch"}),
        new Cat("motel", "Мотели", "#8a96d6", "Н", new String[]{"motelroom*", "motelstorage"}),
        new Cat("bank", "Банки и почта", "#e0c068", "Б", new String[]{"bank*", "vault", "depositboxes", "post", "poststorage"}),
        new Cat("farm", "Фермы", "#b49480", "Ф", new String[]{"farmstorage", "barn", "greenhouse"}),
        new Cat("shop", "Другие магазины", "#cfd8dc", "•", new String[]{"*store", "pawnshop*", "florist", "kitchenwares", "giftstorage", "toystorage", "musicstorage", "furniturestorage", "furnitureshowroom"}),
        new Cat("garage", "Гаражи и сараи", "#8d939a", "г", new String[]{})
    );
    static final Set<String> GARAGE_ONLY = Set.of("garagestorage", "garage", "shed", "woodshed", "emptyoutside", "empty");
    private static final Map<String, Integer> catCache = new HashMap<>();
    /** room types that don't make a house a point of interest (a garage in a family home) */
    static final Set<String> IMPORTANT = Set.of("medical", "police", "guns", "military", "prison", "fuel", "fire");
    static final Set<String> WEAK = Set.of("garagestorage", "shed", "woodshed", "toolstorage", "storageunit", "farmstorage", "barn", "greenhouse", "post");
    static int catOf(String room) {
        Integer c = catCache.get(room);
        if (c != null) return c;
        int found = -1;
        outer:
        for (int i = 0; i < CATS.size(); i++) for (String p : CATS.get(i).patterns()) {
            boolean pre = p.endsWith("*"), suf = p.startsWith("*");
            String core = p.replace("*", "");
            boolean ok = pre && suf ? room.contains(core) : pre ? room.startsWith(core) : suf ? room.endsWith(core) : room.equals(core);
            if (ok) { found = i; break outer; }
        }
        catCache.put(room, found);
        return found;
    }

    // ---------------- data ----------------
    static final class Building {
        int id, x0, y0, x1, y1, minLayer, maxLayer, cat = -1, catMask;
        int[] rects;                 // ground (lowest layer >= 0) room rects, world coords: x,y,w,h,...
        int[][] rooms;               // [typeId, layer, area]
    }
    final List<String> roomTypes = new ArrayList<>();
    private final Map<String, Integer> roomTypeIds = new HashMap<>();
    final List<Building> buildings = new ArrayList<>();
    private final Map<Long, List<Building>> byCell = new HashMap<>();
    final List<Object[]> streets = new ArrayList<>();   // name, width, float[] xy
    final List<Object[]> zones = new ArrayList<>();     // type, name, x, y, w, h, extra
    final List<Object[]> towns = new ArrayList<>();     // name, x, y
    int cellSize = 256, blocksPerCell = 32, cxMin = Integer.MAX_VALUE, cyMin = Integer.MAX_VALUE, cxMax = Integer.MIN_VALUE, cyMax = Integer.MIN_VALUE;
    private final Map<Long, byte[]> zpop = new HashMap<>();
    volatile String status = "loading", error = "";
    volatile long loadedMs = 0;
    Path mapDir;
    private byte[] heatPng;

    private final Config cfg;
    MapData(Config cfg) { this.cfg = cfg; }

    Path resolveMapDir() {
        String explicit = cfg.get("mapDir");
        if (!explicit.isBlank()) return Paths.get(explicit);
        for (Path base : List.of(cfg.serverDir(), Paths.get(cfg.get("gameDir")))) {
            Path p = base.resolve("media").resolve("maps").resolve("Muldraugh, KY");
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    void loadAsync() {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try { load(); status = "ready"; }
            catch (Throwable e) { status = "error"; error = String.valueOf(e.getMessage() == null ? e : e.getMessage()); }
            loadedMs = System.currentTimeMillis() - t0;
            System.out.println("Карта: " + status + " — зданий " + buildings.size() + ", улиц " + streets.size() + ", зон " + zones.size() + ", городов " + towns.size() + " (" + loadedMs + " мс)" + (error.isEmpty() ? "" : " " + error));
        }, "map-data");
        t.setDaemon(true);
        t.start();
    }

    private static final Pattern HEADER = Pattern.compile("(-?\\d+)_(-?\\d+)\\.lotheader");

    void load() throws IOException {
        mapDir = resolveMapDir();
        if (mapDir == null) throw new IOException("Не найдена папка карты media/maps/Muldraugh, KY (укажите mapDir в настройках панели)");
        try (var ds = Files.newDirectoryStream(mapDir, "*.lotheader")) {
            for (Path f : ds) {
                Matcher m = HEADER.matcher(f.getFileName().toString());
                if (!m.matches()) continue;
                try { readLotheader(f, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))); }
                catch (Exception e) { System.err.println("lotheader " + f.getFileName() + ": " + e); }
            }
        }
        try { readStreets(mapDir.resolve("streets.xml")); } catch (Exception e) { System.err.println("streets.xml: " + e); }
        try { readZones(mapDir.resolve("objects.lua")); } catch (Exception e) { System.err.println("objects.lua: " + e); }
        readTowns();
        try { heatPng = buildHeat(); } catch (Exception e) { System.err.println("zombie heat: " + e); }
    }

    private int typeId(String name) {
        return roomTypeIds.computeIfAbsent(name, k -> { roomTypes.add(k); return roomTypes.size() - 1; });
    }

    private static String readLine(ByteBuffer b) {
        int start = b.position();
        while (b.get() != '\n') { /* scan */ }
        int end = b.position() - 1;
        byte[] a = new byte[end - start];
        b.get(start, a);
        return new String(a, StandardCharsets.UTF_8).trim();
    }

    private void readLotheader(Path f, int cx, int cy) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f)).order(ByteOrder.LITTLE_ENDIAN);
        int version = 0;
        if (b.remaining() >= 8 && b.get(0) == 'L' && b.get(1) == 'O' && b.get(2) == 'T' && b.get(3) == 'H') { b.position(4); version = b.getInt(); }
        if (version == 0) { cellSize = 300; blocksPerCell = 30; } else { cellSize = 256; blocksPerCell = 32; }
        int tiles = b.getInt();
        for (int i = 0; i < tiles; i++) readLine(b);
        if (version == 0) b.get();
        b.getInt(); b.getInt();                 // width, height
        if (version == 0) b.getInt(); else { b.getInt(); b.getInt(); }   // layers
        int ox = cx * cellSize, oy = cy * cellSize;
        int nRooms = b.getInt();
        String[] rName = new String[nRooms]; int[] rLayer = new int[nRooms]; int[][] rRects = new int[nRooms][];
        for (int i = 0; i < nRooms; i++) {
            rName[i] = readLine(b);
            rLayer[i] = b.getInt();
            int nr = b.getInt();
            int[] rr = new int[nr * 4];
            for (int j = 0; j < nr * 4; j++) rr[j] = b.getInt();
            rRects[i] = rr;
            int metas = b.getInt();
            b.position(b.position() + metas * 12);
        }
        int nB = b.getInt();
        List<Building> cellList = new ArrayList<>();
        for (int i = 0; i < nB; i++) {
            int n = b.getInt();
            int[] ids = new int[n];
            for (int j = 0; j < n; j++) ids[j] = b.getInt();
            Building bd = new Building();
            bd.x0 = Integer.MAX_VALUE; bd.y0 = Integer.MAX_VALUE; bd.x1 = Integer.MIN_VALUE; bd.y1 = Integer.MIN_VALUE;
            bd.minLayer = Integer.MAX_VALUE; bd.maxLayer = Integer.MIN_VALUE;
            int groundLayer = Integer.MAX_VALUE;
            for (int id : ids) if (id >= 0 && id < nRooms && rLayer[id] >= 0) groundLayer = Math.min(groundLayer, rLayer[id]);
            List<int[]> rooms = new ArrayList<>();
            int[] cnt = new int[CATS.size()];
            List<Integer> ground = new ArrayList<>();
            boolean residential = false;
            for (int id : ids) if (id >= 0 && id < nRooms && (rName[id].startsWith("bedroom") || rName[id].startsWith("kidsbedroom") || rName[id].equals("livingroom"))) { residential = true; break; }
            for (int id : ids) {
                if (id < 0 || id >= nRooms) continue;
                int[] rr = rRects[id];
                int area = 0;
                for (int j = 0; j < rr.length; j += 4) {
                    int x = ox + rr[j], y = oy + rr[j + 1], w = rr[j + 2], h = rr[j + 3];
                    area += w * h;
                    bd.x0 = Math.min(bd.x0, x); bd.y0 = Math.min(bd.y0, y); bd.x1 = Math.max(bd.x1, x + w); bd.y1 = Math.max(bd.y1, y + h);
                    if (rLayer[id] == groundLayer || (groundLayer == Integer.MAX_VALUE && rLayer[id] == 0)) { ground.add(x); ground.add(y); ground.add(w); ground.add(h); }
                }
                bd.minLayer = Math.min(bd.minLayer, rLayer[id]); bd.maxLayer = Math.max(bd.maxLayer, rLayer[id]);
                rooms.add(new int[]{typeId(rName[id]), rLayer[id], area});
                int c = residential && WEAK.contains(rName[id]) ? -1 : catOf(rName[id]);
                if (c >= 0) { cnt[c] += Math.max(1, area); bd.catMask |= 1 << c; }
            }
            if (rooms.isEmpty() || bd.x0 == Integer.MAX_VALUE) continue;
            // main category: earliest in CATS order (CATS is ordered by importance), but ignore token rooms (<10% of building area)
            int catTotal = 0, best = -1;
            for (int c = 0; c < CATS.size(); c++) { catTotal += cnt[c]; if (cnt[c] > 0 && (best < 0 || cnt[c] > cnt[best])) best = c; }
            for (int c = 0; c < CATS.size(); c++) if (cnt[c] > 0 && IMPORTANT.contains(CATS.get(c).id()) && cnt[c] * 5 >= catTotal) { best = c; break; }
            bd.cat = best;
            boolean onlyGarage = !residential;
            for (int id : ids) if (id >= 0 && id < nRooms && !GARAGE_ONLY.contains(rName[id])) { onlyGarage = false; break; }
            if (onlyGarage && (bd.cat < 0 || CATS.get(bd.cat).id().equals("tools"))) {
                boolean any = false; for (int id : ids) if (id >= 0 && id < nRooms && !rName[id].startsWith("empty")) any = true;
                if (any) { bd.cat = CATS.size() - 1; bd.catMask = 1 << bd.cat; }
            }
            bd.rooms = rooms.toArray(new int[0][]);
            bd.rects = ground.stream().mapToInt(Integer::intValue).toArray();
            bd.id = buildings.size();
            buildings.add(bd);
            cellList.add(bd);
        }
        if (!cellList.isEmpty()) byCell.put(key(cx, cy), cellList);
        // zombie population: blocks x blocks bytes (x-major)
        if (b.remaining() >= blocksPerCell * blocksPerCell) {
            byte[] z = new byte[blocksPerCell * blocksPerCell];
            b.get(z);
            zpop.put(key(cx, cy), z);
        }
        cxMin = Math.min(cxMin, cx); cyMin = Math.min(cyMin, cy); cxMax = Math.max(cxMax, cx); cyMax = Math.max(cyMax, cy);
    }

    private static long key(int cx, int cy) { return ((long) cx << 32) ^ (cy & 0xffffffffL); }

    private void readStreets(Path p) throws Exception {
        if (!Files.exists(p)) return;
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = f.newDocumentBuilder().parse(p.toFile());
        var root = doc.getDocumentElement();
        var kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (!(kids.item(i) instanceof org.w3c.dom.Element st)) continue;
            String name = st.getAttribute("name");
            int width = 0; try { width = Integer.parseInt(st.getAttribute("width")); } catch (Exception ignored) {}
            var parts = st.getChildNodes();
            for (int j = 0; j < parts.getLength(); j++) {
                if (!(parts.item(j) instanceof org.w3c.dom.Element pts)) continue;
                var pl = pts.getElementsByTagName("*");
                float[] xy = new float[pl.getLength() * 2];
                int n = 0;
                for (int k = 0; k < pl.getLength(); k++) {
                    var e = (org.w3c.dom.Element) pl.item(k);
                    if (!e.hasAttribute("x")) continue;
                    xy[n++] = Float.parseFloat(e.getAttribute("x"));
                    xy[n++] = Float.parseFloat(e.getAttribute("y"));
                }
                if (n >= 4) streets.add(new Object[]{name, width, Arrays.copyOf(xy, n)});
            }
        }
    }

    static final List<String> ZONE_TYPES = List.of("SpawnPoint", "ZoneStory", "Basement", "LootZone", "TrailerPark", "Farm", "Ranch");
    private static final Pattern ZONE = Pattern.compile(
        "name\\s*=\\s*\"([^\"]*)\"\\s*,\\s*type\\s*=\\s*\"([^\"]*)\"\\s*,\\s*x\\s*=\\s*(-?\\d+)\\s*,\\s*y\\s*=\\s*(-?\\d+)\\s*,\\s*z\\s*=\\s*(-?\\d+)\\s*,\\s*width\\s*=\\s*(\\d+)\\s*,\\s*height\\s*=\\s*(\\d+)(.*)");
    private static final Pattern PROF = Pattern.compile("Professions\\s*=\\s*\"([^\"]*)\"");

    private void readZones(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
            lines.forEach(l -> {
                if (l.indexOf("type") < 0) return;
                Matcher m = ZONE.matcher(l);
                if (!m.find()) return;
                int t = ZONE_TYPES.indexOf(m.group(2));
                if (t < 0) return;
                String extra = "";
                if (t == 0) { Matcher pm = PROF.matcher(m.group(8)); if (pm.find()) extra = pm.group(1); }
                zones.add(new Object[]{t, m.group(1), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7)), Integer.parseInt(m.group(5)), extra});
            });
        }
    }

    private void readTowns() {
        Path maps = mapDir.getParent();
        if (maps == null) return;
        try (var ds = Files.newDirectoryStream(maps)) {
            for (Path d : ds) {
                Path info = d.resolve("map.info");
                if (!Files.exists(info)) continue;
                String txt = Util.readText(info);
                Matcher mx = Pattern.compile("(?m)^zoomX=(\\d+)").matcher(txt), my = Pattern.compile("(?m)^zoomY=(\\d+)").matcher(txt);
                if (!mx.find() || !my.find()) continue;
                String name = d.getFileName().toString().replaceFirst(",\\s*KY$", "");
                towns.add(new Object[]{name, Integer.parseInt(mx.group(1)), Integer.parseInt(my.group(1))});
            }
        } catch (IOException ignored) {}
        // Louisville has no selectable map folder: centre of the town zones in the north-east corner
        boolean hasL = towns.stream().anyMatch(t -> ((String) t[0]).startsWith("Louisville"));
        if (!hasL) {
            long sx = 0, sy = 0, n = 0;
            for (Building b : buildings) if (b.x0 > 11500 && b.y0 < 4000) { sx += (b.x0 + b.x1) / 2; sy += (b.y0 + b.y1) / 2; n++; }
            if (n > 200) towns.add(new Object[]{"Louisville", (int) (sx / n), (int) (sy / n)});
        }
        towns.sort(Comparator.comparing(t -> (String) t[0]));
    }

    private byte[] buildHeat() throws IOException {
        if (zpop.isEmpty()) return null;
        int bw = (cxMax - cxMin + 1) * blocksPerCell, bh = (cyMax - cyMin + 1) * blocksPerCell;
        BufferedImage img = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        int max = 1;
        for (byte[] z : zpop.values()) for (byte v : z) max = Math.max(max, v & 0xff);
        for (var e : zpop.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cy = e.getKey().intValue();
            byte[] z = e.getValue();
            for (int x = 0; x < blocksPerCell; x++) for (int y = 0; y < blocksPerCell; y++) {
                int v = z[x * blocksPerCell + y] & 0xff;
                if (v == 0) continue;
                double t = Math.min(1, v / (double) max);
                int a = (int) (40 + 180 * Math.sqrt(t));
                int r = 255, g = (int) (200 * (1 - t)), bl = 0;
                img.setRGB((cx - cxMin) * blocksPerCell + x, (cy - cyMin) * blocksPerCell + y, (a << 24) | (r << 16) | (g << 8) | bl);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ---------------- API ----------------
    Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("error", error);
        if (!"ready".equals(status)) return m;
        List<Map<String, Object>> cats = new ArrayList<>();
        int[] counts = new int[CATS.size()];
        for (Building b : buildings) if (b.cat >= 0) counts[b.cat]++;
        for (int i = 0; i < CATS.size(); i++) {
            Cat c = CATS.get(i);
            cats.add(Map.of("id", c.id(), "label", c.label(), "color", c.color(), "icon", c.icon(), "count", counts[i]));
        }
        m.put("cats", cats);
        m.put("roomTypes", roomTypes);
        List<Object> pois = new ArrayList<>();
        for (Building b : buildings) {
            if (b.cat < 0) continue;
            // top room types by area, without generic rooms
            List<int[]> rs = new ArrayList<>(Arrays.asList(b.rooms));
            rs.sort((p, q) -> Integer.compare(q[2], p[2]));
            LinkedHashSet<Integer> top = new LinkedHashSet<>();
            for (int[] r : rs) if (catOf(roomTypes.get(r[0])) >= 0) top.add(r[0]);
            for (int[] r : rs) { if (top.size() >= 6) break; top.add(r[0]); }
            pois.add(new Object[]{b.id, b.cat, (b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2, b.maxLayer - Math.max(0, b.minLayer) + 1, b.catMask, top.stream().limit(6).toList(), b.minLayer < 0 ? 1 : 0});
        }
        m.put("pois", pois);
        List<Object> st = new ArrayList<>();
        for (Object[] s : streets) {
            float[] xy = (float[]) s[2];
            int[] ixy = new int[xy.length];
            for (int i = 0; i < xy.length; i++) ixy[i] = Math.round(xy[i]);
            st.add(new Object[]{s[0], s[1], ixy});
        }
        m.put("streets", st);
        m.put("zoneTypes", ZONE_TYPES);
        m.put("zones", zones);
        m.put("towns", towns);
        m.put("buildingsTotal", buildings.size());
        if (heatPng != null) m.put("heat", Map.of("x0", cxMin * cellSize, "y0", cyMin * cellSize, "x1", (cxMax + 1) * cellSize, "y1", (cyMax + 1) * cellSize));
        return m;
    }

    List<Object> buildingsIn(int x0, int y0, int x1, int y1, int limit) {
        List<Object> out = new ArrayList<>();
        if (!"ready".equals(status)) return out;
        for (int cx = Math.floorDiv(x0, cellSize); cx <= Math.floorDiv(x1, cellSize); cx++)
            for (int cy = Math.floorDiv(y0, cellSize); cy <= Math.floorDiv(y1, cellSize); cy++) {
                List<Building> l = byCell.get(key(cx, cy));
                if (l == null) continue;
                for (Building b : l) {
                    if (b.x1 < x0 || b.y1 < y0 || b.x0 > x1 || b.y0 > y1) continue;
                    out.add(new Object[]{b.id, b.cat, b.rects});
                    if (out.size() >= limit) return out;
                }
            }
        return out;
    }

    Map<String, Object> building(int id) {
        if (!"ready".equals(status) || id < 0 || id >= buildings.size()) return Map.of("error", "нет такого здания");
        Building b = buildings.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id);
        m.put("bounds", new int[]{b.x0, b.y0, b.x1, b.y1});
        m.put("cat", b.cat);
        m.put("catMask", b.catMask);
        TreeMap<Integer, Map<String, Integer>> floors = new TreeMap<>();
        for (int[] r : b.rooms) floors.computeIfAbsent(r[1], k -> new TreeMap<>()).merge(roomTypes.get(r[0]), 1, Integer::sum);
        List<Map<String, Object>> fl = new ArrayList<>();
        floors.forEach((layer, rooms) -> fl.add(Map.of("layer", layer, "rooms", rooms)));
        m.put("floors", fl);
        return m;
    }

    byte[] heatPng() { return heatPng; }
}
