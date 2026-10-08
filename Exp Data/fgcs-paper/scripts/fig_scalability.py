"""Scalability figure and table: wall time, event rate and peak memory vs number of devices, for two selector policies."""
import sys, csv, pathlib, statistics as st
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from fig_style import *
setup()
res = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2])
series = [("scalability", "default selector", C[0], "o"), ("scalability-random", "random selector", C[1], "s")]
agg = {}
for d, lab, col, m in series:
    f = res / d / "scalability.csv"
    if not f.exists(): continue
    rows = list(csv.DictReader(open(f)))
    by = {}
    for r in rows: by.setdefault(int(r["devices"]), []).append(r)
    agg[d] = {n: dict(reps=len(v), wall=st.mean(float(x["sim_wall_s"]) for x in v),
                      wall_sd=(st.stdev([float(x["sim_wall_s"]) for x in v]) if len(v) > 1 else 0),
                      events=st.mean(float(x["events"]) for x in v), eps=st.mean(float(x["events_per_s"]) for x in v),
                      rss=st.mean(float(x["peak_rss_mb"]) for x in v), rss_max=max(float(x["peak_rss_mb"]) for x in v),
                      lat=st.mean(float(x["loop_latency_ms"]) for x in v), succ=st.mean(float(x["success"]) for x in v))
              for n, v in sorted(by.items())}
fig, axs = plt.subplots(1, 3, figsize=(7.0, 2.3))
for d, lab, col, m in series:
    if d not in agg: continue
    ns = sorted(agg[d]); a = agg[d]
    axs[0].plot(ns, [a[n]["wall"] for n in ns], "-" + m, color=col, label=lab, mec="white", mew=0.5)
    axs[1].plot(ns, [a[n]["eps"] / 1e3 for n in ns], "-" + m, color=col, label=lab, mec="white", mew=0.5)
    axs[2].plot(ns, [a[n]["rss"] / 1024 for n in ns], "-" + m, color=col, label=lab, mec="white", mew=0.5)
n0 = sorted(agg["scalability"]); ref = agg["scalability"][1000]["wall"]
axs[0].plot([1000, 50000], [ref, ref * 50], ":", color=INK2, lw=0.9, label="linear in devices")
for ax in axs: ax.set_xscale("log"); ax.set_xlabel("Devices")
axs[0].set_yscale("log"); axs[0].set_ylabel("Wall-clock time per run (s)")
axs[1].set_ylabel("Event rate (thousand events/s)"); axs[1].set_ylim(bottom=0)
axs[2].set_ylabel("Peak resident memory (GiB)"); axs[2].set_ylim(bottom=0)
for ax, t in zip(axs, ["(a)", "(b)", "(c)"]): ax.set_title(t, loc="left", fontsize=8)
axs[0].legend(fontsize=6.0, loc="upper left")
fig.tight_layout(); fig.savefig(out / "fig_scalability.pdf"); plt.close(fig)
# table
L = []
for n in sorted(agg["scalability"]):
    a = agg["scalability"][n]; b = agg.get("scalability-random", {}).get(n)
    L.append(f"\\num{{{n}}} & \\num{{{a['events']/1e6:.2f}}} & {a['wall']:.1f} $\\pm$ {a['wall_sd']:.1f} & {a['eps']/1e3:.0f} & {a['rss']/1024:.2f} & "
             + (f"\\num{{{b['events']/1e6:.2f}}} & {b['wall']:.1f} $\\pm$ {b['wall_sd']:.1f} & {b['eps']/1e3:.0f} & {b['rss']/1024:.2f}" if b else "-- & -- & -- & --") + f" & {a['reps']}" + (f"/{b['reps']}" if b else "") + r"\\")
(out / "tab_scalability.tex").write_text("\n".join(L) + "\n")
import json; (out / "scalability_agg.json").write_text(json.dumps(agg, indent=1))
print("\n".join(L))
