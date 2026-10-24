#!/bin/bash
# Метрики процесса сервера PZ для панели, которая работает в другом контейнере (panel.properties: serverMode=remote).
# Запускается внутри контейнера сервера: раз в 5 с атомарно пишет <Zomboid>/.panel-metrics.json.
# Панель берёт оттуда PID, время старта, ЦПУ, ОЗУ и потоки; данные старше 20 с считает устаревшими.
#   ZOMBOID_DIR=/путь/к/Zomboid ./pz-metrics.sh &        # или: ./pz-metrics.sh /путь/к/файлу.json
# Путь должен быть в ОБЩЕМ томе, который панель видит как <zomboidDir>/.panel-metrics.json.
# Процесс сервера ищется по /proc (pgrep/ps не нужны): запускатор ProjectZomboid64 или java ... zombie.network.GameServer.
# Свой шаблон поиска по командной строке: PZ_MATCH='регулярное выражение' ./pz-metrics.sh &
# В контейнере (есть /.dockerenv или /run/.containerenv, либо PZ_SCOPE=container) показывается потребление ВСЕГО контейнера
# по cgroup: ЦПУ, ОЗУ (как в docker stats), число процессов и потоков. PZ_SCOPE=process — только дерево процессов сервера.
# Диагностика пишется в stderr (что найдено и куда пишется файл).
OUT=${1:-${ZOMBOID_DIR:-$HOME/Zomboid}/.panel-metrics.json}
MATCH=${PZ_MATCH:-'zombie\.network\.GameServer|ProjectZomboid64|ProjectZomboid6[0-9]*( |$)'}
HZ=$(getconf CLK_TCK 2>/dev/null || echo 100)
CORES=$(nproc 2>/dev/null || grep -c '^processor' /proc/cpuinfo)
SCOPE=${PZ_SCOPE:-}
if [ -z "$SCOPE" ]; then
  if [ -e /.dockerenv ] || [ -e /run/.containerenv ]; then SCOPE=container; else SCOPE=process; fi
fi
CG=""  # версия cgroup: 2 или 1
if [ "$SCOPE" = container ]; then
  if [ -r /sys/fs/cgroup/memory.current ]; then CG=2
  elif [ -r /sys/fs/cgroup/memory/memory.usage_in_bytes ]; then CG=1
  else SCOPE=process; echo "pz-metrics: cgroup недоступен — считаю только процесс сервера" >&2; fi
fi
mkdir -p "$(dirname "$OUT")" 2>/dev/null
echo "pz-metrics: пишу в $OUT, область: $SCOPE${CG:+ (cgroup v$CG)}, шаблон: $MATCH" >&2
[ -w "$(dirname "$OUT")" ] || echo "pz-metrics: ВНИМАНИЕ: папка $(dirname "$OUT") недоступна для записи" >&2

find_root() {
  local d p comm cmd best=""
  for d in /proc/[0-9]*; do
    p=${d#/proc/}
    [ "$p" = "$$" ] && continue
    comm=$(cat "$d/comm" 2>/dev/null) || continue
    case "$comm" in bash|sh|dash|pz-metrics*|tail|grep|sleep|docker*|pgrep|ps) continue ;; esac
    cmd=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null) || continue
    if [[ "$comm" == ProjectZomboid6* || "$cmd" =~ $MATCH ]]; then
      # самый старый (с наименьшим PID) из подходящих — корень дерева
      if [ -z "$best" ] || [ "$p" -lt "$best" ]; then best=$p; fi
    fi
  done
  echo "$best"
}

