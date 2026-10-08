package org.iotloopsim.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import org.iotloopsim.core.SimTime;

/**
 * A running copy of a module on a compute node (container / function instance).
 * States: PULLING (image download) → STARTING (cold start) → READY → DRAINING → STOPPED.
 * Requests are queued FIFO and served by up to {@code cores} concurrent workers.
 */
public final class ServiceInstance {
    /**
     * Instance lifecycle: {@code PULLING} (image download), {@code STARTING} (cold start), {@code READY}
     * (serving), {@code DRAINING} (finishing queued work, not accepting) and {@code STOPPED} (resources released).
     */
    public enum State { PULLING, STARTING, READY, DRAINING, STOPPED }

    private final String id;
    private final AppModule module;
    private final ComputeNode node;
    private final IoTWorld world;
    private final Deque<Request> queue = new ArrayDeque<>();
    private final java.util.List<Request> inService = new java.util.ArrayList<>();
    private State state;
    private int busy;
    private int generation;          // bumps on kill so in-flight completions are discarded
    private final long createdAt;
    private long readyAt = -1, lastActive, stoppedAt = -1;
    private long processed;
    private int affinity;            // number of devices with sticky sessions on this instance
    private final java.util.List<Runnable> readyWaiters = new java.util.ArrayList<>();

    /** Runs {@code r} when the instance becomes READY (immediately if it already is; also if it stops before). */
    public void whenReady(Runnable r) {
        if (state == State.READY || state == State.DRAINING || state == State.STOPPED) r.run();
        else readyWaiters.add(r);
    }
    private void fireWaiters() {
        java.util.List<Runnable> w = new java.util.ArrayList<>(readyWaiters);
        readyWaiters.clear();
        for (Runnable r : w) r.run();
    }

    ServiceInstance(IoTWorld world, AppModule module, ComputeNode node) {
        this.id = module.qualifiedName() + "#" + world.nextInstanceId();
        this.world = world; this.module = module; this.node = node;
        this.createdAt = world.sim().now();
        this.lastActive = createdAt;
        node.allocate(this);
        world.sim().onEnd(this::chargeCost);
    }

    /** Returns the instance id, {@code module-qualified-name#n}. */
    public String id() { return id; }
    /** Returns the module this instance runs. */
    public AppModule module() { return module; }
    /** Returns the hosting compute node. */
    public ComputeNode node() { return node; }
    /** Returns the current state, or null before {@code launch} has been called. */
    public State state() { return state; }
    /** Returns the number of cores (concurrent workers) allocated, taken from the module. */
    public int cores() { return module.cores(); }
    /** Returns the number of requests waiting (not in service). */
    public int queueLength() { return queue.size(); }
    /** Returns the number of requests currently in service. */
    public int busy() { return busy; }
    /** Returns the number of requests completed. */
    public long processed() { return processed; }
    /** Returns the simulation time (ns) of the last completed request, or of creation/readiness if none. */
    public long lastActive() { return lastActive; }
    /** Returns the number of devices with a sticky session on this instance. */
    public int affinity() { return affinity; }
    void addAffinity(int d) { affinity = Math.max(0, affinity + d); }
    /**
     * Returns true while the instance accepts new requests ({@code PULLING}, {@code STARTING} or {@code READY});
     * requests submitted before {@code READY} queue until it is ready.
     */
    public boolean accepting() { return state == State.READY || state == State.STARTING || state == State.PULLING; }
    /** Returns true if the instance is {@code READY}. */
    public boolean isReady() { return state == State.READY; }
    /** Outstanding work per core (queue + in service). */
    public double load() { return (queue.size() + busy) / (double) cores(); }

    void setState(State s, String reason) {
        State old = state;
        state = s;
        world.lifecycle().instance(id, old == null ? "NEW" : old.name(), s.name(), reason);
    }

