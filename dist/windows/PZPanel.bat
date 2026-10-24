@echo off
chcp 65001 >nul
cd /d "%~dp0"
title PZ Panel
if not exist "..\jre64\bin\java.exe" (
  echo Не найдена ..\jre64\bin\java.exe - положите папку PZPanel внутрь папки Project Zomboid Dedicated Server
  pause
  exit /b 1
)
set PZP_SUPERVISED=1
:run
if exist "pzpanel.jar.new" move /y "pzpanel.jar.new" "pzpanel.jar" >nul
"..\jre64\bin\java.exe" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -Xmx1g -cp "pzpanel.jar;..\java\projectzomboid.jar" pzpanel.Main
rem код 75 - перезапуск панели из веб-интерфейса
if %errorlevel%==75 goto run
echo.
echo Панель остановлена.
pause
