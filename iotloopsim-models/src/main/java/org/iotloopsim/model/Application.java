package org.iotloopsim.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An IoT application: triggered by readings of {@code sensorType} from devices of {@code deviceGroup} (null = any),
 * processed by a tree of modules, optionally closing the loop on an actuator of {@code actuatorType}.
 */
public final class Application {
    /** Lifecycle state of an application; only {@code RUNNING} applications are triggered by sensor readings. */
    public enum State { DEFINED, DEPLOYING, RUNNING, RETIRED }

    private final String name;
    private final String sensorType;
    private final String deviceGroup;
    private final String actuatorType;
    private final long deadlineNs;
    private final Map<String, AppModule> modules = new LinkedHashMap<>();
    private AppModule root;
    private State state = State.DEFINED;
    private String version = "1";

    /**
     * Creates an application in state {@code DEFINED}, version "1"; {@code deviceGroup} and {@code actuatorType}
     * may be null (any group, open loop) and {@code deadlineNs} is the end-to-end deadline in ns.
     */
    public Application(String name, String sensorType, String deviceGroup, String actuatorType, long deadlineNs) {
        this.name = name; this.sensorType = sensorType; this.deviceGroup = deviceGroup;
        this.actuatorType = actuatorType; this.deadlineNs = deadlineNs;
    }

    /** Returns the application name. */
    public String name() { return name; }
    /** Returns the sensor type whose readings trigger this application. */
    public String sensorType() { return sensorType; }
    /** Returns the device group that triggers this application, or null for any group. */
    public String deviceGroup() { return deviceGroup; }
    /** Returns the actuator type that closes the loop, or null if the application is open-loop. */
    public String actuatorType() { return actuatorType; }
    /** Returns the end-to-end deadline in ns. */
    public long deadlineNs() { return deadlineNs; }
    /** Returns the live module map in insertion order. */
    public Map<String, AppModule> modules() { return modules; }
    /** Returns the module named {@code n}, or null. */
    public AppModule module(String n) { return modules.get(n); }
    /** Returns the entry module that receives readings, or null if no module was added. */
    public AppModule root() { return root; }
    /** Returns the current lifecycle state. */
    public State state() { return state; }
    /** Returns the deployed version label (initially "1"; changed by rolling updates). */
    public String version() { return version; }
    void setVersion(String v) { version = v; }
    void setState(State s) { state = s; }

    /** Adds (or replaces by name) module {@code m}; the first module added becomes the root. Returns {@code m}. */
    public AppModule addModule(AppModule m) {
        modules.put(m.name(), m);
        if (root == null) root = m;
        return m;
    }

    /** Sets the root module by name; an unknown name sets the root to null. */
    public void setRoot(String moduleName) { root = modules.get(moduleName); }

    /**
     * Returns true if the application is {@code RUNNING}, {@code s} has this application's sensor type and {@code
     * d} belongs to its device group (or any group if none is set).
     */
    public boolean triggeredBy(IoTDevice d, Sensor s) {
        return state == State.RUNNING && s.type().equals(sensorType) && (deviceGroup == null || deviceGroup.equals(d.group()));
    }
}
