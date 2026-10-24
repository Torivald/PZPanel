[⬅ Main README](../../README.md) · [Overview](README.md) · [Install](INSTALL.md) · [Configuration](CONFIG.md) · [Mods](MODS.md) · [Map](MAP.md) · [Docker](DOCKER.md) · [Architecture](ARCHITECTURE.md) · [Troubleshooting](TROUBLESHOOTING.md)

# Map

![Map](../screenshots/map.png)

Everything is taken straight from the game's map files on the server (`media/maps/Muldraugh, KY`): the panel reads them on start in a few seconds, nothing has to be rendered. The "top view" underlay is optional, see below.

- **Search** (the field on top): players by name, towns, streets, points of interest by type — "pharmacy", "gun store", "police", "gas station", "gunstore"… Points of interest are sorted by distance from the screen centre. Click a result to fly there.
- **Players**: online (green), offline (blue), dead (red), with a list to jump to. Positions update when the server saves the world (the "Update positions" button runs `save`).
- **Points of interest** — buildings classified by their rooms: medical and pharmacies, police, weapons and hunting, military, prison, gas stations, groceries, fire stations, car service, tools and storage, clothing and sport, electronics, books, cafés and bars, gyms, schools, churches, motels, banks and post, farms, other shops, garages and sheds (off by default — there are thousands). Checkboxes toggle categories.
- **Buildings**: when you zoom in the outlines of houses are visible; the colour is the category. Click a building or a point for a card: rooms **by floor** (and the basement), coordinates, "Copy coordinates" and **"Teleport here"** (the selected online player, the `teleportto` command).
- **Layers**: towns, streets with names, zombie density (as set by the game map, ignoring sandbox settings), spawn points (with professions), story locations, basements, rich districts, trailer parks, farms and ranches.
- **Link to a place** — the 🔗 button copies an address like `http://127.0.0.1:8766/#map@12800,1780,1.2` (x, y, scale). Opening it makes the panel show that place.
- Under the map — the cursor coordinates and the nearest town.

## "Top view" underlay (optional)

The usual underlay is the map from the game itself. "Top view" is the real world tiles (roofs, roads, trees) and **each floor separately**. It is rendered once by [pzmap2dzi](https://github.com/cff29546/pzmap2dzi) (MIT) on a **Windows PC with the game installed** — the textures exist only in the game client, not in the dedicated server.

1. On a Windows PC with the game run **`render_top_view.bat`** (it is in `PZPanel` in the server folder on the host PC; in the Linux server archive — `panel/render_top_view.bat` and `.ps1`, which can be put in any folder). The script downloads pzmap2dzi itself (a pinned version), installs the Python dependencies (if there is no Python it offers to install it via winget), finds the game and starts the render.
   - Size: `render_top_view.bat 1` — about 1 h, ~100 MB; **`2` (default)** — 1–2 h, ~300 MB; `4` — 2–4 h, ~1 GB. The time depends on the CPU.
   - You can interrupt at any moment — running it again continues where it stopped.
   - Game in another folder: `render_top_view.bat 2 "D:\SteamLibrary\steamapps\common\ProjectZomboid"`. If the script is not in the server folder next to the game, the game path is mandatory; the result appears in the `map` folder next to the script.
2. The result is `map\html\map_data\base_top` next to the script. Restart the panel: "Top view" and a floor picker appear in "Underlay". The `map\texture` folder (~500 MB) can be deleted after rendering.
3. For a **Linux server**, copy the result to the panel on the server:
   ```bash
   scp -r "PZPanel/map/html/map_data/base_top" pzuser@SERVER_IP:~/pzserver/PZPanel/map/html/map_data/
   ```
   (create the `~/pzserver/PZPanel/map/html/map_data` folder first), then `sudo systemctl restart pzpanel`. Another path can be given in `topViewDir`.
