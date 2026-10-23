#!/bin/sh
# KLogFilter build for Linux / macOS (same result as build.bat)
#   Compiles src, stamps the build date and creates dist/KLogFilter.jar
#   Version rule: MAJOR.MINOR.yyyyMMddHHmmss
#     MAJOR / MINOR : src/AppVersion.java (MINOR +1 for every new feature)
#     date          : time of this build, written to version.properties
set -e
cd "$(dirname "$0")"

BUILD=$(date +%Y%m%d%H%M%S)

rm -rf build
mkdir -p build/classes
javac -encoding UTF-8 -source 1.8 -target 1.8 -nowarn -d build/classes src/*.java

echo "build=$BUILD" > build/classes/version.properties
VERSION=$(java -cp build/classes AppVersion)

{
  echo "Main-Class: LogFilterMain"
  echo "Implementation-Title: KLogFilter"
  echo "Implementation-Version: $VERSION"
  echo "Implementation-URL: https://github.com/nod435/KLogFilter"
} > build/manifest.txt

mkdir -p dist
jar cfm dist/KLogFilter.jar build/manifest.txt -C build/classes .
cp -f LogFilterCmd.ini dist/
cp -f CHANGELOG.txt dist/
cp -f launcher/KLogFilter.bat dist/
cp -f launcher/KLogFilter.sh dist/
chmod +x dist/KLogFilter.sh

echo
echo "Built KLogFilter $VERSION  ->  dist/KLogFilter.jar"
