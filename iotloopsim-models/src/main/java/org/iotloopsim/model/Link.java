package org.iotloopsim.model;

import org.iotloopsim.core.Entity;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.core.Simulation;

/**
 * A wired link (backhaul, LAN, WAN) between two network nodes, simulated at one of two fidelity levels.
 * <ul>
 *   <li>{@link Fidelity#ANALYTICAL} (L0): delay = latency + size / (bandwidth shared equally among active transfers).</li>
 *   <li>{@link Fidelity#QUEUE} (L1): FIFO serialisation with a finite buffer; overflow drops packets.</li>
 * </ul>
 * L2 (packet-level co-simulation) is reached through {@link PacketLevelAdapter}.
 */
public final class Link extends Entity {
    /**
     * Simulation fidelity of a link: {@code ANALYTICAL} (L0, flow sharing), {@code QUEUE} (L1, FIFO with finite
     * buffer) or {@code PACKET} (L2, delegated to a {@link PacketLevelAdapter}; falls back to {@code QUEUE}
     * without one).
     */
    public enum Fidelity { ANALYTICAL, QUEUE, PACKET;
        /**
         * Parses L0/ANALYTICAL/FLOW, L1/QUEUE or L2/PACKET (case-insensitive); throws {@link
         * IllegalArgumentException} otherwise.
         */
        public static Fidelity parse(String s) {
            return switch (s.trim().toUpperCase()) {
                case "L0", "ANALYTICAL", "FLOW" -> ANALYTICAL;
                case "L1", "QUEUE" -> QUEUE;
                case "L2", "PACKET" -> PACKET;
                default -> throw new IllegalArgumentException("unknown fidelity " + s);
            };
        }
    }

    /**
     * Completion callback of a transfer; called exactly once with {@code delivered = true} and a null reason, or
     * {@code false} and a drop reason such as {@code loss}, {@code link-down}, {@code queue-overflow} or {@code
     * no-route}.
     */
    public interface Callback { void done(boolean delivered, String reason); }

    private final NetNode a, b;
    private final double bandwidthBps;
    private final long latencyNs;
    private final double loss;
    private final long bufferBytes;
    private final double joulesPerByte;
    private final double costPerGB;
    private Fidelity fidelity;
    private boolean up = true;
    private long downSince, totalDown;
    // per-direction state (links are full duplex): index 0 = a→b, 1 = b→a
    private final int[] active = new int[2];          // L0
    private final long[] busyUntil = new long[2];     // L1
    private final long[] queuedBytes = new long[2];   // L1
    // statistics
    private long bytesCarried, drops;

    /**
     * Creates an up, full-duplex link between {@code a} and {@code b} with per-direction bandwidth in bit/s,
     * propagation latency in ns, loss probability (0..1) per transfer, per-direction buffer in bytes (0 =
     * unlimited), energy in J/byte and cost per GB.
     */
    public Link(Simulation sim, String name, NetNode a, NetNode b, double bandwidthBps, long latencyNs, double loss,
                long bufferBytes, double joulesPerByte, double costPerGB, Fidelity fidelity) {
        super(sim, name);
        this.a = a; this.b = b;
        this.bandwidthBps = bandwidthBps; this.latencyNs = latencyNs; this.loss = loss;
        this.bufferBytes = bufferBytes; this.joulesPerByte = joulesPerByte; this.costPerGB = costPerGB;
        this.fidelity = fidelity;
    }

    /** Returns the first endpoint. */
    public NetNode a() { return a; }
    /** Returns the second endpoint. */
    public NetNode b() { return b; }
    /** Returns the endpoint opposite {@code n} (returns {@link #a()} for any node other than {@code a}). */
    public NetNode other(NetNode n) { return n == a ? b : a; }
    /** Returns the per-direction bandwidth in bit/s. */
    public double bandwidthBps() { return bandwidthBps; }
    /** Returns the one-way propagation latency in ns. */
    public long latencyNs() { return latencyNs; }
    /** Returns the fidelity level used for new transfers. */
    public Fidelity fidelity() { return fidelity; }
    /** Changes the fidelity level for subsequent transfers. */
    public void setFidelity(Fidelity f) { this.fidelity = f; }
    /** Returns the transfer energy in J per byte, charged per hop to the network layer. */
    public double joulesPerByte() { return joulesPerByte; }
    /** Returns the monetary transfer cost per GB (10^9 bytes). */
    public double costPerGB() { return costPerGB; }
    /** Returns the total bytes accepted for transmission (both directions, including later-dropped transfers). */
    public long bytesCarried() { return bytesCarried; }
    /** Returns the number of transfers dropped on this link (down, loss or buffer overflow). */
    public long drops() { return drops; }
    /** Returns true if the link itself and both endpoints are up. */
    public boolean isUp() { return up && a.isUp() && b.isUp(); }
    /** Returns the link's own up state, ignoring endpoint state. */
    public boolean linkUp() { return up; }

