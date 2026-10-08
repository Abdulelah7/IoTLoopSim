"""Compare a random agent with a greedy latency-and-load agent on a IoTLoopSim decision point.

usage: python compare_agents.py ../../dist/iotloopsim-all-0.1.0.jar ../../scenarios/quickstart.yaml hvac/control
"""
import random
import sys

sys.path.insert(0, __file__.rsplit("/", 2)[0])
from iotloopsim_gym import IoTLoopSimEnv  # noqa: E402

FEATURES = 4  # per node: up, utilization, instance_load, latency_100ms


def greedy(obs, n_nodes):
    best, best_score = 0, float("inf")
    for i in range(n_nodes):
        up, util, load, lat = obs[i * FEATURES:(i + 1) * FEATURES]
        if up < 1 or lat < 0:
            continue
        score = lat * 100 + (load if load >= 0 else 5.0) * 10  # ms of latency + queue penalty; launching costs a cold start
        if score < best_score:
            best, best_score = i, score
    return best


def run(env, policy, seed):
    obs, info = env.reset(seed=seed)
    total, steps, done = 0.0, 0, False
    while not done:
        a = policy(obs)
        obs, r, done, _, info = env.step(a)
        total += r
        steps += 1
    rep = info.get("report", {})
    return total, steps, rep


def main():
    jar, scenario, module = sys.argv[1], sys.argv[2], sys.argv[3]
    env = IoTLoopSimEnv(scenario, module, jar=jar, seed=1)
    print("decision nodes:", env.node_names)
    rng = random.Random(0)
    for name, pol in [("random", lambda o: rng.randrange(env.n_actions)), ("greedy", lambda o: greedy(o, env.n_actions))]:
        total, steps, rep = run(env, pol, seed=1)
        print(f"{name:7s} decisions={steps:6d} return={total:9.1f} "
              f"success={rep.get('loop.success_ratio', float('nan')):.4f} "
              f"latency_ms={rep.get('loop.latency_ms.mean', float('nan')):.1f} "
              f"deadline_miss={rep.get('loop.deadline_miss_ratio', float('nan')):.4f}")
    env.close()


if __name__ == "__main__":
    main()
