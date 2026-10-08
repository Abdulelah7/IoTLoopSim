"""Compact 4-criterion matrix (IoTLoopSim Step 4), condensed from fullstack_evaluation.csv.
Scores 2/1/0 = ●/◐/○. Net and Proc cells show letters but are scored (P=2, A or A/T=1; >=2 tiers=2, 1 tier=1)."""
import csv, os
here=os.path.dirname(os.path.abspath(__file__))
groups=[("Full stack",["Sens","Act","Net","Proto","Proc"]),("Lifecycle",["Deploy","Scale","Migr","Update"]),
        ("Sim. features",["Mob","Energy","Fail","Sec","Cont","ML","Cost"]),("Methodology",["Repro","Valid","GUI","Open"])]
# tool, lang, net label, proc label, scores in column order
D=[("iFogSim2","Java","A/T","E/F/C",[2,2,1,0,2, 2,1,2,0, 2,1,0,0,2,0,1, 0,0,0,2]),
   ("MobFogSim","Java","A/T","F/C",[2,2,1,0,2, 2,0,2,0, 2,0,0,0,2,0,1, 1,2,0,2]),
   ("YAFS","Python","A","F/C",[2,2,1,0,2, 2,1,1,0, 1,1,2,0,0,0,0, 1,0,1,2]),
   ("IoTSim-Edge","Java","A","E",[2,2,1,2,1, 2,0,1,0, 2,2,1,1,1,0,1, 1,0,2,1]),
   ("FogNetSim++","C++","P","F",[2,1,2,2,1, 1,0,1,0, 2,2,1,0,0,0,2, 0,0,2,1]),
   ("ECSNeT++","C++","P","E/C",[2,1,2,1,2, 2,0,0,0, 1,2,0,0,0,0,0, 1,2,0,1]),
   ("IoTSim-Osmosis","Java","A","E/C",[2,0,1,1,2, 2,1,1,0, 0,2,0,0,0,0,1, 0,0,0,1])]
sym={0:"○",1:"◐",2:"●"}
cols=[c for _,cs in groups for c in cs]
hdr1="| Tool | Lang | "+" | ".join(f"**{g}** ({'/'.join(cs)})" for g,cs in groups)+" | Total |"
H="| Tool | Lang | "+" | ".join(cols)+" | "+" | ".join(g for g,_ in groups)+" | Total |"
md=[H,"|---|---|"+"---|"*(len(cols)+len(groups)+1)]
rows=[]
for t,l,net,proc,s in D:
    cells=[]
    for c,v in zip(cols,s):
        cells.append(net if c=="Net" else proc if c=="Proc" else sym[v])
    i=0; gsc=[]
    for g,cs in groups: gsc.append(sum(s[i:i+len(cs)])); i+=len(cs)
    md.append(f"| {t} | {l} | "+" | ".join(cells)+" | "+" | ".join(f"{x}/{2*len(cs)}" for x,(g,cs) in zip(gsc,groups))+f" | **{sum(s)}/40** |")
    rows.append([t,l]+s+gsc+[sum(s)])
md.append("| *Tools with ● (of 7)* | | "+" | ".join(str(sum(1 for r in D if r[4][k]==2)) for k in range(len(cols)))+" | | | | | |")
open(os.path.join(here,"compact_matrix.md"),"w").write("\n".join(md))
with open(os.path.join(here,"compact_matrix.csv"),"w",newline="") as f:
    w=csv.writer(f); w.writerow(["tool","lang"]+cols+[g for g,_ in groups]+["total_of_40"]); w.writerows(rows)
print("\n".join(md))
