"""Validation figures (V1-V3) from IoTLoopSim's results/validation CSVs. No titles; units on axes."""
import sys, csv, pathlib
import numpy as np
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from fig_style import *
setup()
src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
rd = lambda f: list(csv.DictReader(open(src / f)))

# V1: relative error with CI, grouped (deterministic radio rows and conservation rows omitted)
rows = [r for r in rd("v1_analytical.csv") if r["group"] not in ("radio",) and float(r["expected"]) != 0]
lab = {"M/M/1 mean waiting time Wq": "M/M/1 $W_q$", "M/M/4 mean waiting time (Erlang C)": "M/M/4 $W_q$",
       "M/D/1 mean waiting time (P-K)": "M/D/1 $W_q$", "L1 link M/D/1 queueing delay": "L1 link M/D/1 delay",
       "Utilisation law U = lambda*S": "Utilisation law", "Node energy, linear power model": "Node energy",
       "coap-non end-to-end delivery ratio": "CoAP-NON delivery", "coap-con end-to-end delivery ratio": "CoAP-CON delivery",
       "coap-con transmissions per message": "CoAP-CON tx/msg", "Steady-state availability MTBF/(MTBF+MTTR)": "Availability",
       "Battery lifetime, baseline only": "Battery lifetime (baseline)", "Battery lifetime, LoRa sensing every 60 s": "Battery lifetime (LoRa)"}
names = [f'{lab.get(r["test"], r["test"])} ({r["parameters"].replace("rho","ρ").replace("p_loss","p").replace(",",", ")})' for r in rows]
err = np.array([float(r["rel_error_pct"]) for r in rows])
ci = np.array([100 * float(r["ci95_halfwidth"]) / float(r["expected"]) for r in rows])
fig, ax = plt.subplots(figsize=(6.2, 4.6))
y = np.arange(len(rows))[::-1]
ax.axvspan(-1, 1, color="#f1f0ec", zorder=0)
ax.axvline(0, color=INK2, lw=0.8)
ax.errorbar(err, y, xerr=ci, fmt="o", color=C[0], ecolor=C[0], elinewidth=1, capsize=2, ms=3.5)
ax.set_yticks(y); ax.set_yticklabels(names, fontsize=6.6); ax.grid(axis="y", visible=False)
ax.set_xlabel("Relative error versus closed-form value (%), bars: 95% CI"); ax.set_xlim(-4, 4)
fig.savefig(out / "fig_v1.pdf"); plt.close(fig)

# V2: X2 and X3
cs = rd("crosssim_summary.csv")
sims = ["IoTLoopSim", "YAFS", "iFogSim2"]; DISPLAY = {"IoTLoopSim": "IoTLoopSim"}
fig, axs = plt.subplots(1, 2, figsize=(6.6, 2.5))
for ax, exp, tA, tB, la, lb in [(axs[0], "X2", "FIFO", "PS", "M/D/1 FIFO theory", "M/D/1 processor-sharing theory"),
                                 (axs[1], "X3", None, None, "busy for transmission", "busy for transm. + propagation")]:
    pts = [r for r in cs if r["experiment"] == exp]
    lv = sorted({float(r["level"]) for r in pts})
    th = {}
    for r in pts:
        th.setdefault(r["theory_model"], {})[float(r["level"])] = float(r["theory_ms"])
    models = sorted(th)
    for k, (m, ls) in enumerate(zip(models, ["-", "--"])):
        ax.plot(lv, [th[m][l] for l in lv], ls, color=INK2, lw=1, label=[la, lb][k] if exp == "X2" else ["Theory A: " + la, "Theory B: " + lb][k])
    for i, s in enumerate(sims):
        seen = {}
        for r in pts:
            if r["simulator"] == s: seen[float(r["level"])] = (float(r["mean_loop_ms"]), float(r["ci95"]))
        xs = sorted(seen)
        ax.errorbar([x + (i - 1) * 0.006 for x in xs], [seen[x][0] for x in xs], yerr=[seen[x][1] for x in xs],
                    fmt=MARK[i], color=C[i], ms=4.5, capsize=2, lw=0, elinewidth=1, label=DISPLAY.get(s, s), mec="white", mew=0.5)
    ax.set_xlabel("Utilisation of the " + ("edge service" if exp == "X2" else "uplink") + ", ρ")
    ax.set_ylabel("Mean loop latency (ms)")
    ax.set_title("(a) Processing load (X2)" if exp == "X2" else "(b) Link contention (X3)", loc="left", fontsize=8)
    if exp == "X3":
        ax.set_yscale("log"); ax.set_yticks([50, 75, 100, 150, 200]); ax.yaxis.set_major_formatter(mpl.ticker.ScalarFormatter())
        ax.yaxis.set_minor_formatter(mpl.ticker.NullFormatter()); ax.legend(loc="upper left", fontsize=6.3)
    else:
        ax.legend(loc="upper left", fontsize=6.3)
fig.tight_layout(); fig.savefig(out / "fig_v2.pdf"); plt.close(fig)

# V3: LoRaWAN lifetime against the measurement-based model (single column)
lw = [r for r in rd("v3_lorawan_energy.csv") if r["comparison"] == "model"]
fig, ax = plt.subplots(figsize=(3.4, 2.4))
for i, dr in enumerate([0, 2, 5]):
    rr = sorted([r for r in lw if int(r["dr"]) == dr and int(r["payload_B"]) > 1], key=lambda r: float(r["tnotif_min"]))
    t = [float(r["tnotif_min"]) for r in rr]
    ax.plot(t, [float(r["reference_years"]) for r in rr], "-", color=C[i], lw=1, label=f"DR{dr}, model")
    ax.plot(t, [float(r["iotloopsim_years"]) for r in rr], MARK[i], color=C[i], ms=4, mfc="none", label=f"DR{dr}, IoTLoopSim")
ax.set_xscale("log"); ax.set_xlabel("Reporting interval $T_{notif}$ (min)"); ax.set_ylabel("Battery lifetime (years)")
ax.set_ylim(0, 7.8); ax.legend(ncol=2, fontsize=6.0, loc="upper left")
fig.tight_layout(); fig.savefig(out / "fig_v3.pdf"); plt.close(fig)
print("ok")
