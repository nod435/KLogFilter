#!/bin/sh
# KLogFilter launcher for Linux / macOS
# Runs from this folder so the *.ini settings files are read and saved here.
# Usage: ./KLogFilter.sh [logfile]
cd "$(dirname "$0")" || exit 1
exec java -jar "./KLogFilter.jar" "$@"
