package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.List;
import org.iotloopsim.core.Simulation;

/**
 * An IoT end device with sensors, actuators, a radio (access technology + protocol), an optional battery and a
 * firmware version. Covers the perception layer and the device side of the lifecycle.
 */
public final class IoTDevice extends NetNode {
    private final IoTWorld world;
    private final AccessTechnology tech;
    private final Protocol protocol;
    private final Battery battery;          // null = mains powered
    private final double baselineW;          // sleep/idle power drawn continuously
    private final List<Sensor> sensors = new ArrayList<>();
    private final List<Actuator> actuators = new ArrayList<>();
    private final String group;
    private DeviceState state = DeviceState.PROVISIONED;
    private Gateway gateway;
    private String firmware;
    private long lastBaseline;
    private long dutyFreeAt;                // earliest time the radio may transmit under the duty-cycle rule
    private long handshakeUntil;             // security handshake in progress after (re)attach
    private int updating;                    // OTA in progress flag
    private org.iotloopsim.core.EventHandle depletionCheck;
    private long depletionAt = Long.MAX_VALUE;
    private long radioFreeAt;               // end of the last uplink's post phases (measured radio profile only)

    /**
     * Creates a device in state {@code PROVISIONED}; {@code battery} null means mains powered, {@code baselineW}
     * is the continuous sleep/idle power in W. Tags the device with its group, firmware and technology.
     */
    public IoTDevice(IoTWorld world, String name, String group, Position pos, AccessTechnology tech, Protocol protocol,
                     Battery battery, double baselineW, String firmware) {
        super(world.sim(), name, pos);
        this.world = world; this.group = group; this.tech = tech; this.protocol = protocol;
        this.battery = battery; this.baselineW = baselineW; this.firmware = firmware;
        tag("group", group);
        tag("firmware", firmware);
        tag("tech", tech.name());
    }

    /** Returns the world this device belongs to. */
    public IoTWorld world() { return world; }
    /** Returns the device group name (used to select devices for applications, faults and updates). */
    public String group() { return group; }
    /** Returns the radio access technology. */
    public AccessTechnology tech() { return tech; }
    /** Returns the application/transport protocol used for uplink messages. */
    public Protocol protocol() { return protocol; }
    /** Returns the battery, or null if the device is mains powered. */
    public Battery battery() { return battery; }
    /** Returns the current lifecycle state. */
    public DeviceState state() { return state; }
    /** Returns the attached gateway, or null if not attached. */
    public Gateway gateway() { return gateway; }
    /** Returns the installed firmware version. */
    public String firmware() { return firmware; }
    /** Returns the live list of sensors. */
    public List<Sensor> sensors() { return sensors; }
    /** Returns the live list of actuators. */
    public List<Actuator> actuators() { return actuators; }

    /**
     * Binds sensor {@code s} to this device and adds it; returns this device. Sensors start when the device is
     * activated.
     */
    public IoTDevice addSensor(Sensor s) { s.bind(this); sensors.add(s); return this; }
    /** Binds actuator {@code a} to this device and adds it; returns this device. */
    public IoTDevice addActuator(Actuator a) { a.bind(this); actuators.add(a); return this; }

    /** Returns the first actuator of the given type (any actuator if {@code type} is null), or null if none matches. */
    public Actuator actuator(String type) {
        for (Actuator a : actuators) if (type == null || a.type().equals(type)) return a;
        return null;
    }

    /** Device is powered, not failed and not being updated (it may still be out of coverage). */
    public boolean isOperational() {
        return isUp() && (state == DeviceState.ACTIVE || state == DeviceState.DISCONNECTED);
    }

    /** Returns true if the device can take readings (same as {@link #isOperational()}). */
    public boolean canSense() { return isOperational(); }
    /** Returns true if the device is {@code ACTIVE} and attached to a gateway that is up. */
    public boolean isConnected() { return gateway != null && gateway.isUp() && state == DeviceState.ACTIVE; }

    // ------------------------------------------------------------------ lifecycle
    void activate() {
        lastBaseline = sim.now();
        setState(world.attachment().attach(this) ? DeviceState.ACTIVE : DeviceState.DISCONNECTED, "provisioned");
        long phase = 0;
        for (Sensor s : sensors) s.start(phase++);
    }

    /**
     * Changes the lifecycle state: settles baseline energy drawn in the old state, logs the transition to the
     * {@link LifecycleLog} and re-plans the battery depletion event. No effect if the state is unchanged.
     */
    public void setState(DeviceState s, String reason) {
        if (s == state) return;
        DeviceState old = state;
        accountBaseline();                 // charge baseline power drawn in the old state
        state = s;
        world.lifecycle().device(this, old, s, reason);
        scheduleDepletionCheck(drawing());  // and plan the exact depletion time in the new state
    }

