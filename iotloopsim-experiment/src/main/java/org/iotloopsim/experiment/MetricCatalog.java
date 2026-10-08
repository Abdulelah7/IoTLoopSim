package org.iotloopsim.experiment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Documented metric definitions (written into every provenance bundle). Plugins can add definitions for their own
 * metrics with {@link #define(String, String)}.
 */
public final class MetricCatalog {
    private MetricCatalog() {}
    public static final Map<String, String> DEFINITIONS = new LinkedHashMap<>();
    static {
        d("loop.generated", "sensor readings that triggered an application");
        d("loop.completed", "loops whose counting branches all finished: every actuation branch (apps with an actuator) or every sink (apps without)");
        d("loop.latency_ms", "time from sensing until the last counting branch finished (control-loop latency), milliseconds");
        d("loop.success_ratio", "loop.completed / loop.generated");
        d("loop.dropped", "loops lost on a counting branch (counted once per loop, with the first cause in loop.dropped.<reason>)");
        d("app.<name>.branch_dropped", "requests lost on branches that do not count for the loop (e.g. archiving in an actuation app)");
        d("update.interrupted", "devices that were depleted, failed or retired while an OTA update was running");
        d("update.download_failed", "OTA images that never reached the device's gateway (device stays on the old firmware)");
        d("energy.category.radio-overhead_J", "measured radio profile: wake-up, preparation, receive windows and post-processing (above baseline)");
        d("loop.deadline_miss_ratio", "completed loops slower than the application deadline / loop.completed");
        d("loop.dropped.<reason>", "requests lost, by cause (radio-loss, no-coverage, node-failure, queue-full, blackhole, ...)");
        d("app.<name>.*", "the loop metrics per application; sink_* for non-actuating branches");
        d("net.pdr", "end-to-end messages delivered / sent (after protocol retransmissions)");
        d("net.retransmissions", "protocol-level retransmissions (MQTT QoS1/2, CoAP CON, TCP)");
        d("instance.cold_starts", "service instances that went through a cold start");
        d("instance.startup_s", "time from instance creation (incl. image pull) to READY, seconds");
        d("instance.wait_ms", "queueing time of requests at service instances, milliseconds");
        d("scaling.out / scaling.in", "autoscaling actions");
        d("migration.downtime_ms", "session migration time during which requests are buffered, milliseconds");
        d("update.*", "OTA firmware campaign outcomes, per-device and campaign durations");
        d("energy.<layer>_J", "energy per layer (device, gateway, network, edge, fog, cloud), joules");
        d("energy.category.<c>_J", "energy per category (radio-tx, radio-rx, sensing, actuation, baseline, compute, network, security, ota)");
        d("carbon.<layer>_g", "operational carbon, gCO2e, from energy x carbon intensity at the time of use");
        d("cost.<item>", "monetary cost: compute core-hours per tier, network transfer, energy");
        d("availability.<class>", "mean fraction of time up (devices, gateways, nodes, links)");
        d("utilization.<tier>", "mean core utilisation per tier");
        d("battery.*_remaining", "remaining battery fraction at the end (mean, min)");
        d("security.*", "attack messages, blocked packets, detections and detection time");
        d("faults.*", "injected failures (independent, correlated events and targets)");
        d("lifecycle.<kind>.<state>", "number of lifecycle transitions into each state");
        d("sim.events / sim.time_s", "events processed and simulated time");
    }
    private static void d(String k, String v) { DEFINITIONS.put(k, v); }

    /** Adds or replaces the definition of a (plugin) metric; it then appears in provenance.json. */
    public static synchronized void define(String metric, String definition) { DEFINITIONS.put(metric, definition); }
}
