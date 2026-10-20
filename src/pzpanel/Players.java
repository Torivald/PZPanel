package pzpanel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Players {
   private final Config cfg;
   private final ServerManager srv;

   Players(Config var1, ServerManager var2) {
      this.cfg = var1;
      this.srv = var2;
   }

   List<String> online() {
      try {
         String var1 = this.srv.rcon("players");
         ArrayList var2 = new ArrayList();

         for (String var6 : var1.split("\r?\n")) {
            String var7 = var6.trim();
            if (var7.startsWith("-")) {
               var2.add(var7.substring(1).trim());
            }
         }

         return var2;
      } catch (Exception var8) {
         return null;
      }
   }

   Map<String, Object> all() {
      LinkedHashMap var1 = new LinkedHashMap();
      List var2 = this.online();
      var1.put("rconOk", var2 != null);
      HashSet<String> var3 = new HashSet<>(var2 == null ? List.of() : var2);
      LinkedHashMap<String, Map<String, Object>> var4 = new LinkedHashMap<>();
      ArrayList var5 = new ArrayList();
      Path var6 = this.cfg.accountsDb();
      if (Files.exists(var6)) {
         try (
            Players.Copy var7 = new Players.Copy(var6);
            Statement var8 = var7.c.createStatement();
         ) {
            HashMap var9 = new HashMap();

            try (ResultSet var10 = var8.executeQuery("select id,name from role")) {
               while (var10.next()) {
                  var9.put(var10.getInt(1), var10.getString(2));
               }
            } catch (SQLException var30) {
            }

            try (ResultSet var39 = var8.executeQuery("select * from whitelist")) {
               ResultSetMetaData var11 = var39.getMetaData();
               HashSet var12 = new HashSet();

               for (int var13 = 1; var13 <= var11.getColumnCount(); var13++) {
                  var12.add(var11.getColumnName(var13).toLowerCase(Locale.ROOT));
               }

               while (var39.next()) {
                  String var45 = var39.getString("username");
                  if (var45 != null) {
                     Map<String, Object> var14 = var4.computeIfAbsent(var45, var0 -> new LinkedHashMap<>());
                     var14.put("username", var45);
                     var14.put("lastConnection", var12.contains("lastconnection") ? var39.getString("lastConnection") : null);
                     if (var12.contains("role")) {
                        int var15 = var39.getInt("role");
                        var14.put("role", var9.getOrDefault(var15, String.valueOf(var15)));
                     } else if (var12.contains("admin")) {
                        var14.put("role", var39.getBoolean("admin") ? "admin" : "user");
                     }

                     var14.put("steamid", var12.contains("steamid") ? var39.getString("steamid") : null);
                     var14.put("displayName", var12.contains("displayname") ? var39.getString("displayName") : null);
                  }
               }
            }
         } catch (Exception var33) {
            var5.add("Аккаунты: " + var33.getMessage());
         }
      }

      ArrayList var34 = new ArrayList();
      Path var35 = this.cfg.playersDb();
      if (Files.exists(var35)) {
         try (
            Players.Copy var36 = new Players.Copy(var35);
            Statement var40 = var36.c.createStatement();
            ResultSet var43 = var40.executeQuery("select username,name,steamid,x,y,z,isDead from networkPlayers");
         ) {
            while (var43.next()) {
               LinkedHashMap var44 = new LinkedHashMap();
               String var46 = var43.getString("username");
               var44.put("username", var46);
               var44.put("character", var43.getString("name"));
               var44.put("steamid", var43.getString("steamid"));
               var44.put("x", var43.getDouble("x"));
               var44.put("y", var43.getDouble("y"));
               var44.put("z", var43.getDouble("z"));
               var44.put("dead", var43.getBoolean("isDead"));
               var44.put("online", var46 != null && var3.contains(var46));
               var34.add(var44);
               Map<String, Object> var47 = var4.computeIfAbsent(var46, var0 -> new LinkedHashMap<>(Map.of("username", (String)var0)));
               List<Object> var48 = (List<Object>)var47.computeIfAbsent("characters", var0 -> new ArrayList<Object>());
               var48.add(var44);
            }
         } catch (Exception var27) {
            var5.add("Персонажи: " + var27.getMessage());
         }

         try {
            var1.put("positionsSavedAt", Files.getLastModifiedTime(var35).toMillis());
         } catch (IOException var16) {
         }
      }

      for (String var41 : var3) {
         var4.computeIfAbsent(var41, var0 -> new LinkedHashMap<>(Map.of("username", (String)var0)));
      }

      for (Map<String, Object> var42 : var4.values()) {
         var42.put("online", var3.contains(String.valueOf(var42.get("username"))));
      }

      var1.put("accounts", new ArrayList(var4.values()));
      var1.put("characters", var34);
      var1.put("online", var2 == null ? List.of() : var2);
      var1.put("errors", var5);
      var1.put("saveEveryMinutes", Configs.iniValue(this.cfg.serverIni(), "SaveWorldEveryMinutes"));
      return var1;
   }

   static final class Copy implements AutoCloseable {
      final Path tmp = Files.createTempFile("pzpanel-", ".db");
      final Connection c;

      Copy(Path var1) throws IOException, SQLException {
         Files.copy(var1, this.tmp, StandardCopyOption.REPLACE_EXISTING);
         this.c = DriverManager.getConnection("jdbc:sqlite:" + this.tmp.toAbsolutePath());
      }

      @Override
      public void close() {
         try {
            this.c.close();
         } catch (SQLException var3) {
         }

         try {
            Files.deleteIfExists(this.tmp);
         } catch (IOException var2) {
         }
      }
   }
}
