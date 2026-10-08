package org.iotloopsim.model.policy;

import java.util.List;
import org.iotloopsim.model.IoTDevice;

/** Splits an OTA firmware campaign into batches and decides whether to continue after each batch. */
public interface RolloutPolicy {
    /** Splits the campaign's target devices into ordered batches. */
    List<List<IoTDevice>> batches(List<IoTDevice> targets);
    /** Pause between the end of one batch and the start of the next, in ns. */
    long batchIntervalNs();
    /** Called after each batch with the failure rate so far; return false to abort the campaign. */
    boolean proceed(int finishedBatches, double failureRate);
}
