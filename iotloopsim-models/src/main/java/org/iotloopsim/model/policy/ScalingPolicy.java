package org.iotloopsim.model.policy;

import java.util.List;
import org.iotloopsim.model.AppModule;
import org.iotloopsim.model.IoTWorld;
import org.iotloopsim.model.ServiceInstance;

/** Periodically decides how many instances to add (positive) or remove (negative) for a module. */
public interface ScalingPolicy {
    /** How often {@link #decide} is called, in ns. */
    long intervalNs();
    /**
     * Returns the change in instance count: positive values add instances (placed through the placement policy's
     * {@code forNewInstance}), negative values drain READY instances with the fewest sessions, then the least loaded
     * (never below the module's {@code minInstances}). {@code active} holds the instances that accept work.
     */
    int decide(AppModule module, List<ServiceInstance> active, IoTWorld world);
}
