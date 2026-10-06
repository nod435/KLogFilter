@echo off
rem Run from this folder so the *.ini settings files are read and saved here.
cd /d "%~dp0"
start "" javaw -jar "%~dp0KLogFilter.jar" %*
