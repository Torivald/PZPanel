package pzpanel;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class MapData {
   static final List<MapData.Cat> CATS = List.of(
      new MapData.Cat(
         "medical",
         "Медицина и аптеки",
         "#ff5c5c",
         "+",
         new String[]{
            "pharmacy*", "medical*", "medicine", "hospital*", "dentist*", "oldmedical", "morgue", "medclinic", "clinic", "optometrist", "vet", "coroneroffice"
         }
      ),
      new MapData.Cat(
         "police", "Полиция", "#4f8cff", "P", new String[]{"police*", "evidenceroom", "interrogationroom", "detectiveoffice", "swat*", "ww_sherrif"}
      ),
      new MapData.Cat(
         "guns",
         "Оружие и охота",
         "#ff8a00",
         "G",
         new String[]{"gunstore*", "gunstorage", "hunting", "hunterstorage", "armysurplus*", "knifestore", "knifeshipping", "knifefactory"}
      ),
      new MapData.Cat("military", "Военные", "#7da33a", "A", new String[]{"army*", "military*", "oldarmy"}),
      new MapData.Cat("prison", "Тюрьма", "#a1887f", "Т", new String[]{"prison*"}),
      new MapData.Cat("fuel", "Заправки", "#ffd400", "F", new String[]{"gasstore*", "gasstorage", "fossoil", "gas2go"}),
      new MapData.Cat(
         "food",
         "Продукты",
         "#5ccc5e",
         "Е",
         new String[]{
            "grocer*",
            "gigamart*",
            "conveniencestore*",
            "cornerstore*",
            "generalstore*",
            "ww_generalstore",
            "producestorage",
            "butcher",
            "knoxbutcher",
            "bakery*",
            "liquorstore",
            "zippee*",
            "candy*",
            "potatostorage"
         }
      ),
      new MapData.Cat("fire", "Пожарные", "#ff4d2e", "О", new String[]{"firestorage", "firegarage"}),
      new MapData.Cat("cars", "Автосервис", "#b0b6bb", "М", new String[]{"mechanic", "carsupply*", "cardealershipoffice"}),
      new MapData.Cat(
         "tools",
         "Инструменты, склады, заводы",
         "#c98a4b",
         "И",
         new String[]{
            "toolstore*",
            "ww_toolstore",
            "toolstorage",
            "garagestorage",
            "warehouse*",
            "construction",
            "metal*",
            "welding*",
            "carpentryworkshop",
            "masonrystore",
            "factory*",
            "*factory",
            "logging*",
            "storageunit",
            "paintershop",
            "gardenstore",
            "machinery",
            "workshop",
            "blacksmith",
            "ww_blacksmith"
         }
      ),
      new MapData.Cat(
         "clothing",
         "Одежда, сумки, спорт",
         "#d773da",
         "О",
         new String[]{
            "clothesstore*",
            "clothesstorage",
            "clothesestorage",
            "clothingstore",
            "clothingstorage",
            "leatherclothesstore",
            "shoestore",
            "shoestorage",
            "bagstore",
            "tailoringstore",
            "tailorworkshop",
            "sewingstore",
            "sewingstorage",
            "departmentstore*",
            "departmentstorage",
            "weddingstore*",
            "sportstore",
            "sportstorage",
            "outdoorsupply*",
            "camping*",
            "jewelrystore",
            "jewelrystorage",
            "walletshop"
         }
      ),
      new MapData.Cat(
         "electronics",
         "Электроника",
         "#33c9dc",
         "Э",
         new String[]{
            "electronic*",
            "radio*",
            "cdstore",
            "camerastore",
            "cybercafe",
            "movierental",
            "communications",
            "broadcasting",
            "serverroom",
            "batterystorage",
            "batteryfactory"
         }
      ),
      new MapData.Cat("books", "Книги и библиотеки", "#c5a07a", "К", new String[]{"bookstore*", "*library", "comic*", "newsroom", "newspaper*"}),
      new MapData.Cat(
         "eat",
         "Кафе, бары, рестораны",
         "#ff6f9a",
         "Р",
         new String[]{
            "restaurant*",
            "diner*",
            "pizza*",
            "burger*",
            "spiffo*",
            "cafe",
            "cafekitchen",
            "cafeteria*",
            "bar",
            "barkitchen",
            "barstorage",
            "barcountertwiggy",
            "ww_bar",
            "sushi*",
            "chinese*",
            "italian*",
            "mexican*",
            "western*",
            "donut*",
            "icecream*",
            "jayschicken*",
            "catfish*",
            "fishchips*",
            "seafood*",
            "taco*",
            "foodcourt",
            "hotdogstand",
            "beergarden",
            "brewery*",
            "stripclub*",
            "arenakitchen"
         }
      ),
      new MapData.Cat("gym", "Спортзалы", "#a5d46a", "С", new String[]{"gym", "gymstorage", "boxing", "fitness"}),
      new MapData.Cat(
         "school",
         "Школы и университет",
         "#ffbb5c",
         "Ш",
         new String[]{"*classroom*", "elementary*", "secondary*", "school*", "daycare", "university*", "musicschool"}
      ),
      new MapData.Cat("church", "Церкви", "#eeeeee", "Ц", new String[]{"church", "officechurch", "lobbychurch", "storagechurch"}),
      new MapData.Cat("motel", "Мотели", "#8a96d6", "Н", new String[]{"motelroom*", "motelstorage"}),
      new MapData.Cat("bank", "Банки и почта", "#e0c068", "Б", new String[]{"bank*", "vault", "depositboxes", "post", "poststorage"}),
      new MapData.Cat("farm", "Фермы", "#b49480", "Ф", new String[]{"farmstorage", "barn", "greenhouse"}),
      new MapData.Cat(
         "shop",
         "Другие магазины",
         "#cfd8dc",
         "•",
         new String[]{"*store", "pawnshop*", "florist", "kitchenwares", "giftstorage", "toystorage", "musicstorage", "furniturestorage", "furnitureshowroom"}
      ),
      new MapData.Cat("garage", "Гаражи и сараи", "#8d939a", "г", new String[0])
   );
   static final Set<String> GARAGE_ONLY = Set.of("garagestorage", "garage", "shed", "woodshed", "emptyoutside", "empty");
   private static final Map<String, Integer> catCache = new HashMap<>();
   static final Set<String> IMPORTANT = Set.of("medical", "police", "guns", "military", "prison", "fuel", "fire");
   static final Set<String> WEAK = Set.of("garagestorage", "shed", "woodshed", "toolstorage", "storageunit", "farmstorage", "barn", "greenhouse", "post");
   final List<String> roomTypes = new ArrayList<>();
   private final Map<String, Integer> roomTypeIds = new HashMap<>();
   final List<MapData.Building> buildings = new ArrayList<>();
   private final Map<Long, List<MapData.Building>> byCell = new HashMap<>();
   final List<Object[]> streets = new ArrayList<>();
   final List<Object[]> zones = new ArrayList<>();
   final List<Object[]> towns = new ArrayList<>();
   int cellSize = 256;
   int blocksPerCell = 32;
   int cxMin = Integer.MAX_VALUE;
   int cyMin = Integer.MAX_VALUE;
   int cxMax = Integer.MIN_VALUE;
   int cyMax = Integer.MIN_VALUE;
   private final Map<Long, byte[]> zpop = new HashMap<>();
   volatile String status = "loading";
   volatile String error = "";
   volatile long loadedMs = 0L;
   Path mapDir;
   private byte[] heatPng;
   private final Config cfg;
   private static final Pattern HEADER = Pattern.compile("(-?\\d+)_(-?\\d+)\\.lotheader");
   static final List<String> ZONE_TYPES = List.of("SpawnPoint", "ZoneStory", "Basement", "LootZone", "TrailerPark", "Farm", "Ranch");
   private static final Pattern ZONE = Pattern.compile(
      "name\\s*=\\s*\"([^\"]*)\"\\s*,\\s*type\\s*=\\s*\"([^\"]*)\"\\s*,\\s*x\\s*=\\s*(-?\\d+)\\s*,\\s*y\\s*=\\s*(-?\\d+)\\s*,\\s*z\\s*=\\s*(-?\\d+)\\s*,\\s*width\\s*=\\s*(\\d+)\\s*,\\s*height\\s*=\\s*(\\d+)(.*)"
   );
   private static final Pattern PROF = Pattern.compile("Professions\\s*=\\s*\"([^\"]*)\"");

   static int catOf(String var0) {
      Integer var1 = catCache.get(var0);
      if (var1 != null) {
         return var1;
      } else {
         int var2 = -1;

         label42:
         for (int var3 = 0; var3 < CATS.size(); var3++) {
            for (String var7 : CATS.get(var3).patterns()) {
               boolean var8 = var7.endsWith("*");
               boolean var9 = var7.startsWith("*");
               String var10 = var7.replace("*", "");
               boolean var11 = var8 && var9 ? var0.contains(var10) : (var8 ? var0.startsWith(var10) : (var9 ? var0.endsWith(var10) : var0.equals(var10)));
               if (var11) {
                  var2 = var3;
                  break label42;
               }
            }
         }

         catCache.put(var0, var2);
         return var2;
      }
   }

   MapData(Config var1) {
      this.cfg = var1;
   }

   Path resolveMapDir() {
      String var1 = this.cfg.get("mapDir");
      if (!var1.isBlank()) {
         return Paths.get(var1);
      } else {
         for (Path var3 : List.of(this.cfg.serverDir(), Paths.get(this.cfg.get("gameDir")))) {
            Path var4 = var3.resolve("media").resolve("maps").resolve("Muldraugh, KY");
            if (Files.isDirectory(var4)) {
               return var4;
            }
         }

         return null;
      }
   }

   void loadAsync() {
      Thread var1 = new Thread(
         () -> {
            long var1x = System.currentTimeMillis();

            try {
               this.load();
               this.status = "ready";
            } catch (Throwable var4) {
               this.status = "error";
               this.error = String.valueOf(var4.getMessage() == null ? var4 : var4.getMessage());
            }

            this.loadedMs = System.currentTimeMillis() - var1x;
            System.out
               .println(
                  "Карта: "
                     + this.status
                     + " — зданий "
                     + this.buildings.size()
                     + ", улиц "
                     + this.streets.size()
                     + ", зон "
                     + this.zones.size()
                     + ", городов "
                     + this.towns.size()
                     + " ("
                     + this.loadedMs
                     + " мс)"
                     + (this.error.isEmpty() ? "" : " " + this.error)
               );
         },
         "map-data"
      );
      var1.setDaemon(true);
      var1.start();
   }

   void load() throws IOException {
      this.mapDir = this.resolveMapDir();
      if (this.mapDir == null) {
         throw new IOException("Не найдена папка карты media/maps/Muldraugh, KY (укажите mapDir в настройках панели)");
      } else {
         try (DirectoryStream<Path> var1 = Files.newDirectoryStream(this.mapDir, "*.lotheader")) {
            for (Path var3 : var1) {
               Matcher var4 = HEADER.matcher(var3.getFileName().toString());
               if (var4.matches()) {
                  try {
                     this.readLotheader(var3, Integer.parseInt(var4.group(1)), Integer.parseInt(var4.group(2)));
                  } catch (Exception var10) {
                     System.err.println("lotheader " + var3.getFileName() + ": " + var10);
                  }
               }
            }
         }

         try {
            this.readStreets(this.mapDir.resolve("streets.xml"));
         } catch (Exception var9) {
            System.err.println("streets.xml: " + var9);
         }

         try {
            this.readZones(this.mapDir.resolve("objects.lua"));
         } catch (Exception var8) {
            System.err.println("objects.lua: " + var8);
         }

         this.readTowns();

         try {
            this.heatPng = this.buildHeat();
         } catch (Exception var7) {
            System.err.println("zombie heat: " + var7);
         }
      }
   }

   private int typeId(String var1) {
      return this.roomTypeIds.computeIfAbsent(var1, var1x -> {
         this.roomTypes.add(var1x);
         return this.roomTypes.size() - 1;
      });
   }

   private static String readLine(ByteBuffer var0) {
      int var1 = var0.position();

      while (var0.get() != 10) {
      }

      int var2 = var0.position() - 1;
      byte[] var3 = new byte[var2 - var1];
      var0.get(var1, var3);
      return new String(var3, StandardCharsets.UTF_8).trim();
   }

   private void readLotheader(Path var1, int var2, int var3) throws IOException {
      ByteBuffer var4 = ByteBuffer.wrap(Files.readAllBytes(var1)).order(ByteOrder.LITTLE_ENDIAN);
      int var5 = 0;
      if (var4.remaining() >= 8 && var4.get(0) == 76 && var4.get(1) == 79 && var4.get(2) == 84 && var4.get(3) == 72) {
         var4.position(4);
         var5 = var4.getInt();
      }

      if (var5 == 0) {
         this.cellSize = 300;
         this.blocksPerCell = 30;
      } else {
         this.cellSize = 256;
         this.blocksPerCell = 32;
      }

      int var6 = var4.getInt();

      for (int var7 = 0; var7 < var6; var7++) {
         readLine(var4);
      }

      if (var5 == 0) {
         var4.get();
      }

      var4.getInt();
      var4.getInt();
      if (var5 == 0) {
         var4.getInt();
      } else {
         var4.getInt();
         var4.getInt();
      }

      int var35 = var2 * this.cellSize;
      int var8 = var3 * this.cellSize;
      int var9 = var4.getInt();
      String[] var10 = new String[var9];
      int[] var11 = new int[var9];
      int[][] var12 = new int[var9][];

      for (int var13 = 0; var13 < var9; var13++) {
         var10[var13] = readLine(var4);
         var11[var13] = var4.getInt();
         int var14 = var4.getInt();
         int[] var15 = new int[var14 * 4];

         for (int var16 = 0; var16 < var14 * 4; var16++) {
            var15[var16] = var4.getInt();
         }

         var12[var13] = var15;
         int var40 = var4.getInt();
         var4.position(var4.position() + var40 * 12);
      }

      int var36 = var4.getInt();
      ArrayList var37 = new ArrayList();

      for (int var38 = 0; var38 < var36; var38++) {
         int var41 = var4.getInt();
         int[] var17 = new int[var41];

         for (int var18 = 0; var18 < var41; var18++) {
            var17[var18] = var4.getInt();
         }

         MapData.Building var42 = new MapData.Building();
         var42.x0 = Integer.MAX_VALUE;
         var42.y0 = Integer.MAX_VALUE;
         var42.x1 = Integer.MIN_VALUE;
         var42.y1 = Integer.MIN_VALUE;
         var42.minLayer = Integer.MAX_VALUE;
         var42.maxLayer = Integer.MIN_VALUE;
         int var19 = Integer.MAX_VALUE;

         for (int var23 : var17) {
            if (var23 >= 0 && var23 < var9 && var11[var23] >= 0) {
               var19 = Math.min(var19, var11[var23]);
            }
         }

         ArrayList<int[]> var43 = new ArrayList<>();
         int[] var44 = new int[CATS.size()];
         ArrayList<Integer> var45 = new ArrayList<>();
         boolean var46 = false;

         for (int var27 : var17) {
            if (var27 >= 0
               && var27 < var9
               && (var10[var27].startsWith("bedroom") || var10[var27].startsWith("kidsbedroom") || var10[var27].equals("livingroom"))) {
               var46 = true;
               break;
            }
         }

         for (int var55 : var17) {
            if (var55 >= 0 && var55 < var9) {
               int[] var28 = var12[var55];
               int var29 = 0;

               for (byte var30 = 0; var30 < var28.length; var30 += 4) {
                  int var31 = var35 + var28[var30];
                  int var32 = var8 + var28[var30 + 1];
                  int var33 = var28[var30 + 2];
                  int var34 = var28[var30 + 3];
                  var29 += var33 * var34;
                  var42.x0 = Math.min(var42.x0, var31);
                  var42.y0 = Math.min(var42.y0, var32);
                  var42.x1 = Math.max(var42.x1, var31 + var33);
                  var42.y1 = Math.max(var42.y1, var32 + var34);
                  if (var11[var55] == var19 || var19 == Integer.MAX_VALUE && var11[var55] == 0) {
                     var45.add(var31);
                     var45.add(var32);
                     var45.add(var33);
                     var45.add(var34);
                  }
               }

               var42.minLayer = Math.min(var42.minLayer, var11[var55]);
               var42.maxLayer = Math.max(var42.maxLayer, var11[var55]);
               var43.add(new int[]{this.typeId(var10[var55]), var11[var55], var29});
               int var62 = var46 && WEAK.contains(var10[var55]) ? -1 : catOf(var10[var55]);
               if (var62 >= 0) {
                  var44[var62] += Math.max(1, var29);
                  var42.catMask |= 1 << var62;
               }
            }
         }

         if (!var43.isEmpty() && var42.x0 != Integer.MAX_VALUE) {
            int var48 = 0;
            int var50 = -1;

            for (int var52 = 0; var52 < CATS.size(); var52++) {
               var48 += var44[var52];
               if (var44[var52] > 0 && (var50 < 0 || var44[var52] > var44[var50])) {
                  var50 = var52;
               }
            }

            for (int var53 = 0; var53 < CATS.size(); var53++) {
               if (var44[var53] > 0 && IMPORTANT.contains(CATS.get(var53).id()) && var44[var53] * 5 >= var48) {
                  var50 = var53;
                  break;
               }
            }

            var42.cat = var50;
            boolean var54 = !var46;

            for (int var63 : var17) {
               if (var63 >= 0 && var63 < var9 && !GARAGE_ONLY.contains(var10[var63])) {
                  var54 = false;
                  break;
               }
            }

            if (var54 && (var42.cat < 0 || CATS.get(var42.cat).id().equals("tools"))) {
               boolean var57 = false;

               for (int var65 : var17) {
                  if (var65 >= 0 && var65 < var9 && !var10[var65].startsWith("empty")) {
                     var57 = true;
                  }
               }

               if (var57) {
                  var42.cat = CATS.size() - 1;
                  var42.catMask = 1 << var42.cat;
               }
            }

            var42.rooms = var43.toArray(new int[0][]);
            var42.rects = var45.stream().mapToInt(Integer::intValue).toArray();
            var42.id = this.buildings.size();
            this.buildings.add(var42);
            var37.add(var42);
         }
      }

      if (!var37.isEmpty()) {
         this.byCell.put(key(var2, var3), var37);
      }

      if (var4.remaining() >= this.blocksPerCell * this.blocksPerCell) {
         byte[] var39 = new byte[this.blocksPerCell * this.blocksPerCell];
         var4.get(var39);
         this.zpop.put(key(var2, var3), var39);
      }

      this.cxMin = Math.min(this.cxMin, var2);
      this.cyMin = Math.min(this.cyMin, var3);
      this.cxMax = Math.max(this.cxMax, var2);
      this.cyMax = Math.max(this.cyMax, var3);
   }

   private static long key(int var0, int var1) {
      return (long)var0 << 32 ^ var1 & 4294967295L;
   }

   private void readStreets(Path var1) throws Exception {
      if (Files.exists(var1)) {
         DocumentBuilderFactory var2 = DocumentBuilderFactory.newInstance();
         var2.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
         Document var3 = var2.newDocumentBuilder().parse(var1.toFile());
         Element var4 = var3.getDocumentElement();
         NodeList var5 = var4.getChildNodes();

         for (int var6 = 0; var6 < var5.getLength(); var6++) {
            if (var5.item(var6) instanceof Element var7) {
               String var19 = var7.getAttribute("name");
               int var9 = 0;

               try {
                  var9 = Integer.parseInt(var7.getAttribute("width"));
               } catch (Exception var18) {
               }

               NodeList var10 = var7.getChildNodes();

               for (int var11 = 0; var11 < var10.getLength(); var11++) {
                  if (var10.item(var11) instanceof Element var12) {
                     NodeList var20 = var12.getElementsByTagName("*");
                     float[] var14 = new float[var20.getLength() * 2];
                     int var15 = 0;

                     for (int var16 = 0; var16 < var20.getLength(); var16++) {
                        Element var17 = (Element)var20.item(var16);
                        if (var17.hasAttribute("x")) {
                           var14[var15++] = Float.parseFloat(var17.getAttribute("x"));
                           var14[var15++] = Float.parseFloat(var17.getAttribute("y"));
                        }
                     }

                     if (var15 >= 4) {
                        this.streets.add(new Object[]{var19, var9, Arrays.copyOf(var14, var15)});
                     }
                  }
               }
            }
         }
      }
   }

   private void readZones(Path var1) throws IOException {
      if (Files.exists(var1)) {
         try (Stream<String> var2 = Files.lines(var1, StandardCharsets.UTF_8)) {
            var2.forEach(
               var1x -> {
                  if (var1x.indexOf("type") >= 0) {
                     Matcher var2x = ZONE.matcher(var1x);
                     if (var2x.find()) {
                        int var3 = ZONE_TYPES.indexOf(var2x.group(2));
                        if (var3 >= 0) {
                           String var4 = "";
                           if (var3 == 0) {
                              Matcher var5 = PROF.matcher(var2x.group(8));
                              if (var5.find()) {
                                 var4 = var5.group(1);
                              }
                           }

                           this.zones
                              .add(
                                 new Object[]{
                                    var3,
                                    var2x.group(1),
                                    Integer.parseInt(var2x.group(3)),
                                    Integer.parseInt(var2x.group(4)),
                                    Integer.parseInt(var2x.group(6)),
                                    Integer.parseInt(var2x.group(7)),
                                    Integer.parseInt(var2x.group(5)),
                                    var4
                                 }
                              );
                        }
                     }
                  }
               }
            );
         }
      }
   }

   private void readTowns() {
      Path var1 = this.mapDir.getParent();
      if (var1 != null) {
         try (DirectoryStream<Path> var2 = Files.newDirectoryStream(var1)) {
            for (Path var4 : var2) {
               Path var5 = var4.resolve("map.info");
               if (Files.exists(var5)) {
                  String var6 = Util.readText(var5);
                  Matcher var7 = Pattern.compile("(?m)^zoomX=(\\d+)").matcher(var6);
                  Matcher var8 = Pattern.compile("(?m)^zoomY=(\\d+)").matcher(var6);
                  if (var7.find() && var8.find()) {
                     String var9 = var4.getFileName().toString().replaceFirst(",\\s*KY$", "");
                     this.towns.add(new Object[]{var9, Integer.parseInt(var7.group(1)), Integer.parseInt(var8.group(1))});
                  }
               }
            }
         } catch (IOException var13) {
         }

         boolean var14 = this.towns.stream().anyMatch(var0 -> ((String)var0[0]).startsWith("Louisville"));
         if (!var14) {
            long var15 = 0L;
            long var16 = 0L;
            long var17 = 0L;

            for (MapData.Building var10 : this.buildings) {
               if (var10.x0 > 11500 && var10.y0 < 4000) {
                  var15 += (var10.x0 + var10.x1) / 2;
                  var16 += (var10.y0 + var10.y1) / 2;
                  var17++;
               }
            }

            if (var17 > 200L) {
               this.towns.add(new Object[]{"Louisville", (int)(var15 / var17), (int)(var16 / var17)});
            }
         }

         this.towns.sort(Comparator.comparing(var0 -> (String)var0[0]));
      }
   }

   private byte[] buildHeat() throws IOException {
      if (this.zpop.isEmpty()) {
         return null;
      } else {
         int var1 = (this.cxMax - this.cxMin + 1) * this.blocksPerCell;
         int var2 = (this.cyMax - this.cyMin + 1) * this.blocksPerCell;
         BufferedImage var3 = new BufferedImage(var1, var2, 2);
         int var4 = 1;

         for (byte[] var6 : this.zpop.values()) {
            for (byte var10 : var6) {
               var4 = Math.max(var4, var10 & 255);
            }
         }

         for (Entry var21 : this.zpop.entrySet()) {
            int var22 = (int)((Long)var21.getKey() >> 32);
            int var23 = ((Long)var21.getKey()).intValue();
            byte[] var24 = (byte[])var21.getValue();

            for (int var25 = 0; var25 < this.blocksPerCell; var25++) {
               for (int var11 = 0; var11 < this.blocksPerCell; var11++) {
                  int var12 = var24[var25 * this.blocksPerCell + var11] & 255;
                  if (var12 != 0) {
                     double var13 = Math.min(1.0, (double)var12 / var4);
                     int var15 = (int)(40.0 + 180.0 * Math.sqrt(var13));
                     short var16 = 255;
                     int var17 = (int)(200.0 * (1.0 - var13));
                     byte var18 = 0;
                     var3.setRGB(
                        (var22 - this.cxMin) * this.blocksPerCell + var25,
                        (var23 - this.cyMin) * this.blocksPerCell + var11,
                        var15 << 24 | var16 << 16 | var17 << 8 | var18
                     );
                  }
               }
            }
         }

         ByteArrayOutputStream var20 = new ByteArrayOutputStream();
         ImageIO.write(var3, "png", var20);
         return var20.toByteArray();
      }
   }

   Map<String, Object> summary() {
      LinkedHashMap var1 = new LinkedHashMap();
      var1.put("status", this.status);
      var1.put("error", this.error);
      if (!"ready".equals(this.status)) {
         return var1;
      } else {
         ArrayList var2 = new ArrayList();
         int[] var3 = new int[CATS.size()];

         for (MapData.Building var5 : this.buildings) {
            if (var5.cat >= 0) {
               var3[var5.cat]++;
            }
         }

         for (int var11 = 0; var11 < CATS.size(); var11++) {
            MapData.Cat var13 = CATS.get(var11);
            var2.add(Map.of("id", var13.id(), "label", var13.label(), "color", var13.color(), "icon", var13.icon(), "count", var3[var11]));
         }

         var1.put("cats", var2);
         var1.put("roomTypes", this.roomTypes);
         ArrayList var12 = new ArrayList();

         for (MapData.Building var6 : this.buildings) {
            if (var6.cat >= 0) {
               ArrayList<int[]> var7 = new ArrayList<>(Arrays.asList(var6.rooms));
               var7.sort((var0, var1x) -> Integer.compare(var1x[2], var0[2]));
               LinkedHashSet var8 = new LinkedHashSet();

               for (int[] var10 : var7) {
                  if (catOf(this.roomTypes.get(var10[0])) >= 0) {
                     var8.add(var10[0]);
                  }
               }

               for (int[] var21 : var7) {
                  if (var8.size() >= 6) {
                     break;
                  }

                  var8.add(var21[0]);
               }

               var12.add(
                  new Object[]{
                     var6.id,
                     var6.cat,
                     (var6.x0 + var6.x1) / 2,
                     (var6.y0 + var6.y1) / 2,
                     var6.maxLayer - Math.max(0, var6.minLayer) + 1,
                     var6.catMask,
                     var8.stream().limit(6L).toList(),
                     var6.minLayer < 0 ? 1 : 0
                  }
               );
            }
         }

         var1.put("pois", var12);
         ArrayList var15 = new ArrayList();

         for (Object[] var17 : this.streets) {
            float[] var18 = (float[])var17[2];
            int[] var20 = new int[var18.length];

            for (int var22 = 0; var22 < var18.length; var22++) {
               var20[var22] = Math.round(var18[var22]);
            }

            var15.add(new Object[]{var17[0], var17[1], var20});
         }

         var1.put("streets", var15);
         var1.put("zoneTypes", ZONE_TYPES);
         var1.put("zones", this.zones);
         var1.put("towns", this.towns);
         var1.put("buildingsTotal", this.buildings.size());
         if (this.heatPng != null) {
            var1.put(
               "heat",
               Map.of(
                  "x0",
                  this.cxMin * this.cellSize,
                  "y0",
                  this.cyMin * this.cellSize,
                  "x1",
                  (this.cxMax + 1) * this.cellSize,
                  "y1",
                  (this.cyMax + 1) * this.cellSize
               )
            );
         }

         return var1;
      }
   }

   List<Object> buildingsIn(int var1, int var2, int var3, int var4, int var5) {
      ArrayList var6 = new ArrayList();
      if (!"ready".equals(this.status)) {
         return var6;
      } else {
         for (int var7 = Math.floorDiv(var1, this.cellSize); var7 <= Math.floorDiv(var3, this.cellSize); var7++) {
            for (int var8 = Math.floorDiv(var2, this.cellSize); var8 <= Math.floorDiv(var4, this.cellSize); var8++) {
               List<MapData.Building> var9 = this.byCell.get(key(var7, var8));
               if (var9 != null) {
                  for (MapData.Building var11 : var9) {
                     if (var11.x1 >= var1 && var11.y1 >= var2 && var11.x0 <= var3 && var11.y0 <= var4) {
                        var6.add(new Object[]{var11.id, var11.cat, var11.rects});
                        if (var6.size() >= var5) {
                           return var6;
                        }
                     }
                  }
               }
            }
         }

         return var6;
      }
   }

   Map<String, Object> building(int var1) {
      if ("ready".equals(this.status) && var1 >= 0 && var1 < this.buildings.size()) {
         MapData.Building var2 = this.buildings.get(var1);
         LinkedHashMap var3 = new LinkedHashMap();
         var3.put("id", var2.id);
         var3.put("bounds", new int[]{var2.x0, var2.y0, var2.x1, var2.y1});
         var3.put("cat", var2.cat);
         var3.put("catMask", var2.catMask);
         TreeMap<Integer, TreeMap<String, Integer>> var4 = new TreeMap<>();

         for (int[] var8 : var2.rooms) {
            var4.computeIfAbsent(var8[1], var0 -> new TreeMap<>()).merge(this.roomTypes.get(var8[0]), 1, Integer::sum);
         }

         ArrayList var9 = new ArrayList();
         var4.forEach((var1x, var2x) -> var9.add(Map.of("layer", var1x, "rooms", var2x)));
         var3.put("floors", var9);
         return var3;
      } else {
         return Map.of("error", "нет такого здания");
      }
   }

   byte[] heatPng() {
      return this.heatPng;
   }

   static final class Building {
      int id;
      int x0;
      int y0;
      int x1;
      int y1;
      int minLayer;
      int maxLayer;
      int cat = -1;
      int catMask;
      int[] rects;
      int[][] rooms;
   }

   record Cat(String id, String label, String color, String icon, String[] patterns) {
   }
}
