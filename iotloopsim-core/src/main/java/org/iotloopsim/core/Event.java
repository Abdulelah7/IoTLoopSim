package org.iotloopsim.core;

/**
 * A scheduled action. Events are ordered by (time, priority, sequence number); the
 * sequence number makes ties deterministic so that a run with the same seed always
 * processes events in the same order.
 */
public final class Event implements Comparable<Event>, EventHandle {
    final long time;
    final int priority;
    final long seq;
    final String label;
    final Runnable action;
    boolean cancelled;

    Event(long time, int priority, long seq, String label, Runnable action) {
        this.time = time;
        this.priority = priority;
        this.seq = seq;
        this.label = label;
        this.action = action;
    }

    /** Orders by time (ns), then priority (lower first), then scheduling sequence number. */
    @Override
    public int compareTo(Event o) {
        int c = Long.compare(time, o.time);
        if (c != 0) return c;
        c = Integer.compare(priority, o.priority);
        if (c != 0) return c;
        return Long.compare(seq, o.seq);
    }

    /** Returns the descriptive label given when scheduling (may be null); passed to the {@link Tracer}. */
    /** Returns the absolute simulation time (ns) at which the event fires. */
    /** Returns true if {@link #cancel()} has been called. */
    /** Marks the event cancelled; the kernel skips it when it reaches the head of the event list. */
    @Override public void cancel() { cancelled = true; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public long time() { return time; }
    public String label() { return label; }
}
