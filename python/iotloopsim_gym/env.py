"""Gymnasium-compatible environment that drives a IoTLoopSim simulation over TCP.

The agent chooses, for every request of one application module, the compute node that serves it
(an offloading / load-balancing decision). The simulation pauses at each decision.

Works with or without the ``gymnasium`` package: if it is installed the class is a ``gymnasium.Env``
with proper spaces; otherwise it exposes the same reset/step API.
"""
from __future__ import annotations

import json
import os
import socket
import random
import subprocess
import threading
import time
from typing import Any

try:  # optional dependency
    import gymnasium as gym
    from gymnasium import spaces
    import numpy as np
    _Base = gym.Env
except ImportError:  # pragma: no cover
    gym = None
    spaces = None
    np = None
    _Base = object


class _Discrete:
    """Minimal stand-in for ``gymnasium.spaces.Discrete`` when gymnasium is not installed."""

    def __init__(self, n: int):
        self.n = n

    def sample(self) -> int:
        return random.randrange(self.n)

    def contains(self, x) -> bool:
        return isinstance(x, int) and 0 <= x < self.n


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class IoTLoopSimEnv(_Base):
    """IoTLoopSim environment.

    Args:
        scenario: path to a scenario YAML file.
        module: decision point as ``"application/module"``, e.g. ``"traffic/detect"``.
        jar: path to ``iotloopsim-all-<version>.jar``; started automatically on a free port.
        host, port: connect to an already running bridge instead of starting one.
        seed: default seed for :meth:`reset`.
    """

    metadata = {"render_modes": []}

    def __init__(self, scenario: str, module: str, jar: str | None = None, host: str = "127.0.0.1",
                 port: int | None = None, seed: int | None = None, java: str = "java"):
        self.scenario = os.path.abspath(scenario)
        self.module = module
        self.default_seed = seed
        self._proc = None
        if port is None:
            if jar is None:
                raise ValueError("give either jar= (to start the bridge) or port= (to connect to a running one)")
            port = _free_port()
            self._proc = subprocess.Popen([java, "-jar", jar, "gym", "--port", str(port)],
                                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            # wait for the "listening" line (the JVM may print other lines first), then keep draining stdout in the
            # background so that a full pipe can never block the simulator
            deadline = time.time() + 30
            while time.time() < deadline:
                line = self._proc.stdout.readline()
                if not line and self._proc.poll() is not None:
                    raise RuntimeError(f"IoTLoopSim bridge exited with code {self._proc.returncode}")
                if "listening" in line:
                    break
            threading.Thread(target=lambda: [None for _ in self._proc.stdout], daemon=True).start()
        self._sock = None
        for _ in range(50):
            try:
                self._sock = socket.create_connection((host, port))
                break
            except OSError:
                time.sleep(0.1)
        if self._sock is None:
            raise RuntimeError(f"cannot connect to IoTLoopSim bridge on {host}:{port}")
        self._file = self._sock.makefile("rw", encoding="utf-8")
        first = self._call({"cmd": "reset", "scenario": self.scenario, "module": self.module, "seed": seed})
        sp = self._call({"cmd": "spaces"})
        self.n_actions = sp["n_actions"]
        self.obs_dim = sp["obs_dim"]
        self.node_names = sp["nodes"]
        self._pending_first = first
        if spaces is not None:
            self.action_space = spaces.Discrete(self.n_actions)
            self.observation_space = spaces.Box(low=-1.0, high=np.inf, shape=(self.obs_dim,), dtype=np.float64)
        else:
            self.action_space = _Discrete(self.n_actions)
            self.observation_space = None

    # ------------------------------------------------------------------ protocol
    def _call(self, msg: dict) -> dict:
        self._file.write(json.dumps(msg) + "\n")
        self._file.flush()
        resp = json.loads(self._file.readline())
        if "error" in resp:
            raise RuntimeError(resp["error"])
        return resp

    def _obs(self, r: dict):
        return np.asarray(r["obs"], dtype=np.float64) if np is not None else r["obs"]

    # ------------------------------------------------------------------ gym API
    def reset(self, *, seed: int | None = None, options: dict | None = None):
        if self._pending_first is not None and seed is None:
            r, self._pending_first = self._pending_first, None
        else:
            self._pending_first = None
            r = self._call({"cmd": "reset", "scenario": self.scenario, "module": self.module,
                            "seed": seed if seed is not None else self.default_seed})
        return self._obs(r), r["info"]

    def step(self, action: int):
        r = self._call({"cmd": "step", "action": int(action)})
        return self._obs(r), float(r["reward"]), bool(r["done"]), False, r["info"]

    def report(self) -> dict:
        return self._call({"cmd": "report"})["report"]

    def close(self):
        try:
            if self._sock is not None:
                self._file.write(json.dumps({"cmd": "close"}) + "\n")
                self._file.flush()
                self._sock.close()
        finally:
            if self._proc is not None:
                self._proc.terminate()
                self._proc.wait(timeout=5)
