package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.iotloopsim.core.MetricsRegistry;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.core.Simulation;
import org.iotloopsim.model.policy.BuiltinPolicies;
import org.iotloopsim.model.policy.InstanceSelector;
import org.iotloopsim.model.policy.MigrationPolicy;
import org.iotloopsim.model.policy.PlacementPolicy;
import org.iotloopsim.model.policy.PolicyRegistry;
import org.iotloopsim.model.policy.RecoveryPolicy;
import org.iotloopsim.model.policy.ScalingPolicy;

/**
 * The simulated IoT system: devices (perception), gateways and links (network), compute nodes (processing) and
 * applications, plus the lifecycle, mobility, energy/carbon, cost, fault and security models. Build it through the
 * Java API or from a scenario file, call {@link #run()} and read {@link #report()}.
 */
public final class IoTWorld {
    private final Simulation sim;
    private final Network network;
    private final Attachment attachment;
    private final EnergyLedger energy;
    private final CarbonModel carbon = new CarbonModel();
    private final CostLedger cost = new CostLedger();
    private final LifecycleLog lifecycle;
    private final AppRuntime runtime;
    private final MobilityManager mobility;
    private final FaultInjector faults;
    private final SecurityManager security;
    private final UpdateManager updates;

    private final List<IoTDevice> devices = new ArrayList<>();
    private final List<IoTDevice> attackers = new ArrayList<>();
    private final List<Gateway> gateways = new ArrayList<>();
    private final List<ComputeNode> nodes = new ArrayList<>();
    private final Map<String, Application> apps = new LinkedHashMap<>();
    private final List<Runnable> scheduled = new ArrayList<>();

    private Link.Fidelity radioFidelity = Link.Fidelity.ANALYTICAL;
    private SecurityProfile channelSecurity = SecurityProfile.NONE;
    private Protocol internalProtocol = Protocol.preset("tcp");
    private PlacementPolicy placement;
    private InstanceSelector selector;
    private ScalingPolicy scaling;
    private MigrationPolicy migration;
    private RecoveryPolicy recovery;
    private long deviceStart;
    private boolean started;
    private int instanceIds;

    int nextInstanceId() { return instanceIds++; }

    /**
     * Creates an empty world with its own {@link Simulation} seeded with {@code seed} and default policies:
     * edge-first placement, nearest-least-loaded selection, no scaling, no migration and replace-on-failure
     * recovery.
     */
    public IoTWorld(long seed) {
        this.sim = new Simulation(seed);
        this.network = new Network(this);
        this.attachment = new Attachment(this);
        this.energy = new EnergyLedger(this);
        this.lifecycle = new LifecycleLog(sim);
        this.runtime = new AppRuntime(this);
        this.mobility = new MobilityManager(this);
        this.faults = new FaultInjector(this);
        this.security = new SecurityManager(this);
        this.updates = new UpdateManager(this);
        this.placement = PolicyRegistry.create(PolicyRegistry.Kind.PLACEMENT, "edge-first", null);
        this.selector = PolicyRegistry.create(PolicyRegistry.Kind.SELECTOR, "nearest-least-loaded", null);
        this.scaling = new BuiltinPolicies.NoScaling();
        this.migration = PolicyRegistry.create(PolicyRegistry.Kind.MIGRATION, "none", null);
        this.recovery = PolicyRegistry.create(PolicyRegistry.Kind.RECOVERY, "replace", null);
    }

