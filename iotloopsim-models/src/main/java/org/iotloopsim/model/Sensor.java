package org.iotloopsim.model;

import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;

/** A sensor that periodically produces readings of {@code payloadBytes}; the interval is a distribution in seconds. */
public final class Sensor {
    private final String type;
    private final Distribution intervalS;
    private final int payloadBytes;
    private final double joulesPerSample;
    private IoTDevice device;
    private long samples;

    /**
     * Creates a sensor; {@code intervalS} samples the gap between readings in s, {@code payloadBytes} is the
     * reading size in bytes and {@code joulesPerSample} the energy in J charged per reading.
     */
    public Sensor(String type, Distribution intervalS, int payloadBytes, double joulesPerSample) {
        this.type = type; this.intervalS = intervalS; this.payloadBytes = payloadBytes; this.joulesPerSample = joulesPerSample;
    }

    /** Returns the sensor type name. */
    public String type() { return type; }
    /** Returns the size of one reading in bytes. */
    public int payloadBytes() { return payloadBytes; }
    /** Returns the number of readings taken so far. */
    public long samples() { return samples; }
    /** Returns the owning device, or null before it is bound. */
    public IoTDevice device() { return device; }
    void bind(IoTDevice d) { this.device = d; }

    /**
     * Starts sampling. The first reading is drawn from the equilibrium (stationary) distribution of the sampling
     * process, so devices switched on together do not fire in lock-step: U(0, T) for a fixed period T, and again
     * exponential for Poisson sensing. Implemented by size-biased resampling of the interval distribution.
     */
    void start(long minDelay) {
        java.util.SplittableRandom r = device.sim().stream("sensor-phase:" + device.name() + ":" + type);
        double[] v = new double[32];
        double sum = 0;
        for (int i = 0; i < v.length; i++) { v[i] = Math.max(0, intervalS.sample(r)); sum += v[i]; }
        double pick = r.nextDouble() * sum, acc = 0, len = v[v.length - 1];
        for (double x : v) { acc += x; if (acc >= pick) { len = x; break; } }
        long phase = SimTime.s(len * r.nextDouble());
        device.sim().schedule(Math.max(minDelay, phase), "sense", this::tick);
    }

    private void tick() {
        IoTDevice d = device;
        if (d.state() == DeviceState.DECOMMISSIONED || d.state() == DeviceState.DEPLETED) return; // stop for good
        if (d.canSense()) {
            samples++;
            d.consume(joulesPerSample, "sensing");
            if (d.canSense()) d.world().runtime().onReading(d, this);
        }
        long next = Math.max(1, SimTime.s(intervalS.sample(d.sim().stream("sensor:" + d.name() + ":" + type))));
        d.sim().schedule(next, "sense", this::tick);
    }
}
