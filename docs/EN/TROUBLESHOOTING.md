[⬅ Main README](../../README.md) · [Overview](README.md) · [Install](INSTALL.md) · [Configuration](CONFIG.md) · [Mods](MODS.md) · [Map](MAP.md) · [Docker](DOCKER.md) · [Architecture](ARCHITECTURE.md) · [Troubleshooting](TROUBLESHOOTING.md)

# Troubleshooting

| Symptom | What to do |
|---|---|
| The browser says "Forbidden host" | Open exactly `127.0.0.1` or `localhost` (through the tunnel) |
| "Java not found" / "projectzomboid.jar not found" | The panel must be in `~/pzserver/PZPanel` (Windows: in the server folder) and the server must be installed |
| The map is grey | There is no `pyramid.zip`: set the path in `pyramidZip` (it is in `media/maps/Muldraugh, KY/` of the server or the game) |
| "Map layers unavailable" | The map folder was not found — set `mapDir` (the server's `media/maps/Muldraugh, KY`) |
| "Top view" is unavailable | You did not render it or did not copy `base_top` — see "Top view underlay" in [MAP.md](MAP.md) |
| Command buttons do not work, the server was not started from the panel | Set `RCONPassword` in the ini and restart the server |
| Port 8766 is busy | Change `port` in `panel.properties` (and in the SSH tunnel) |
| "Stop" starts the server again in Docker | The container's restart policy brings it back after `quit`; stop it with container tools (`docker stop`) — see [DOCKER.md](DOCKER.md) |
