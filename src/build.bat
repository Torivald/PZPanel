@echo off
rem Builds pzpanel.jar from sources. Needs JDK 21+ (winget install EclipseAdoptium.Temurin.21.JDK).
rem   build.bat                          - projectzomboid.jar is looked up in ..\..\java (src inside PZPanel in the server folder)
rem   build.bat "...\java\projectzomboid.jar"
rem Keep this file ASCII-only: cmd misreads non-ASCII text in .bat files.
chcp 65001 >nul
cd /d "%~dp0"
set "PZ_JAR=%~1"
if "%PZ_JAR%"=="" set "PZ_JAR=..\..\java\projectzomboid.jar"
if not exist "%PZ_JAR%" (echo %PZ_JAR% not found - pass the path: build.bat "...\java\projectzomboid.jar" & pause & exit /b 1)
if exist build rmdir /s /q build
mkdir build
javac --release 21 -encoding UTF-8 -nowarn -cp "%PZ_JAR%" -d build pzpanel\*.java || (pause & exit /b 1)
xcopy /e /i /q web build\web >nul
if exist pzpanel.jar del pzpanel.jar
jar --create --file pzpanel.jar --main-class pzpanel.Main -C build .
rmdir /s /q build
echo Done: %cd%\pzpanel.jar
pause
