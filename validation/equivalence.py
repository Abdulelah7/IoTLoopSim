#!/usr/bin/env python3
"""Equivalence (TOST-style) criterion for the V1 analytical checks.

Reads results/validation/v1_analytical.csv (not modified) and writes
results/validation/v1_analytical_equivalence.csv with columns
group,test,rel_error_pct,ci_rel_halfwidth_pct,tolerance_pct,equivalent.

A check is "equivalent" when the whole 95 % CI of its relative error lies inside
+/- tolerance:  |rel_error| + ci95_halfwidth / |expected| <= tolerance.
For expected == 0 the error, the CI and the tolerance are absolute, exactly as
in Check.relError() (the CSV then reports them multiplied by 100).
Values are taken from the CSV as printed (3-6 significant digits), so results
within rounding of the margin are flagged in the summary.

usage: python3 validation/equivalence.py [in.csv] [out.csv]
"""
import csv
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
src = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "results/validation/v1_analytical.csv"
dst = Path(sys.argv[2]) if len(sys.argv) > 2 else root / "results/validation/v1_analytical_equivalence.csv"

rows = []
with open(src, newline="") as f:
    for r in csv.DictReader(f):
        exp = float(r["expected"])
        h = float(r["ci95_halfwidth"])
        rel_pct = float(r["rel_error_pct"])
        tol_pct = float(r["tolerance_pct"])
        ci_rel_pct = (h if exp == 0 else h / abs(exp)) * 100
        eq = abs(rel_pct) + ci_rel_pct <= tol_pct + 1e-12
        rows.append({"group": r["group"], "test": r["test"] + (" [" + r["parameters"] + "]" if r["parameters"] not in ("", "-") else ""),
                     "rel_error_pct": f"{rel_pct:.3f}", "ci_rel_halfwidth_pct": f"{ci_rel_pct:.4f}",
                     "tolerance_pct": f"{tol_pct:.3f}", "equivalent": "yes" if eq else "no",
                     "_verdict": r["verdict"], "_slack": tol_pct - abs(rel_pct) - ci_rel_pct})

with open(dst, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["group", "test", "rel_error_pct", "ci_rel_halfwidth_pct", "tolerance_pct", "equivalent"])
    for r in rows:
        w.writerow([r["group"], r["test"], r["rel_error_pct"], r["ci_rel_halfwidth_pct"], r["tolerance_pct"], r["equivalent"]])

n_eq = sum(r["equivalent"] == "yes" for r in rows)
n_pass = sum(r["_verdict"] == "PASS" for r in rows)
print(f"{n_pass}/{len(rows)} PASS (CI-or-tolerance rule); {n_eq}/{len(rows)} equivalent (whole CI inside +/- tolerance)")
for r in rows:
    if r["equivalent"] == "no":
        print(f"  not equivalent: {r['group']}: {r['test']}  err={r['rel_error_pct']}%  ci=+/-{r['ci_rel_halfwidth_pct']}%  tol={r['tolerance_pct']}%")
for r in rows:
    if r["equivalent"] == "yes" and abs(r["_slack"]) < 0.01:
        print(f"  marginal (within CSV rounding): {r['group']}: {r['test']}  slack={r['_slack']:.4f} pct-points")
print(f"written {dst}")
