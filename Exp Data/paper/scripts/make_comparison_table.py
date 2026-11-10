"""Comparison table for the paper: prior-tool ratings from Exp Data/fullstack_evaluation.csv (unchanged) and IoTLoopSim's
*implemented* rating (not the design target), with the reason for every partial rating."""
import csv, sys, pathlib
src = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2])
actual = {"A3": (1, "flow/queue level; packet level only through an adapter interface"),
          "C7": (1, "policies can query network latency; radio and battery state are not used by built-in policies"),
          "C8": (1, "L0/L1 with reported difference; L2 adapter interface, no ns-3 adapter ships"),
          "C3": (1, "operational device, network and compute energy and carbon; gateway idle/forwarding power, RAN and embodied carbon not modelled"),
          "D2": (0, "compared with a published measurement-based model only; no testbed (same rule as for the other tools)"),
          "D4": (1, "scriptable (YAML, CLI, Java API); no GUI"),
          "D6": (2, "Apache-2.0; public repository with a commit in the last 12 months")}
# Evidence for IoTLoopSim's ratings (classes are in iotloopsim-models/src/main/java/org/iotloopsim/model unless noted)
EVID = {"A1": "Sensor, IoTDevice: typed sensors, payloads, energy, sampling distributions",
        "A2": "Actuator; AppRuntime.actuatorFor: commands sent to the device's or nearest actuator",
        "A4": "AccessTechnology (18 presets: Wi-Fi, BLE, Zigbee/802.15.4, LoRaWAN SF/DR, NB-IoT, LTE, 5G, Ethernet); Protocol (MQTT 0/1/2, CoAP NON/CON, HTTP, AMQP)",
        "A5": "ComputeNode with edge/fog/cloud tiers; Network multi-hop routing",
        "A6": "AppRuntime loop accounting: loop.latency_ms, deadline misses, actuation",
        "B1": "BuiltinPolicies: edge-first, fog-first, cloud-only, latency-aware placement",
        "B2": "ThresholdScaling; keep-alive scale-to-zero (AppRuntime)",
        "B3": "follow-me migration with PREWARM and COLD models; handover",
        "B4": "FaultInjector independent faults; replace recovery policy; route recomputation",
        "B5": "UpdateManager OTA campaigns (all-at-once, batch, canary, rollback, abort); rolling service updates; decommission and retireAt",
        "C1": "random-waypoint and trace mobility; handover with hysteresis",
        "C2": "EnergyLedger with device, radio, gateway (IDS), network, edge, fog and cloud layers",
        "C3": "EnergyLedger end-to-end totals; carbon intensity per layer (constant or hourly); gateway idle power not modelled",
        "C4": "container images, registry pulls, cold starts, module trees (ServiceInstance, AppModule)",
        "C5": "DTLS/TLS overheads and handshakes; flood, blackhole and IDS models with service-level metrics (CS2)",
        "C6": "correlated faults by zone, backhaul, tier or firmware version (FaultInjector selectors); CS1, CS2",
        "C9": "CostLedger: core-hours per tier, $/GB on priced links, $/kWh",
        "D1": "named RNG streams, replications with CIs, provenance.json, per-run result hashes (iotloopsim-core, iotloopsim-experiment)",
        "D3": "built-in reference policies (Section 4.7) and bundled reference scenarios",
        "C10": "Gym-style bridge (iotloopsim-bridge) with a Python client",
        "D5": "scalability study up to 50,000 devices (Exp Data/paper/results/scalability*)"}
rows = list(csv.DictReader(open(src)))
# Criteria rated in the CSVs but not reported in the paper (multi-fidelity, ML/RL interface)
EXCLUDE = {"C8", "C10"}
# IoTNetSim was rated for this paper from its full text (results/iotnetsim_rating.csv); it is not in the review corpus
inet = {r["code"]: r["iotnetsim"] for r in csv.DictReader(open(pathlib.Path(__file__).parent.parent / "results" / "iotnetsim_rating.csv"))}
for r in rows: r["IoTNetSim"] = inet[r["code"]]
tools = ["iFogSim2", "MobFogSim", "YAFS", "IoTSim-Edge", "FogNetSim++", "ECSNeT++", "IoTSim-Osmosis", "IoTNetSim"]
cite = {"iFogSim2": "mahmud2022ifogsim2", "MobFogSim": "puliafito2020mobfogsim", "YAFS": "lera2019yafs", "IoTSim-Edge": "jha2020_105",
        "FogNetSim++": "qayyum2018fognetsim", "ECSNeT++": "amarasinghe2020ecsnet", "IoTSim-Osmosis": "alwasel2021osmosis", "IoTNetSim": "salama2019iotnetsim"}
sym = {0: r"\none", 1: r"\half", 2: r"\full"}
L = [r"\begin{tabular}{@{}l l *{9}{c}@{}}", r"\toprule",
     r" & Criterion & " + " & ".join(r"\rotatebox{75}{%s~\cite{%s}}" % (t.replace("_", r"\_"), cite[t]) for t in tools) + r" & \rotatebox{75}{\textbf{\iotloopsim{}}}\\", r"\midrule"]
paper_rows = [r for r in rows if r["code"] not in EXCLUDE]
# number the remaining criteria consecutively within each group for display (C9 -> C8)
_n = {}
for r in paper_rows:
    g = r["code"][0]; _n[g] = _n.get(g, 0) + 1; r["shown"] = f"{g}{_n[g]}"
with open(out.parent / "iotloopsim_actual_rating.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["paper_code", "review_code", "criterion", "iotloopsim_actual", "in_paper", "evidence_or_reason"])
    for r in rows:
        a = actual.get(r["code"], (2, ""))
        inp = r["code"] not in EXCLUDE
        note = a[1] if a[0] < 2 else EVID.get(r["code"], "")
        if not inp: note = "rated in the review data but not reported in the paper; " + note
        w.writerow([r.get("shown", "") if inp else "", r["code"], r["criterion"], a[0], "yes" if inp else "no", note])
group = None; tot = {t: 0 for t in tools + ["IoTLoopSim"]}; gtot = {}
for r in paper_rows:
    if r["group"] != group:
        if group is not None:
            L.append(r"\addlinespace[2pt]")
        group = r["group"]; gtot = {t: 0 for t in tools + ["IoTLoopSim"]}
        L.append(r"\multicolumn{11}{@{}l}{\textbf{%s}}\\" % group.replace("&", r"\&"))
    crit = r["criterion"].replace("&", r"\&").replace("≥", r"$\geq$").replace("→", r"$\to$").replace("–", "--").replace("●", "full").replace("◐", "partial")
    crit = crit.replace("(packet = full, flow/trace = partial)", "(packet = full)").replace(" (Wi-Fi/LTE/BLE; MQTT/CoAP)", "").replace(" ($\\geq$2 of edge/fog/cloud = full)", " ($\\geq$2 tiers)").replace(" (commit in last 12 months)", "").replace(" (scenario files, seeds, repeated runs)", "").replace("Closed application loop", "Complete application loop")
    vals = [int(r[t]) for t in tools] + [actual.get(r["code"], (2,))[0]]
    for t, v in zip(tools + ["IoTLoopSim"], vals): tot[t] += v; gtot[t] += v
    L.append(r"%s & %s & " % (r["shown"], crit) + " & ".join(sym[v] for v in vals) + r"\\")
L += [r"\bottomrule", r"\end{tabular}"]
(out).write_text("\n".join(L) + "\n")
print(tot)
