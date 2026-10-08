#!/usr/bin/env bash
# Reproduces every result used in the FGCS paper from one build of IoTLoopSim.
# Usage (from the repository root, after ./build.sh):
#   bash "Exp Data/fgcs-paper/scripts/reproduce_all.sh" dist/iotloopsim-all-0.1.0.jar [all|validation|cases|scal|figs]
# Run from inside the git checkout so that every provenance.json records the commit.
set -u
export JAVA_TOOL_OPTIONS=
JAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; WHAT="${2:-all}"
ROOT="$(pwd)"; HERE="$(cd "$(dirname "$0")/.." && pwd)"; C="$HERE/configs"; R="$HERE/results"; S="$HERE/scripts"
REPS=30
run() { out="$1"; shift; mkdir -p "$(dirname "$out")"; java -Xmx3g -jar "$JAR" run "$@" --reps $REPS --threads 2 --out "$out" > "$out.log" 2>&1 || echo "FAILED $out"; }

if [ "$WHAT" = all ] || [ "$WHAT" = validation ]; then
  echo "== validation (V1 analytical and lifecycle, V3 LoRaWAN energy)"
  V="$ROOT/results/validation"
  java -cp "$JAR" org.iotloopsim.validation.AnalyticalValidation "$V" 10 > "$V/analytical.log" 2>&1
  java -cp "$JAR" org.iotloopsim.validation.LifecycleValidation "$V" 10 > "$V/lifecycle.log" 2>&1
  java -cp "$JAR" org.iotloopsim.validation.LoRaWANEnergyValidation "$V" > "$V/lorawan.log" 2>&1
  java -cp "$JAR" org.iotloopsim.validation.EmpiricalValidation "$V" > "$V/empirical.log" 2>&1   # repository only, not in the paper
  python3 "$ROOT/validation/equivalence.py" "$V/v1_analytical.csv" "$V/v1_analytical_equivalence.csv" 2>/dev/null || python3 "$ROOT/validation/equivalence.py"
  # V2 (docking with YAFS and iFogSim2) needs the two simulators; see validation/cross-sim. Its results are archived.
  mkdir -p "$HERE/../validation"; cp "$V"/*.csv "$HERE/../validation/"
  echo "== bundled example results"
  for sc in quickstart smart-city lorawan-ota lorawan-battery; do rm -rf "$ROOT/results/$sc"; java -jar "$JAR" run "$ROOT/scenarios/$sc.yaml" --reps 10 --out "$ROOT/results/$sc" > /dev/null 2>&1; done
fi

if [ "$WHAT" = all ] || [ "$WHAT" = cases ]; then
  echo "== CS1: OTA rollout on the LoRaWAN farm"
  O="$R/cs1-ota"; AAO="lifecycle.updates.0.rollout={name: all-at-once}"
  BAT="lifecycle.updates.0.rollout={name: batch, batchSize: 40, interval: 30min, abortAbove: 0.3}"
  run "$O/latent-all-at-once" "$C/lorawan-ota.yaml" --set "$AAO"
  run "$O/latent-batch"       "$C/lorawan-ota.yaml" --set "$BAT"
  run "$O/latent-canary"      "$C/lorawan-ota.yaml"
  run "$O/latent-canary-norollback" "$C/lorawan-ota.yaml" --set lifecycle.updates.0.rollback=false
  run "$O/bad-all-at-once" "$C/lorawan-ota.yaml" --set lifecycle.updates.0.failProb=0.4 --set "$AAO"
  run "$O/bad-batch"       "$C/lorawan-ota.yaml" --set lifecycle.updates.0.failProb=0.4 --set "$BAT"
  run "$O/bad-canary"      "$C/lorawan-ota.yaml" --set lifecycle.updates.0.failProb=0.4
  # sensitivity: gateway downlink duty cycle (1 % sub-band, unconstrained) and crash probability of the bug
  for gd in 0.01 1.0; do
    run "$O/sens-gwduty-$gd-all-at-once" "$C/lorawan-ota.yaml" --set "$AAO" --set lifecycle.updates.0.gatewayDutyCycle=$gd
    run "$O/sens-gwduty-$gd-batch"       "$C/lorawan-ota.yaml" --set "$BAT" --set lifecycle.updates.0.gatewayDutyCycle=$gd
  done
  for p in 0.05 0.30; do
    run "$O/sens-crash-$p-all-at-once" "$C/lorawan-ota.yaml" --set "$AAO" --set faults.correlated.0.p=$p
    run "$O/sens-crash-$p-canary"      "$C/lorawan-ota.yaml" --set faults.correlated.0.p=$p
  done
  for rb in 5min 60min; do run "$O/sens-rollback-$rb-canary" "$C/lorawan-ota.yaml" --set lifecycle.updates.0.rollbackAfter=$rb; done
  # scenario variant: the same bug, but not correlated with the firmware version (strikes every soil sensor)
  UNC="faults.correlated.0={name: watchdog-bug, target: device, group: soil, every: exp(7200), p: 0.15, duration: const(600)}"
  run "$O/var-uncorrelated-all-at-once" "$C/lorawan-ota.yaml" --set "$AAO" --set "$UNC"
  run "$O/var-uncorrelated-canary"      "$C/lorawan-ota.yaml" --set "$UNC"

  echo "== CS2: IDS x backup links on the smart city"
  O="$R/cs2-security"
  run "$O/ids-backup"     "$C/smart-city.yaml"
  run "$O/noids-backup"   "$C/smart-city-noids.yaml"
  run "$O/ids-nobackup"   "$C/smart-city-nobackup.yaml"
  run "$O/noids-nobackup" "$C/smart-city-noids-nobackup.yaml"
  # sensitivity: IDS detection probability and mean detection time
  for dp in 0.5 1.0; do run "$O/sens-idsp-$dp" "$C/smart-city.yaml" --set security.ids.detectProbability=$dp; done
  run "$O/sens-idst-300" "$C/smart-city.yaml" --set "security.ids.detectionTime=exp(300)"
  # scenario variants: attacks and faults simulated in separate runs
  run "$O/var-attacks-only-ids"   "$C/smart-city.yaml" --set "faults={}"
  run "$O/var-attacks-only-noids" "$C/smart-city-noids.yaml" --set "faults={}"
  run "$O/var-faults-only-ids"    "$C/smart-city.yaml" --set "attacks=[]"
  run "$O/var-faults-only-noids"  "$C/smart-city-noids.yaml" --set "attacks=[]"
fi

if [ "$WHAT" = all ] || [ "$WHAT" = scal ]; then
  echo "== scalability (one JVM per run)"
  python3 "$S/run_scal.py" "$JAR" "$C/scal" "$R/scalability" 3 100 500 1000 2000 5000 10000 20000 50000 > "$R/scalability.log" 2>&1
  IOTLOOPSIM_SET="policies.selector=random" python3 "$S/run_scal.py" "$JAR" "$C/scal" "$R/scalability-random" 3 1000 5000 10000 20000 50000 > "$R/scalability-random.log" 2>&1
  echo "== thread samples of the 50,000-device run (20 samples, 3 s apart, from 40 s)"
  bash "$S/profile_scal.sh" "$JAR" "$C/scal/scal-50000.yaml" "$R/profiling"
fi

if [ "$WHAT" = all ] || [ "$WHAT" = figs ]; then
  echo "== figures and tables"
  python3 "$S/fig_validation.py" "$ROOT/results/validation" "$HERE/figures"
  python3 "$S/fig_cases.py" "$R" "$HERE/figures"
  python3 "$S/fig_scalability.py" "$R" "$HERE/figures"
  python3 "$S/make_comparison_table.py" "$HERE/../fullstack_evaluation.csv" "$R/comparison.tex"
  python3 "$S/make_supplement_tables.py" "$ROOT/results/validation" "$HERE/figures" "$HERE/figures/supplement"
fi
echo DONE
