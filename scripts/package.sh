#!/usr/bin/env bash
# Сборка pzpanel.jar и готовых архивов для Windows и Linux (как в релизах).
#   PZ_JAR=/путь/к/projectzomboid.jar bash scripts/package.sh
# Результат: out/PZPanel_<версия>_Windows.zip и out/PZPanel_<версия>_Linux.zip
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# .bat должны быть только ASCII: при chcp 65001 cmd неверно читает UTF-8 и выполняет обрывки строк
if LC_ALL=C grep -l '[^ -~[:space:]]' dist/windows/*.bat src/build.bat; then echo "Ошибка: в .bat есть не-ASCII символы (см. выше)"; exit 1; fi
bash src/build.sh
VER="$(grep -oP 'static final String VERSION = "\K[^"]+' src/pzpanel/Main.java)"
OUT="$ROOT/out"; rm -rf "$OUT/stage"; mkdir -p "$OUT"
for os in windows linux; do
  S="$OUT/stage/$os/PZPanel"; mkdir -p "$S"
  cp dist/$os/* "$S/"
  cp src/pzpanel.jar "$S/"
  mkdir -p "$S/src"; cp -r src/pzpanel src/web src/build.sh src/build.bat "$S/src/"
  [ "$os" = linux ] && chmod +x "$S"/*.sh "$S/src/build.sh"
  name="PZPanel_${VER}_$( [ $os = windows ] && echo Windows || echo Linux ).zip"
  rm -f "$OUT/$name"; (cd "$OUT/stage/$os" && zip -qr "$OUT/$name" PZPanel)
  echo "Готово: out/$name"
done
rm -rf "$OUT/stage" src/pzpanel.jar
