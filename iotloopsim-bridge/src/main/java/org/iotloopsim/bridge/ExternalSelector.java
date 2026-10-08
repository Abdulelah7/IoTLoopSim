package org.iotloopsim.bridge;

import java.util.ArrayDeque;
import java.util.Deque;
import org.iotloopsim.model.AppModule;
import org.iotloopsim.model.policy.InstanceSelector;
import org.iotloopsim.model.policy.SelectionContext;

/**
 * Hands routing decisions for one module to an external agent: requests for {@code target} are parked until the
 * agent answers; all other decisions go to the delegate policy.
 */
public final class ExternalSelector implements InstanceSelector {
    private final InstanceSelector delegate;
    private final AppModule target;
    private final Deque<SelectionContext> pending = new ArrayDeque<>();

    /**
     * Wraps {@code delegate}; non-attack requests for module {@code target} are queued for the external agent
     * instead.
     */
    public ExternalSelector(InstanceSelector delegate, AppModule target) { this.delegate = delegate; this.target = target; }

    @Override public void select(SelectionContext ctx) {
        if (ctx.module() == target && !ctx.request().attack) pending.add(ctx);
        else delegate.select(ctx);
    }

    /** Returns true if a decision for the target module is waiting for the agent. */
    public boolean hasPending() { return !pending.isEmpty(); }
    /** Returns the oldest pending decision without removing it, or null if none. */
    public SelectionContext peek() { return pending.peek(); }
    /** Removes and returns the oldest pending decision, or null if none; the caller must resolve it exactly once. */
    public SelectionContext poll() { return pending.poll(); }
}
