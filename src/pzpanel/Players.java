package pzpanel;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Accounts (db/<server>.db), characters + last saved positions (players.db), online list (RCON). */
final class Players {
    private final Config cfg;
    private final ServerManager srv;

    Players(Config cfg, ServerManager srv) { this.cfg = cfg; this.srv = srv; }

    /** Server may hold the DB open; read a temporary copy so we never lock or corrupt it. */
    static final class Copy implements AutoCloseable {
        final Path tmp; final Connection c;
        Copy(Path db) throws IOException, SQLException {
            tmp = Files.createTempFile("pzpanel-", ".db");
            Files.copy(db, tmp, StandardCopyOption.REPLACE_EXISTING);
            c = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath());
        }
        @Override public void close() { try { c.close(); } catch (SQLException ignored) {} try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
    }

    List<String> online() {
        try {
            String r = srv.rcon("players");
            List<String> out = new ArrayList<>();
            for (String l : r.split("\r?\n")) { String t = l.trim(); if (t.startsWith("-")) out.add(t.substring(1).trim()); }
            return out;
        } catch (Exception e) { return null; }
    }

    Map<String, Object> all() {
        Map<String, Object> res = new LinkedHashMap<>();
        List<String> online = online();
        res.put("rconOk", online != null);
        Set<String> on = new HashSet<>(online == null ? List.of() : online);
        Map<String, Map<String, Object>> byUser = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        Path acc = cfg.accountsDb();
        if (Files.exists(acc)) {
            try (Copy cp = new Copy(acc); Statement st = cp.c.createStatement()) {
                Map<Integer, String> roles = new HashMap<>();
                try (ResultSet rs = st.executeQuery("select id,name from role")) { while (rs.next()) roles.put(rs.getInt(1), rs.getString(2)); }
                catch (SQLException ignored) {}
                try (ResultSet rs = st.executeQuery("select * from whitelist")) {
                    ResultSetMetaData md = rs.getMetaData();
                    Set<String> cols = new HashSet<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnName(i).toLowerCase(Locale.ROOT));
                    while (rs.next()) {
                        String u = rs.getString("username");
                        if (u == null) continue;
                        Map<String, Object> m = byUser.computeIfAbsent(u, k -> new LinkedHashMap<>());
                        m.put("username", u);
                        m.put("lastConnection", cols.contains("lastconnection") ? rs.getString("lastConnection") : null);
                        if (cols.contains("role")) { int r = rs.getInt("role"); m.put("role", roles.getOrDefault(r, String.valueOf(r))); }
                        else if (cols.contains("admin")) m.put("role", rs.getBoolean("admin") ? "admin" : "user");
                        m.put("steamid", cols.contains("steamid") ? rs.getString("steamid") : null);
                        m.put("displayName", cols.contains("displayname") ? rs.getString("displayName") : null);
                        // never expose password hashes
                    }
                }
            } catch (Exception e) { errors.add("Аккаунты: " + e.getMessage()); }
        }
        List<Map<String, Object>> chars = new ArrayList<>();
        Path pdb = cfg.playersDb();
        if (Files.exists(pdb)) {
            try (Copy cp = new Copy(pdb); Statement st = cp.c.createStatement();
                 ResultSet rs = st.executeQuery("select username,name,steamid,x,y,z,isDead from networkPlayers")) {
                while (rs.next()) {
                    Map<String, Object> ch = new LinkedHashMap<>();
                    String u = rs.getString("username");
                    ch.put("username", u);
                    ch.put("character", rs.getString("name"));
                    ch.put("steamid", rs.getString("steamid"));
                    ch.put("x", rs.getDouble("x"));
                    ch.put("y", rs.getDouble("y"));
                    ch.put("z", rs.getDouble("z"));
                    ch.put("dead", rs.getBoolean("isDead"));
                    ch.put("online", u != null && on.contains(u));
                    chars.add(ch);
                    Map<String, Object> m = byUser.computeIfAbsent(u, k -> new LinkedHashMap<>(Map.of("username", k)));
                    @SuppressWarnings("unchecked") List<Map<String, Object>> l = (List<Map<String, Object>>) m.computeIfAbsent("characters", k -> new ArrayList<>());
                    l.add(ch);
                }
            } catch (Exception e) { errors.add("Персонажи: " + e.getMessage()); }
            try { res.put("positionsSavedAt", Files.getLastModifiedTime(pdb).toMillis()); } catch (IOException ignored) {}
        }
        for (String u : on) byUser.computeIfAbsent(u, k -> new LinkedHashMap<>(Map.of("username", k)));
        for (var m : byUser.values()) m.put("online", on.contains(String.valueOf(m.get("username"))));
        res.put("accounts", new ArrayList<>(byUser.values()));
        res.put("characters", chars);
        res.put("online", online == null ? List.of() : online);
        res.put("errors", errors);
        res.put("saveEveryMinutes", Configs.iniValue(cfg.serverIni(), "SaveWorldEveryMinutes"));
        return res;
    }
}
