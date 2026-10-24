[⬅ Main README](../../README.md) · [Overview](README.md) · [Install](INSTALL.md) · [Configuration](CONFIG.md) · [Mods](MODS.md) · [Map](MAP.md) · [Docker](DOCKER.md) · [Architecture](ARCHITECTURE.md) · [Troubleshooting](TROUBLESHOOTING.md)

# Technical description

## Design

The panel is a single Java process with no third-party dependencies: an HTTP server on `com.sun.net.httpserver`, the interface is static files (`index.html`, `app.js`, `style.css`) packed into `pzpanel.jar`. SQLite (accounts, players) and JSON come from the server's `projectzomboid.jar`, so the panel is compiled against exactly that (`javac --release 21`).

| Path | What is there |
|---|---|
| `src/pzpanel/Main.java` | HTTP server, `/api/...` routes, authorization, token |
| `src/pzpanel/ServerManager.java` | start, stop, restart, console, `serverMode=remote` |
| `src/pzpanel/Mods.java` | mods: reading `mod.info`, order, Workshop, "Pin" |
| `src/pzpanel/ModTools.java` | mod auto-sorter, mod archives for players and their cleanup |
| `src/pzpanel/Worlds.java`, `Backups.java` | worlds and backups |
| `src/pzpanel/Configs.java`, `Config.java` | ini, sandbox, `panel.properties` |
| `src/pzpanel/Players.java`, `Rcon.java`, `Stats.java` | players, RCON, load |
| `src/pzpanel/MapData.java` | map data (buildings, streets, zones) from the game files |
| `src/web/` | interface: `index.html`, `app.js`, `style.css`, `i18n.js` + `lang_en.js` (translation) |
| `dist/windows/`, `dist/linux/` | launch scripts, systemd service, installer |
| `scripts/package.sh` | builds the release archives (version — `Main.VERSION`) |

## HTTP API

All requests use JSON; modifying requests (POST) need the `X-PZP` header with the page token. Main routes:

| Group | Routes |
|---|---|
| Server | `/api/status`, `/api/stats`, `/api/server/start|stop|restart|save`, `/api/console`, `/api/rcon` |
| Players | `/api/players` |
| Map | `/api/map/info`, `/api/map/data`, `/api/map/buildings`, `/api/map/building` |
| Mods | `/api/mods`, `/api/mods/sort`, `/api/mods/frozen`, `/api/mods/pack`, `/api/mods/archive/delete`, `/modarchive/<name>` (download) |
| Workshop | `/api/workshop/search|item|details|pin|collection` |
| Configs | `/api/config/ini`, `/api/config/sandbox`, `/api/file`, `/api/zb` |
| Worlds and backups | `/api/worlds[/activate|create|wipe|rename|delete]`, `/api/backups[/create|restore|delete]`, `/backup/<name>` |
| Panel | `/api/settings` |

## Auto-sorter

`ModTools.sort(list, mode)`: a stable topological sort (Kahn's algorithm) over the edges `require`, `loadModAfter`, `loadModBefore`. Mode `fix` keeps the current order; mode `categories` first ranks the mods by a heuristic category (libraries → resources → maps → vehicles → code → clothing → interface → other → translations), then alphabetically, and then the same dependency check is applied. Cycles and the mods depending on them are appended to the end and returned in the warnings.

## Mod archives

Folder `PZPanel/mod_archives/`. A build runs in the background (a job with progress), the file is written as `.part` and renamed when done. Cleanup (every 10 minutes and on every build): one newest full archive `PZ_mods_all_*.zip` stays; single-mod archives `PZ_mod_*.zip` older than `modArchiveTtlHours` (24 h) are deleted; abandoned `.part` files are deleted. Download is `/modarchive/<name>`, for logged-in users only, the name is checked against a pattern.

## Interface language

The source texts are Russian. For English `i18n.js` replaces Russian phrases on the fly using the dictionary `lang_en.js` (page text, attributes, `confirm`/`prompt`/`alert`, server messages); the choice is kept in the browser's `localStorage` (`pzp.lang`). A new interface string is added to the dictionary as a "Russian — English" pair; `{}` is a substituted value.

## Versions and releases

The version is the constant `Main.VERSION`. `scripts/package.sh` builds `out/PZPanel_<version>_{Windows,Linux}.zip` (the panel, launch scripts, sources, README).
