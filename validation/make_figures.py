"""Builds the validation figures from results/validation/*.csv (light background, CVD-checked palette)."""
import csv
import math
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

RES = sys.argv[1] if len(sys.argv) > 1 else "../results/validation"
OUT = sys.argv[2] if len(sys.argv) > 2 else "figs"
os.makedirs(OUT, exist_ok=True)
C = {"IoTLoopSim": "#2a78d6", "YAFS": "#eb6834", "iFogSim2": "#1baf7a"}
M = {"IoTLoopSim": "o", "YAFS": "s", "iFogSim2": "^"}
INK, MUTED, GRID = "#1f1f1e", "#6b6a64", "#e6e5df"
plt.rcParams.update({"font.size": 9, "axes.edgecolor": MUTED, "axes.labelcolor": INK, "xtick.color": MUTED,
                     "ytick.color": MUTED, "axes.spines.top": False, "axes.spines.right": False,
                     "figure.facecolor": "white", "axes.facecolor": "white", "lines.linewidth": 2})


def save(fig, name):
    fig.tight_layout()
    for ext in ("pdf", "png"):
        fig.savefig(os.path.join(OUT, f"{name}.{ext}"), dpi=200)
    plt.close(fig)


# ---------------------------------------------------------------- V1: relative errors of the analytical checks
rows = list(csv.DictReader(open(os.path.join(RES, "v1_analytical.csv"))))
rows = [r for r in rows if float(r["expected"]) != 0]
fig, ax = plt.subplots(figsize=(8.2, 0.22 * len(rows) + 1.2))
labels = []
for i, r in enumerate(rows):
    e = float(r["expected"]); m = float(r["simulated_mean"]); h = float(r["ci95_halfwidth"])
    err = (m - e) / abs(e) * 100; ci = h / abs(e) * 100
    ax.errorbar(err, i, xerr=ci, fmt="o", ms=4, color=C["IoTLoopSim"], ecolor=C["IoTLoopSim"], elinewidth=1.2, capsize=2)
    labels.append(f"{r['test']} ({r['parameters']})")
ax.axvline(0, color=INK, lw=1)
ax.axvspan(-1, 1, color=GRID, zorder=0)
ax.set_yticks(range(len(rows)))
ax.set_yticklabels(labels, fontsize=7)
ax.invert_yaxis()
ax.set_xlabel("Relative error vs analytical value (%); bars: 95% CI")
ax.set_xlim(-3, 3)
ax.set_title("V1 - IoTLoopSim vs closed-form models (shaded band: ±1%)", fontsize=9, color=INK, loc="right")
save(fig, "fig_v1_analytical_errors")

# ---------------------------------------------------------------- V2: cross-simulator
summ = list(csv.DictReader(open(os.path.join(RES, "crosssim_summary.csv"))))
P = None


def theory_curve(exp, model_key, xs):
    import json
    p = json.load(open(os.path.join(os.path.dirname(__file__), "cross-sim", "crs_params.json")))
    c = p["experiments"][exp]
    lat = c.get("gw_edge_latency_ms", p["gw_edge_latency_ms"])
    up = c["payload_bytes"] * 8 / c["bandwidth_bps"] * 1000
    down = p["response_bytes"] * 8 / c["bandwidth_bps"] * 1000
    svc = c["mi"] / c["mips"] * 1000
    const = p["sensor_link_latency_ms"] + lat + up + lat + down + p["actuator_link_latency_ms"]
    ys = []
    for r in xs:
        if exp == "X2":
            ys.append(const + svc + (r * svc / (2 * (1 - r)) if model_key == "FIFO" else svc / (1 - r) - svc))
        else:
            busy = up if model_key == "FIFO" else up + lat
            rr = r * busy / up
            ys.append(const + svc + (rr * busy / (2 * (1 - rr)) if rr < 0.97 else float("nan")))
    return ys


for exp, title, xlabel, models in [
    ("X2", "X2 - processing load: IoTLoopSim and YAFS follow FIFO theory, iFogSim2 is near processor-sharing theory",
     "Utilisation of the service, rho", [("FIFO", "M/D/1 FIFO theory", "-"), ("PS", "M/D/1 processor-sharing theory", "--")]),
    ("X3", "X3 - link contention: YAFS holds links busy during propagation",
     "Utilisation of the uplink by transmission, rho", [("FIFO", "M/D/1 link theory (busy = transmit)", "-"),
                                                        ("PROP", "theory with busy = transmit + propagation", "--")])]:
    fig, ax = plt.subplots(figsize=(6.6, 3.6))
    xs = [i / 200 for i in range(1, 196)]
    for key, lab, ls in models:
        ax.plot(xs, theory_curve(exp, key, xs), ls, color=MUTED, lw=1.4, label=lab, zorder=1)
    done = set()
    for r in summ:
        if r["experiment"] != exp or (r["simulator"], r["level"]) in done:
            continue
        done.add((r["simulator"], r["level"]))
        x = float(r["level"]); off = {"IoTLoopSim": -0.006, "YAFS": 0, "iFogSim2": 0.006}[r["simulator"]]
        ax.errorbar(x + off, float(r["mean_loop_ms"]), yerr=float(r["ci95"]), fmt=M[r["simulator"]], ms=7,
                    color=C[r["simulator"]], mec="white", mew=1.0, capsize=2, zorder=3,
                    label=r["simulator"] if r["simulator"] not in [h.get_label() for h in ax.get_legend_handles_labels()[0]] else None)
    levels = sorted({float(r["level"]) for r in summ if r["experiment"] == exp})
    ax.set_xlim(0, max(levels) + 0.1)
    ys = [float(r["mean_loop_ms"]) for r in summ if r["experiment"] == exp]
    ax.set_ylim(min(ys) * 0.9, max(ys) * 1.12)
    ax.grid(axis="y", color=GRID, lw=0.8)
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Mean control-loop latency (ms)")
    ax.set_title(title, fontsize=9, color=INK, loc="left")
    ax.legend(frameon=False, fontsize=8, loc="upper left")
    save(fig, f"fig_v2_{exp.lower()}")

