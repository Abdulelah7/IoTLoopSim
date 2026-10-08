IoTLoopSim
==========

IoTLoopSim is a discrete-event simulator for IoT systems. A single scenario
file describes the whole system: sensors and actuators, the wireless access
network, gateways and backhaul, and the edge, fog and cloud nodes that run
the application. The simulator also models what happens to the system over
time: deployment, scaling, migration, firmware updates, failures and attacks.

Version 0.1.0 (research prototype). Licensed under Apache 2.0.


Requirements
------------

JDK 21 or newer. No other libraries are needed to run it. The build script
downloads JUnit 4 for the tests if it is not already in lib/.


Building and running
--------------------

    ./build.sh
    java -jar dist/iotloopsim-all-0.1.0.jar run scenarios/quickstart.yaml --reps 10
    java -jar dist/iotloopsim-all-0.1.0.jar run scenarios/smart-city.yaml --reps 10 --out results/smart-city

Maven also works (mvn package) if you have internet access.

Each run writes the following files to the output folder:

    summary.csv              mean, standard deviation and 95% CI per metric
    runs.csv                 one row per replication, with a result hash
    provenance.json          versions, seeds, scenario hash, metric definitions
    lifecycle_rep0.csv       every lifecycle transition in replication 0
    scenario.resolved.json   the scenario after defaults were applied


Commands
--------

    iotloopsim run <scenario.yaml> [--reps N] [--seed S] [--threads T] [--out DIR] [--set path=value ...]
    iotloopsim compare-fidelity <scenario.yaml> [--reps N]
    iotloopsim validate <scenario.yaml>
    iotloopsim policies
    iotloopsim gym [--port 5555]

--set overrides any scenario value, which is useful for parameter sweeps:

    --set policies.placement=cloud-only --set devices.0.count=500 --set duration=2h

compare-fidelity runs the scenario with analytical links and with queued
links and reports the difference. validate only parses and checks the
scenario. Unknown keys are rejected, with a suggestion for the closest valid
key.


What is modelled
----------------

Devices: sensors and actuators, batteries and energy use, mobility (random
waypoint or traces) and handover.

Access technologies: Wi-Fi, BLE, 802.15.4/Zigbee, LoRaWAN (with duty cycle),
NB-IoT, LTE, 5G and Ethernet.

Protocols: MQTT QoS 0/1/2, CoAP CON/NON, HTTP and AMQP, with retransmission.

Infrastructure: gateways, multi-hop wired routing with failover, and edge,
fog and cloud compute nodes. Applications are chains of modules that end
with an actuator.

Lifecycle: deployment, image pull and cold start, autoscaling, scale to
zero, session migration, rolling updates, OTA firmware campaigns (batch or
canary, with rollback) and decommissioning.

Failures: independent failures and correlated ones (by zone, backhaul link
or firmware version).

Security: DTLS/TLS overhead, DoS flooding, blackhole gateways and an IDS.
Their cost shows up in the latency and energy results.

Other: carbon accounting with hourly profiles, a cost model, and a bridge
for reinforcement-learning agents in Python.


Bundled scenarios
-----------------

    scenarios/quickstart.yaml        small smart building
    scenarios/smart-city.yaml        174 devices over 1 hour, uses most features
    scenarios/lorawan-ota.yaml       LoRaWAN farm over one day with a canary OTA campaign
    scenarios/lorawan-battery.yaml   battery lifetime per LoRaWAN data rate


Java API
--------

    IoTWorld w = new IoTWorld(seed);
    Gateway gw = w.addGateway("gw", new Position(0, 0), Set.of("wifi"), 0);
    ComputeNode edge = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 4, 2000, 8192, 20, 60, false);
    w.addLink("gw-edge", gw, edge, 1e9, SimTime.ms(1), 0, 4 << 20, 1e-9, 0, Link.Fidelity.QUEUE);
    // add devices, applications and policies, then:
    Map<String, Double> report = w.run(SimTime.MIN * 10);

A full example is in examples/java/QuickStartApi.java.


Adding your own policies
------------------------

Placement, routing, scaling, migration, recovery and OTA rollout are all
policies. To add one, implement the interface, register it in a
PolicyProvider, and list the provider in

    META-INF/services/org.iotloopsim.model.policy.PolicyProvider

Scenarios can then refer to the policy by name. examples/custom-policy/
builds a carbon-aware selector as a separate JAR.


Python / reinforcement learning
-------------------------------

"iotloopsim gym" lets a Python agent make the routing or offloading decision
for a module. It works with Gymnasium if that package is installed.

    from iotloopsim_gym import IoTLoopSimEnv
    env = IoTLoopSimEnv("scenarios/smart-city.yaml", "traffic/detect",
                        jar="dist/iotloopsim-all-0.1.0.jar")
    obs, info = env.reset(seed=1)
    obs, reward, done, truncated, info = env.step(env.action_space.sample())

python/examples/compare_agents.py compares a random agent with a greedy one.


Validation and limitations
--------------------------

The models were checked against closed-form queueing, reliability and
renewal results, against YAFS and iFogSim2 on a shared reference system,
against published container-migration testbed measurements, and against a
measured LoRaWAN energy model (Casals et al., 2017). The scripts and data
are in validation/ and results/validation/.

The simulator has not yet been calibrated on its own physical testbed.
Packet-level co-simulation is defined as an interface (PacketLevelAdapter),
but no ns-3 or OMNeT++ adapter is included yet.


Citation and contact
--------------------

If you use IoTLoopSim in your research, please cite it using CITATION.cff.

Abdulelah Alwabel
Prince Sattam bin Abdulaziz University
a.alwabel@psau.edu.sa
