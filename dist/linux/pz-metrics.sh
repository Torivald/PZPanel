#!/bin/bash
# Метрики процесса сервера PZ для панели, которая работает в другом контейнере (panel.properties: serverMode=remote).
# Запускается внутри контейнера сервера: раз в 5 с атомарно пишет <Zomboid>/.panel-metrics.json.
# Панель берёт оттуда PID, время старта, ЦПУ, ОЗУ и потоки; данные старше 20 с считает устаревшими.
#   ZOMBOID_DIR=/путь/к/Zomboid ./pz-metrics.sh &        # или: ./pz-metrics.sh /путь/к/файлу.json
OUT=${1:-${ZOMBOID_DIR:-$HOME/Zomboid}/.panel-metrics.json}
HZ=$(getconf CLK_TCK)
CORES=$(nproc)
while :; do
  root=$(pgrep -o -x ProjectZomboid6)  # имя процесса в /proc обрезано до 15 символов
  if [ -n "$root" ]; then
    pids=$root
    frontier=$root
    while [ -n "$frontier" ]; do
      next=$(for p in $frontier; do pgrep -P "$p"; done)
      pids="$pids $next"
      frontier=$next
    done
    rss=0 thr=0 ticks=0 n=0
    for p in $pids; do
      [ -r "/proc/$p/status" ] || continue
      n=$((n + 1))
      r=$(awk '/^VmRSS:/{print $2}' "/proc/$p/status")
      t=$(awk '/^Threads:/{print $2}' "/proc/$p/status")
      rss=$((rss + ${r:-0}))
      thr=$((thr + ${t:-0}))
      # после ") " идут поля с 3-го: utime(14)=$12, stime(15)=$13
      set -- $(sed 's/.*) //' "/proc/$p/stat" 2>/dev/null)
      ticks=$((ticks + ${12:-0} + ${13:-0}))
    done
    set -- $(sed 's/.*) //' "/proc/$root/stat" 2>/dev/null)
    start=${20:-0}  # starttime (22) в тиках с загрузки системы
    up=$(awk '{print int($1)}' /proc/uptime)
    startEpoch=$(($(date +%s) - up + start / HZ))
    tmp="$OUT.tmp"
    printf '{"ts":%s,"pid":%s,"startEpoch":%s,"ticks":%s,"hz":%s,"cores":%s,"rssKb":%s,"threads":%s,"procs":%s}\n' \
      "$(date +%s%3N)" "$root" "$startEpoch" "$ticks" "$HZ" "$CORES" "$rss" "$thr" "$n" > "$tmp" && mv -f "$tmp" "$OUT"
  else
    rm -f "$OUT"
  fi
  sleep 5
done
