package org.iotloopsim.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/**
 * Discrete-event simulation kernel.
 *
 * <p>Single-threaded and deterministic: the future-event list is a binary heap ordered by
 * (time, priority, insertion sequence). Randomness is obtained only through named
 * {@link RngStreams}, so adding a new model does not perturb the random numbers seen by
 * existing models. Parallelism is achieved across independent replications, not inside a run.
 */
public final class Simulation {
    /** Default event priorities (lower runs first at equal time). */
    public static final int PRIO_SYSTEM = 0, PRIO_HIGH = 10, PRIO_NORMAL = 50, PRIO_LOW = 90;

    private final PriorityQueue<Event> fel = new PriorityQueue<>();
    private final RngStreams rng;
    private final MetricsRegistry metrics = new MetricsRegistry();
    private final List<Entity> entities = new ArrayList<>();
    private final Map<String, Entity> byName = new LinkedHashMap<>();
    private final List<Runnable> endHooks = new ArrayList<>();
    private final long seed;
    private long now;
    private long seq;
    private long endTime = Long.MAX_VALUE;
    private long eventsProcessed;
    private boolean stopped;
    private boolean finished;
    private Tracer tracer = Tracer.NONE;

    /** Creates an empty simulation at time 0 whose random streams all derive from {@code seed}. */
    public Simulation(long seed) {
        this.seed = seed;
        this.rng = new RngStreams(seed);
    }

    // ---------------------------------------------------------------- time and scheduling
    /** Returns the current simulation time in ns. */
    public long now() { return now; }
    /** Returns the current simulation time in seconds. */
    public double nowSeconds() { return SimTime.toSeconds(now); }
    /** Returns the master seed passed to the constructor. */
    public long seed() { return seed; }
    /** Returns the number of non-cancelled events executed so far. */
    public long eventsProcessed() { return eventsProcessed; }
    /** Returns the number of events in the future-event list, including cancelled ones not yet discarded. */
    public int pendingEvents() { return fel.size(); }

    /**
     * Sets the absolute end time in ns; events scheduled after it are not executed. Default {@code Long.MAX_VALUE}
     * (no limit).
     */
    public void setEndTime(long endTime) { this.endTime = endTime; }
    /** Returns the absolute end time in ns, or {@code Long.MAX_VALUE} if unlimited. */
    public long endTime() { return endTime; }

    /**
     * Schedules {@code action} {@code delay} ns from now at {@link #PRIO_NORMAL} without a label; throws {@link
     * IllegalArgumentException} if {@code delay} is negative.
     */
    public EventHandle schedule(long delay, Runnable action) {
        return schedule(delay, PRIO_NORMAL, null, action);
    }

    /**
     * Schedules a labelled {@code action} {@code delay} ns from now at {@link #PRIO_NORMAL}; throws {@link
     * IllegalArgumentException} if {@code delay} is negative.
     */
    public EventHandle schedule(long delay, String label, Runnable action) {
        return schedule(delay, PRIO_NORMAL, label, action);
    }

    /**
     * Schedules {@code action} {@code delay} ns from now with the given priority (lower runs first at equal time);
     * throws {@link IllegalArgumentException} if {@code delay} is negative.
     */
    public EventHandle schedule(long delay, int priority, String label, Runnable action) {
        if (delay < 0) throw new IllegalArgumentException("negative delay " + delay + " for " + label);
        return scheduleAt(now + delay, priority, label, action);
    }

    /**
     * Schedules {@code action} at absolute time {@code time} (ns); throws {@link IllegalArgumentException} if
     * {@code time} is before {@link #now()}.
     */
    public EventHandle scheduleAt(long time, int priority, String label, Runnable action) {
        if (time < now) throw new IllegalArgumentException("cannot schedule in the past: " + time + " < " + now);
        Event e = new Event(time, priority, seq++, label, action);
        fel.add(e);
        return e;
    }