# ---------------------------------------------------------------- V3: migration vs testbed
v3 = [r for r in csv.DictReader(open(os.path.join(RES, "v3_empirical_migration.csv"))) if r["state_MB_definition"] == "115 MiB"]
fig, ax = plt.subplots(figsize=(5.6, 3.6))
for i, r in enumerate(v3):
    meas, ci = float(r["measured_s"]), float(r["measured_ci_s"])
    ax.errorbar(i - 0.12, meas, yerr=ci, fmt="D", ms=7, color=INK, capsize=3, label="Testbed (Puliafito et al. 2020)" if i == 0 else None)
    ax.plot(i + 0.12, float(r["predicted_s"]), "o", ms=8, color=C["IoTLoopSim"], mec="white",
            label="IoTLoopSim, overhead calibrated on the other condition" if i == 0 else None)
    ax.plot(i + 0.12, float(r["simulated_no_overhead_s"]), "o", ms=6, mfc="white", mec=C["IoTLoopSim"], mew=1.5,
            label="IoTLoopSim, transfer only (no checkpoint/restore)" if i == 0 else None)
    ax.annotate(f"{float(r['error_pct']):+.1f}%", (i + 0.18, float(r["predicted_s"])), fontsize=8, color=INK, va="center")
ax.set_xticks(range(len(v3)))
ax.set_xticklabels([f"Condition {r['condition']}" for r in v3])
ax.set_xlim(-0.6, len(v3) - 0.3)
ax.set_ylabel("Cold migration time (s)")
ax.grid(axis="y", color=GRID, lw=0.8)
ax.set_title("V3 - cold container migration vs testbed measurements", fontsize=9, color=INK, loc="left")
ax.set_ylim(0, 100)
ax.legend(frameon=False, fontsize=7.5, loc="upper center", bbox_to_anchor=(0.5, -0.12), ncol=1)
save(fig, "fig_v3_migration")
print("figures in", OUT)


# ---------------------------------------------------------------- V3 E2: LoRaWAN battery lifetime (Casals et al. 2017)
p = os.path.join(RES, "v3_lorawan_energy.csv")
if os.path.exists(p):
    rows = list(csv.DictReader(open(p)))
    maxpay = {"0": "51", "5": "242", "6": "242"}
    col = {"0": C["IoTLoopSim"], "5": C["YAFS"], "6": C["iFogSim2"]}
    fig, ax = plt.subplots(figsize=(5.6, 3.6))
    for dr in ("0", "5", "6"):
        pts = sorted([r for r in rows if r["comparison"] == "model" and r["dr"] == dr and r["payload_B"] == maxpay[dr]],
                     key=lambda r: float(r["tnotif_min"]))
        x = [float(r["tnotif_min"]) for r in pts]
        ax.plot(x, [float(r["reference_years"]) for r in pts], color=col[dr], lw=1.5, label=f"DR{dr}: paper model (line), IoTLoopSim (o)")
        ax.plot(x, [float(r["iotloopsim_years"]) for r in pts], "o", color=col[dr], ms=6, mfc="white", mew=1.5)
        txt = [r for r in rows if r["comparison"] == "paper-text" and r["dr"] == dr]
        ax.plot([float(r["tnotif_min"]) for r in txt], [float(r["reference_years"]) for r in txt], "x", color=INK, ms=7, mew=1.5)
    ax.plot([], [], "x", color=INK, ms=7, mew=1.5, label="value printed in the paper's text")
    ax.set_xscale("log")
    ax.set_xlabel("Notification period T_notif (min)")
    ax.set_ylabel("Battery lifetime, 2400 mAh (years)")
    ax.grid(True, color=GRID, lw=0.8)
    ax.legend(frameon=False, fontsize=8, loc="upper left")
    ax.set_title("LoRaWAN class-A lifetime: IoTLoopSim vs Casals et al. (2017)", fontsize=9, color=INK)
    save(fig, "fig_v3_lorawan_lifetime")
