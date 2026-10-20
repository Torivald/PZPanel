package pzpanel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Configs {
   private static final Pattern OPEN = Pattern.compile("^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\{\\s*$");
   private static final Pattern KV = Pattern.compile("^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\"(?:[^\"\\\\]++|\\\\.)*+\"|[^,{}]+?)\\s*,\\s*(--.*)?$");
   private static final Pattern CLOSE = Pattern.compile("^\\s*\\}\\s*,?\\s*$");

   static List<Map<String, Object>> readIni(Path var0) throws IOException {
      ArrayList var1 = new ArrayList();
      if (!Files.exists(var0)) {
         return var1;
      } else {
         String[] var2 = Util.readText(var0).split("\r?\n", -1);
         ArrayList var3 = new ArrayList();

         for (String var7 : var2) {
            String var8 = var7.trim();
            if (var8.startsWith("#")) {
               var3.add(var8.replaceFirst("^#\\s?", ""));
            } else {
               int var9 = var7.indexOf(61);
               if (var9 > 0 && !var8.isEmpty()) {
                  LinkedHashMap var10 = new LinkedHashMap();
                  String var11 = var7.substring(0, var9).trim();
                  var10.put("key", var11);
                  var10.put("value", var7.substring(var9 + 1));
                  var10.put("comment", String.join("\n", var3));
                  var10.put("secret", var11.toLowerCase(Locale.ROOT).contains("password") || var11.equalsIgnoreCase("DiscordToken"));
                  var1.add(var10);
               }

               var3.clear();
            }
         }

         return var1;
      }
   }

   static String iniValue(Path var0, String var1) {
      try {
         for (String var5 : Util.readText(var0).split("\r?\n")) {
            int var6 = var5.indexOf(61);
            if (var6 > 0 && var5.substring(0, var6).trim().equals(var1)) {
               return var5.substring(var6 + 1).trim();
            }
         }
      } catch (IOException var7) {
      }

      return "";
   }

   static void writeIni(Path var0, Map<String, String> var1, Path var2) throws IOException {
      String var3 = Util.readText(var0);
      String var4 = Util.lineSep(var3);
      String[] var5 = var3.split("\r?\n", -1);
      HashSet var6 = new HashSet();

      for (int var7 = 0; var7 < var5.length; var7++) {
         String var8 = var5[var7];
         if (!var8.trim().startsWith("#")) {
            int var9 = var8.indexOf(61);
            if (var9 > 0) {
               String var10 = var8.substring(0, var9).trim();
               if (var1.containsKey(var10)) {
                  var5[var7] = var10 + "=" + clean((String)var1.get(var10));
                  var6.add(var10);
               }
            }
         }
      }

      StringBuilder var11 = new StringBuilder(String.join(var4, var5));

      for (Entry var13 : var1.entrySet()) {
         if (!var6.contains(var13.getKey())) {
            if (var11.length() > 0 && !var11.toString().endsWith(var4)) {
               var11.append(var4);
            }

            var11.append((String)var13.getKey()).append('=').append(clean((String)var13.getValue())).append(var4);
         }
      }

      Util.writeTextWithBackup(var0, var11.toString(), var2);
   }

   private static String clean(String var0) {
      return var0 == null ? "" : var0.replace("\r", "").replace("\n", " ");
   }

   static List<Map<String, Object>> readSandbox(Path var0) throws IOException {
      ArrayList var1 = new ArrayList();
      if (!Files.exists(var0)) {
         return var1;
      } else {
         String[] var2 = Util.readText(var0).split("\r?\n", -1);
         ArrayDeque var3 = new ArrayDeque();
         ArrayList var4 = new ArrayList();

         for (int var5 = 0; var5 < var2.length; var5++) {
            String var6 = var2[var5];
            String var7 = var6.trim();
            if (var7.startsWith("--")) {
               var4.add(var7.replaceFirst("^--\\s?", ""));
            } else {
               Matcher var8;
               if ((var8 = OPEN.matcher(var6)).matches()) {
                  var3.addLast(var8.group(2));
                  var4.clear();
               } else if (CLOSE.matcher(var6).matches()) {
                  if (!var3.isEmpty()) {
                     var3.removeLast();
                  }

                  var4.clear();
               } else {
                  if ((var8 = KV.matcher(var6)).matches()) {
                     ArrayList var9 = new ArrayList(var3);
                     if (!var9.isEmpty() && ((String)var9.get(0)).equals("SandboxVars")) {
                        var9.remove(0);
                     }

                     String var10 = var8.group(3);
                     LinkedHashMap var11 = new LinkedHashMap();
                     var11.put("line", var5);
                     var11.put("group", var9.isEmpty() ? "Основные" : String.join(".", var9));
                     var11.put("key", var8.group(2));
                     var11.put("value", var10.startsWith("\"") && var10.endsWith("\"") && var10.length() >= 2 ? var10.substring(1, var10.length() - 1) : var10);
                     var11.put(
                        "type",
                        !var10.equals("true") && !var10.equals("false")
                           ? (var10.startsWith("\"") ? "string" : (var10.matches("-?[0-9.]+") ? "number" : "raw"))
                           : "bool"
                     );
                     var11.put("comment", String.join("\n", var4));
                     var1.add(var11);
                  }

                  var4.clear();
               }
            }
         }

         return var1;
      }
   }

   static int writeSandbox(Path var0, List<Map<String, Object>> var1, Path var2) throws IOException {
      String var3 = Util.readText(var0);
      String var4 = Util.lineSep(var3);
      String[] var5 = var3.split("\r?\n", -1);
      int var6 = 0;

      for (Map var8 : var1) {
         int var9 = ((Number)var8.get("line")).intValue();
         if (var9 >= 0 && var9 < var5.length) {
            Matcher var10 = KV.matcher(var5[var9]);
            if (var10.matches() && var10.group(2).equals(String.valueOf(var8.get("key")))) {
               String var11 = String.valueOf(var8.get("type"));
               String var12 = String.valueOf(var8.get("value"));

               String var13 = switch (var11) {
                  case "bool" -> Boolean.parseBoolean(var12) ? "true" : "false";
                  case "number" -> var12.matches("-?[0-9]+(\\.[0-9]+)?") ? var12 : null;
                  case "string" -> "\"" + var12.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                  default -> var12;
               };
               if (var13 != null) {
                  var5[var9] = var10.group(1) + var10.group(2) + " = " + var13 + "," + (var10.group(4) != null ? " " + var10.group(4) : "");
                  var6++;
               }
            }
         }
      }

      Util.writeTextWithBackup(var0, String.join(var4, var5), var2);
      return var6;
   }
}
