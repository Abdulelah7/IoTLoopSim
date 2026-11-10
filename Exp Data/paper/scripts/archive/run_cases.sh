#!/usr/bin/env bash
# Case studies for the paper. 10 replications each, common seeds (base seed from the scenario) for all variants.
set -u
export JAVA_TOOL_OPTIONS=
HERE="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$1"; R="$HERE/results"; C="$HERE/configs"; REPS=10
run() { out="$1"; shift; [ -f "$out/summary.csv" ] && return; java -Xmx3g -jar "$JAR" run "$@" --reps $REPS --threads 2 --out "$out" > "$out.log" 2>&1; }
mkdir -p "$R/cs1" "$R/cs2" "$R/cs3"
# CS1: placement policy on the smart-city scenario
for p in edge-first fog-first cloud-only latency-aware; do run "$R/cs1/$p" "$C/smart-city.yaml" --set policies.placement=$p; done
# CS2: OTA rollout policy on the LoRaWAN farm
run "$R/cs2/all-at-once" "$C/lorawan-ota.yaml" --set "lifecycle.updates.0.rollout={name: all-at-once}"
run "$R/cs2/batch"       "$C/lorawan-ota.yaml" --set "lifecycle.updates.0.rollout={name: batch, batchSize: 40, interval: 30min, abortAbove: 0.3}"
run "$R/cs2/canary"      "$C/lorawan-ota.yaml"
run "$R/cs2/canary-norollback" "$C/lorawan-ota.yaml" --set lifecycle.updates.0.rollback=false
# CS3 moved to run_cs3.sh (security x redundancy)
# Fidelity L0 vs L1 on smart-city
java -Xmx3g -jar "$JAR" compare-fidelity "$C/smart-city.yaml" --reps $REPS > "$R/fidelity_smart-city.txt" 2>&1
echo ALLDONE
