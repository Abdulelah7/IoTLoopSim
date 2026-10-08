package org.iotloopsim.model;

/** Simple linear battery: capacity in joules, drained by sensing, radio, crypto and baseline power. */
public final class Battery {
    private final double capacityJ;
    private double remainingJ;

    /** Creates a full battery with the given capacity in J. */
    public Battery(double capacityJ) { this.capacityJ = capacityJ; this.remainingJ = capacityJ; }
    /** Convenience: capacity from mAh at a nominal voltage. */
    public static Battery fromMah(double mAh, double volts) { return new Battery(mAh / 1000.0 * volts * 3600.0); }

    /** Drains energy; returns true if the battery is now empty. */
    public boolean drain(double joules) {
        if (joules < 0) throw new IllegalArgumentException("drain(" + joules + "): use charge() or IoTDevice.harvest() to add energy");
        remainingJ = Math.max(0, remainingJ - joules); return remainingJ <= 0;
    }
    /** Adds harvested energy, capped at the capacity; returns the joules actually stored. */
    public double charge(double joules) {
        double stored = Math.max(0, Math.min(joules, capacityJ - remainingJ));
        remainingJ += stored;
        return stored;
    }
    /** Returns the remaining energy in J (never negative). */
    public double remainingJ() { return remainingJ; }
    /** Returns the capacity in J. */
    public double capacityJ() { return capacityJ; }
    /** Returns the state of charge in {@code [0, 1]}, or 0 if the capacity is not positive. */
    public double fraction() { return capacityJ <= 0 ? 0 : remainingJ / capacityJ; }
}
