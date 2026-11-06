#!/bin/sh
# Builds build/jpaibridge-<version>.jar. Requires a Java 17 JDK (javac and jar on PATH, or JAVA_HOME set).
# The compile-only libraries are downloaded from Maven repositories on the first run.
set -e
cd "$(dirname "$0")"
if [ -n "$JAVA_HOME" ]; then BIN="$JAVA_HOME/bin/"; else BIN=""; fi
VERSION=$(sed -n 's/^version="\(.*\)"/\1/p' src/main/resources/META-INF/mods.toml)

mkdir -p libs
fetch() { [ -f "libs/$1" ] || curl -sSfL -o "libs/$1" "$2"; }
fetch gson-2.10.1.jar https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
fetch slf4j-api.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7.jar
for a in fmlcore fmlloader javafmllanguage; do
  fetch "$a.jar" "https://maven.minecraftforge.net/net/minecraftforge/$a/1.20.1-47.4.0/$a-1.20.1-47.4.0.jar"
done
CP=libs/gson-2.10.1.jar:libs/fmlloader.jar:libs/javafmllanguage.jar:libs/fmlcore.jar:libs/slf4j-api.jar

rm -rf build/classes && mkdir -p build/classes
"${BIN}javac" --release 17 -encoding UTF-8 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -R src/main/resources/. build/classes/
cp LICENSE.txt build/classes/LICENSE.txt
printf 'Manifest-Version: 1.0\nImplementation-Title: JP AI Bridge\nImplementation-Version: %s\n' "$VERSION" > build/MANIFEST.MF
"${BIN}jar" --create --file "build/jpaibridge-$VERSION.jar" --manifest build/MANIFEST.MF -C build/classes .
echo "built build/jpaibridge-$VERSION.jar"

# Self test (no Minecraft needed): ./build.sh test
if [ "$1" = "test" ]; then
  rm -rf build/selftest && mkdir -p build/selftest
  "${BIN}javac" -encoding UTF-8 -cp libs/gson-2.10.1.jar -d build/selftest $(find src/main/java/com/aperogames/jpaibridge/core src/test/java -name '*.java')
  "${BIN}java" -cp build/selftest:libs/gson-2.10.1.jar com.aperogames.jpaibridge.core.BridgeSelfTest
fi
