package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iotloopsim.core.MetricsRegistry;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.core.Simulation;
import org.iotloopsim.model.policy.SelectionContext;

/**
 * Application runtime: deploys modules, routes each request through the module tree (sensor → modules → actuator),
 * keeps sticky sessions for stateful modules, and performs lifecycle operations (scale out/in, scale to zero,
 * follow-me migration, rolling service update, retirement).
 */
public final class AppRuntime {
    private final IoTWorld w;
    private final Simulation sim;
    private final Map<AppModule, List<ServiceInstance>> instances = new LinkedHashMap<>();
    private final Map<IoTDevice, Map<AppModule, ServiceInstance>> affinity = new HashMap<>();
    private final Map<String, List<Runnable>> migrating = new HashMap<>();
    private final Map<String, IoTDevice> actuatorCache = new HashMap<>();
    private final Map<AppModule, Double> meanMi = new HashMap<>();
    // migration model: PREWARM overlaps the destination start-up with the state transfer (live/pre-provisioned);
    // COLD is sequential: checkpoint at the source, transfer, then restore (start-up) at the destination
    /**
     * How a session migration is carried out: {@code PREWARM} starts the destination instance while the state is
     * transferred; {@code COLD} checkpoints at the source, transfers, then cold-starts (restores) at the
     * destination.
     */
    public enum MigrationMode { PREWARM, COLD }
    private MigrationMode migrationMode = MigrationMode.PREWARM;
    private org.iotloopsim.core.Distribution checkpointS = org.iotloopsim.core.Distribution.constant(0);

    /** Sets the migration mode and the checkpoint-time distribution in s (used only by {@code COLD}). */
    public void setMigrationModel(MigrationMode mode, org.iotloopsim.core.Distribution checkpointSeconds) {
        this.migrationMode = mode;
        this.checkpointS = checkpointSeconds;
    }
    /** Returns the migration mode (default {@code PREWARM}). */
    public MigrationMode migrationMode() { return migrationMode; }

    AppRuntime(IoTWorld w) { this.w = w; this.sim = w.sim(); }

