"""YAFS implementation of the Common Reference System (CRS). Prints one JSON line per run.

Units: time = ms. YAFS computes link transmit = message.bytes / (BW * 1e6) and treats `bytes` as bits,
so we pass bits and set BW = bandwidth_bps / 1e9 to obtain milliseconds. Service time = instructions / IPT,
so IPT = MIPS * 1e3 instructions per ms.
"""
import json
import logging
import sys
import tempfile

import networkx as nx
import numpy as np

import os
sys.path.insert(0, os.environ.get("YAFS_SRC", "YAFS/src"))
from yafs.application import Application, Message, fractional_selectivity  # noqa: E402
from yafs.core import Sim  # noqa: E402
from yafs.distribution import Distribution, deterministic_distribution  # noqa: E402
from yafs.placement import Placement  # noqa: E402
from yafs.population import Statical  # noqa: E402
from yafs.selection import Selection  # noqa: E402
from yafs.topology import Topology  # noqa: E402

logging.disable(logging.CRITICAL)


class ExpFloat(Distribution):
    """Unbiased exponential inter-arrival times (YAFS's own classes truncate to integers)."""

    def __init__(self, mean, seed):
        super().__init__(name="exp")
        self.mean = mean
        self.rnd = np.random.RandomState(seed)

    def next(self):
        return float(self.rnd.exponential(self.mean))


class OnEdge(Placement):
    def initial_allocation(self, sim, app_name):
        ids = sim.topology.find_IDs({"mytag": "edge"})
        app = sim.apps[app_name]
        for module in app.services:
            sim.deploy_module(app_name, module, app.services[module], ids)


class ShortestPath(Selection):
    def get_path(self, sim, app_name, message, topology_src, alloc_DES, alloc_module, traffic, from_des):
        best_path, best_des = [], []
        for des in alloc_module[app_name][message.dst]:
            best_path = [list(nx.shortest_path(sim.topology.G, source=topology_src, target=alloc_DES[des]))]
            best_des = [des]
        return best_path, best_des


def run(exp, p, seed, rho=None):
    c = p["experiments"][exp]
    lat_gw_edge = c.get("gw_edge_latency_ms", p["gw_edge_latency_ms"])
    topo = {"entity": [
        {"id": 0, "model": "sensors", "mytag": "sensors", "IPT": 1e12, "RAM": 1e6},
        {"id": 1, "model": "gateway", "mytag": "gateway", "IPT": 1e12, "RAM": 1e6},
        {"id": 2, "model": "edge", "mytag": "edge", "IPT": c["mips"] * 1e3, "RAM": 1e6},
    ], "link": [
        {"s": 0, "d": 1, "BW": 1e6, "PR": p["sensor_link_latency_ms"]},
        {"s": 1, "d": 2, "BW": c["bandwidth_bps"] / 1e9, "PR": lat_gw_edge},
    ]}
    t = Topology()
    t.load(topo)
    app = Application(name="crs")
    app.set_modules([{"Sensor": {"Type": Application.TYPE_SOURCE}},
                     {"Service": {"RAM": 1, "Type": Application.TYPE_MODULE}},
                     {"Actuator": {"Type": Application.TYPE_SINK}}])
    m_in = Message("M.IN", "Sensor", "Service", instructions=c["mi"] * 1e6, bytes=c["payload_bytes"] * 8)
    m_out = Message("M.OUT", "Service", "Actuator", instructions=0, bytes=p["response_bytes"] * 8)
    app.add_source_messages(m_in)
    app.add_service_module("Service", m_in, m_out, fractional_selectivity, threshold=1.0)

    pop = Statical("pop")
    pop.set_sink_control({"model": "sensors", "number": 1, "module": app.get_sink_modules()})
    if exp == "X1":
        dist = deterministic_distribution(name="det", time=c["interval_ms"])
    elif exp == "X2":
        lam_total = rho / (c["mi"] / c["mips"] * 1000.0)      # per ms
        dist = ExpFloat(c["sensors"] / lam_total, seed)
    else:
        tx = c["payload_bytes"] * 8 / c["bandwidth_bps"] * 1000.0
        lam_total = rho / tx
        dist = ExpFloat(c["sensors"] / lam_total, seed)
    pop.set_src_control({"model": "sensors", "number": c["sensors"], "message": app.get_message("M.IN"), "distribution": dist})

    out = tempfile.mkdtemp()
    s = Sim(t, default_results_path=out + "/trace")
    s.deploy_app2(app, OnEdge("edge"), pop, ShortestPath())
    s.run(c["horizon_s"] * 1000.0, show_progress_monitor=False)
    s.metrics.close()
    import pandas as pd
    df = pd.read_csv(out + "/trace.csv")
    emit = df[df["message"] == "M.IN"].groupby("id")["time_emit"].min()
    arrive = df[df["message"] == "M.OUT"].groupby("id")["time_in"].max()
    loop = (arrive - emit.reindex(arrive.index)).dropna()
    return float(loop.mean()), int(len(loop))


if __name__ == "__main__":
    params = json.load(open(sys.argv[1]))
    exp = sys.argv[2]
    reps = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    c = params["experiments"][exp]
    levels = [None] if exp == "X1" else c.get("rho", c.get("rho_link"))
    for level in levels:
        for r in range(reps if level is not None else 1):
            mean, n = run(exp, params, 1000 + r, level)
            print(json.dumps({"sim": "YAFS", "exp": exp, "rho": level, "rep": r, "loop_ms": mean, "n": n}), flush=True)
