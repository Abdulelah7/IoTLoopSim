#!/usr/bin/env bash
# Builds IoTLoopSim with only a JDK (21+): compiles all modules, runs the unit tests if JUnit 4 is available,
# and writes JARs to dist/. With internet access you can use Maven instead:  mvn package
set -euo pipefail
cd "$(dirname "$0")"
VERSION=0.1.0
OUT=build
DIST=dist
MODULES="iotloopsim-core iotloopsim-models iotloopsim-experiment iotloopsim-bridge iotloopsim-validation"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/test-classes" "$DIST"

echo "[1/4] compiling"
CP="$OUT/classes"
for m in $MODULES; do
  mkdir -p "$OUT/$m"
  javac --release 21 -encoding UTF-8 -d "$OUT/$m" -cp "$CP" $(find "$m/src/main/java" -name '*.java')
  if [ -d "$m/src/main/resources" ]; then cp -R "$m/src/main/resources/." "$OUT/$m/"; fi
  cp -R "$OUT/$m/." "$OUT/classes/"
done

echo "[2/4] tests"
JUNIT="${JUNIT_JAR:-}"
HAMCREST="${HAMCREST_JAR:-}"
[ -z "$JUNIT" ] && JUNIT=$(ls lib/junit-4*.jar 2>/dev/null | head -1 || true)
[ -z "$HAMCREST" ] && HAMCREST=$(ls lib/hamcrest-core-*.jar 2>/dev/null | head -1 || true)
if { [ -z "$JUNIT" ] || [ -z "$HAMCREST" ]; } && [ "${IOTLOOPSIM_OFFLINE:-0}" != "1" ] && command -v curl >/dev/null; then
  echo "  fetching JUnit 4.13.2 and Hamcrest 1.3 from Maven Central into lib/ (set IOTLOOPSIM_OFFLINE=1 to skip)"
  mkdir -p lib
  M=https://repo1.maven.org/maven2
  curl -fsSL -o lib/junit-4.13.2.jar $M/junit/junit/4.13.2/junit-4.13.2.jar 2>/dev/null \
    && curl -fsSL -o lib/hamcrest-core-1.3.jar $M/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar 2>/dev/null \
    && JUNIT=lib/junit-4.13.2.jar && HAMCREST=lib/hamcrest-core-1.3.jar \
    || { rm -f lib/junit-4.13.2.jar.part; echo "  download failed"; }
fi
if [ -n "$JUNIT" ] && [ -n "$HAMCREST" ]; then
  TESTS=$(find iotloopsim-*/src/test/java -name '*Test.java')
  javac --release 21 -encoding UTF-8 -d "$OUT/test-classes" -cp "$OUT/classes:$JUNIT:$HAMCREST" $TESTS
  CLASSES=$(cd "$OUT/test-classes" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | tr '\n' ' ')
  java -cp "$OUT/classes:$OUT/test-classes:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore $CLASSES
else
  echo "  WARNING: JUnit 4 not found - TESTS SKIPPED (put junit-4.13.2.jar and hamcrest-core-1.3.jar in lib/ or set JUNIT_JAR/HAMCREST_JAR)"
fi

echo "[3/4] packaging"
for m in $MODULES; do jar --create --file "$DIST/$m-$VERSION.jar" -C "$OUT/$m" .; done
mkdir -p "$OUT/all"
cp -R "$OUT/classes/." "$OUT/all/"
jar --create --file "$DIST/iotloopsim-all-$VERSION.jar" --main-class org.iotloopsim.bridge.Launcher -C "$OUT/all" .

echo "[4/4] done: $DIST/iotloopsim-all-$VERSION.jar"
echo "  try:  java -jar $DIST/iotloopsim-all-$VERSION.jar run scenarios/quickstart.yaml --reps 5"
