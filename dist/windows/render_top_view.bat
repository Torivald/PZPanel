@echo off
rem Renders the "top view" base layer for the panel map. Keep this file ASCII-only.
chcp 65001 >nul
cd /d "%~dp0"
echo Rendering the top view base layer for the panel map...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0render_top_view.ps1" %*
pause
