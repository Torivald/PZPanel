[⬅ Main README](../../README.md) · [Overview](README.md) · [Install](INSTALL.md) · [Configuration](CONFIG.md) · [Mods](MODS.md) · [Map](MAP.md) · [Docker](DOCKER.md) · [Architecture](ARCHITECTURE.md) · [Troubleshooting](TROUBLESHOOTING.md)

# Panel in a separate container

The panel can run apart from the server, for example in two Docker containers. Both mount the same folders (the server folder, `Zomboid`, `~/.zombie_buddy`); in `panel.properties`:

```
serverMode=remote
rconHost=<container name or server host>
```

The panel finds the server by its open RCON port, and commands go through RCON. There is no server process in its container, so for the PID, uptime and CPU/RAM charts run `pz-metrics.sh` from `dist/linux` inside the **server** container:

```
ZOMBOID_DIR=/path/to/Zomboid ./pz-metrics.sh &
```

The script finds the server process through `/proc` (the `ProjectZomboid64` launcher or `java … zombie.network.GameServer`; a custom pattern via the `PZ_MATCH` variable). Inside a container it reports the consumption of the **whole container** (CPU, RAM as in `docker stats`, number of processes and threads, from the cgroup); `PZ_SCOPE=process` — only the server processes. What it found and where it writes goes to the script's stderr. The file path must be on the shared volume that the panel sees as `<zomboidDir>/.panel-metrics.json`.

The script writes `<Zomboid>/.panel-metrics.json` every 5 s; the panel treats data older than 20 s as stale and shows "no fresh metrics". "Stop" in this mode sends `save` and `quit` (if the container has a restart policy the server comes back up), "Start" works through `startRequestFile` (see [CONFIG.md](CONFIG.md)) and "Kill" is not available.
