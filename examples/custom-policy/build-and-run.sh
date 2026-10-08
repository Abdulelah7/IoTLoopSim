#!/usr/bin/env bash
# Builds the example plugin and runs the smart-city scenario with it (no change to IoTLoopSim itself).
set -euo pipefail
cd "$(dirname "$0")"
JAR=../../dist/iotloopsim-all-0.1.0.jar
rm -rf out && mkdir -p out
javac --release 21 -cp "$JAR" -d out src/com/example/iotloopsim/MyPolicies.java
cp -R src/META-INF out/
jar --create --file my-policies.jar -C out .
java -cp "$JAR:my-policies.jar" org.iotloopsim.bridge.Launcher run ../../scenarios/smart-city.yaml --reps 3 \
     --set policies.selector.name=greenest --set policies.selector.maxLoad=3 --out ../../results/smart-city-greenest