    /** Schedules {@code action} every {@code period} starting after {@code initialDelay}; cancel via the handle. */
    public EventHandle every(long initialDelay, long period, String label, Runnable action) {
        if (period <= 0) throw new IllegalArgumentException("period must be > 0");
        PeriodicHandle h = new PeriodicHandle();
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (h.cancelled) return;
            action.run();
            if (!h.cancelled) h.current = schedule(period, PRIO_NORMAL, label, tick[0]);
        };
        h.current = schedule(initialDelay, PRIO_NORMAL, label, tick[0]);
        return h;
    }

    private static final class PeriodicHandle implements EventHandle {
        EventHandle current;
        boolean cancelled;
        @Override public void cancel() { cancelled = true; if (current != null) current.cancel(); }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public long time() { return current == null ? -1 : current.time(); }
    }

    // ---------------------------------------------------------------- running
    /** Runs until the event list is empty, the end time is reached, or {@link #stop()} is called. */
    public void run() {
        runUntilPaused(() -> false);
        finish();
    }

    /**
     * Processes events until {@code pause} becomes true (checked after each event), the end time is reached,
     * or no events remain. Returns true if it paused (more simulation remains), false if the run is over.
     * Used by the Gym bridge to hand decisions to an external agent.
     */
    public boolean runUntilPaused(BooleanSupplier pause) {
        while (!stopped) {
            Event e = fel.peek();
            if (e == null || e.time > endTime) { now = Math.max(now, Math.min(endTime, e == null ? now : endTime)); return false; }
            fel.poll();
            if (e.cancelled) continue;
            now = e.time;
            tracer.onEvent(now, e.label);
            e.action.run();
            eventsProcessed++;
            if (pause.getAsBoolean()) return true;
        }
        return false;
    }

    /** Must be called once after the run (run() does it) to let models close their accounting. */
    public void finish() {
        if (finished) return;
        finished = true;
        if (endTime != Long.MAX_VALUE && now < endTime && !stopped) now = endTime;
        for (Runnable r : endHooks) r.run();
    }

    /** Returns true once {@link #finish()} has run. */
    public boolean isFinished() { return finished; }
    /** Requests the run loop to stop after the current event; end hooks still run in {@link #finish()}. */
    public void stop() { stopped = true; }
    /** Returns true if {@link #stop()} has been called. */
    public boolean isStopped() { return stopped; }

    /** Hooks run when the simulation ends (e.g. to integrate energy up to the end time). */
    public void onEnd(Runnable hook) { endHooks.add(hook); }

    // ---------------------------------------------------------------- services
    /** Returns the named random-stream factory of this run. */
    public RngStreams rng() { return rng; }
    /** Shortcut for {@code rng().stream(name)}. */
    public SplittableRandom stream(String name) { return rng.stream(name); }
    /** Returns the metrics registry of this run. */
    public MetricsRegistry metrics() { return metrics; }
    /** Installs a per-event trace hook; null restores the no-op {@link Tracer#NONE}. */
    public void setTracer(Tracer tracer) { this.tracer = tracer == null ? Tracer.NONE : tracer; }

    // ---------------------------------------------------------------- entities
    /**
     * Adds {@code e} to the entity list and name index and returns it; called by the {@link Entity} constructor.
     * Throws {@link IllegalArgumentException} on a duplicate name.
     */
    public <T extends Entity> T register(T e) {
        if (byName.containsKey(e.name())) throw new IllegalArgumentException("duplicate entity name: " + e.name());
        entities.add(e);
        byName.put(e.name(), e);
        return e;
    }

    /** Returns the id the next registered entity will receive (the current entity count). */
    public int nextEntityId() { return entities.size(); }
    /** Returns an unmodifiable list of all entities in registration order. */
    public List<Entity> entities() { return Collections.unmodifiableList(entities); }
    /** Returns the entity with the given name, or null if none. */
    public Entity entity(String name) { return byName.get(name); }

    /** Returns a new list of all entities that are instances of {@code type}, in registration order. */
    @SuppressWarnings("unchecked")
    public <T extends Entity> List<T> entities(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Entity e : entities) if (type.isInstance(e)) out.add((T) e);
        return out;
    }
}
