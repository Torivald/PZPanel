@echo off
rem PZ Panel launcher.
rem Keep this file ASCII-only: with "chcp 65001" cmd misreads non-ASCII text in .bat files
rem and runs pieces of lines as commands ('exist' is not recognized..., 'tderr.encoding'...).
chcp 65001 >nul
cd /d "%~dp0"
title PZ Panel
if not exist "..\jre64\bin\java.exe" (
  echo ..\jre64\bin\java.exe not found.
  echo Put the PZPanel folder inside the "Project Zomboid Dedicated Server" folder.
  pause
  exit /b 1
)
set PZP_SUPERVISED=1
:run
if exist "pzpanel.jar.new" move /y "pzpanel.jar.new" "pzpanel.jar" >nul
"..\jre64\bin\java.exe" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -Xmx1g -cp "pzpanel.jar;..\java\projectzomboid.jar" pzpanel.Main
rem exit code 75 = "Restart panel" from the web interface
if %errorlevel%==75 goto run
echo.
echo PZ Panel stopped.
pause
