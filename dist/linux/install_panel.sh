#!/usr/bin/env bash
# Ставит или обновляет PZ Panel в ~/pzserver/PZPanel (запускать из распакованной папки PZPanel).
#   bash install_panel.sh              — от pzuser: файлы панели (при обновлении panel.properties не трогается)
#   sudo bash install_panel.sh --service — от root: ещё и systemd-служба pzpanel (вместо pzserver)
# Переменные: PZ_USER (pzuser), PZ_DIR (~/pzserver), SERVERNAME (Banda — только для нового panel.properties).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PZ_USER="${PZ_USER:-pzuser}"
SERVERNAME="${SERVERNAME:-Banda}"

if [ "${1:-}" = "--service" ]; then
  [ "$(id -u)" = 0 ] || { echo "Для --service запустите через sudo"; exit 1; }
  sudo -u "$PZ_USER" -H env SERVERNAME="$SERVERNAME" bash "$0"
  if systemctl is-enabled pzserver >/dev/null 2>&1; then
    echo "Отключаю старую службу pzserver (tmux): сервером теперь управляет панель"
    systemctl disable --now pzserver || true
  fi
  cp "$HERE/pzpanel.service" /etc/systemd/system/pzpanel.service
  systemctl daemon-reload
  systemctl enable pzpanel
  systemctl restart pzpanel
  echo "Служба pzpanel запущена: systemctl status pzpanel  |  журнал: journalctl -u pzpanel -f"
  exit 0
fi

PZ_DIR="${PZ_DIR:-$HOME/pzserver}"
DEST="$PZ_DIR/PZPanel"
[ -f "$PZ_DIR/start-server.sh" ] || { echo "Не найден $PZ_DIR/start-server.sh — сначала поставьте сервер"; exit 1; }
if [ "$HERE" = "$DEST" ]; then echo "Запускайте скрипт из распакованного архива, а не из $DEST"; exit 1; fi
mkdir -p "$DEST"
[ -f "$DEST/pzpanel.jar" ] && cp "$DEST/pzpanel.jar" "$DEST/pzpanel.jar.old" && echo "Прежняя версия: $DEST/pzpanel.jar.old"
cp "$HERE/pzpanel.jar" "$HERE/pzpanel.sh" "$DEST/"
rm -rf "$DEST/src" && cp -r "$HERE/src" "$DEST/src"
chmod +x "$DEST/pzpanel.sh"
if [ ! -f "$DEST/panel.properties" ]; then
  cat > "$DEST/panel.properties" <<PROPS
# PZ Panel. Остальные параметры панель допишет сама при первом запуске.
serverDir=$PZ_DIR
startScript=start-server.sh
zomboidDir=$HOME/Zomboid
zbConfigDir=$HOME/.zombie_buddy
serverName=$SERVERNAME
bind=127.0.0.1
port=8766
openBrowser=false
autoStartServer=true
stopServerOnExit=true
PROPS
  echo "Создан $DEST/panel.properties"
else
  echo "$DEST/panel.properties уже есть — не трогаю"
fi
echo "Панель установлена в $DEST"
if systemctl is-active --quiet pzpanel 2>/dev/null; then
  echo "Служба pzpanel работает — чтобы включить новую версию: sudo systemctl restart pzpanel"
  echo "(при остановке служба сохранит мир и выключит сервер, затем запустит его снова)"
else
  echo "Запуск вручную:  $DEST/pzpanel.sh     (или служба: sudo bash install_panel.sh --service)"
fi
echo "Открыть с вашего ПК:  ssh -L 8766:127.0.0.1:8766 $(id -un)@IP_СЕРВЕРА   →   http://127.0.0.1:8766/"
