package org.iotloopsim.core;

/** Optional per-event trace hook (e.g. to write an event log for debugging or provenance). */
@FunctionalInterface
public interface Tracer {
    /** No-op tracer used by default. */
    Tracer NONE = (t, label) -> {};
    /** Called before each event executes with its time in ns and its label (may be null). */
    void onEvent(long time, String label);
}
