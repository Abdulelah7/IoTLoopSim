package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.List;
import org.iotloopsim.core.Distribution;

/**
 * A processing step of an application (a microservice / function). Modules form a tree rooted at the module that
 * receives sensor data; modules flagged {@code actuate} send a command back to an actuator when they finish.
 */
public final class AppModule {
    private final String name;
    private final Application app;
    private final Distribution mi;          // million instructions per request
    private final int outBytes;
    private ContainerImage image;
    private final int cores, ramMB;
    private final int minInstances, maxInstances;
    private final long stateBytes;
    private final Tier pinnedTier;
    private final boolean actuate;
    private final Distribution startupS;
    private final long keepAliveNs;         // >0: scale to zero after this idle time (serverless)
    private final int maxQueue;
    private final List<AppModule> children = new ArrayList<>();

    /**
     * @param app          owning application
     * @param name         module name (unique within the application)
     * @param mi           work per request in million instructions (service time = mi / node MIPS per core)
     * @param outBytes     bytes this module emits per request: the message sent to each child module, or the command
     *                     sent to the actuator when {@code actuate} is set (32 B if 0)
     * @param image        container image (pulled from the nearest registry on first start at a node)
     * @param cores        cores reserved per instance; {@code ramMB} memory reserved per instance
     * @param minInstances instances kept running; {@code maxInstances} upper bound for scaling
     * @param stateBytes   session state per device (&gt; 0 makes the module stateful: sticky sessions, migration)
     * @param pinnedTier   restricts placement to one tier ({@code null} = any); placement policies must honour it
     * @param actuate      when processing finishes, send a command to the application's actuator
     * @param startupS     container start-up time distribution, in seconds
     * @param keepAliveNs  &gt; 0: scale to zero after this idle time (serverless); 0 = never
     * @param maxQueue     queue capacity per instance (requests beyond it are dropped as queue-full)
     */
    public AppModule(Application app, String name, Distribution mi, int outBytes, ContainerImage image, int cores, int ramMB,
                     int minInstances, int maxInstances, long stateBytes, Tier pinnedTier, boolean actuate,
                     Distribution startupS, long keepAliveNs, int maxQueue) {
        this.app = app; this.name = name; this.mi = mi; this.outBytes = outBytes; this.image = image;
        this.cores = cores; this.ramMB = ramMB; this.minInstances = minInstances; this.maxInstances = maxInstances;
        this.stateBytes = stateBytes; this.pinnedTier = pinnedTier; this.actuate = actuate;
        this.startupS = startupS; this.keepAliveNs = keepAliveNs; this.maxQueue = maxQueue;
    }

    /** Returns the module name, unique within its application. */
    public String name() { return name; }
    /** Returns {@code application/module}, unique within the world. */
    public String qualifiedName() { return app.name() + "/" + name; }
    /** Returns the owning application. */
    public Application app() { return app; }
    /** Returns the per-request work distribution in million instructions. */
    public Distribution mi() { return mi; }
    /**
     * Returns the bytes emitted per request to each child module or as an actuation command (0 means a 32 B
     * command).
     */
    public int outBytes() { return outBytes; }
    /** Returns the current container image (changed by rolling updates). */
    public ContainerImage image() { return image; }
    void setImage(ContainerImage img) { this.image = img; }
    /** Returns the cores reserved per instance, which is also its number of concurrent workers. */
    public int cores() { return cores; }
    /** Returns the memory reserved per instance in MB. */
    public int ramMB() { return ramMB; }
    /** Returns the number of instances kept running (lower bound for scale-in and scale-to-zero). */
    public int minInstances() { return minInstances; }
    /** Returns the upper bound on concurrent instances. */
    public int maxInstances() { return maxInstances; }
    /** Returns the per-device session state size in bytes (0 for stateless modules). */
    public long stateBytes() { return stateBytes; }
    /** Returns true if {@link #stateBytes()} is positive (sticky sessions and migration apply). */
    public boolean stateful() { return stateBytes > 0; }
    /** Returns the tier this module is restricted to, or null for any tier. */
    public Tier pinnedTier() { return pinnedTier; }
    /** Returns true if finishing a request sends a command to the application's actuator. */
    public boolean actuate() { return actuate; }
    /** Returns the container start-up time distribution in s. */
    public Distribution startupS() { return startupS; }
    /** Returns the idle time in ns after which an instance is scaled to zero, or 0 if instances are never reaped. */
    public long keepAliveNs() { return keepAliveNs; }
    /** Returns the per-instance queue capacity in requests (0 or less means unbounded). */
    public int maxQueue() { return maxQueue; }
    /** Returns the live list of downstream modules. */
    public List<AppModule> children() { return children; }
    /** Appends {@code m} as a downstream module that receives this module's output. */
    public void addChild(AppModule m) { children.add(m); }

    /** True if this module, or any module downstream of it, sends an actuation command. */
    public boolean leadsToActuation() {
        if (actuate) return true;
        for (AppModule c : children) if (c.leadsToActuation()) return true;
        return false;
    }

    /**
     * Whether requests at this module are part of the sensing-to-outcome loop measured by {@code loop.*} metrics: in an
     * application with actuation, only branches that lead to an actuator; otherwise every branch (the loop ends at the sinks).
     */
    public boolean countsForLoop() { return !app.root().leadsToActuation() || leadsToActuation(); }
    @Override public String toString() { return qualifiedName(); }
}
