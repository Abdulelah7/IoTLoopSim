package org.iotloopsim.model;

/**
 * Chooses the gateway a device is attached to (nearest gateway in range that supports the device's technology)
 * and handles handover, coverage loss and failover after gateway failures.
 */
public final class Attachment {
    private final IoTWorld world;
    private long handoverDelayNs = 50_000_000L; // 50 ms default
    private double hysteresis = 0.15;           // switch only if the new gateway is 15% closer
    private final java.util.Set<IoTDevice> inHandover = new java.util.HashSet<>();   // membership tests only (no iteration)

    Attachment(IoTWorld world) { this.world = world; }

    /** Sets the connectivity interruption in ns during a handover (default 50 ms). */
    public void setHandoverDelay(long ns) { this.handoverDelayNs = ns; }
    /**
     * Sets the handover hysteresis as a fraction (default 0.15): a device switches only if the new gateway is that
     * much closer than the current one.
     */
    public void setHysteresis(double h) { this.hysteresis = h; }

    /**
     * Returns the nearest up gateway that supports the device's technology and has it within range, or null if
     * none.
     */
    public Gateway best(IoTDevice d) {
        Gateway best = null;
        double bd = Double.MAX_VALUE;
        for (Gateway g : world.gateways()) {
            if (!g.isUp() || !g.supports(d.tech())) continue;
            double dist = g.position().distance(d.position());
            if (dist <= g.range(d.tech()) && dist < bd) { bd = dist; best = g; }
        }
        return best;
    }

    /** Attaches to the best gateway; returns whether the device is now connected. */
    boolean attach(IoTDevice d) {
        Gateway g = best(d);
        d.setGateway(g);
        return g != null;
    }

    /** Re-evaluates attachment after movement; performs a handover with interruption if a better gateway exists. */
    public void onMoved(IoTDevice d) {
        if (!d.isUp() || d.state() == DeviceState.PROVISIONED || d.state() == DeviceState.UPDATING
                || d.state() == DeviceState.DECOMMISSIONED) return;           // not yet switched on, busy or retired
        if (inHandover.contains(d)) return;                                    // a handover is already in progress
        Gateway cur = d.gateway();
        Gateway best = best(d);
        if (cur != null && cur.isUp()) {
            double dc = cur.position().distance(d.position());
            boolean inRange = dc <= cur.range(d.tech());
            if (inRange && (best == null || best == cur || best.position().distance(d.position()) > dc * (1 - hysteresis))) return;
        }
        if (best == null) {
            if (cur != null) { d.setGateway(null); d.setState(DeviceState.DISCONNECTED, "out-of-coverage"); world.sim().metrics().inc("mobility.coverage_loss"); }
            return;
        }
        handover(d, cur, best, "mobility");
    }

    void reattach(IoTDevice d, String reason) {
        Gateway old = d.gateway();
        d.setGateway(null);
        if (!d.isUp() || d.state() == DeviceState.DECOMMISSIONED) return;
        Gateway best = best(d);
        if (best == null) { d.setState(DeviceState.DISCONNECTED, reason); return; }
        handover(d, old, best, reason);
    }

    private void handover(IoTDevice d, Gateway from, Gateway to, String reason) {
        d.setGateway(null);
        if (d.state() == DeviceState.ACTIVE) d.setState(DeviceState.DISCONNECTED, "handover");
        world.sim().metrics().inc("mobility.handovers");
        inHandover.add(d);
        world.sim().schedule(handoverDelayNs, "handover", () -> {
            inHandover.remove(d);
            if (!d.isUp() || d.state() == DeviceState.DECOMMISSIONED || d.state() == DeviceState.DEPLETED) return;
            Gateway target = to.isUp() ? to : best(d);
            if (target == null) return;
            if (d.state() == DeviceState.UPDATING) { d.setGateway(target); return; }   // the update's end restores ACTIVE
            d.setGateway(target);
            d.setState(DeviceState.ACTIVE, reason);
            world.runtime().onHandover(d, from, target);
        });
    }

    void rescanDisconnected() {
        for (IoTDevice d : world.devices())
            if (d.isUp() && d.state() == DeviceState.DISCONNECTED && d.gateway() == null && !inHandover.contains(d)) {
                Gateway g = best(d);
                if (g != null) { d.setGateway(g); d.setState(DeviceState.ACTIVE, "coverage-restored"); }
            }
    }
}