    void setGateway(Gateway g) {
        Gateway old = this.gateway;
        this.gateway = g;
        if (old != null) old.detach(this);
        if (g != null) {
            g.attach(this);
            SecurityProfile sp = world.channelSecurity();
            if (sp.handshakeBytes() > 0) { // a fresh security session is needed after attach/handover
                long rtt = 2 * (tech.latencyNs() + tech.airtimeNs(sp.handshakeBytes() / Math.max(1, sp.handshakeRtts())));
                handshakeUntil = sim.now() + sp.handshakeRtts() * rtt;
                consume(tech.txPowerW() * tech.airtimeNs(sp.handshakeBytes() / 2) / 1e9
                        + tech.rxPowerW() * tech.airtimeNs(sp.handshakeBytes() / 2) / 1e9, "security");
            }
        }
    }

    void setFirmware(String v) { firmware = v; tag("firmware", v); }
    void beginUpdate() { updating++; setState(DeviceState.UPDATING, "ota"); }
    void endUpdate(boolean ok) {
        updating = Math.max(0, updating - 1);
        // the device may have been depleted, failed or decommissioned while the update was running
        if (state != DeviceState.UPDATING || !isUp()) return;
        if (!ok) { fail("ota-failure"); return; }
        setState(isConnectedGateway() ? DeviceState.ACTIVE : DeviceState.DISCONNECTED, "ota-done");
    }
    private boolean isConnectedGateway() { return gateway != null && gateway.isUp(); }

    /** Brings an attacker device online (no sensors). */
    public void activateAttacker() { activate(); }

    /**
     * Retires the device (state {@code DECOMMISSIONED}, stops baseline draw and sensing) and detaches it from its
     * gateway.
     */
    public void decommission() {
        setState(DeviceState.DECOMMISSIONED, "retired");
        setGateway(null);
    }

    @Override protected void onFail(String reason) {
        if ("battery".equals(reason) && state != DeviceState.DEPLETED) {   // battery lifetime from the start of the run
            double h = sim.now() / 3.6e12;
            sim.metrics().record("devices.lifetime_h", h);
            sim.metrics().record("devices." + group + ".lifetime_h", h);
        }
        setState("battery".equals(reason) ? DeviceState.DEPLETED : DeviceState.FAILED, reason);
    }

    @Override protected void onRepair() {
        if (state == DeviceState.FAILED) setState(world.attachment().attach(this) ? DeviceState.ACTIVE : DeviceState.DISCONNECTED, "repaired");
    }

    // ------------------------------------------------------------------ energy
    /** Charges energy to the device (and its battery). Depletion fails the device. */
    public void consume(double joules, String category) { consume(joules, category, sim.now()); }

    /** As {@link #consume(double, String)} for energy drawn uniformly since {@code fromNs} (affects carbon only). */
    void consume(double joules, String category, long fromNs) {
        if (joules <= 0) return;
        if (battery != null) {
            if (battery.remainingJ() <= 0) return;                 // an empty battery delivers nothing more
            joules = Math.min(joules, battery.remainingJ());
        }
        world.energy().charge(this, category, joules, fromNs);
        if (battery != null && battery.drain(joules) && isUp()) { fail("battery"); return; }
        if (battery != null && !"baseline".equals(category) && depletionCheck != null) {
            // other consumption brings depletion forward; re-plan only when it moved by more than max(1 s, 0.1 %)
            long now = sim.now();
            double rem = battery.remainingJ() - baselineW * (now - lastBaseline) / 1e9;
            long predicted = now + (long) Math.min(Math.max(0, rem / baselineW * 1e9), (double) (Long.MAX_VALUE / 2));
            long tol = Math.max(org.iotloopsim.core.SimTime.S, (depletionAt - now) / 1000);
            if (predicted < depletionAt - tol) accountBaseline();
        }
    }

    /**
     * Adds harvested energy (J) to the battery, capped at its capacity, and re-plans the depletion time. A depleted
     * device is not revived automatically; call {@link #repair()} if the model should restart it.
     */
    public double harvest(double joules) {
        if (battery == null || joules <= 0) return 0;
        accountBaseline();                         // settle baseline drawn so far at the old level
        double stored = battery.charge(joules);
        world.sim().metrics().add("energy.harvested_J", stored);
        scheduleDepletionCheck(drawing());
        return stored;
    }

