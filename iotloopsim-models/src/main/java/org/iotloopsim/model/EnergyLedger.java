package org.iotloopsim.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.iotloopsim.core.Entity;

/**
 * End-to-end energy accounting: every joule is charged to an entity and a category, then aggregated per layer
 * (device, gateway, network, edge, fog, cloud). Carbon is computed at charge time from the carbon-intensity model.
 */
public final class EnergyLedger {
    private final IoTWorld world;
    private final Map<String, Double> byLayer = new TreeMap<>();
    private final Map<String, Double> byCategory = new TreeMap<>();
    private final Map<String, Double> carbonByLayer = new TreeMap<>();
    private final Map<String, Double> byEntity = new LinkedHashMap<>();

    EnergyLedger(IoTWorld world) { this.world = world; }

    /** Charges energy drawn at the current instant. */
    public void charge(Entity e, String category, double joules) { charge(e, category, joules, world.sim().now()); }

    /**
     * Charges energy drawn uniformly over [{@code fromNs}, now] (lazily integrated power, e.g. baseline or idle power);
     * carbon uses the time-weighted intensity over that interval.
     */
    public void charge(Entity e, String category, double joules, long fromNs) {
        if (joules <= 0 || Double.isNaN(joules)) return;
        String layer = layerOf(e);
        byLayer.merge(layer, joules, Double::sum);
        byCategory.merge(category, joules, Double::sum);
        byEntity.merge(e.name(), joules, Double::sum);
        long now = world.sim().now();
        double g = joules / 3.6e6 * world.carbon().meanIntensity(layer, Math.min(fromNs, now), now);
        carbonByLayer.merge(layer, g, Double::sum);
    }

    /**
     * Returns the accounting layer of an entity: device, gateway, network (links), the lower-case tier of a
     * compute node, or other.
     */
    public static String layerOf(Entity e) {
        if (e instanceof IoTDevice) return "device";
        if (e instanceof Gateway) return "gateway";
        if (e instanceof Link) return "network";
        if (e instanceof ComputeNode c) return c.tier().name().toLowerCase();
        return "other";
    }

    /** Returns the total energy charged so far, in J. */
    public double total() { return byLayer.values().stream().mapToDouble(Double::doubleValue).sum(); }
    /** Returns the total emissions charged so far, in gCO2e. */
    public double carbonTotal() { return carbonByLayer.values().stream().mapToDouble(Double::doubleValue).sum(); }
    /** Returns the live per-layer energy totals in J. */
    public Map<String, Double> byLayer() { return byLayer; }
    /** Returns the live per-category energy totals in J (e.g. compute, radio-tx, sensing, security). */
    public Map<String, Double> byCategory() { return byCategory; }
    /** Returns the live per-layer emission totals in gCO2e. */
    public Map<String, Double> carbonByLayer() { return carbonByLayer; }
    /** Returns the energy in J charged to the entity named {@code name}, or 0 if none. */
    public double ofEntity(String name) { return byEntity.getOrDefault(name, 0.0); }
}