    /** Starts the instance: pull the image if the node lacks it, then cold-start. */
    void launch(String reason, Consumer<ServiceInstance> onReady) {
        world.sim().metrics().inc("instance.launched");
        if (node.hasImage(module.image().name())) { setState(State.STARTING, reason); coldStart(onReady); return; }
        setState(State.PULLING, reason);
        ComputeNode reg = world.registryFor(node);
        long t0 = world.sim().now();
        Runnable pulled = () -> {
            if (state == State.STOPPED) return;
            world.sim().metrics().record("instance.image_pull_s", SimTime.toSeconds(world.sim().now() - t0));
            node.cacheImage(module.image().name());
            setState(State.STARTING, "image-pulled");
            coldStart(onReady);
        };
        if (reg == null && world.hasRegistries()) {    // registries exist but none is up/reachable: the pull fails
            world.sim().metrics().inc("instance.pull_failed");
            world.sim().schedule(0, "image-pull-failed", () -> kill("image-pull-failed"));
            return;
        }
        if (reg == null || reg == node) {   // no registry configured (images pre-provisioned) or local registry
            world.sim().schedule(Math.round(module.image().bytes() * 8.0 / 4e9 * 1e9), "image-load", pulled); // local disk ~500 MB/s
        } else {
            Message m = new Message(Message.Kind.IMAGE, (int) Math.min(Integer.MAX_VALUE, module.image().bytes()), world.sim().now(), null);
            world.network().send(m, reg, node, Protocol.preset("tcp"), (ok, r) -> {
                if (ok) pulled.run();
                else { world.sim().metrics().inc("instance.pull_failed"); kill("image-pull-failed"); }
            });
        }
    }

    private void coldStart(Consumer<ServiceInstance> onReady) {
        world.sim().metrics().inc("instance.cold_starts");
        long t = SimTime.s(module.startupS().sample(world.sim().stream("startup:" + module.qualifiedName())));
        world.sim().schedule(t, "cold-start", () -> {
            if (state == State.STOPPED) return;
            readyAt = world.sim().now();
            world.sim().metrics().record("instance.startup_s", SimTime.toSeconds(readyAt - createdAt));
            setState(State.READY, "started");
            lastActive = readyAt;
            if (onReady != null) onReady.accept(this);
            fireWaiters();
            serve();
        });
    }

    /** Enqueues a request; returns false if the instance cannot accept it. */
    boolean submit(Request r) {
        if (!accepting() || !node.isUp()) return false;
        if (module.maxQueue() > 0 && queue.size() >= module.maxQueue()) { world.sim().metrics().inc("instance.queue_overflow"); return false; }
        r.enqueuedAt = world.sim().now();
        queue.add(r);
        serve();
        return true;
    }

    private void serve() {
        while (state == State.READY || (state == State.DRAINING && !queue.isEmpty())) {
            if (busy >= cores() || queue.isEmpty()) break;
            Request r = queue.poll();
            busy++;
            inService.add(r);
            node.coreBusy(+1);
            double mi = r.mi > 0 ? r.mi : module.mi().sample(world.sim().stream("mi:" + module.qualifiedName()));
            mi += world.channelSecurity().serverMi();
            long svc = Math.max(1, Math.round(mi / node.mipsPerCore() * 1e9));
            int gen = generation;
            world.sim().metrics().record("instance.wait_ms", SimTime.toMillis(world.sim().now() - r.enqueuedAt));
            world.sim().schedule(svc, "exec", () -> {
                if (gen != generation) return;
                busy--;
                inService.remove(r);
                node.coreBusy(-1);
                processed++;
                lastActive = world.sim().now();
                world.sim().metrics().inc("processed." + node.tier().name().toLowerCase());
                world.runtime().onProcessed(r, this);
                serve();
                if (state == State.DRAINING && queue.isEmpty() && busy == 0) stop("drained");
            });
        }
    }

    /** Graceful stop: stop accepting, finish queued work, then release resources. */
    void drain(String reason) {
        if (state == State.STOPPED || state == State.DRAINING) return;
        if (state != State.READY) { stop(reason); return; }
        setState(State.DRAINING, reason);
        if (queue.isEmpty() && busy == 0) stop(reason);
    }

    void stop(String reason) {
        if (state == State.STOPPED) return;
        setState(State.STOPPED, reason);
        stoppedAt = world.sim().now();
        node.release(this);
        chargeCost();
        world.runtime().onInstanceStopped(this);
        fireWaiters();
    }

    /** Abrupt stop (node failure): queued and in-service requests are lost. */
    void kill(String reason) {
        if (state == State.STOPPED) return;
        for (Request r : queue) world.runtime().dropped(r, reason);
        for (Request r : inService) world.runtime().dropped(r, reason);
        queue.clear();
        inService.clear();
        generation++;
        if (busy > 0) { node.coreBusy(-busy); busy = 0; }
        stop(reason);
    }

    private boolean costCharged;
    private void chargeCost() {
        if (costCharged) return;
        costCharged = true;
        long end = stoppedAt >= 0 ? stoppedAt : world.sim().now();
        double hours = (end - createdAt) / (double) SimTime.H;
        world.cost().add("compute." + node.tier().name().toLowerCase(), hours * cores() * world.cost().corePrice(node.tier()));
    }
}