    void accountBaseline() {
        long now = sim.now();
        boolean drawing = drawing();
        double j = drawing ? baselineW * (now - lastBaseline) / 1e9 : 0;
        long from = lastBaseline;
        lastBaseline = now;
        if (j > 0) consume(j, "baseline", from);
        scheduleDepletionCheck(drawing);
    }

    private boolean drawing() {
        return state != DeviceState.DECOMMISSIONED && state != DeviceState.DEPLETED && state != DeviceState.PROVISIONED;
    }

    /** Baseline power is integrated lazily; schedule an event at the exact time it alone would empty the battery. */
    private void scheduleDepletionCheck(boolean drawing) {
        if (depletionCheck != null) { depletionCheck.cancel(); depletionCheck = null; depletionAt = Long.MAX_VALUE; }
        if (battery == null || baselineW <= 0 || !drawing || !isUp()) return;
        double tD = Math.ceil(battery.remainingJ() / baselineW * 1e9);
        if (tD >= Long.MAX_VALUE / 2 - sim.now()) return;   // beyond any representable horizon: never depletes from baseline alone
        long t = Math.max(1, (long) tD);
        depletionAt = sim.now() + t;
        depletionCheck = sim.schedule(t, Simulation.PRIO_HIGH, "depletion-check", this::accountBaseline);
    }

    // ------------------------------------------------------------------ radio
    /**
     * Sends {@code bytes} over the access link to the attached gateway. Applies duty-cycle waiting, handshake waiting,
     * airtime and per-frame loss, and charges transmit energy. With a measured {@link RadioProfile} the transmission
     * starts after the profile's pre phases, the radio stays busy until its post phases end (class-A receive
     * windows), and energy follows the profile instead of {@code txPowerW}.
     */
    void radioUp(int bytes, Link.Callback cb) {
        if (!isConnected()) { cb.done(false, "no-coverage"); return; }
        long now = sim.now();
        long airtime = tech.airtimeNs(bytes);
        int frames = tech.frames(bytes);
        RadioProfile prof = tech.profile();
        long preNs = 0, postNs = 0;
        if (prof != null) {
            for (long x : prof.preNs(tech)) preNs += x;
            for (long x : prof.postNs(tech)) postNs += x;
        }
        long start = Math.max(now + preNs, Math.max(radioFreeAt + preNs, Math.max(dutyFreeAt, handshakeUntil)));
        long chStart = gateway.reserveChannel(tech, start, airtime);
        long between = (long) (frames - 1) * (preNs + postNs);   // multi-frame messages: one full profile per frame
        if (tech.dutyCycle() < 1.0) dutyFreeAt = chStart + between + Math.round(airtime / tech.dutyCycle());
        if (prof != null) {
            radioFreeAt = chStart + airtime + between + postNs;
            consume(prof.txPowerAboveBaselineW(baselineW) * airtime / 1e9, "radio-tx");
            consume(frames * prof.overheadJ(tech, baselineW), "radio-overhead");
        } else {
            consume(tech.txPowerW() * airtime / 1e9, "radio-tx");
        }
        double pLoss = 1 - Math.pow(1 - tech.loss(), frames);
        boolean lost = sim.stream("radio:" + name()).nextDouble() < pLoss;
        Gateway g = gateway;
        sim.scheduleAt(chStart + airtime + between + tech.latencyNs(), Simulation.PRIO_HIGH, "radio-up", () -> {
            if (!g.isUp() || gateway != g) cb.done(false, "handover-loss");
            else if (lost) cb.done(false, "radio-loss");
            else cb.done(true, null);
        });
    }

    /** Receives {@code bytes} from the gateway (downlink); charges receive energy. */
    void radioDown(int bytes, Link.Callback cb) {
        if (!isConnected()) { cb.done(false, "no-coverage"); return; }
        long airtime = tech.airtimeNs(bytes);
        long chStart = gateway.reserveChannel(tech, sim.now(), airtime);
        double pLoss = 1 - Math.pow(1 - tech.loss(), tech.frames(bytes));
        boolean lost = sim.stream("radio:" + name()).nextDouble() < pLoss;
        sim.scheduleAt(chStart + airtime + tech.latencyNs(), Simulation.PRIO_HIGH, "radio-down", () -> {
            if (drawing()) consume(tech.rxPowerW() * airtime / 1e9, "radio-rx");
            if (!isConnected()) cb.done(false, "no-coverage");
            else if (lost) cb.done(false, "radio-loss");
            else cb.done(true, null);
        });
    }
}
