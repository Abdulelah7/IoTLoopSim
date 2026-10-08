"""Case-study figures, tables and paired statistics for the FGCS paper.
CS1 = OTA rollout on the LoRaWAN farm (results/cs1-ota), CS2 = IDS x backup links on the smart city (results/cs2-security).
All variants of a study share their seeds (common random numbers), so paired differences are taken per seed.
Usage: fig_cases.py <results dir> <output dir>"""
import sys, csv, json, math, pathlib
import numpy as np
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from fig_style import *
setup()
res = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
T95 = {9: 2.262, 29: 2.045}

def load(p):
    return {r["metric"]: (float(r["mean"]), float(r["ci95_high"]) - float(r["mean"])) for r in csv.DictReader(open(res / p / "summary.csv"))}
def runs(p):
    return {int(r["seed"]): r for r in csv.DictReader(open(res / p / "runs.csv"))}
g = lambda d, k: d.get(k, (0.0, 0.0))
def paired(a, b, k, s=1.0, combo=None):
    """Mean and 95 % CI half-width of the per-seed difference a - b (or of a linear combination of variants)."""
    if combo is None: combo = [(a, 1), (b, -1)]
    rs = [(runs(p), w) for p, w in combo]
    seeds = sorted(set.intersection(*[set(r) for r, _ in rs]))
    v = [s * sum(w * float(r[sd].get(k) or 0) for r, w in rs) for sd in seeds]
    n = len(v); m = sum(v) / n; sd = math.sqrt(sum((x - m) ** 2 for x in v) / (n - 1))
    return m, T95.get(n - 1, 2.0) * sd / math.sqrt(n), n
pm = lambda d, k, s=1, fmt="{:.1f}": fmt.format(g(d, k)[0] * s) + r" $\pm$ " + fmt.format(g(d, k)[1] * s)
summary = {}

# ---------------- CS1: OTA rollout
O = "cs1-ota/"
R = ["all-at-once", "batch", "canary"]; RL = {"all-at-once": "All-at-once", "batch": "Batch", "canary": "Canary"}
lat = {r: load(O + "latent-" + r) for r in R}; bad = {r: load(O + "bad-" + r) for r in R}; nrb = load(O + "latent-canary-norollback")
fig, axs = plt.subplots(1, 3, figsize=(7.0, 2.2)); x = np.arange(len(R)); w = 0.38
for ax, (k, s, ylab) in zip(axs, [("update.failed", 1, "Failed installations"), ("faults.correlated_targets", 1, "Device crashes (firmware bug)"),
                                   ("update.campaign_duration_s.mean", 1 / 3600, "Campaign duration (h)")]):
    for j, (dd, lab) in enumerate([(lat, "Latent bug (3% install failures)"), (bad, "Bad build (40% install failures)")]):
        ax.bar(x + (j - 0.5) * w, [g(dd[r], k)[0] * s for r in R], w, yerr=[g(dd[r], k)[1] * s for r in R], color=C[j], label=lab,
               capsize=1.5, ecolor=INK2, error_kw=dict(lw=0.7))
    ax.set_xticks(x); ax.set_xticklabels([RL[r] for r in R]); ax.set_ylabel(ylab); ax.grid(axis="x", visible=False)
fig.legend(*axs[0].get_legend_handles_labels(), loc="upper center", ncol=2, fontsize=7, bbox_to_anchor=(0.5, 1.06))
for ax, t in zip(axs, "abc"): ax.set_title(f"({t})", loc="left", fontsize=8)
fig.tight_layout(); fig.savefig(out / "fig_cs1.pdf"); plt.close(fig)
rows = []
for lab, dd in [("Latent bug", lat), ("Bad build", bad)]:
    for r in R:
        d = dd[r]; ab = "aborted" if g(d, "update.campaigns_aborted")[0] >= 0.5 else "completed"
        rows.append(f"{lab} & {RL[r]} & {pm(d, 'update.succeeded')} & {pm(d, 'update.failed')} & {ab} & {pm(d, 'update.campaign_duration_s.mean', 1/3600, '{:.2f}')} & "
                    f"{pm(d, 'faults.correlated_targets')} & {pm(d, 'availability.devices', 100, '{:.2f}')} & {pm(d, 'energy.category.ota_J')}\\\\")
