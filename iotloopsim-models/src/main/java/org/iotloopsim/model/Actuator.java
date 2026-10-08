package org.iotloopsim.model;

import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;

/** An actuator that executes commands; actuation time is a distribution in milliseconds. */
public final class Actuator {
    private final String type;
    private final Distribution actuationMs;
    private final double joulesPerAction;
    private IoTDevice device;
    private long actions;

    /**
     * Creates an actuator; {@code actuationMs} samples the actuation delay in ms and {@code joulesPerAction} is
     * the energy in J charged per completed action.
     */
    public Actuator(String type, Distribution actuationMs, double joulesPerAction) {
        this.type = type; this.actuationMs = actuationMs; this.joulesPerAction = joulesPerAction;
    }

    /** Returns the actuator type name. */
    public String type() { return type; }
    /** Returns the number of successfully completed actions. */
    public long actions() { return actions; }
    /** Returns the owning device, or null before it is bound. */
    public IoTDevice device() { return device; }
    void bind(IoTDevice d) { this.device = d; }

    /** Executes a command and calls back when the physical action completes (or fails if the device is down). */
    public void actuate(Runnable onDone, Runnable onFail) {
        IoTDevice d = device;
        if (!d.isOperational()) { onFail.run(); return; }
        long t = SimTime.ms(actuationMs.sample(d.sim().stream("actuator:" + d.name() + ":" + type)));
        d.sim().schedule(t, "actuate", () -> {
            if (!d.isOperational()) { onFail.run(); return; }
            actions++;
            d.consume(joulesPerAction, "actuation");
            onDone.run();
        });
    }
}
