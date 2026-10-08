#!/usr/bin/env bash
# CS2b: a bad firmware build (40% install failures) under the three rollout policies, 10 replications each.
set -u
export JAVA_TOOL_OPTIONS=
HERE="$(cd "$(dirname "$0")/.." && pwd)"; JAR="$1"; R="$HERE/results/cs2b"; C="$HERE/configs"; mkdir -p "$R"
run() { out="$1"; shift; java -Xmx3g -jar "$JAR" run "$@" --set lifecycle.updates.0.failProb=0.4 --reps 10 --threads 1 --out "$out" > "$out.log" 2>&1; }
run "$R/all-at-once" "$C/lorawan-ota.yaml" --set "lifecycle.updates.0.rollout={name: all-at-once}"
run "$R/batch"       "$C/lorawan-ota.yaml" --set "lifecycle.updates.0.rollout={name: batch, batchSize: 40, interval: 30min, abortAbove: 0.3}"
run "$R/canary"      "$C/lorawan-ota.yaml"
echo CS2BDONE
