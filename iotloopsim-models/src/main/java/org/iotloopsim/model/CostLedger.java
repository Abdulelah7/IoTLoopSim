package org.iotloopsim.model;

import java.util.Map;
import java.util.TreeMap;

/** Monetary cost accounting (compute instance time, network transfer, energy). Units are whatever the scenario uses (e.g. USD). */
public final class CostLedger {
    private final Map<String, Double> items = new TreeMap<>();
    private final Map<Tier, Double> pricePerCoreHour = new TreeMap<>();
    private double energyPricePerKWh;

    /**
     * Creates an empty ledger with default core prices per hour of 0.02 (edge), 0.03 (fog) and 0.04 (cloud), and
     * energy price 0.
     */
    public CostLedger() {
        pricePerCoreHour.put(Tier.EDGE, 0.02);
        pricePerCoreHour.put(Tier.FOG, 0.03);
        pricePerCoreHour.put(Tier.CLOUD, 0.04);
    }

    /** Sets the price per core-hour for tier {@code t}. */
    public void setCorePrice(Tier t, double perHour) { pricePerCoreHour.put(t, perHour); }
    /** Returns the price per core-hour for tier {@code t}, or 0 if unset. */
    public double corePrice(Tier t) { return pricePerCoreHour.getOrDefault(t, 0.0); }
    /** Sets the energy price per kWh. */
    public void setEnergyPrice(double perKWh) { this.energyPricePerKWh = perKWh; }
    /** Returns the energy price per kWh. */
    public double energyPrice() { return energyPricePerKWh; }
    /** Adds {@code amount} to cost item {@code item}; non-positive amounts are ignored. */
    public void add(String item, double amount) { if (amount > 0) items.merge(item, amount, Double::sum); }
    /** Returns the live per-item cost map, sorted by item name. */
    public Map<String, Double> items() { return items; }
    /** Returns the sum of all cost items. */
    public double total() { return items.values().stream().mapToDouble(Double::doubleValue).sum(); }
}
