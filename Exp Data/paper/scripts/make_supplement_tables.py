"""LaTeX rows for the supplementary material of the paper.
Usage: make_supplement_tables.py <results/validation dir> <figures dir with cases_summary.json> <output dir>
Writes supp_v1.tex (37 analytical checks with equivalence and run settings), supp_lifecycle.tex (19 lifecycle checks),
supp_cs1.tex and supp_cs2.tex (sensitivity and scenario-variant rows)."""
import sys, csv, json, pathlib
val = pathlib.Path(sys.argv[1]); figs = pathlib.Path(sys.argv[2]); out = pathlib.Path(sys.argv[3]); out.mkdir(parents=True, exist_ok=True)
esc = lambda t: (t.replace("\\", r"\textbackslash{}").replace("&", r"\&").replace("%", r"\%").replace("_", r"\_")
                  .replace("^", r"\textasciicircum{}").replace("~", r"$\sim$").replace("#", r"\#").replace("<=", r"$\le$"))
def num(x):
    v = float(x)
    return f"{v:.6g}"

# V1 analytical checks with the equivalence verdict
A = list(csv.DictReader(open(val / "v1_analytical.csv")))
E = {(r["group"], r["test"]): r for r in csv.DictReader(open(val / "v1_analytical_equivalence.csv"))}
L = []
for r in A:
    key = (r["group"], f'{r["test"]} [{r["parameters"]}]') if (r["group"], f'{r["test"]} [{r["parameters"]}]') in E else None
    e = E.get(key) if key else None
    if e is None:  # fall back on order
        e = list(E.values())[A.index(r)]
    L.append(f'{esc(r["group"])} & {esc(r["test"])} & {esc(r["parameters"])} & {num(r["expected"])} & {num(r["simulated_mean"])} & '
             f'{float(r["rel_error_pct"]):.3f} & {float(e["ci_rel_halfwidth_pct"]):.3f} & {float(r["tolerance_pct"]):.1f} & {r["verdict"]} & {e["equivalent"]}\\\\')
(out / "supp_v1.tex").write_text("\n".join(L) + "\n")

# run settings
L = [f'{esc(r["group"])} & {esc(r["test"])} & {esc(r["sim_time_per_rep"])} & {r["reps"]} & {esc(r["warmup"])}\\\\'
     for r in csv.DictReader(open(val / "v1_run_settings.csv"))]
(out / "supp_runs.tex").write_text("\n".join(L) + "\n")

# lifecycle checks
L = []
for r in csv.DictReader(open(val / "v1_lifecycle.csv")):
    L.append(f'{esc(r["test"])} & {esc(r["parameters"])} & {num(r["expected"])} & {num(r["simulated_mean"])} & {num(r["ci95_halfwidth"])} & '
             f'{esc(r["unit"])} & {float(r["rel_error_pct"]):.3f} & {r["n"]} & {r["verdict"]} & {r["equivalent"]}\\\\')
(out / "supp_lifecycle.tex").write_text("\n".join(L) + "\n")

# case-study sensitivity and scenario-variant rows
S = json.load(open(figs / "cases_summary.json"))
pm = lambda m, h, s=1, f="{:.2f}": f.format(m * s) + r" $\pm$ " + f.format(h * s)
L = []
for name, v in S["cs1"]["sensitivity"]:
    d, c, a = v["update.campaign_duration_s.mean"], v["faults.correlated_targets"], v["availability.devices"]
    L.append(f"{esc(name)} & {pm(*d, 1/3600)} & {pm(*c, 1, '{:.1f}')} & {pm(*a, 100)}\\\\")
(out / "supp_cs1.tex").write_text("\n".join(L) + "\n")
L = []
base = [("base: IDS on, backup links", S["cs2"]["means"]["ids-backup"]), ("base: IDS off, backup links", S["cs2"]["means"]["noids-backup"])]
for name, v in base + list(S["cs2"]["sensitivity"]):
    L.append(f"{esc(name)} & {pm(*v['loop.success_ratio'], 100)} & {pm(*v['loop.dropped'], 1, '{:.0f}')} & {pm(*v['loop.dropped.blackhole'], 1, '{:.0f}')} & "
             f"{pm(*v['loop.dropped.no-coverage'], 1, '{:.0f}')} & {pm(*v['app.traffic.latency_ms.mean'], 1, '{:.0f}')} & {100*v['availability.gateways'][0]:.1f}\\\\")
(out / "supp_cs2.tex").write_text("\n".join(L) + "\n")
# paired differences
READ = {"faults.correlated_targets": "device crashes", "availability.devices": "device availability (pp)", "crashes": "device crashes",
        "loop success %": "loop success (pp)", "loops lost": "loops lost per hour", "traffic latency mean ms": "mean traffic latency (ms)",
        "gateway availability %": "gateway availability (pp)", "total energy Wh": "total energy (Wh)", "carbon g": "carbon (g CO$_2$e)", "cost units": "cost (units)"}
def readable(k):
    head, _, metric = k.rpartition(": ")
    return esc(head) + ": " + READ.get(metric, esc(metric))
L = [f"CS1 & {readable(k)} & {m:.3f} $\\pm$ {h:.3f} & {n}\\\\" for k, (m, h, n) in S["cs1"]["paired"].items()]
L += [f"CS2 & {readable(k)} & {m:.3f} $\\pm$ {h:.3f} & {n}\\\\" for k, (m, h, n) in S["cs2"]["paired"].items()]
(out / "supp_paired.tex").write_text("\n".join(L) + "\n")
print("written", sorted(p.name for p in out.glob("supp_*.tex")))
