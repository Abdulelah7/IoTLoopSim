package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An edge, fog or cloud server. Hosts service instances within its core and memory capacity, draws power linearly
 * between idle and maximum according to core utilisation, and caches container images.
 */
public final class ComputeNode extends NetNode {
    private final IoTWorld world;
    private final Tier tier;
    private final int cores;
    private final double mipsPerCore;
    private final int ramMB;
    private final double idleW, maxW;
    private final boolean registry;
    private final List<ServiceInstance> instances = new ArrayList<>();
    private final Set<String> imageCache = new HashSet<>();
    private int allocCores, allocRam, busyCores;
    private long lastPower;
    private double busyCoreNs;

    /**
     * Creates a server with {@code cores} cores of {@code mipsPerCore} MIPS, {@code ramMB} MB of RAM and power
     * linear between {@code idleW} and {@code maxW} (W); a {@code registry} node holds every image. Tags the node
     * with its tier and registers end-of-run power accounting.
     */
    public ComputeNode(IoTWorld world, String name, Position pos, Tier tier, int cores, double mipsPerCore, int ramMB,
                       double idleW, double maxW, boolean registry) {
        super(world.sim(), name, pos);
        this.world = world; this.tier = tier; this.cores = cores; this.mipsPerCore = mipsPerCore; this.ramMB = ramMB;
        this.idleW = idleW; this.maxW = maxW; this.registry = registry;
        tag("tier", tier.name().toLowerCase());
        world.sim().onEnd(this::accountPower);
    }

    /** Returns the tier (edge, fog or cloud). */
    public Tier tier() { return tier; }
    /** Returns the total number of cores. */
    public int cores() { return cores; }
    /** Returns the speed of one core in MIPS. */
    public double mipsPerCore() { return mipsPerCore; }
    /** Returns the total RAM in MB. */
    public int ramMB() { return ramMB; }
    /** Returns true if this node is an image registry (has every image locally). */
    public boolean isRegistry() { return registry; }
    /** Returns the live list of instances allocated on this node. */
    public List<ServiceInstance> instances() { return instances; }
    /** Returns cores not allocated to instances (allocation, not current load). */
    public int freeCores() { return cores - allocCores; }
    /** Returns RAM in MB not allocated to instances. */
    public int freeRam() { return ramMB - allocRam; }
    /** Returns the number of cores currently executing work. */
    public int busyCores() { return busyCores; }
    /** Returns the instantaneous core utilisation (busy cores / cores), 0 if the node has no cores. */
    public double utilization() { return cores == 0 ? 0 : busyCores / (double) cores; }
    /** Returns true if the named image is cached here or this node is a registry. */
    public boolean hasImage(String image) { return registry || imageCache.contains(image); }
    void cacheImage(String image) { imageCache.add(image); }

    /** Mean utilisation over the run so far. */
    public double meanUtilization() {
        long now = sim.now();
        return now == 0 || cores == 0 ? 0 : (busyCoreNs + busyCores * (double) (now - lastPower)) / (cores * (double) now);
    }

    /** Returns true if the node is up and has enough unallocated cores and RAM for one instance of module {@code m}. */
    public boolean canHost(AppModule m) { return isUp() && freeCores() >= m.cores() && freeRam() >= m.ramMB(); }

    void allocate(ServiceInstance i) { instances.add(i); allocCores += i.cores(); allocRam += i.module().ramMB(); }
    void release(ServiceInstance i) { if (instances.remove(i)) { allocCores -= i.cores(); allocRam -= i.module().ramMB(); } }

    void coreBusy(int delta) { accountPower(); busyCores += delta; }

    void accountPower() {
        long now = sim.now();
        if (now <= lastPower) return;
        long dt = now - lastPower;
        busyCoreNs += busyCores * (double) dt;
        if (isUp()) {
            double p = idleW + (maxW - idleW) * utilization();
            world.energy().charge(this, "compute", p * dt / 1e9, lastPower);
        }
        lastPower = now;
    }

    @Override protected void onFail(String reason) {
        accountPower();
        world.lifecycle().node(this, "UP", "DOWN", reason);
        List<ServiceInstance> lost = new ArrayList<>(instances);
        for (ServiceInstance i : lost) i.kill("node-failure");
        world.network().topologyChanged();
        world.runtime().onNodeFailed(this, lost);
    }

    @Override protected void onRepair() {
        lastPower = sim.now();
        world.lifecycle().node(this, "DOWN", "UP", "repaired");
        world.network().topologyChanged();
        world.runtime().onNodeRepaired(this);
    }
}
