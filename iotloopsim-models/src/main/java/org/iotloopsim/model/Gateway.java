package org.iotloopsim.model;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.iotloopsim.core.Simulation;

/**
 * Gateway / base station: radio access point for devices and entry point to the wired network.
 * Supports a set of access technologies; at QUEUE fidelity the radio channel is a shared medium.
 */
public final class Gateway extends NetNode {
    private final IoTWorld world;
    private final Set<String> techs;
    private final double rangeOverride;
    private final Set<IoTDevice> attached = new LinkedHashSet<>();
    private final Map<String, Long> channelBusy = new HashMap<>();
    private final Map<String, Long> downlinkFree = new HashMap<>();
    private final long forwardNs;
    // security state
    private double blackholeDrop;     // fraction of packets silently dropped (compromised gateway)
    private long idsDelayNs;          // inspection delay per packet when an IDS runs here
    private double idsJoulesPerPacket;

    /**
     * Creates a gateway supporting the named access technologies; {@code rangeOverride} in m replaces each
     * technology's range when positive, and {@code forwardNs} is the per-packet forwarding delay in ns.
     */
    public Gateway(IoTWorld world, String name, Position pos, Set<String> techs, double rangeOverride, long forwardNs) {
        super(world.sim(), name, pos);
        this.world = world; this.techs = techs; this.rangeOverride = rangeOverride; this.forwardNs = forwardNs;
    }

    /** Returns the names of supported access technologies. */
    public Set<String> techs() { return techs; }
    /** Returns the live set of currently attached devices. */
    public Set<IoTDevice> attached() { return attached; }
    /** Returns true if technology {@code t} is supported. */
    public boolean supports(AccessTechnology t) { return techs.contains(t.name()); }
    /**
     * Returns the radio range in m for technology {@code t}: the override if positive, otherwise the technology
     * default.
     */
    public double range(AccessTechnology t) { return rangeOverride > 0 ? rangeOverride : t.rangeM(); }
    /** Returns the per-packet forwarding delay in ns, including any IDS inspection delay. */
    public long forwardNs() { return forwardNs + idsDelayNs; }

    void attach(IoTDevice d) { attached.add(d); }
    void detach(IoTDevice d) { attached.remove(d); }

    /** Reserves the shared channel (QUEUE fidelity only); returns the transmission start time. */
    long reserveChannel(AccessTechnology t, long earliest, long airtime) {
        if (world.radioFidelity() != Link.Fidelity.QUEUE) return earliest;
        long start = Math.max(earliest, channelBusy.getOrDefault(t.name(), 0L));
        channelBusy.put(t.name(), start + airtime);
        return start;
    }

    // security hooks
    /**
     * Makes the gateway silently drop the given fraction (0..1) of packets, modelling a compromised gateway; 0
     * disables.
     */
    public void setBlackhole(double dropFraction) { this.blackholeDrop = dropFraction; }
    /** Returns the blackhole drop fraction (0..1). */
    public double blackhole() { return blackholeDrop; }
    /**
     * Enables an intrusion-detection system adding {@code delayNs} ns of delay and {@code joulesPerPacket} J of
     * energy per packet; zeros disable it.
     */
    public void setIds(long delayNs, double joulesPerPacket) { this.idsDelayNs = delayNs; this.idsJoulesPerPacket = joulesPerPacket; }
    /** Returns true if an IDS delay or energy cost is configured. */
    public boolean hasIds() { return idsDelayNs > 0 || idsJoulesPerPacket > 0; }

    /** Per-packet processing at the gateway: IDS energy and blackhole drops. Returns a drop reason or null. */
    String process(Message m) {
        if (idsJoulesPerPacket > 0) world.energy().charge(this, "security", idsJoulesPerPacket);
        if (blackholeDrop > 0 && sim.stream("blackhole:" + name()).nextDouble() < blackholeDrop) return "blackhole";
        return world.security().filter(this, m);
    }

    @Override protected void onFail(String reason) {
        world.lifecycle().node(this, "UP", "DOWN", reason);
        for (IoTDevice d : attached.toArray(new IoTDevice[0])) world.attachment().reattach(d, "gateway-failure");
        world.network().topologyChanged();
    }

    @Override protected void onRepair() {
        world.lifecycle().node(this, "DOWN", "UP", "repaired");
        world.network().topologyChanged();
        world.attachment().rescanDisconnected();
    }

    /**
     * Reserves the gateway's downlink for one technology for {@code occupancyNs} (airtime divided by the downlink duty
     * cycle) starting no earlier than {@code nowNs}; downlink transfers of one technology are served first come, first
     * served. Returns the time at which the reservation ends.
     */
    public long reserveDownlink(AccessTechnology t, long nowNs, long occupancyNs) {
        long start = Math.max(nowNs, downlinkFree.getOrDefault(t.name(), 0L));
        long end = start + occupancyNs;
        downlinkFree.put(t.name(), end);
        return end;
    }

    int priority() { return Simulation.PRIO_HIGH; }
}