    /** Marks the link down; transfers in flight are dropped on arrival. No effect if already down. */
    public void fail() { if (up) { up = false; downSince = sim.now(); } }
    /** Marks the link up again and adds the downtime to the availability account. */
    public void repair() { if (!up) { up = true; totalDown += sim.now() - downSince; } }
    /** Returns the fraction of {@code [0, now]} during which the link itself was up (1 at time 0). */
    public double availability() {
        long now = sim.now();
        return now == 0 ? 1 : 1 - (totalDown + (up ? 0 : now - downSince)) / (double) now;
    }

    private int dir(NetNode from) { return from == b ? 1 : 0; }

    /** Estimated one-way delay for {@code bytes} sent now from {@code from} (used by policies for latency estimates). */
    public long estimateDelay(int bytes, NetNode from) {
        int d = dir(from);
        long ser = Math.round(bytes * 8.0 / bandwidthBps * 1e9);
        if (fidelity == Fidelity.QUEUE) return Math.max(0, busyUntil[d] - sim.now()) + ser + latencyNs;
        return latencyNs + ser * Math.max(1, active[d] + 1);
    }

    /** Estimated delay in the a→b direction. */
    public long estimateDelay(int bytes) { return estimateDelay(bytes, a); }

    /** Sends {@code bytes} across the link from {@code from}; the callback fires on arrival or drop. */
    void transmit(int bytes, NetNode from, Callback cb) {
        int d = dir(from);
        if (!isUp()) { drops++; cb.done(false, "link-down"); return; }
        bytesCarried += bytes;
        Fidelity f = fidelity == Fidelity.PACKET ? Fidelity.QUEUE : fidelity; // PACKET handled by adapter; fallback
        boolean lost = loss > 0 && sim.stream("link-loss:" + name()).nextDouble() < loss;
        if (f == Fidelity.ANALYTICAL) {
            active[d]++;
            long ser = Math.round(bytes * 8.0 / (bandwidthBps / active[d]) * 1e9);
            sim.schedule(ser + latencyNs, Simulation.PRIO_HIGH, "link", () -> {
                active[d]--;
                if (!isUp()) { drops++; cb.done(false, "link-down"); }
                else if (lost) { drops++; cb.done(false, "loss"); }
                else cb.done(true, null);
            });
        } else {
            // packets that do not fit the buffer are dropped; bulk transfers larger than the buffer model a TCP flow
            // under flow control and are serialised instead of dropped
            if (bufferBytes > 0 && bytes <= bufferBytes && queuedBytes[d] + bytes > bufferBytes) { drops++; cb.done(false, "queue-overflow"); return; }
            long now = sim.now();
            long start = Math.max(now, busyUntil[d]);
            long ser = Math.round(bytes * 8.0 / bandwidthBps * 1e9);
            busyUntil[d] = start + ser;
            queuedBytes[d] += bytes;
            long done = busyUntil[d];
            sim.scheduleAt(done, Simulation.PRIO_HIGH, "link-tx", () -> queuedBytes[d] -= bytes);
            sim.scheduleAt(done + latencyNs, Simulation.PRIO_HIGH, "link", () -> {
                if (!isUp()) { drops++; cb.done(false, "link-down"); }
                else if (lost) { drops++; cb.done(false, "loss"); }
                else cb.done(true, null);
            });
        }
    }

    @Override public String toString() { return "Link(" + name() + " " + SimTime.format(latencyNs) + ", " + bandwidthBps / 1e6 + " Mbps)"; }
}
