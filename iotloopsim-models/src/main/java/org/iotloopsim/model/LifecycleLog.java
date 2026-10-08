package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.iotloopsim.core.Simulation;

/**
 * Records every lifecycle transition of devices, nodes, applications and service instances with time and reason,
 * so lifecycle operations (deploy, scale, migrate, update, fail/recover, retire) can be analysed after a run.
 */
public final class LifecycleLog {
    /**
     * One lifecycle transition: {@code time} in ns, {@code kind} (device, node, app or instance), entity name,
     * from and to states, and a free-text reason (may be null).
     */
    public record Entry(long time, String kind, String entity, String from, String to, String reason) {}

    private final Simulation sim;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Integer> counts = new TreeMap<>();
    private int cap = 200_000;

    LifecycleLog(Simulation sim) { this.sim = sim; }

    /**
     * Sets the maximum number of entries retained (default 200000); transitions beyond it are still counted but
     * not stored.
     */
    public void setCapacity(int cap) { this.cap = cap; }

    /**
     * Records a transition at the current simulation time and increments the count {@code kind + "." +
     * to.toLowerCase()}; {@code to} must not be null.
     */
    public void record(String kind, String entity, String from, String to, String reason) {
        counts.merge(kind + "." + to.toLowerCase(), 1, Integer::sum);
        if (entries.size() < cap) entries.add(new Entry(sim.now(), kind, entity, from, to, reason));
    }

    /** Records a device state transition (kind {@code device}). */
    public void device(IoTDevice d, DeviceState from, DeviceState to, String reason) { record("device", d.name(), from.name(), to.name(), reason); }
    /** Records a gateway or compute-node transition (kind {@code node}), e.g. UP to DOWN. */
    public void node(NetNode n, String from, String to, String reason) { record("node", n.name(), from, to, reason); }
    /** Records an application state transition (kind {@code app}). */
    public void app(String app, String from, String to, String reason) { record("app", app, from, to, reason); }
    /** Records a service-instance state transition (kind {@code instance}). */
    public void instance(String inst, String from, String to, String reason) { record("instance", inst, from, to, reason); }

    /** Returns the live list of stored entries in time order (at most the configured capacity). */
    public List<Entry> entries() { return entries; }
    /**
     * Returns the live transition counts keyed by {@code kind.tostate} (lower-case target state), including
     * transitions beyond the capacity.
     */
    public Map<String, Integer> counts() { return counts; }

    /**
     * Renders stored entries as CSV with header {@code time_s,kind,entity,from,to,reason}; times are in seconds
     * and values are not quoted.
     */
    public String toCsv() {
        StringBuilder b = new StringBuilder("time_s,kind,entity,from,to,reason\n");
        for (Entry e : entries)
            b.append(String.format("%.6f,%s,%s,%s,%s,%s%n", e.time / 1e9, e.kind, e.entity, e.from, e.to, e.reason == null ? "" : e.reason));
        return b.toString();
    }
}
