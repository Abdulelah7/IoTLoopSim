package org.iotloopsim.model.policy;

import org.iotloopsim.model.Gateway;
import org.iotloopsim.model.IoTDevice;
import org.iotloopsim.model.IoTWorld;

/** Reacts to device handovers (e.g. follow-me migration of stateful sessions). */
public interface MigrationPolicy {
    /**
     * Called after {@code device} has re-attached from {@code from} (may be null) to {@code to}. To move a stateful
     * session, call {@code world.runtime().migrateSession(device, module, targetNode)}; the configured migration mode
     * (PREWARM or COLD) decides how the state moves.
     */
    void onHandover(IoTDevice device, Gateway from, Gateway to, IoTWorld world);
}
