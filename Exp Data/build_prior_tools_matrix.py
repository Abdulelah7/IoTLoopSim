"""Build the IoT-capable prior-tool matrix for IoTLoopSim from the DCN survey coding.
Source: ../../DCN/Exp Data/capability_matrix.csv (0 none, 1 partial, 2 explicit).
Added columns are taken from statements in DCN/main.tex (Sections on evaluation and requirements)."""
import csv, os
here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, "..", "..", "DCN", "Exp Data", "capability_matrix.csv")
rows = {r["id"]: r for r in csv.DictReader(open(src))}

# IoT-capable tools (model IoT end devices and/or IoT access networks), grouped; tiers D=device N=access network E=edge/fog C=cloud
groups = [
 ("IoT-to-cloud continuum (fog/edge with IoT devices)", [
  ("001","D N E C"),("002","D N E C"),("004","D E C"),("007","D E C"),("105","D N E"),("106","D N E C"),
  ("023","D N E C"),("028","D N E C"),("032","D E C"),("042","D N E"),("041","D N E"),("019","D N E"),("011","D E")]),
 ("Vehicular, satellite and space-air-ground IoT", [
  ("013","D N E"),("018","D N E"),("015","D N E"),("022","D N E"),("031","D N E"),("020","D N E C"),("024","D N E")]),
 ("IoT network and wireless (device + access network)", [
  ("056","D N"),("063","D N"),("070","D N"),("073","D N"),("075","D N"),("045","D N"),("087","D N"),
  ("081","D N"),("044","D N"),("046","N"),("051","D N"),("086","N")]),
 ("IoT + cloud / live platform", [("101","D C"),("090","D C")]),
]
# Evidence-based extra criteria (DCN/main.tex): validation/calibration vs real system; repeated runs with CI/dispersion; multi-fidelity co-simulation
validation = {"002":2,"007":2}
stats = {"007":2,"063":2,"028":1,"002":1}
cosim = {"063":2,"101":1}
dims = ["mobility","energy","failures","security","containers_microservices","ml_rl_hooks","gui_nocode","open_source_code"]
sym = {0:"○",1:"◐",2:"●"}
out = []
for g, items in groups:
    for tid, tiers in items:
        r = rows[tid]
        vals = [int(r[d]) for d in dims] + [validation.get(tid,0), stats.get(tid,0), cosim.get(tid,0)]
        out.append(dict(group=g, id=tid, tool=r["tool"], focus=r["focus"], foundation=r["foundation"], tiers=tiers,
                        net_model=r["network_model"], **{k:v for k,v in zip(dims+["real_validation","repeats_CI","multi_fidelity_cosim"], vals)},
                        explicit=sum(v==2 for v in vals), partial=sum(v==1 for v in vals)))
cols = list(out[0].keys())
with open(os.path.join(here,"prior_iot_tools_matrix.csv"),"w",newline="") as f:
    w = csv.DictWriter(f, fieldnames=cols+["correlated_failures","carbon","security_service_impact"]); w.writeheader()
    for o in out: w.writerow({**o,"correlated_failures":0,"carbon":0,"security_service_impact":0})
# Markdown
hdr = "| Tool | Focus | Foundation | Tiers | Net | Mob | Energy | Fail | Sec | Cont/µsvc | ML/RL | GUI | Open | Real valid. | Repeats/CI | Co-sim | Corr. fail | Carbon | Sec→svc | ● / ◐ |"
md = []
for g,_ in groups:
    md.append(f"\n**{g}**\n\n"+hdr+"\n|"+"---|"*20)
    for o in [x for x in out if x["group"]==g]:
        v = [o[d] for d in dims+["real_validation","repeats_CI","multi_fidelity_cosim"]]
        md.append(f"| {o['tool']} | {o['focus']} | {o['foundation']} | {o['tiers']} | {o['net_model']} | "+" | ".join(sym[x] for x in v)+f" | ○ | ○ | ○ | {o['explicit']} / {o['partial']} |")
n=len(out)
tot = " | ".join(f"{sum(o[d]==2 for o in out)}+{sum(o[d]==1 for o in out)}" for d in dims+["real_validation","repeats_CI","multi_fidelity_cosim"])
md.append(f"\n**Explicit + partial across the {n} IoT-capable tools:** "+tot)
open(os.path.join(here,"prior_iot_tools_matrix.md"),"w").write("\n".join(md))
full = sum(1 for o in out if set(o['tiers'].split())=={'D','N','E','C'})
print(n, "tools; full D-N-E-C tier span:", full); print("max explicit:", max(o['explicit'] for o in out), [o['tool'] for o in out if o['explicit']>=4])
print("totals", tot)
