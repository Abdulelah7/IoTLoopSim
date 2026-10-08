package org.iotloopsim.model;

import org.iotloopsim.core.Entity;
import org.iotloopsim.core.Simulation;

/**
 * Anything that can send, relay or receive messages: devices, gateways and compute nodes.
 * Tracks up/down state so that availability can be reported and faults can be injected.
 */
public abstract class NetNode extends Entity {
    protected Position position;
    private boolean up = true;
    private long downSince = -1;
    private long totalDown;
    private String downReason;

    /** Creates an up node at {@code position} and registers it with {@code sim}. */
    protected NetNode(Simulation sim, String name, Position position) {
        super(sim, name);
        this.position = position;
    }

    /** Returns the current position (coordinates in m). */
    public Position position() { return position; }
    /** Moves the node; does not trigger handover by itself. */
    public void setPosition(Position p) { this.position = p; }
    /** Returns true unless the node has failed and not yet been repaired. */
    public boolean isUp() { return up; }
    /** Returns the reason passed to the last {@link #fail(String)}, or null while up. */
    public String downReason() { return downReason; }

    /** Marks the node failed. Subclasses override {@link #onFail} to drop work and notify policies. */
    public final void fail(String reason) {
        if (!up) return;
        up = false;
        downSince = sim.now();
        downReason = reason;
        onFail(reason);
    }

    /**
     * Marks a failed node up again, adds the downtime to the availability account and calls {@link #onRepair()};
     * no effect if already up.
     */
    public final void repair() {
        if (up) return;
        up = true;
        totalDown += sim.now() - downSince;
        downSince = -1;
        downReason = null;
        onRepair();
    }

    /** Hook called once when the node goes down; the default does nothing. */
    protected void onFail(String reason) {}
    /** Hook called once when the node comes back up; the default does nothing. */
    protected void onRepair() {}

    /** Fraction of [0, now] the node was up. */
    public double availability() {
        long now = sim.now();
        if (now == 0) return 1.0;
        long down = totalDown + (up ? 0 : now - downSince);
        return 1.0 - down / (double) now;
    }
}