d = nrb
rows.append(r"\midrule" + "\n" + f"Latent bug & Canary, no rollback & {pm(d, 'update.succeeded')} & {pm(d, 'update.failed')} & completed & {pm(d, 'update.campaign_duration_s.mean', 1/3600, '{:.2f}')} & "
            f"{pm(d, 'faults.correlated_targets')} & {pm(d, 'availability.devices', 100, '{:.2f}')} & {pm(d, 'energy.category.ota_J')}\\\\")
(out / "tab_cs1.tex").write_text("\n".join(rows) + "\n")
cs1 = {"means": {f"{b}-{r}": {k: g(dd[r], k) for k in ["update.succeeded", "update.failed", "update.campaign_duration_s.mean", "faults.correlated_targets",
                                                      "availability.devices", "energy.category.ota_J", "update.download_s.mean", "update.download_s.max"]}
                 for b, dd in [("latent", lat), ("bad", bad)] for r in R},
       "norollback_availability": g(nrb, "availability.devices")}
cs1["paired"] = {}
for b in ["latent", "bad"]:
    for r in ["batch", "canary"]:
        for k, s in [("faults.correlated_targets", 1), ("availability.devices", 100)]:
            cs1["paired"][f"{b}: all-at-once - {r}: {k}"] = paired(O + f"{b}-all-at-once", O + f"{b}-{r}", k, s)
cs1["paired"]["latent: canary - canary-norollback: availability.devices"] = paired(O + "latent-canary", O + "latent-canary-norollback", "availability.devices", 100)
# sensitivity runs and scenario variants
sens = []
def srow(name, p, keys=("update.campaign_duration_s.mean", "faults.correlated_targets", "availability.devices")):
    d = load(O + p)
    sens.append((name, {k: g(d, k) for k in keys}))
for gd in ["0.01", "1.0"]:
    for r in ["all-at-once", "batch"]: srow(f"gateway duty cycle {gd}, {r}", f"sens-gwduty-{gd}-{r}")
for p in ["0.05", "0.30"]:
    for r in ["all-at-once", "canary"]: srow(f"bug crash probability {p}, {r}", f"sens-crash-{p}-{r}")
for rb in ["5min", "60min"]: srow(f"rollback after {rb}, canary", f"sens-rollback-{rb}-canary")
for r in ["all-at-once", "canary"]: srow(f"bug not tied to firmware, {r}", f"var-uncorrelated-{r}")
cs1["sensitivity"] = sens
cs1["paired"]["uncorrelated: all-at-once - canary: crashes"] = paired(O + "var-uncorrelated-all-at-once", O + "var-uncorrelated-canary", "faults.correlated_targets")
for p in ["0.05", "0.30"]:
    cs1["paired"][f"crash {p}: all-at-once - canary: crashes"] = paired(O + f"sens-crash-{p}-all-at-once", O + f"sens-crash-{p}-canary", "faults.correlated_targets")
lines = []
for name, v in sens:
    lines.append(f"{name} & {v['update.campaign_duration_s.mean'][0]/3600:.2f} $\\pm$ {v['update.campaign_duration_s.mean'][1]/3600:.2f} & "
                 f"{v['faults.correlated_targets'][0]:.1f} $\\pm$ {v['faults.correlated_targets'][1]:.1f} & "
                 f"{100*v['availability.devices'][0]:.2f} $\\pm$ {100*v['availability.devices'][1]:.2f}\\\\")
(out / "tab_cs1_sens.tex").write_text("\n".join(lines) + "\n")
summary["cs1"] = cs1

