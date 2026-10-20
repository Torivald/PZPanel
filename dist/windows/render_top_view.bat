@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Рендер подложки «вид сверху» для карты панели.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0render_top_view.ps1" %*
pause
