package org.iotloopsim.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.iotloopsim.core.SimTime;

/**
 * Carbon intensity in gCO2e per kWh, either constant or a repeating 24-value hourly profile, with optional
 * per-layer overrides (e.g. a greener cloud region).
 */
public final class CarbonModel {
    private double[] defaultProfile = {400};
    private final Map<String, double[]> perLayer = new HashMap<>();

    /**
     * Sets the default intensity profile in gCO2e/kWh: one value for a constant, or N values indexed by hour of
     * simulation time modulo N.
     */
    public void setDefault(List<Double> profile) { defaultProfile = toArray(profile); }
    /**
     * Sets the intensity profile in gCO2e/kWh for one layer (device, gateway, network, edge, fog or cloud),
     * overriding the default.
     */
    public void setLayer(String layer, List<Double> profile) { perLayer.put(layer, toArray(profile)); }

    /** Returns the carbon intensity in gCO2e/kWh for {@code layer} at simulation time {@code time} (ns). */
    public double intensity(String layer, long time) {
        double[] p = perLayer.getOrDefault(layer, defaultProfile);
        if (p.length == 1) return p[0];
        int hour = (int) ((time / SimTime.H) % p.length);
        return p[hour];
    }

    /** Time-weighted mean intensity over [t0, t1] (ns), splitting the interval at hour boundaries of the profile. */
    public double meanIntensity(String layer, long t0, long t1) {
        double[] p = perLayer.getOrDefault(layer, defaultProfile);
        if (p.length == 1 || t1 <= t0) return intensity(layer, t1);
        double acc = 0;
        long t = t0;
        while (t < t1) {
            long next = Math.min(t1, (t / SimTime.H + 1) * SimTime.H);
            acc += p[(int) ((t / SimTime.H) % p.length)] * (double) (next - t);
            t = next;
        }
        return acc / (t1 - t0);
    }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
