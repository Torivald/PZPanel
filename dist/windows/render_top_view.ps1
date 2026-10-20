# Рендер подложки «вид сверху» для карты PZ Panel (pzmap2dzi, MIT, github.com/cff29546/pzmap2dzi).
# Нужна установленная игра Project Zomboid (текстуры есть только в клиенте, не в выделенном сервере).
# Запуск: render_top_view.bat  [размер клетки в пикселях: 1, 2 (по умолчанию) или 4]
param([int]$Square = 2, [string]$Game = '')

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
function Say($t, $c = 'Gray') { Write-Host $t -ForegroundColor $c }

$PanelDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Work     = Join-Path $PanelDir 'map'
$Commit   = '5025122c1a6de655122d79ef84dfcb40a632ea68'
$SrcDir   = Join-Path $Work 'pzmap2dzi'

# 1. Игра
if (-not $Game) {
    $srv = Split-Path -Parent $PanelDir                       # ...\common\Project Zomboid Dedicated Server
    $Game = Join-Path (Split-Path -Parent $srv) 'ProjectZomboid'
}
if (-not (Test-Path (Join-Path $Game 'media\texturepacks\Tiles2x.pack'))) {
    Say "Не найдена игра (нужны media\texturepacks): $Game" Red
    Say 'Укажите путь: render_top_view.bat 2 "D:\SteamLibrary\steamapps\common\ProjectZomboid"' Yellow
    exit 1
}
if ($Square -lt 1 -or $Square -gt 4) { $Square = 2 }
Say "Игра: $Game" Cyan
Say "Размер клетки: $Square px. Примерно: 1 — ~1 ч и ~100 МБ, 2 — 1-2 ч и ~300 МБ, 4 — 2-4 ч и ~1 ГБ (зависит от процессора)." Cyan
Say "Временно понадобится ~1 ГБ на диске под распакованные текстуры (папка map\texture, после рендера её можно удалить)." Cyan

# 2. Python 3.8+
$py = $null
foreach ($c in @('py -3', 'python', 'python3')) {
    try {
        $v = & cmd /c "$c -c ""import sys;print(sys.version_info[0]*100+sys.version_info[1])"" 2>nul"
        if ($LASTEXITCODE -eq 0 -and [int]$v -ge 308) { $py = $c; break }
    } catch {}
}
if (-not $py) {
    Say 'Python 3 не найден.' Yellow
    $a = Read-Host 'Установить Python 3.12 через winget? (y/n)'
    if ($a -ne 'y') { Say 'Установите Python с python.org (галочка «Add python.exe to PATH») и запустите снова.' Yellow; exit 1 }
    & winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements
    Say 'Python установлен. Закройте это окно и запустите render_top_view.bat ещё раз.' Green
    exit 0
}
Say "Python: $py" Cyan

# 3. pzmap2dzi (фиксированная версия)
New-Item -ItemType Directory -Force -Path $Work | Out-Null
if (-not (Test-Path (Join-Path $SrcDir 'main.py'))) {
    $zip = Join-Path $Work 'pzmap2dzi.zip'
    Say 'Скачиваю pzmap2dzi с GitHub...' Cyan
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/cff29546/pzmap2dzi/archive/$Commit.zip" -OutFile $zip
    Expand-Archive -Force $zip $Work
    Move-Item -Force (Join-Path $Work "pzmap2dzi-$Commit") $SrcDir
    Remove-Item $zip
}

# 4. Окружение Python
$venv = Join-Path $Work 'venv'
$vpy = Join-Path $venv 'Scripts\python.exe'
if (-not (Test-Path $vpy)) { & cmd /c "$py -m venv ""$venv""" ; if ($LASTEXITCODE) { throw 'не удалось создать venv' } }
Say 'Ставлю зависимости (pillow, pyyaml и др.)...' Cyan
& $vpy -m pip install -q --upgrade pip
& $vpy -m pip install -q -r (Join-Path $SrcDir 'requirements.txt')
if ($LASTEXITCODE) { throw 'pip install не удался' }

# 5. Конфиг
$conf = Join-Path $SrcDir 'conf\conf.yaml'
$c = [IO.File]::ReadAllText($conf)
function SetBlock([string]$text, [string]$key, [string]$val) {
    $re = [regex]::new("(?m)^($key\: \|-[^\r\n]*\r?\n)[ \t]+[^\r\n]*")
    return $re.Replace($text, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $m.Groups[1].Value + '    ' + $val }, 1)
}
$c = SetBlock $c 'pz_root' ($Game -replace '\\', '/')
$c = SetBlock $c 'output_root' ($Work -replace '\\', '/')
$c = SetBlock $c 'mod_root' ((Join-Path $Work 'nomods') -replace '\\', '/')
$c = [regex]::Replace($c, '(?m)^(\s*)top_view_square_size:\s*\d+', "`${1}top_view_square_size: $Square")
[IO.File]::WriteAllText((Join-Path $SrcDir 'conf\panel.yaml'), $c, (New-Object System.Text.UTF8Encoding $false))

# 6. Рендер
Push-Location $SrcDir
try {
    $t0 = Get-Date
    Say 'Распаковываю текстуры игры (несколько минут)...' Cyan
    & $vpy main.py -c conf/panel.yaml unpack
    if ($LASTEXITCODE) { throw 'unpack не удался' }
    Say 'Рендер вида сверху. Можно прервать и запустить снова — продолжит с места остановки.' Cyan
    & $vpy main.py -c conf/panel.yaml render base_top
    if ($LASTEXITCODE) { throw 'render не удался' }
    $out = Join-Path $Work 'html\map_data\base_top'
    $mb = [math]::Round(((Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB)
    Say ''
    Say "ГОТОВО за $([math]::Round(((Get-Date) - $t0).TotalMinutes)) мин: $out ($mb МБ)" Green
    Say 'Перезапустите панель — на вкладке «Карта» появится подложка «Вид сверху».' Green
    Say 'Для Linux-сервера скопируйте папку map\html\map_data\base_top в ~/pzserver/PZPanel/map/html/map_data/ (см. PANEL.md).' Green
    Say 'Папку map\texture можно удалить (~500 МБ), она нужна только для рендера.' Gray
} finally { Pop-Location }
