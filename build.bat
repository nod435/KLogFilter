@echo off
rem KLogFilter build
rem   Compiles src, stamps the build date and creates dist\KLogFilter.jar
rem   Version rule: MAJOR.MINOR.yyyyMMddHHmmss
rem     MAJOR / MINOR : src\AppVersion.java (MINOR +1 for every new feature)
rem     date          : time of this build, written to version.properties
setlocal
cd /d "%~dp0"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMddHHmmss"') do set BUILD=%%i
if "%BUILD%"=="" (
  echo Failed to get the build date.
  exit /b 1
)

if exist build rmdir /s /q build
mkdir build\classes
javac -encoding UTF-8 -source 1.8 -target 1.8 -nowarn -d build\classes src\*.java
if errorlevel 1 (
  echo Compile failed.
  exit /b 1
)

> build\classes\version.properties echo build=%BUILD%
for /f %%v in ('java -cp build\classes AppVersion') do set VERSION=%%v

> build\manifest.txt echo Main-Class: LogFilterMain
>> build\manifest.txt echo Implementation-Title: KLogFilter
>> build\manifest.txt echo Implementation-Version: %VERSION%
>> build\manifest.txt echo Implementation-URL: https://github.com/nod435/KLogFilter

if not exist dist mkdir dist
jar cfm dist\KLogFilter.jar build\manifest.txt -C build\classes .
if errorlevel 1 (
  echo Jar creation failed.
  exit /b 1
)
copy /y LogFilterCmd.ini dist\ >nul
copy /y launcher\KLogFilter.bat dist\ >nul

echo.
echo Built KLogFilter %VERSION%  -^>  dist\KLogFilter.jar
endlocal
