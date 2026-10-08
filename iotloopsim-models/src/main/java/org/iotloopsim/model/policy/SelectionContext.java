package org.iotloopsim.model.policy;

import java.util.List;
import org.iotloopsim.model.AppModule;
import org.iotloopsim.model.IoTWorld;
import org.iotloopsim.model.NetNode;
import org.iotloopsim.model.Request;
import org.iotloopsim.model.ServiceInstance;

/**
 * A pending routing decision: which instance of {@code module} should serve {@code request}, sent from {@code from}.
 * A selector must eventually call exactly one of {@link #dispatch}, {@link #launchOn} or {@link #drop}; it may do so
 * later (e.g. an external RL agent), which pauses that request until the decision arrives.
 */
public interface SelectionContext {
    /** The request to route (its {@code bytes}, {@code origin} device and {@code sensedAt} time). */
    Request request();
    /** The module that must serve it. */
    AppModule module();
    /** Where the request currently is (a gateway or compute node). */
    NetNode from();
    /** Instances of the module that accept work (READY, STARTING or PULLING), in a stable order. */
    List<ServiceInstance> candidates();
    /** The world, for topology, latency estimates and policy state. */
    IoTWorld world();
    /** Sends the request to {@code instance}. */
    void dispatch(ServiceInstance instance);
    /** Starts a new instance on {@code node} (scale from zero / on-demand) and serves the request there. */
    void launchOn(org.iotloopsim.model.ComputeNode node);
    /** Drops the request; {@code reason} is counted as {@code loop.dropped.<reason>}. */
    void drop(String reason);
}
