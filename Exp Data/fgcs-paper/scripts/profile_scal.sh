#!/usr/bin/env bash
# Thread-stack samples of the 50,000-device scalability run for both selectors: 20 jstack samples, 3 s apart,
# starting 40 s into the run. Writes the raw samples and, per sample, whether the selector is on the worker's stack.
# Usage: profile_scal.sh <jar> <scal-50000.yaml> <out dir>
set -u
export JAVA_TOOL_OPTIONS=
JAR="$1"; SC="$2"; OUT="$3"; mkdir -p "$OUT"
for sel in nearest-least-loaded random; do
  tmp="$(mktemp -d)"
  java -Xmx6g -jar "$JAR" run "$SC" --set policies.selector=$sel --reps 1 --threads 1 --out "$tmp/run" > /dev/null 2>&1 &
  PID=$!; sleep 40
  raw="$OUT/jstack-50000-$sel.txt"; : > "$raw"
  for i in $(seq 1 20); do echo "=== sample $i $(date +%T)" >> "$raw"; jstack $PID >> "$raw" 2>/dev/null; sleep 3; done
  kill $PID 2>/dev/null; wait $PID 2>/dev/null; rm -rf "$tmp"
  python3 - "$raw" "$OUT/summary-50000-$sel.txt" <<'PY'
import sys, re, collections
txt = open(sys.argv[1]).read().split("=== sample ")[1:]
top = collections.Counter(); onstack = collections.Counter(); n = 0
for smp in txt:
    # the simulation worker is the thread whose stack contains the event loop
    for th in smp.split("\n\n"):
        if "org.iotloopsim.core.Simulation.run" in th:
            frames = re.findall(r"^\s+at ([\w.$]+)\(", th, re.M)
            if not frames: continue
            n += 1; top[frames[0]] += 1
            for key, pat in [("selector (InstanceSelector.select)", r"policy\.BuiltinPolicies\$\w+\.select$"),
                             ("event list (PriorityQueue)", r"PriorityQueue"), ("energy ledger", r"EnergyLedger"), ("metrics", r"Metrics")]:
                if any(re.search(pat, f) for f in frames): onstack[key] += 1
            break
with open(sys.argv[2], "w") as f:
    f.write(f"samples with a simulation worker: {n}\n\non the worker's stack:\n")
    for k, v in onstack.most_common(): f.write(f"  {v:3d}  {k}\n")
    f.write("\ntop frame:\n")
    for k, v in top.most_common(): f.write(f"  {v:3d}  {k}\n")
print(open(sys.argv[2]).read())
PY
done
