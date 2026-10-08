package org.iotloopsim.core;

/** Handle returned when an event is scheduled; allows cancellation. */
public interface EventHandle {
    /**
     * Cancels the pending event (or, for periodic handles, all future repetitions); no effect if it has already
     * run.
     */
    void cancel();
    /** Returns true if the event has been cancelled. */
    boolean isCancelled();
    /**
     * Returns the absolute simulation time (ns) of the pending firing; periodic handles return -1 before the first
     * schedule.
     */
    long time();
}
