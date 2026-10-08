"""Scalability sweep: one JVM per run, wall time and events from IoTLoopSim output, peak RSS from the OS."""
import subprocess, resource, csv, sys, os, pathlib, time
jar, cfgdir, outdir = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
sizes = [int(x) for x in sys.argv[5:]]; reps = int(sys.argv[4])
outdir.mkdir(parents=True, exist_ok=True)
rows = []
env = dict(os.environ, JAVA_TOOL_OPTIONS="")
for n in sizes:
    for r in range(reps):
        od = outdir / f"n{n}_r{r}"
        before = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
        t0 = time.time()
        p = subprocess.Popen(["java", "-Xmx6g", "-jar", jar, "run", str(cfgdir / f"scal-{n}.yaml"), "--reps", "1",
                              "--threads", "1", "--seed", str(1000 + r), "--out", str(od)] + (["--set", os.environ["IOTLOOPSIM_SET"]] if os.environ.get("IOTLOOPSIM_SET") else []), env=env,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        _, status, ru = os.wait4(p.pid, 0)
        proc_wall = time.time() - t0
        rss_mb = ru.ru_maxrss / 1024.0
        m = {row["metric"]: float(row["mean"]) for row in csv.DictReader(open(od / "summary.csv"))}
        run = next(csv.DictReader(open(od / "runs.csv")))
        sim_wall = float(run["wall_s"])
        rows.append(dict(devices=n, rep=r, seed=1000 + r, events=m["sim.events"], sim_wall_s=sim_wall,
                         process_wall_s=round(proc_wall, 3), events_per_s=m["sim.events"] / sim_wall,
                         peak_rss_mb=round(rss_mb, 1), loops=m["loop.generated"],
                         loop_latency_ms=m["loop.latency_ms.mean"], success=m["loop.success_ratio"],
                         edge_util=m["utilization.edge"]))
        print(rows[-1], flush=True)
with open(outdir / "scalability.csv", "w", newline="") as f:  # selector override, if any, is in IOTLOOPSIM_SET
    w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