# ---------------- CS2: IDS x backup links
O = "cs2-security/"
V = [("ids-backup", "On", "Yes"), ("noids-backup", "Off", "Yes"), ("ids-nobackup", "On", "No"), ("noids-nobackup", "Off", "No")]
rows = []; cs2 = {"means": {}}
for v, ids, bk in V:
    d = load(O + v)
    rows.append(f"{ids} & {bk} & {pm(d, 'loop.success_ratio', 100, '{:.2f}')} & {pm(d, 'app.traffic.latency_ms.mean', fmt='{:.0f}')} & {pm(d, 'app.traffic.latency_ms.p95', fmt='{:.0f}')} & "
                f"{pm(d, 'loop.dropped.blackhole', fmt='{:.0f}')} & {pm(d, 'loop.dropped.no-coverage', fmt='{:.0f}')} & {pm(d, 'loop.dropped.no-route', fmt='{:.1f}')} & "
                f"{pm(d, 'security.attack_processed', fmt='{:.0f}')} & {pm(d, 'energy.category.security_J', fmt='{:.0f}')}\\\\")
    cs2["means"][v] = {k: g(d, k) for k in ["loop.success_ratio", "app.traffic.latency_ms.mean", "app.traffic.latency_ms.p95", "app.traffic.latency_ms.max",
                                          "loop.dropped", "loop.dropped.blackhole", "loop.dropped.no-coverage", "loop.dropped.no-route",
                                          "security.attack_processed", "energy.category.security_J", "availability.gateways", "carbon.total_g", "cost.total", "energy.total_kWh"]}
(out / "tab_cs2.tex").write_text("\n".join(rows) + "\n")
cs2["paired"] = {
    "IDS on - off (backup): loop success %": paired(O + "ids-backup", O + "noids-backup", "loop.success_ratio", 100),
    "IDS on - off (backup): loops lost": paired(O + "ids-backup", O + "noids-backup", "loop.dropped"),
    "IDS on - off (backup): traffic latency mean ms": paired(O + "ids-backup", O + "noids-backup", "app.traffic.latency_ms.mean"),
    "IDS on - off (backup): gateway availability %": paired(O + "ids-backup", O + "noids-backup", "availability.gateways", 100),
    "IDS on - off (backup): total energy Wh": paired(O + "ids-backup", O + "noids-backup", "energy.total_kWh", 1000),
    "IDS on - off (backup): carbon g": paired(O + "ids-backup", O + "noids-backup", "carbon.total_g"),
    "IDS on - off (backup): cost units": paired(O + "ids-backup", O + "noids-backup", "cost.total"),
    "backup yes - no (IDS on): loop success %": paired(O + "ids-backup", O + "ids-nobackup", "loop.success_ratio", 100),
    "interaction (on,yes - off,yes - on,no + off,no): loop success %": paired(None, None, "loop.success_ratio", 100,
        combo=[(O + "ids-backup", 1), (O + "noids-backup", -1), (O + "ids-nobackup", -1), (O + "noids-nobackup", 1)]),
}
sens2 = []
for name, p in [("detection probability 0.5", "sens-idsp-0.5"), ("detection probability 1.0", "sens-idsp-1.0"), ("mean detection time 300 s", "sens-idst-300"),
                ("attacks only, IDS on", "var-attacks-only-ids"), ("attacks only, IDS off", "var-attacks-only-noids"),
                ("faults only, IDS on", "var-faults-only-ids"), ("faults only, IDS off", "var-faults-only-noids")]:
    d = load(O + p)
    sens2.append((name, {k: g(d, k) for k in ["loop.success_ratio", "loop.dropped", "loop.dropped.blackhole", "loop.dropped.no-coverage", "app.traffic.latency_ms.mean", "availability.gateways"]}))
cs2["sensitivity"] = sens2
lines = [f"{n} & {100*v['loop.success_ratio'][0]:.2f} $\\pm$ {100*v['loop.success_ratio'][1]:.2f} & {v['loop.dropped'][0]:.0f} $\\pm$ {v['loop.dropped'][1]:.0f} & "
         f"{v['app.traffic.latency_ms.mean'][0]:.0f} $\\pm$ {v['app.traffic.latency_ms.mean'][1]:.0f} & {100*v['availability.gateways'][0]:.1f}\\\\" for n, v in sens2]
(out / "tab_cs2_sens.tex").write_text("\n".join(lines) + "\n")
summary["cs2"] = cs2
(out / "cases_summary.json").write_text(json.dumps(summary, indent=1))
print(json.dumps(summary, indent=1)[:4000])
