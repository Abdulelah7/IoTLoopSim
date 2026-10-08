#!/usr/bin/env bash
# CS3: security x redundancy on the smart-city scenario (2x2), 10 replications each.
set -u
export JAVA_TOOL_OPTIONS=
HERE="$(cd "$(dirname "$0")/.." && pwd)"; JAR="$1"; R="$HERE/results/cs3"; C="$HERE/configs"; mkdir -p "$R"
run() { out="$1"; shift; [ -f "$out/summary.csv" ] && return; java -Xmx3g -jar "$JAR" run "$@" --reps 10 --threads 1 --out "$out" > "$out.log" 2>&1; }
run "$R/ids-backup"     "$C/smart-city.yaml"
run "$R/noids-backup"   "$C/smart-city-noids.yaml"
run "$R/ids-nobackup"   "$C/smart-city-nobackup.yaml"
run "$R/noids-nobackup" "$C/smart-city-noids-nobackup.yaml"
echo CS3DONE
