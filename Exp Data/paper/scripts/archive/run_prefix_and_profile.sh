#!/usr/bin/env bash
# (1) Pre-fix build (before the session-rebalancing fix) on CS1 fog-first and cloud-only, to quantify the defect.
# (2) Thread-stack sampling of the 50,000-device scalability runs (both selectors), 20 samples each, 3 s apart.
set -u
export JAVA_TOOL_OPTIONS=
HERE="$(cd "$(dirname "$0")/.." && pwd)"; OLD="$1"; NEW="$2"; R="$HERE/results"; C="$HERE/configs"
for p in fog-first cloud-only; do
  java -Xmx3g -jar "$OLD" run "$C/smart-city.yaml" --set policies.placement=$p --reps 10 --threads 2 --out "$R/prefix-check/$p" > "$R/prefix-check/$p.log" 2>&1
done
for sel in nearest-least-loaded random; do
  java -Xmx6g -jar "$NEW" run "$C/scal/scal-50000.yaml" --set policies.selector=$sel --reps 1 --threads 1 --out /tmp/prof-$sel > /dev/null 2>&1 &
  PID=$!; sleep 40
  out="$R/profiling/jstack-50000-$sel.txt"; : > "$out"
  for i in $(seq 1 20); do echo "=== sample $i $(date +%T)" >> "$out"; jstack $PID >> "$out" 2>/dev/null; sleep 3; done
  kill $PID
  # top iotloopsim/java frame of the worker thread in each sample
  awk '/^=== sample/{s=1} /"pool-/{w=1;n=0;next} w&&/at /{if(n==0){print $2}; n++} /^$/{w=0}' "$out" | sort | uniq -c | sort -rn > "$R/profiling/top-frames-50000-$sel.txt"
done
echo DONE
