[⬅ Main README](../../README.md) · [Overview](README.md) · [Install](INSTALL.md) · [Configuration](CONFIG.md) · [Mods](MODS.md) · [Map](MAP.md) · [Docker](DOCKER.md) · [Architecture](ARCHITECTURE.md) · [Troubleshooting](TROUBLESHOOTING.md)

# Installation

Ready-made archives are on the [Releases](https://github.com/Torivald/PZPanel/releases) page: `PZPanel_<version>_Windows.zip` and `PZPanel_<version>_Linux.zip`. Nothing has to be installed for the panel: Java and libraries come from the server itself.

## Windows (server on this PC)

1. The `PZPanel` folder must be **inside** the server folder: `...\steamapps\common\Project Zomboid Dedicated Server\PZPanel\`.
2. Double-click **`PZPanel.bat`**. The browser opens: http://127.0.0.1:8766/
3. Keep the panel's console window open while you use the panel. A server started from the panel writes its console right into that window. If you close the panel the server keeps running (but there is nowhere to answer ZombieBuddy any more — then start the server with `StartServer64.bat`).

If the server was started not from the panel (with `StartServer64.bat`) the panel will still see it. Commands go through RCON (`RCONPassword` is needed in the ini); the console is not available.

## Linux

Download `PZPanel_<version>_Linux.zip` from [Releases](https://github.com/Torivald/PZPanel/releases), unpack it and, from the `PZPanel` folder, run as user `pzuser` (server in `~/pzserver`):

```bash
bash install_panel.sh
```

The script puts the panel in `~/pzserver/PZPanel/` and creates a Linux `panel.properties`.

### Option A — panel as a service (recommended)

The panel starts on boot, starts the server itself, and on service stop or reboot it saves the world and shuts the server down cleanly.

```bash
sudo bash install_panel.sh --service
```

The script disables the old `pzserver` (tmux) service if it was enabled: the server must not be started in two ways at once.

```bash
systemctl status pzpanel          # state
journalctl -u pzpanel -f          # panel and server log
sudo systemctl restart pzpanel    # restart the panel (and the server: save + quit, then start again)
```

The server console and ZombieBuddy prompts are on the "Overview and console" tab.

### Option B — server in tmux, panel next to it

Run the server as before (`scripts/start.sh` or the `pzserver` service) and start the panel by hand: `~/pzserver/PZPanel/pzpanel.sh`. The panel will see the server and work through RCON. Set `autoStartServer=false` and `stopServerOnExit=false` in `panel.properties`. In this option the server console is only in tmux (`tmux attach -t pz`).

### Opening the panel from your PC

The panel listens on `127.0.0.1` on the server only, so it is not visible from outside. Open an SSH tunnel from your PC:

```bash
ssh -L 8766:127.0.0.1:8766 pzuser@SERVER_IP
```

and, while the connection is open, go to **http://127.0.0.1:8766/** in the browser. On Windows this works in PowerShell (OpenSSH is built in) or in PuTTY: Connection → SSH → Tunnels, Source port `8766`, Destination `127.0.0.1:8766`.

Exposing the panel to the internet directly (`bind=0.0.0.0`) is not recommended: it has no HTTPS. If you really need it, set `panelPassword`, open the port for your own IP only and restart the panel.

## Security

- By default the panel listens on `127.0.0.1` only; it is not visible from outside.
- Access through a domain or proxy — only with the `panelPassword` password. The panel has no HTTPS: put a TLS reverse proxy in front of it.
- Every modifying request is protected by a token (against CSRF) and the Host header is checked ("Forbidden host" — open it through `127.0.0.1` / `localhost`).

## Building from source

You need **JDK 21+** and **`projectzomboid.jar`** from your dedicated server (`<server folder>/java/projectzomboid.jar`). Game files are not part of the repository.

```bash
# pzpanel.jar only
PZ_JAR=/path/to/projectzomboid.jar bash src/build.sh

# pzpanel.jar + ready archives for Windows and Linux in out/
PZ_JAR=/path/to/projectzomboid.jar bash scripts/package.sh
```

On Windows: `src\build.bat "C:\...\Project Zomboid Dedicated Server\java\projectzomboid.jar"`. If the `src` folder is inside `PZPanel` in the server folder, the path can be omitted.

JDK: Linux — `sudo apt install -y openjdk-21-jdk-headless`, Windows — `winget install EclipseAdoptium.Temurin.21.JDK`.
