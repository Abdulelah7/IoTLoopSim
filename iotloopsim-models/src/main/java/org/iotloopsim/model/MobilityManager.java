package org.iotloopsim.model;

import org.iotloopsim.core.SimTime;

/** Periodically moves mobile devices and lets the attachment manager perform handovers. */
public final class MobilityManager {
    private final IoTWorld world;
    private long intervalNs = SimTime.S;

    MobilityManager(IoTWorld world) { this.world = world; }
    /**
     * Sets the position-update and handover-check period in ns (default 1 s); must be called before the world
     * starts.
     */
    public void setInterval(long ns) { this.intervalNs = ns; }

    void start() {
        boolean any = world.devices().stream().anyMatch(d -> d.has(MobilityModel.class));
        if (!any) return;
        world.sim().every(intervalNs, intervalNs, "mobility", () -> {
            long now = world.sim().now();
            for (IoTDevice d : world.devices()) {
                MobilityModel m = d.get(MobilityModel.class);
                if (m == null || !d.isUp()) continue;
                d.setPosition(m.positionAt(now));
                world.attachment().onMoved(d);
            }
        });
    }
}
