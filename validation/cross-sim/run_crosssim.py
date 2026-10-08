"""Runs the Common Reference System experiments in IoTLoopSim, YAFS and iFogSim2 and compares them with queueing theory.

usage: python3 run_crosssim.py <iotloopsim-all.jar> <ifogsim-classpath> <yafs-src> <out-dir> [reps]
Writes crosssim_runs.csv (one row per run) and crosssim_summary.csv (means, 95% CI, theory).
"""
import csv
import json
import math
import os
import re
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
P = json.load(open(os.path.join(HERE, "crs_params.json")))
T975 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228]


def ci95(v):
    return 0.0 if len(v) < 2 else T975[len(v) - 2] * statistics.stdev(v) / math.sqrt(len(v))


def theory(exp, level):
    c = P["experiments"][exp]
    lat = c.get("gw_edge_latency_ms", P["gw_edge_latency_ms"])
    bw = c["bandwidth_bps"]
    up = c["payload_bytes"] * 8 / bw * 1000
    down = P["response_bytes"] * 8 / bw * 1000
    svc = c["mi"] / c["mips"] * 1000
    const = P["sensor_link_latency_ms"] + lat + up + lat + down + P["actuator_link_latency_ms"]
    if exp == "X1":
        return {"FIFO": const + svc, "PS": const + svc}
    rho = level
    if exp == "X2":   # queueing at the service (deterministic service time)
        return {"FIFO (M/D/1)": const + svc + rho * svc / (2 * (1 - rho)),
                "PS (M/D/1-PS)": const + svc / (1 - rho)}
    # X3: queueing at the uplink (deterministic serialisation time)
    fifo_link = rho * up / (2 * (1 - rho))
    busy = up + lat                        # YAFS keeps the link busy for transmit + propagation
    rho_y = rho * busy / up
    yafs = rho_y * busy / (2 * (1 - rho_y)) if rho_y < 1 else float("inf")
    return {"FIFO link, busy=transmit (M/D/1)": const + svc + fifo_link,
            "FIFO link, busy=transmit+propagation": const + svc + yafs}


def run_iotloopsim(jar, exp, reps):
    out = subprocess.run(["java", "-cp", jar, "org.iotloopsim.validation.CrossSimReference", os.path.join(HERE, "crs_params.json"), exp, str(reps)],
                         capture_output=True, text=True, env={**os.environ, "JAVA_TOOL_OPTIONS": ""}).stdout
    rows = []
    for line in out.splitlines():
        if line.startswith("{"):
            d = json.loads(line)
            rows.append(("IoTLoopSim", exp, None if d["rho"] == "null" else float(d["rho"]), int(d["rep"]), d["loop_ms"], d["n"]))
    return rows


def run_yafs(yafs_src, exp, reps):
    out = subprocess.run([sys.executable, os.path.join(HERE, "yafs_crs.py"), os.path.join(HERE, "crs_params.json"), exp, str(reps)],
                         capture_output=True, text=True, env={**os.environ, "YAFS_SRC": yafs_src}).stdout
    rows = []
    for line in out.splitlines():
        if line.startswith("{"):
            d = json.loads(line)
            rows.append(("YAFS", exp, d["rho"], d["rep"], d["loop_ms"], d["n"]))
    return rows


def run_ifogsim(cp, exp, reps):
    c = P["experiments"][exp]
    lat = c.get("gw_edge_latency_ms", P["gw_edge_latency_ms"])
    levels = [None] if exp == "X1" else c.get("rho", c.get("rho_link"))
    rows = []
    for level in levels:
        for r in range(1 if level is None else reps):
            if exp == "X1":
                interval, poisson = c["interval_ms"], "0"
            elif exp == "X2":
                lam = level / (c["mi"] / c["mips"] * 1000)
                interval, poisson = c["sensors"] / lam, "1"
            else:
                lam = level / (c["payload_bytes"] * 8 / c["bandwidth_bps"] * 1000)
                interval, poisson = c["sensors"] / lam, "1"
            args = ["java", "-cp", cp, "IFogSimCRS", exp, str(c["sensors"]), str(c["payload_bytes"]), str(P["response_bytes"]),
                    str(c["bandwidth_bps"]), str(lat), str(P["sensor_link_latency_ms"]), str(c["mi"]), str(c["mips"]),
                    str(interval), poisson, str(int(c["horizon_s"] * 1000)), str(1000 + r)]
            out = subprocess.run(args, capture_output=True, text=True, env={**os.environ, "JAVA_TOOL_OPTIONS": ""}).stdout
            m = re.search(r"\[S, Service, ACT\] ---> ([0-9.Ee+-]+)", out)
            rows.append(("iFogSim2", exp, level, r, float(m.group(1)) if m else float("nan"), None))
    return rows


def main():
    jar, ifog_cp, yafs_src, out_dir = sys.argv[1:5]
    reps = int(sys.argv[5]) if len(sys.argv) > 5 else 5
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    for exp in ["X1", "X2", "X3"]:
        for fn, arg in [(run_iotloopsim, jar), (run_yafs, yafs_src), (run_ifogsim, ifog_cp)]:
            r = fn(arg, exp, reps)
            rows.extend(r)
            print(f"{exp} {r[0][0] if r else fn.__name__}: {len(r)} runs", flush=True)
    with open(os.path.join(out_dir, "crosssim_runs.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["simulator", "experiment", "level", "rep", "mean_loop_ms", "loops"])
        w.writerows(rows)
    groups = {}
    for sim, exp, level, rep, v, n in rows:
        groups.setdefault((exp, level, sim), []).append(v)
    with open(os.path.join(out_dir, "crosssim_summary.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["experiment", "level", "simulator", "mean_loop_ms", "ci95", "n_runs", "theory_model", "theory_ms", "rel_diff_pct"])
        for (exp, level, sim), v in sorted(groups.items(), key=lambda x: (x[0][0], x[0][1] or 0, x[0][2])):
            th = theory(exp, level)
            for name, val in th.items():
                w.writerow([exp, level, sim, round(statistics.mean(v), 4), round(ci95(v), 4), len(v), name, round(val, 4),
                            round((statistics.mean(v) - val) / val * 100, 3) if math.isfinite(val) else ""])
    print("written", out_dir)


if __name__ == "__main__":
    main()
