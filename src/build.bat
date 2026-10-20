@echo off
rem Сборка pzpanel.jar из исходников. Нужен JDK 21+ (winget install EclipseAdoptium.Temurin.21.JDK).
rem   build.bat                          - projectzomboid.jar ищется в ..\..\java (src внутри PZPanel в папке сервера)
rem   build.bat "...\java\projectzomboid.jar"
chcp 65001 >nul
cd /d "%~dp0"
set "PZ_JAR=%~1"
if "%PZ_JAR%"=="" set "PZ_JAR=..\..\java\projectzomboid.jar"
if not exist "%PZ_JAR%" (echo Не найден %PZ_JAR% - укажите путь: build.bat "...\java\projectzomboid.jar" & pause & exit /b 1)
if exist build rmdir /s /q build
mkdir build
javac --release 21 -encoding UTF-8 -nowarn -cp "%PZ_JAR%" -d build pzpanel\*.java || (pause & exit /b 1)
xcopy /e /i /q web build\web >nul
if exist pzpanel.jar del pzpanel.jar
jar --create --file pzpanel.jar --main-class pzpanel.Main -C build .
rmdir /s /q build
echo Готово: %cd%\pzpanel.jar
pause
