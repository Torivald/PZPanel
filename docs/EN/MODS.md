[⬅ Main README](../../README.md) · [Overview](README.md) · [Install](INSTALL.md) · [Configuration](CONFIG.md) · [Mods](MODS.md) · [Map](MAP.md) · [Docker](DOCKER.md) · [Architecture](ARCHITECTURE.md) · [Troubleshooting](TROUBLESHOOTING.md)

# Mods

## Frozen mods

![Mods: the Pin button](../screenshots/mods_pin.png)

After freezing (`scripts/04_freeze_mods.sh` or `freeze_mods.bat`) the mods on the "Mods" tab are marked **"local"**, and the "Workshop items" list is empty — that is how it should be.

If you add a **new** mod from the Workshop through the panel, its item goes into `WorkshopItems=` and the server will download only it from Steam. To freeze it too: stop the server → start it once (the mod downloads) → stop it → `bash scripts/04_freeze_mods.sh` (on Windows `freeze_mods.bat`) → send players the new mod archive (it appears in `~`, on Windows in the server folder). Already frozen mods are neither copied nor updated again — only the new one is added, and the archive is built with all the mods. If the server is not frozen and the mods were just updated, `bash scripts/03_build_mods_archive.sh` builds a fresh archive without changing anything.

### Pin a Workshop mod right in the panel

Next to an item in the "Workshop items" list there is a **"📌 Pin"** button. The panel takes the mod from the server's `steamapps/workshop/content/108600/<id>` (if it is not there it downloads it with `steamcmd`, the `steamcmd` option is needed), copies the mod's folders to `Zomboid/mods`, appends to `_frozen_manifest.txt` and removes the ID from `WorkshopItems=`. From then on the server does not update this mod from Steam, and on the "Mods" tab it is marked **"📌 pinned"**. A server restart is needed. If the mod has Java code (`.jar`) the panel shows its sha256 and offers to write an approval to ZombieBuddy — otherwise the server blocks the mod on start. The archive for players is still built by the freeze script.

**On Windows:**
- the mod is looked up first among those already downloaded: the server's Workshop, then the Steam client's Workshop on this PC (`<Steam library>\steamapps\workshop\content\108600`) and the folders from `workshopDirs`. A folder downloaded by the client is not deleted;
- if the mod is nowhere, SteamCMD does the job (anonymously, no Steam account needed). If it is not found the panel downloads it from Valve's site into `PZPanel\steamcmd`. The first SteamCMD run updates itself, so the first pin takes longer;
- **you can pin only while the server is stopped**: while it runs, Windows keeps the mod files (`.jar`) open;
- the panel also appends to `_frozen_workshop_items.txt`, so `freeze_mods.bat` and `pack_mods.bat` see the pinned mod.

### Upload a mod as an archive

If a mod is not on the Workshop (your own build, a mod from another site, an item downloaded by hand), install it from a zip archive: "Mods" tab → the **"Upload a mod as an archive"** card → choose a `.zip` → "Upload".

- The panel finds the mod folders in the archive by `mod.info`. Supported: `<Mod folder>/42/mod.info` (B42), `<Mod folder>/mod.info`, a Workshop item as is (`<ID>/mods/<Mod folder>/…`), `mod.info` right in the archive root (the folder is named after the mod `id`) and a players' mod archive (several folders at once, item IDs are taken from its `_frozen_manifest.txt`).
- Before installing, the panel shows what it found: folders, mod `id`s, whether there is Java code, clashes with mods already installed. You can untick folders, choose which mods to enable right away and give the Workshop item ID (optional).
- The folders go to `Zomboid/mods`, the ticked mods are enabled in `Mods=` and the list is saved. The server does not update such mods from Steam. If an item ID is given, it is written to `_frozen_manifest.txt` and `_frozen_workshop_items.txt` (as for pinned mods) and removed from `WorkshopItems=`; then a ZombieBuddy approval for Java code can be written right away.
- A folder with the same name is not deleted but moved to `Zomboid/mods_replaced/<date_time>/`, from where you can bring it back. **An installed folder can be replaced only while the server is stopped**; a new one can be added while it runs (picked up after a restart).
- It must be a `.zip` (repack 7z and rar). The size limit is `modUploadMaxMb` (4096 MB by default). If the panel is behind a proxy (nginx, a router), the proxy may have its own request size limit.
- After adding or replacing a mod, players need a new mod archive ("⬇ Download from server" → "All installed mods").

### Mods on disk and old versions

The **"Mods on disk"** card → "Show": all the server's mods with sizes — `Zomboid/mods` folders and items downloaded by the server itself (`steamapps/workshop/content/108600`; the game's Steam client Workshop is not touched). Tick the ones you need (or "select all") → **"Delete selected"**. With "remove the deleted mods from the … mod list" ticked, their mods are removed from `Mods=` (if no other copy of the mod is left) and the items from `WorkshopItems=` of the active world; `_frozen_manifest.txt` is cleaned too. Deleting works only while the server is stopped and cannot be undone; to delete all mods the panel asks you to type "УДАЛИТЬ".

The same card lists **old versions** from `Zomboid/mods_replaced` (archive uploads put them there): "Restore" puts that version in place of the current one (the current one goes to `mods_replaced`), "Delete" removes it for good. Versions older than `modReplacedKeepDays` days (30 by default, 0 — forever) are deleted automatically.

## Mod auto-sorter

![Mod auto-sorter](../screenshots/sorter_en.png)

On the "Mods" tab the **"Sort…"** button orders the list of enabled mods. The idea is the same as the [Mod Load Order Sorter [b42]](https://steamcommunity.com/sharedfiles/filedetails/?id=3423660713) mod, but it works on the server, before the game starts. A preview is shown first (what moves where, warnings); the order changes only after **"Apply"** and reaches the server after **"Save"**.

- **Fix the order** — your order is kept, only the mods that break `require`, `loadModAfter`, `loadModBefore` are moved (a stable topological sort).
- **By category and alphabet** — libraries → resources → maps → vehicles → code → clothing → interface → other → translations, alphabetical inside a category; then the same dependency check. A hand-tuned order is lost.
- Circular dependencies and the mods that depend on them stay at the end of the list and are reported in the warnings; missing requirements and incompatible pairs (`incompatible`) are shown too.

Change the mod order only while the server is stopped and before the game starts: in an already started world, changing the order can corrupt the save.

## Downloading mods by players

![Download a mod from the server](../screenshots/download.png)

Every **frozen** mod (tag "local" / "📌 pinned") on the "Mods" tab has a **"⬇ Download from server"** button. It opens a choice:

- **Only this mod** — a zip with the mod's folder(s) (`PZ_mod_<name>_<time>.zip`);
- **All installed mods** — a full archive (`PZ_mods_all_<time>.zip`): all folders from `Zomboid/mods`, a `default.txt` with the server's order, a manifest and a README. It can also be built with the "Prepare archive" button in the "Mod archives for players" card.

The archive is built in the background with a progress indicator, then a download link appears (panel login required). The archive folder is `PZPanel/mod_archives/`. **Retention policy** (checked every 10 minutes and on every build):

- there is always **one** full archive — a new one replaces the old;
- a single-mod archive is deleted after **24 hours** (option `modArchiveTtlHours`); a fresh archive of the same mod is reused;
- unfinished `.part` files are deleted. Any archive can be deleted by hand.