    // ------------------------------------------------------------------ accessors
    /** Returns the underlying simulation kernel. */
    public Simulation sim() { return sim; }
    /** Returns the network transport and routing model. */
    public Network network() { return network; }
    /** Returns the device-to-gateway attachment manager. */
    public Attachment attachment() { return attachment; }
    /** Returns the energy and carbon ledger. */
    public EnergyLedger energy() { return energy; }
    /** Returns the carbon-intensity model used by the energy ledger. */
    public CarbonModel carbon() { return carbon; }
    /** Returns the monetary cost ledger. */
    public CostLedger cost() { return cost; }
    /** Returns the lifecycle transition log. */
    public LifecycleLog lifecycle() { return lifecycle; }
    /** Returns the application runtime (deployment, request routing, scaling, migration). */
    public AppRuntime runtime() { return runtime; }
    /** Returns the mobility manager. */
    public MobilityManager mobility() { return mobility; }
    /** Returns the fault injector. */
    public FaultInjector faults() { return faults; }
    /** Returns the security model (attacks, IDS). */
    public SecurityManager security() { return security; }
    /** Returns the OTA firmware update manager. */
    public UpdateManager updates() { return updates; }
    /** Returns an unmodifiable list of regular devices (attackers excluded) in creation order. */
    public List<IoTDevice> devices() { return Collections.unmodifiableList(devices); }
    /** Returns an unmodifiable list of gateways in creation order. */
    public List<Gateway> gateways() { return Collections.unmodifiableList(gateways); }
    /** Returns an unmodifiable list of compute nodes in creation order. */
    public List<ComputeNode> computeNodes() { return Collections.unmodifiableList(nodes); }
    /** Returns a new list of all applications in insertion order. */
    public List<Application> applications() { return new ArrayList<>(apps.values()); }
    /** Returns the application named {@code name}, or null. */
    public Application application(String name) { return apps.get(name); }
    /**
     * Returns the radio fidelity; at {@code QUEUE} the gateway channel is a shared medium, otherwise transmissions
     * do not contend.
     */
    public Link.Fidelity radioFidelity() { return radioFidelity; }
    /** Returns the channel security profile applied to every message (default {@link SecurityProfile#NONE}). */
    public SecurityProfile channelSecurity() { return channelSecurity; }
    /** Returns the protocol used between modules on compute nodes (default {@code tcp}). */
    public Protocol internalProtocol() { return internalProtocol; }
    /** Returns the active placement policy. */
    public PlacementPolicy placement() { return placement; }
    /** Returns the active instance-selection policy. */
    public InstanceSelector selector() { return selector; }
    /** Returns the active autoscaling policy. */
    public ScalingPolicy scaling() { return scaling; }
    /** Returns the active migration policy. */
    public MigrationPolicy migration() { return migration; }
    /** Returns the active failure-recovery policy. */
    public RecoveryPolicy recovery() { return recovery; }

    /** Sets the radio fidelity ({@code ANALYTICAL} or {@code QUEUE}); call before {@link #start()}. */
    public void setRadioFidelity(Link.Fidelity f) { this.radioFidelity = f; }
    /** Sets the channel security profile applied to every message. */
    public void setChannelSecurity(SecurityProfile p) { this.channelSecurity = p; }
    /** Sets the protocol used for module-to-module messages. */
    public void setInternalProtocol(Protocol p) { this.internalProtocol = p; }
    /** Replaces the placement policy. */
    public void setPlacement(PlacementPolicy p) { this.placement = p; }
    /** Replaces the instance-selection policy. */
    public void setSelector(InstanceSelector s) { this.selector = s; }
    /** Replaces the autoscaling policy. */
    public void setScaling(ScalingPolicy s) { this.scaling = s; }
    /** Replaces the migration policy. */
    public void setMigration(MigrationPolicy m) { this.migration = m; }
    /** Replaces the failure-recovery policy. */
    public void setRecovery(RecoveryPolicy r) { this.recovery = r; }
    /** Delay before devices start sensing (lets services finish their cold start). */
    public void setDeviceStart(long ns) { this.deviceStart = ns; }

    // ------------------------------------------------------------------ building
    /**
     * Adds a gateway with the default forwarding delay of 100 us; {@code rangeOverride} in m replaces technology
     * ranges when positive.
     */
    public Gateway addGateway(String name, Position pos, Set<String> techs, double rangeOverride) {
        return addGateway(name, pos, techs, rangeOverride, SimTime.us(100));
    }

