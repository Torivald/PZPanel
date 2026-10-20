#!/usr/bin/env bash
# Запуск PZ Panel на Linux. Java и projectzomboid.jar берутся из папки сервера.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PZ_DIR="${PZ_DIR:-$(dirname "$HERE")}"
[ -f "$PZ_DIR/start-server.sh" ] || PZ_DIR="$HOME/pzserver"
JAVA="$PZ_DIR/jre64/bin/java"
[ -x "$JAVA" ] || JAVA="$(command -v java || true)"
[ -n "$JAVA" ] || { echo "Не найдена Java: ожидалась $PZ_DIR/jre64/bin/java"; exit 1; }
JAR="$PZ_DIR/java/projectzomboid.jar"
[ -f "$JAR" ] || JAR="$PZ_DIR/projectzomboid.jar"
[ -f "$JAR" ] || { echo "Не найден projectzomboid.jar в $PZ_DIR (нужен для SQLite и JSON)"; exit 1; }
exec "$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -Xmx512m \
  -cp "$HERE/pzpanel.jar:$JAR" pzpanel.Main "$HERE"