    // ------------------------------------------------------------------ queries
    /** Returns the live list of instances of {@code m} in any non-stopped state (empty if none). */
    public List<ServiceInstance> instancesOf(AppModule m) { return instances.getOrDefault(m, List.of()); }
    /** Returns a new list of instances of {@code m} that accept requests and whose node is up. */
    public List<ServiceInstance> accepting(AppModule m) {
        List<ServiceInstance> l = new ArrayList<>();
        for (ServiceInstance i : instancesOf(m)) if (i.accepting() && i.node().isUp()) l.add(i);
        return l;
    }
    /** Returns the instance holding device {@code d}'s sticky session for module {@code m}, or null if none. */
    public ServiceInstance affinity(IoTDevice d, AppModule m) {
        Map<AppModule, ServiceInstance> a = affinity.get(d);
        return a == null ? null : a.get(m);
    }
    /**
     * Returns the mean work per request of module {@code m} in million instructions, estimated once from 200
     * samples with a fixed seed and cached.
     */
    public double meanMi(AppModule m) {
        return meanMi.computeIfAbsent(m, k -> {
            java.util.SplittableRandom r = new java.util.SplittableRandom(42);
            double s = 0;
            for (int i = 0; i < 200; i++) s += k.mi().sample(r);
            return s / 200;
        });
    }
    /**
     * Returns the stateful modules of running applications triggered by device {@code d} (matching group and a
     * sensor of the application's type).
     */
    public List<AppModule> statefulModulesOf(IoTDevice d) {
        List<AppModule> out = new ArrayList<>();
        for (Application a : w.applications()) {
            if (a.state() != Application.State.RUNNING) continue;
            if (a.deviceGroup() != null && !a.deviceGroup().equals(d.group())) continue;
            boolean has = d.sensors().stream().anyMatch(s -> s.type().equals(a.sensorType()));
            if (!has) continue;
            for (AppModule m : a.modules().values()) if (m.stateful()) out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ deployment
    /**
     * Places the initial instances of every module using the placement policy and marks the application {@code
     * RUNNING}; modules that cannot be placed increment {@code deploy.unplaced_modules}.
     */
    public void deploy(Application app) {
        app.setState(Application.State.DEPLOYING);
        w.lifecycle().app(app.name(), "DEFINED", "DEPLOYING", "deploy");
        for (AppModule m : app.modules().values()) {
            List<ComputeNode> nodes = w.placement().initial(m, w);
            if (nodes.isEmpty() && m.minInstances() > 0) sim.metrics().inc("deploy.unplaced_modules");
            for (ComputeNode n : nodes) launch(m, n, "deploy");
        }
        app.setState(Application.State.RUNNING);
        w.lifecycle().app(app.name(), "DEPLOYING", "RUNNING", "deployed");
    }

    /**
     * Starts an instance of {@code m} on {@code n}. Returns {@code null} (and counts why) if the node lacks capacity or
     * violates the module's pinned tier, so a faulty placement plugin cannot break the scenario's constraints.
     */
    public ServiceInstance launch(AppModule m, ComputeNode n, String reason) {
        if (m.pinnedTier() != null && n.tier() != m.pinnedTier()) { sim.metrics().inc("deploy.pinned_tier_violation"); return null; }
        if (!n.canHost(m)) { sim.metrics().inc("deploy.no_capacity"); return null; }
        ServiceInstance i = new ServiceInstance(w, m, n);
        instances.computeIfAbsent(m, k -> new ArrayList<>()).add(i);
        i.launch(reason, null);
        return i;
    }

    void onInstanceStopped(ServiceInstance i) {
        List<ServiceInstance> l = instances.get(i.module());
        if (l != null) l.remove(i);
        for (Map<AppModule, ServiceInstance> a : affinity.values()) a.values().removeIf(x -> x == i);
    }

    /**
     * Marks the application {@code RETIRED} (it stops being triggered) and drains all its instances; no effect if
     * already retired.
     */
    public void retire(Application app) {
        if (app.state() == Application.State.RETIRED) return;
        w.lifecycle().app(app.name(), app.state().name(), "RETIRED", "retire");
        app.setState(Application.State.RETIRED);
        for (AppModule m : app.modules().values()) for (ServiceInstance i : new ArrayList<>(instancesOf(m))) i.drain("retired");
    }

    // ------------------------------------------------------------------ request flow
    void onReading(IoTDevice d, Sensor s) {
        for (Application app : w.applications()) {
            if (!app.triggeredBy(d, s)) continue;
            Request r = new Request(app, d, sim.now(), app.root(), s.payloadBytes(), false);
            MetricsRegistry mx = sim.metrics();
            mx.inc("loop.generated");
            mx.inc("app." + app.name() + ".generated");
            if (!d.isConnected()) { dropped(r, "no-coverage"); continue; }
            route(r, d);
        }
    }

    /** Injects attacker work into a module (application-layer DoS); {@code mi} per request. */
    public void injectAttack(IoTDevice attacker, AppModule target, int bytes, double mi) {
        Request r = new Request(target.app(), attacker, sim.now(), target, bytes, true);
        r.mi = mi;
        route(r, attacker);
    }

    private void route(Request r, NetNode from) {
        String key = r.origin.name() + "|" + r.module.qualifiedName();
        List<Runnable> buf = migrating.get(key);
        if (buf != null && !r.attack) { buf.add(() -> route(r, from)); sim.metrics().inc("migration.buffered"); return; }
        w.selector().select(new Ctx(r, from));
    }

    private final class Ctx implements SelectionContext {
        final Request r;
        final NetNode from;
        boolean done;
        Ctx(Request r, NetNode from) { this.r = r; this.from = from; }
        /** Returns the request being placed. */
        public Request request() { return r; }
        /** Returns the module the request is for. */
        public AppModule module() { return r.module; }
        /** Returns the node the request is sent from. */
        public NetNode from() { return from; }
        /** Returns the accepting instances of the module. */
        public List<ServiceInstance> candidates() { return accepting(r.module); }
        /** Returns the world. */
        public IoTWorld world() { return w; }
        /** Sends the request to instance {@code i}; may be called only once per context. */
        public void dispatch(ServiceInstance i) { if (once()) sendTo(r, from, i); }
        /**
         * Launches an on-demand instance on {@code n} (if under the module's instance limit and capacity allows)
         * and sends the request to it, otherwise drops it with reason {@code no-capacity}.
         */
        public void launchOn(ComputeNode n) {
            if (!once()) return;
            ServiceInstance i = instancesOf(r.module).size() < r.module.maxInstances() ? launch(r.module, n, "on-demand") : null;
            if (i == null) dropped(r, "no-capacity");
            else { sim.metrics().inc("instance.on_demand"); sendTo(r, from, i); }
        }
        /** Drops the request with the given reason. */
        public void drop(String reason) { if (once()) dropped(r, reason); }
        private boolean once() { if (done) throw new IllegalStateException("selection already resolved"); done = true; return true; }
    }

    private void sendTo(Request r, NetNode from, ServiceInstance inst) {
        if (r.module.stateful() && !r.attack && affinity(r.origin, r.module) == null) setAffinity(r.origin, r.module, inst);
        if (from == inst.node()) { deliver(r, inst); return; }
        boolean fromDevice = from instanceof IoTDevice;
        Protocol p = fromDevice ? ((IoTDevice) from).protocol() : w.internalProtocol();
        Message.Kind k = r.attack ? Message.Kind.ATTACK : (fromDevice ? Message.Kind.SENSOR_DATA : Message.Kind.INTERNAL);
        Message m = new Message(k, w.network().wireBytes(r.bytes, p), sim.now(), r, r.attack ? "attack:" + r.origin.name() : "legit");
        if (r.attack) w.network().transfer(m, from, inst.node(), (ok, reason) -> { if (ok) deliver(r, inst); });
        else w.network().send(m, from, inst.node(), p, (ok, reason) -> {
            if (ok) deliver(r, inst);
            else dropped(r, reason);
        });
    }

    private void deliver(Request r, ServiceInstance inst) {
        if (inst.submit(r)) return;
        if (inst.state() == ServiceInstance.State.STOPPED && r.reroutes++ < 2) { route(r, inst.node()); return; }
        dropped(r, inst.state() == ServiceInstance.State.STOPPED ? "instance-gone" : "queue-full");
    }

    void onProcessed(Request r, ServiceInstance inst) {
        if (r.attack) { sim.metrics().inc("security.attack_processed"); return; }
        AppModule m = r.module;
        if (m.countsForLoop()) {   // replace this branch by its counting sub-branches
            int branches = m.actuate() ? 1 : 0;
            for (AppModule c : m.children()) if (c.countsForLoop()) branches++;
            r.loop.pending += branches - 1;
        }
        for (AppModule c : m.children()) route(r.next(c), inst.node());
        if (m.actuate()) sendCommand(r, inst);
        else if (m.children().isEmpty()) {
            sim.metrics().inc("app." + r.app.name() + ".sink_completed");
            sim.metrics().record("app." + r.app.name() + ".sink_ms", SimTime.toMillis(sim.now() - r.sensedAt));
            if (m.countsForLoop()) branchDone(r);
        }
    }

    private void sendCommand(Request r, ServiceInstance inst) {
        IoTDevice target = actuatorFor(r);
        if (target == null) { dropped(r, "no-actuator"); return; }
        Actuator a = target.actuator(r.app.actuatorType());
        int cmdBytes = r.module.outBytes() > 0 ? r.module.outBytes() : 32;   // command size = output size of the actuating module
        Message m = new Message(Message.Kind.COMMAND, w.network().wireBytes(cmdBytes, target.protocol()), sim.now(), r);
        w.network().send(m, inst.node(), target, target.protocol(), (ok, reason) -> {
            if (!ok) { dropped(r, reason); return; }
            a.actuate(() -> branchDone(r), () -> dropped(r, "actuator-down"));
        });
    }

    private IoTDevice actuatorFor(Request r) {
        String type = r.app.actuatorType();
        if (r.origin.actuator(type) != null) return r.origin;
        String key = r.origin.name() + "|" + type;
        IoTDevice cached = actuatorCache.get(key);
        if (cached != null && cached.isOperational()) return cached;
        IoTDevice best = null;
        double bd = Double.MAX_VALUE;
        for (IoTDevice d : w.devices()) {
            if (d.actuator(type) == null || !d.isOperational()) continue;
            double dist = d.position().distance(r.origin.position());
            if (dist < bd) { bd = dist; best = d; }
        }
        if (best != null) actuatorCache.put(key, best);
        return best;
    }

    /** A counting branch finished; the loop completes when the last one does (latency = time of the last branch). */
    private void branchDone(Request r) {
        Request.Loop l = r.loop;
        if (l.closed || --l.pending > 0) return;
        l.closed = true;
        if (!l.failed) completeLoop(r);
    }

    private void completeLoop(Request r) {
        double ms = SimTime.toMillis(sim.now() - r.sensedAt);
        MetricsRegistry mx = sim.metrics();
        mx.inc("loop.completed");
        mx.inc("app." + r.app.name() + ".completed");
        mx.record("loop.latency_ms", ms);
        mx.record("app." + r.app.name() + ".latency_ms", ms);
        if (r.app.deadlineNs() > 0 && sim.now() - r.sensedAt > r.app.deadlineNs()) {
            mx.inc("loop.deadline_miss");
            mx.inc("app." + r.app.name() + ".deadline_miss");
        }
    }

    /**
     * A request was lost. Counted once per loop (with the first reason) if it was on a counting branch; drops on other
     * branches (e.g. archiving in an actuation app) are counted under {@code app.<name>.branch_dropped}.
     */
    void dropped(Request r, String reason) {
        if (r.attack) return;
        MetricsRegistry mx = sim.metrics();
        if (!r.module.countsForLoop()) { mx.inc("app." + r.app.name() + ".branch_dropped"); return; }
        Request.Loop l = r.loop;
        if (!l.failed && !l.closed) {
            l.failed = true;
            mx.inc("loop.dropped");
            mx.inc("loop.dropped." + reason);
            mx.inc("app." + r.app.name() + ".dropped");
        }
        if (!l.closed && --l.pending <= 0) l.closed = true;
    }

    // ------------------------------------------------------------------ sessions and migration
    void setAffinity(IoTDevice d, AppModule m, ServiceInstance i) {
        ServiceInstance old = affinity(d, m);
        if (old != null) old.addAffinity(-1);
        affinity.computeIfAbsent(d, k -> new HashMap<>()).put(m, i);
        if (i != null) i.addAffinity(1);
    }

    void onHandover(IoTDevice d, Gateway from, Gateway to) { w.migration().onHandover(d, from, to, w); }

    /**
     * Moves a device's session for module {@code m} to {@code target}: requests are buffered while the state is
     * transferred and (if needed) a new instance cold-starts there; the downtime is recorded.
     */
    public void migrateSession(IoTDevice d, AppModule m, ComputeNode target) {
        String key = d.name() + "|" + m.qualifiedName();
        if (migrating.containsKey(key)) return;
        ServiceInstance cur = affinity(d, m);
        // Destination: the least-loaded accepting instance on the target node other than the current one. Before this
        // fix the first instance on the node was taken, which could be the current instance itself: rebalancing a
        // session between two instances on the same node then "migrated" it to where it already was, and the
        // overloaded instance was never relieved (found by the placement case study, 2026-10-06).
        ServiceInstance existing = null;
        for (ServiceInstance i : accepting(m))
            if (i.node() == target && i != cur && (existing == null || i.load() < existing.load())) existing = i;
        if (existing == null && cur != null && cur.node() == target) { sim.metrics().inc("migration.noop"); return; }
        boolean cold = migrationMode == MigrationMode.COLD && existing == null;
        if (existing == null && instancesOf(m).size() >= m.maxInstances()) { sim.metrics().inc("migration.no_capacity"); return; }
        if (existing == null && !target.canHost(m)) { sim.metrics().inc("migration.no_capacity"); return; }
        List<Runnable> buffer = new ArrayList<>();
        migrating.put(key, buffer);
        long t0 = sim.now();
        sim.metrics().inc("migration.started");
        w.lifecycle().record("session", key, cur == null ? "none" : cur.node().name(), target.name(), "migrate-" + (cold ? "cold" : "prewarm"));
        ServiceInstance[] dest = {existing};
        boolean[] failed = {false};
        Runnable finish = () -> {
            migrating.remove(key);
            if (failed[0] || dest[0] == null || dest[0].state() == ServiceInstance.State.STOPPED) {
                sim.metrics().inc("migration.failed");
                setAffinity(d, m, null);
            } else {
                setAffinity(d, m, dest[0]);
                sim.metrics().inc("migration.completed");
                sim.metrics().record("migration.downtime_ms", SimTime.toMillis(sim.now() - t0));
                if (cur != null && cur != dest[0] && cur.affinity() == 0 && accepting(m).size() > Math.max(1, m.minInstances()))
                    cur.drain("migrated-away");
            }
            for (Runnable r : buffer) r.run();
        };
        boolean transferNeeded = cur != null && cur.node().isUp() && cur.node() != target && m.stateBytes() > 0;
        if (!cold) {
            // PREWARM: start (or reuse) the destination instance now, transfer state in parallel, finish when both are done
            if (dest[0] == null) dest[0] = launch(m, target, "migration");
            if (dest[0] == null) { migrating.remove(key); sim.metrics().inc("migration.no_capacity"); for (Runnable r : buffer) r.run(); return; }
            int[] pending = {2};
            Runnable step = () -> { if (--pending[0] == 0) finish.run(); };
            dest[0].whenReady(step);
            if (!transferNeeded) step.run();
            else sendState(cur.node(), target, m, ok -> { if (!ok) failed[0] = true; step.run(); });
        } else {
            // COLD: checkpoint at the source, then transfer, then restore (cold start) at the destination
            long ckpt = SimTime.s(checkpointS.sample(sim.stream("checkpoint:" + m.qualifiedName())));
            sim.metrics().record("migration.checkpoint_s", SimTime.toSeconds(ckpt));
            sim.schedule(ckpt, "checkpoint", () -> {
                Runnable restore = () -> {
                    dest[0] = launch(m, target, "migration-restore");
                    if (dest[0] == null) { failed[0] = true; finish.run(); return; }
                    dest[0].whenReady(finish);
                };
                if (!transferNeeded) restore.run();
                else sendState(cur.node(), target, m, ok -> { if (!ok) { failed[0] = true; finish.run(); } else restore.run(); });
            });
        }
    }

    private void sendState(ComputeNode from, ComputeNode to, AppModule m, java.util.function.Consumer<Boolean> done) {
        Message st = new Message(Message.Kind.STATE, (int) Math.min(Integer.MAX_VALUE, m.stateBytes()), sim.now(), null);
        sim.metrics().add("migration.state_bytes", m.stateBytes());
        long t = sim.now();
        w.network().send(st, from, to, w.internalProtocol(), (ok, reason) -> {
            sim.metrics().record("migration.transfer_s", SimTime.toSeconds(sim.now() - t));
            done.accept(ok);
        });
    }

    // ------------------------------------------------------------------ failures
    void onNodeFailed(ComputeNode n, List<ServiceInstance> lost) { w.recovery().onNodeFailure(n, lost, w); }
    void onNodeRepaired(ComputeNode n) {
        w.recovery().onNodeRepair(n, w);
        for (Application a : w.applications()) {
            if (a.state() != Application.State.RUNNING) continue;
            for (AppModule m : a.modules().values())
                if (accepting(m).size() < m.minInstances()) {
                    ComputeNode t = w.placement().forNewInstance(m, n, w);
                    if (t != null) launch(m, t, "restore-min");
                }
        }
    }

    // ------------------------------------------------------------------ periodic management
    void startManagement() {
        long iv = w.scaling().intervalNs();
        if (iv > 0) sim.every(iv, iv, "autoscale", this::autoscale);
        boolean serverless = w.applications().stream().flatMap(a -> a.modules().values().stream()).anyMatch(m -> m.keepAliveNs() > 0);
        if (serverless) sim.every(SimTime.S, SimTime.S, "keepalive", this::reapIdle);
    }

    private void autoscale() {
        for (Application a : w.applications()) {
            if (a.state() != Application.State.RUNNING) continue;
            for (AppModule m : a.modules().values()) {
                List<ServiceInstance> act = accepting(m);
                int d = w.scaling().decide(m, act, w);
                if (d > 0) {
                    ServiceInstance hot = act.stream().max(java.util.Comparator.comparingDouble(ServiceInstance::load)).orElse(null);
                    for (int k = 0; k < d; k++) {
                        ComputeNode n = w.placement().forNewInstance(m, hot == null ? null : hot.node(), w);
                        if (n == null || launch(m, n, "scale-out") == null) { sim.metrics().inc("scaling.blocked"); break; }
                        sim.metrics().inc("scaling.out");
                    }
                } else if (d < 0) {
                    int allowed = Math.max(0, act.size() - m.minInstances());   // never scale below minInstances
                    act.removeIf(i -> !i.isReady());
                    act.sort(java.util.Comparator.comparingInt(ServiceInstance::affinity).thenComparingDouble(ServiceInstance::load));
                    for (int k = 0; k < -d && k < act.size() && k < allowed; k++) { act.get(k).drain("scale-in"); sim.metrics().inc("scaling.in"); }
                }
            }
        }
    }

    private void reapIdle() {
        long now = sim.now();
        for (Map.Entry<AppModule, List<ServiceInstance>> e : instances.entrySet()) {
            AppModule m = e.getKey();
            if (m.keepAliveNs() <= 0) continue;
            int alive = accepting(m).size();
            for (ServiceInstance i : new ArrayList<>(e.getValue())) {
                if (alive <= m.minInstances()) break;
                if (i.isReady() && i.queueLength() == 0 && i.busy() == 0 && now - i.lastActive() > m.keepAliveNs()) {
                    i.drain("idle-timeout");
                    sim.metrics().inc("instance.scaled_to_zero");
                    alive--;
                }
            }
        }
    }

    /** Rolling update of a module to a new image: replaces instances one at a time, waiting for each replacement to be ready. */
    public void rollingUpdate(AppModule m, ContainerImage newImage, long pauseNs) {
        List<ServiceInstance> old = new ArrayList<>(accepting(m));
        m.setImage(newImage);
        m.app().setVersion(newImage.name());
        long t0 = sim.now();
        w.lifecycle().app(m.app().name(), "RUNNING", "UPDATING", "rolling-update " + m.name());
        replaceNext(m, old, 0, pauseNs, t0);
    }

    private void replaceNext(AppModule m, List<ServiceInstance> old, int idx, long pause, long t0) {
        if (idx >= old.size()) {
            sim.metrics().record("update.service_duration_s", SimTime.toSeconds(sim.now() - t0));
            w.lifecycle().app(m.app().name(), "UPDATING", "RUNNING", "rolling-update done");
            return;
        }
        ServiceInstance o = old.get(idx);
        ComputeNode n = o.node().isUp() && o.node().freeCores() >= m.cores() ? o.node() : w.placement().forNewInstance(m, o.node(), w);
        ServiceInstance fresh = n == null ? null : launch(m, n, "rolling-update");
        Runnable next = () -> {
            o.drain("replaced");
            sim.metrics().inc("update.instances_replaced");
            sim.schedule(pause, "rolling-update", () -> replaceNext(m, old, idx + 1, pause, t0));
        };
        if (fresh == null) {   // no capacity for a replacement: keep the old instance (on the old image) rather than lose capacity
            sim.metrics().inc("update.replace_blocked");
            sim.schedule(pause, "rolling-update", () -> replaceNext(m, old, idx + 1, pause, t0));
        }
        else fresh.whenReady(next);
    }
}