    /** Gateway with an explicit per-packet forwarding delay. */
    public Gateway addGateway(String name, Position pos, Set<String> techs, double rangeOverride, long forwardNs) {
        Gateway g = new Gateway(this, name, pos, techs, rangeOverride, forwardNs);
        gateways.add(g);
        return g;
    }

    /**
     * Creates and adds a compute node; see {@link ComputeNode#ComputeNode(IoTWorld, String, Position, Tier, int,
     * double, int, double, double, boolean)} for parameter units.
     */
    public ComputeNode addComputeNode(String name, Position pos, Tier tier, int cores, double mipsPerCore, int ramMB,
                                      double idleW, double maxW, boolean registry) {
        ComputeNode n = new ComputeNode(this, name, pos, tier, cores, mipsPerCore, ramMB, idleW, maxW, registry);
        nodes.add(n);
        return n;
    }

    /**
     * Creates a link and adds it to the network; see {@link Link#Link(Simulation, String, NetNode, NetNode,
     * double, long, double, long, double, double, Link.Fidelity)} for parameter units.
     */
    public Link addLink(String name, NetNode a, NetNode b, double bandwidthBps, long latencyNs, double loss, long bufferBytes,
                        double joulesPerByte, double costPerGB, Link.Fidelity fidelity) {
        return network.addLink(new Link(sim, name, a, b, bandwidthBps, latencyNs, loss, bufferBytes, joulesPerByte, costPerGB, fidelity));
    }

    /**
     * Creates and adds a device; see {@link IoTDevice#IoTDevice(IoTWorld, String, String, Position,
     * AccessTechnology, Protocol, Battery, double, String)} for parameter meanings.
     */
    public IoTDevice addDevice(String name, String group, Position pos, AccessTechnology tech, Protocol protocol, Battery battery,
                               double baselineW, String firmware) {
        IoTDevice d = new IoTDevice(this, name, group, pos, tech, protocol, battery, baselineW, firmware);
        devices.add(d);
        return d;
    }

    void registerAttacker(IoTDevice d) { attackers.add(d); }

    /** Adds (or replaces by name) an application; it is deployed when the world starts. Returns {@code a}. */
    public Application addApplication(Application a) { apps.put(a.name(), a); return a; }

    /** Runs {@code action} at absolute time {@code at} (e.g. retire an application, decommission devices). */
    public void at(long at, Runnable action) { scheduled.add(() -> sim.scheduleAt(at, Simulation.PRIO_NORMAL, "scheduled", action)); }

    /** True if any compute node is configured as an image registry (up or not). */
    public boolean hasRegistries() { for (ComputeNode c : nodes) if (c.isRegistry()) return true; return false; }

    /** Nearest up and reachable image registry for {@code n}, or {@code null} if none. */
    public ComputeNode registryFor(NetNode n) {
        ComputeNode best = null;
        long bl = Long.MAX_VALUE;
        for (ComputeNode c : nodes) {
            if (!c.isRegistry() || !c.isUp()) continue;
            if (n == null) return c;
            long l = c == n ? 0 : network.estimateLatency(n, c, 1000);
            if (l < bl) { bl = l; best = c; }
        }
        return best;
    }

    // ------------------------------------------------------------------ running
    /**
     * Deploys applications, schedules device activation after the device-start delay, and starts management,
     * mobility, faults, security, updates and actions registered with {@link #at(long, Runnable)}. Idempotent;
     * does not run the event loop.
     */
    public void start() {
        if (started) return;
        started = true;
        for (Application a : apps.values()) runtime.deploy(a);
        sim.schedule(deviceStart, Simulation.PRIO_SYSTEM, "devices-start", () -> { for (IoTDevice d : devices) d.activate(); });
        runtime.startManagement();
        mobility.start();
        faults.start();
        security.start();
        updates.start();
        for (Runnable r : scheduled) r.run();
        sim.onEnd(() -> { for (IoTDevice d : devices) d.accountBaseline(); for (IoTDevice d : attackers) d.accountBaseline(); });
    }