children_of() {  # PID потомков процесса $1 (по ppid из /proc/*/stat)
  local want=$1 d p pp
  for d in /proc/[0-9]*; do
    p=${d#/proc/}
    pp=$(sed 's/.*) //' "$d/stat" 2>/dev/null | awk '{print $2}')
    [ "$pp" = "$want" ] && echo "$p"
  done
}

# потребление всего контейнера по cgroup: выводит "ticks rssKb threads procs cores"
cgroup_stats() {
  local ticks=0 rss=0 thr=0 procs=0 cores=$CORES
  if [ "$CG" = 2 ]; then
    local us inact q per
    us=$(awk '/^usage_usec/{print $2}' /sys/fs/cgroup/cpu.stat 2>/dev/null)
    ticks=$(( ${us:-0} * HZ / 1000000 ))
    rss=$(( $(cat /sys/fs/cgroup/memory.current 2>/dev/null || echo 0) / 1024 ))
    inact=$(awk '/^inactive_file /{print $2}' /sys/fs/cgroup/memory.stat 2>/dev/null)
    rss=$(( rss - ${inact:-0} / 1024 )); [ $rss -lt 0 ] && rss=0
    thr=$(cat /sys/fs/cgroup/pids.current 2>/dev/null || echo 0)
    procs=$(wc -l < /sys/fs/cgroup/cgroup.procs 2>/dev/null || echo 0)
    read -r q per < /sys/fs/cgroup/cpu.max 2>/dev/null
    if [ -n "$q" ] && [ "$q" != max ] && [ "${per:-0}" -gt 0 ]; then cores=$(( (q + per - 1) / per )); fi
  else
    local ns inact
    ns=$(cat /sys/fs/cgroup/cpuacct/cpuacct.usage /sys/fs/cgroup/cpu,cpuacct/cpuacct.usage 2>/dev/null | head -1)
    ticks=$(( ${ns:-0} / 1000 * HZ / 1000000 ))
    rss=$(( $(cat /sys/fs/cgroup/memory/memory.usage_in_bytes 2>/dev/null || echo 0) / 1024 ))
    inact=$(awk '/^total_inactive_file /{print $2}' /sys/fs/cgroup/memory/memory.stat 2>/dev/null)
    rss=$(( rss - ${inact:-0} / 1024 )); [ $rss -lt 0 ] && rss=0
    thr=$(cat /sys/fs/cgroup/pids/pids.current 2>/dev/null || echo 0)
    procs=$(wc -l < /sys/fs/cgroup/memory/cgroup.procs 2>/dev/null || echo 0)
  fi
  [ "${thr:-0}" -gt 0 ] 2>/dev/null || thr=$(cat /proc/[0-9]*/status 2>/dev/null | awk '/^Threads:/{s+=$2} END{print s+0}')
  echo "$ticks $rss $thr $procs $cores"
}

last=""
while :; do
  root=$(find_root)
  if [ -n "$root" ]; then
    [ "$last" != "$root" ] && echo "pz-metrics: найден процесс сервера PID $root ($(tr '\0' ' ' < /proc/$root/cmdline | cut -c1-120))" >&2
    last=$root
    pids=$root
    frontier=$root
    depth=0
    while [ -n "$frontier" ] && [ $depth -lt 6 ]; do
      next=$(for p in $frontier; do children_of "$p"; done)
      pids="$pids $next"
      frontier=$next
      depth=$((depth + 1))
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
    out_cores=$CORES
    if [ "$SCOPE" = container ]; then
      read -r ticks rss thr n out_cores <<< "$(cgroup_stats)"
    fi
    set -- $(sed 's/.*) //' "/proc/$root/stat" 2>/dev/null)
    start=${20:-0}  # starttime (22) в тиках с загрузки системы
    up=$(awk '{print int($1)}' /proc/uptime)
    startEpoch=$(($(date +%s) - up + start / HZ))
    tmp="$OUT.tmp"
    if printf '{"ts":%s,"pid":%s,"startEpoch":%s,"ticks":%s,"hz":%s,"cores":%s,"rssKb":%s,"threads":%s,"procs":%s,"scope":"%s"}\n' \
      "$(date +%s%3N)" "$root" "$startEpoch" "$ticks" "$HZ" "$out_cores" "$rss" "$thr" "$n" "$SCOPE" > "$tmp" 2>/dev/null && mv -f "$tmp" "$OUT" 2>/dev/null; then :; else
      [ "$last" != "err" ] && echo "pz-metrics: не удалось записать $OUT" >&2; last=err
    fi
  else
    [ "$last" != "none" ] && echo "pz-metrics: процесс сервера не найден (шаблон: $MATCH) — файл удалён" >&2
    last=none
    rm -f "$OUT"
  fi
  sleep 5
done
