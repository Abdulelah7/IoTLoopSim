"""Unified full-stack evaluation of IoT simulators (IoTLoopSim Step 3).
Scores: 2 = explicit (●), 1 = partial (◐), 0 = not reported (○).
Sources: DCN capability matrix, lifecycle_coverage.csv, full-text checks of each tool paper, GitHub activity (2026-10-04).
R-codes refer to requirements R1-R13 in the DCN survey (Table 'Design requirements')."""
import csv, os
here=os.path.dirname(os.path.abspath(__file__))
tools=["iFogSim2","MobFogSim","YAFS","IoTSim-Edge","FogNetSim++","ECSNeT++","IoTSim-Osmosis"]
C=[ # group, code, criterion, requirement, scores..., evidence note
("A Full-stack layers","A1","Sensors / data sources","R1",[2,2,2,2,2,2,2],"all model sensing devices"),
("A Full-stack layers","A2","Actuators","R1",[2,2,2,2,1,1,0],"FogNetSim++ pub/sub subscribers; ECSNeT++ sinks"),
("A Full-stack layers","A3","Network fidelity (packet = ●, flow/trace = ◐)","R2",[1,1,1,1,2,2,1],"FogNetSim++, ECSNeT++ on OMNeT++/INET"),
("A Full-stack layers","A4","Access technologies & IoT protocols (Wi-Fi/LTE/BLE; MQTT/CoAP)","R1",[0,0,0,2,2,1,1],"IoTSim-Edge CoAP/MQTT/XMPP/AMQP + Wi-Fi/4G/BLE; FogNetSim++ MQTT broker; ECSNeT++ WLAN/LTE; Osmosis SD-WAN"),
("A Full-stack layers","A5","Processing tiers (≥2 of edge/fog/cloud = ●)","R1",[2,2,2,1,1,2,2],"IoTSim-Edge edge only; FogNetSim++ fog only"),
("A Full-stack layers","A6","Closed application loop (sense→process→act)","R1",[2,2,2,2,1,1,0],""),
("B Lifecycle","B1","Deployment / placement policies","R3",[2,2,2,2,1,2,2],""),
("B Lifecycle","B2","Scaling / orchestration","R3",[1,0,1,0,0,0,1],"iFogSim2 microservice scaling; YAFS dynamic module allocation"),
("B Lifecycle","B3","Migration / handover","R4",[2,2,1,1,1,0,1],"MobFogSim container migration; FogNetSim++ handover"),
("B Lifecycle","B4","Failure & recovery","R6",[0,0,2,1,1,0,0],"YAFS dynamic node failures; IoTSim-Edge battery depletion"),
("B Lifecycle","B5","Software/firmware update (OTA) & retirement","—",[0,0,0,0,0,0,0],"not modelled by any tool"),
("C Simulation features","C1","Mobility","R4",[2,2,1,2,2,1,0],"DCN matrix"),
("C Simulation features","C2","Energy model (any layer)","R8",[1,0,1,2,2,2,2],"DCN matrix"),
("C Simulation features","C3","End-to-end energy & carbon","R8",[0,0,0,0,0,0,1],"Osmosis device+network+DC energy, no carbon"),
("C Simulation features","C4","Containers / microservices","R3",[2,2,0,1,0,0,0],"DCN matrix"),
("C Simulation features","C5","Security with service-level overhead","R7",[0,0,0,1,0,0,0],"IoTSim-Edge models DTLS/SSL header overheads only"),
("C Simulation features","C6","Correlated failures","R6",[0,0,0,0,0,0,0],"none"),
("C Simulation features","C7","Joint compute–network decisions","R5",[0,1,0,0,1,0,1],"location-aware migration; scheduling with handover; SD-WAN routing + placement"),
("C Simulation features","C8","Multi-fidelity / co-simulation","R2",[0,0,0,0,1,0,0],"FogNetSim++ coupled with Node-RED (paper 041)"),
("C Simulation features","C9","Cost model","—",[1,1,0,1,2,0,1],"FogNetSim++ pricing models; CloudSim-based cost elsewhere"),
("C Simulation features","C10","ML / RL interface","R13",[0,0,0,0,0,0,0],"none in base tools (Osmosis-MARL extension 2025)"),
("D Methodology & usability","D1","Reproducibility (scenario files, seeds, repeated runs)","R9",[0,1,1,1,0,1,0],"YAFS JSON + fixed seeds; IoTSim-Edge JSON; MobFogSim 30 runs + CI; ECSNeT++ IQR"),
("D Methodology & usability","D2","Validation against a real testbed","R10",[0,2,0,0,0,2,0],"MobFogSim, ECSNeT++"),
("D Methodology & usability","D3","Benchmarks / built-in reference policies","R11",[1,0,1,0,0,0,0],"iFogSim2 benchmark policies; YAFS vs iFogSim comparison"),
("D Methodology & usability","D4","GUI + scriptable","R12",[0,0,1,2,2,0,0],"DCN matrix"),
("D Methodology & usability","D5","Scalability evaluated","—",[1,1,1,0,2,0,0],"FogNetSim++ CPU/memory up to ~1,300 devices; Osmosis 2–10 devices"),
("D Methodology & usability","D6","Open source & actively maintained (commit in last 12 months)","—",[2,2,2,1,1,1,1],"GitHub 2026-10-04"),
]
sym={0:"○",1:"◐",2:"●"}
with open(os.path.join(here,"fullstack_evaluation.csv"),"w",newline="") as f:
    w=csv.writer(f); w.writerow(["group","code","criterion","requirement"]+tools+["IoTLoopSim_target","evidence"])
    for g,c,n,r,s,e in C: w.writerow([g,c,n,r]+s+[2,e])
groups=[]; [groups.append(g) for g,*_ in C if g not in groups]
md=["| # | Criterion | Req. | "+" | ".join(tools)+" | **IoTLoopSim target** |","|---|---|---|"+"---|"*len(tools)+"---|"]
tot=[0]*len(tools); summary=[]
for g in groups:
    md.append(f"| | ***{g}*** | | "+" | "*(len(tools))+" |")
    gs=[0]*len(tools); n=0
    for gg,c,name,r,s,e in C:
        if gg!=g: continue
        n+=1; md.append(f"| {c} | {name} | {r} | "+" | ".join(sym[x] for x in s)+" | ● |")
        gs=[a+b for a,b in zip(gs,s)]
    tot=[a+b for a,b in zip(tot,gs)]
    md.append(f"| | *{g.split(' ',1)[1]} score (max {2*n})* | | "+" | ".join(f"*{x}*" for x in gs)+f" | *{2*n}* |")
    summary.append((g,2*n,gs))
mx=2*len(C)
md.append(f"| | **Total (max {mx})** | | "+" | ".join(f"**{x}** ({round(100*x/mx)}%)" for x in tot)+f" | **{mx}** |")
open(os.path.join(here,"fullstack_evaluation_table.md"),"w").write("\n".join(md))
for t,x in sorted(zip(tools,tot),key=lambda z:-z[1]): print(t,x,round(100*x/mx))
for g,m,gs in summary: print(g,m,gs)
