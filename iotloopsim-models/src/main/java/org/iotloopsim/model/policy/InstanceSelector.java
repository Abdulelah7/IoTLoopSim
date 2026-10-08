package org.iotloopsim.model.policy;

/** Chooses the service instance for each request (load balancing / offloading decision). */
public interface InstanceSelector {
    /**
     * Decides for one request. Must eventually call exactly one of {@code ctx.dispatch}, {@code ctx.launchOn} or
     * {@code ctx.drop} (a second call throws {@link IllegalStateException}); may defer the call.
     */
    void select(SelectionContext ctx);
}