    /** Sets the end time to {@code duration} ns, starts the world, runs the event loop, finalises the accounting and returns {@link #report()}. */
    public Map<String, Double> run(long duration) {
        sim.setEndTime(duration);
        start();
        sim.run();
        return report();
    }

    /**
     * Starts the world and runs until no events remain (or the end time set on the simulation), then returns
     * {@link #report()}.
     */
    public Map<String, Double> run() { start(); sim.run(); return report(); }

    // ------------------------------------------------------------------ reporting
    /**
     * Returns all metrics plus derived values (success, deadline-miss and delivery ratios, energy in J and kWh,
     * carbon in g, cost, availability, utilisation, battery state, lifecycle counts); can be called repeatedly.
     * Ratios with a zero denominator are NaN.
     */
    public Map<String, Double> report() {
        MetricsRegistry mx = sim.metrics();
        Map<String, Double> out = new LinkedHashMap<>(mx.snapshot());
        double gen = mx.counter("loop.generated"), done = mx.counter("loop.completed");
        out.put("loop.success_ratio", gen == 0 ? Double.NaN : done / gen);
        out.put("loop.deadline_miss_ratio", done == 0 ? Double.NaN : mx.counter("loop.deadline_miss") / done);
        double sent = mx.counter("net.messages.sent");
        out.put("net.pdr", sent == 0 ? Double.NaN : mx.counter("net.messages.delivered") / sent);
        energy.byLayer().forEach((k, v) -> out.put("energy." + k + "_J", v));
        energy.byCategory().forEach((k, v) -> out.put("energy.category." + k + "_J", v));
        out.put("energy.total_J", energy.total());
        out.put("energy.total_kWh", energy.total() / 3.6e6);
        energy.carbonByLayer().forEach((k, v) -> out.put("carbon." + k + "_g", v));
        out.put("carbon.total_g", energy.carbonTotal());
        // energy cost is derived at report time and never written back, so report() can be called repeatedly
        double energyCost = cost.energyPrice() > 0 ? energy.total() / 3.6e6 * cost.energyPrice() : 0;
        cost.items().forEach((k, v) -> out.put("cost." + k, v));
        if (energyCost > 0) out.merge("cost.energy", energyCost, Double::sum);
        out.put("cost.total", cost.total() + energyCost);
        out.put("availability.devices", devices.stream().mapToDouble(NetNode::availability).average().orElse(1));
        out.put("availability.gateways", gateways.stream().mapToDouble(NetNode::availability).average().orElse(1));
        out.put("availability.nodes", nodes.stream().mapToDouble(NetNode::availability).average().orElse(1));
        out.put("availability.links", network.links().stream().mapToDouble(Link::availability).average().orElse(1));
        for (Tier t : Tier.values()) {
            double u = nodes.stream().filter(n -> n.tier() == t).mapToDouble(ComputeNode::meanUtilization).average().orElse(Double.NaN);
            if (!Double.isNaN(u)) out.put("utilization." + t.name().toLowerCase(), u);
        }
        List<IoTDevice> battery = devices.stream().filter(d -> d.battery() != null).toList();
        if (!battery.isEmpty()) {
            out.put("battery.mean_remaining", battery.stream().mapToDouble(d -> d.battery().fraction()).average().orElse(1));
            out.put("battery.min_remaining", battery.stream().mapToDouble(d -> d.battery().fraction()).min().orElse(1));
        }
        out.put("devices.depleted", (double) devices.stream().filter(d -> d.state() == DeviceState.DEPLETED).count());
        out.put("devices.failed", (double) devices.stream().filter(d -> d.state() == DeviceState.FAILED).count());
        lifecycle.counts().forEach((k, v) -> out.put("lifecycle." + k, (double) v));
        out.put("sim.events", (double) sim.eventsProcessed());
        out.put("sim.time_s", sim.nowSeconds());
        return out;
    }
}
