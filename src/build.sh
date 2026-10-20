#!/usr/bin/env bash
# Сборка pzpanel.jar из исходников. Нужен JDK 21+ (sudo apt install -y openjdk-21-jdk-headless).
#   bash build.sh                       — projectzomboid.jar ищется в ~/pzserver/java
#   PZ_JAR=/путь/к/projectzomboid.jar bash build.sh
set -euo pipefail
cd "$(dirname "$0")"
PZ_JAR="${PZ_JAR:-}"
for c in "$PZ_JAR" ../../java/projectzomboid.jar "$HOME/pzserver/java/projectzomboid.jar"; do
  [ -n "$c" ] && [ -f "$c" ] && { PZ_JAR="$c"; break; }
done
[ -f "$PZ_JAR" ] || { echo "Не найден projectzomboid.jar — укажите PZ_JAR=/путь/к/projectzomboid.jar"; exit 1; }
rm -rf build && mkdir -p build
javac --release 21 -encoding UTF-8 -nowarn -cp "$PZ_JAR" -d build pzpanel/*.java
cp -r web build/web
rm -f pzpanel.jar
jar --create --file pzpanel.jar --main-class pzpanel.Main -C build .
rm -rf build
echo "Готово: $(pwd)/pzpanel.jar"
