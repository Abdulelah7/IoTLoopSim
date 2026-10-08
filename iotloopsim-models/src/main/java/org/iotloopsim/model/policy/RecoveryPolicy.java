package org.iotloopsim.model.policy;

import java.util.List;
import org.iotloopsim.model.ComputeNode;
import org.iotloopsim.model.IoTWorld;
import org.iotloopsim.model.ServiceInstance;

/** Reacts to compute-node failures and repairs. */
public interface RecoveryPolicy {
    /**
     * Called when {@code node} fails; {@code lost} are the instances that were running there (already stopped). To
     * replace them, call {@code world.runtime().launch(module, node, "recovery")} on another node.
     */
    void onNodeFailure(ComputeNode node, List<ServiceInstance> lost, IoTWorld world);
    /** Called when {@code node} is repaired (empty, with its image cache kept). */
    default void onNodeRepair(ComputeNode node, IoTWorld world) {}
}
