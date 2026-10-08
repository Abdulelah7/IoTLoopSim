package org.iotloopsim.model.policy;

import java.util.List;
import org.iotloopsim.model.AppModule;
import org.iotloopsim.model.ComputeNode;
import org.iotloopsim.model.IoTWorld;
import org.iotloopsim.model.NetNode;

/** Decides where module instances run: at deployment and whenever an extra instance is needed. */
public interface PlacementPolicy {
    /** Nodes for the initial instances of a module (one entry per instance; repeats allowed). */
    List<ComputeNode> initial(AppModule module, IoTWorld world);
    /** Node for one additional instance, preferably close to {@code near} (may be null); null if no capacity. */
    ComputeNode forNewInstance(AppModule module, NetNode near, IoTWorld world);
}
