package org.iotloopsim.model;

/** One unit of application work flowing through the module tree (a sensor reading and its derived messages). */
public final class Request {
    private static long next;
    /** Process-wide unique, increasing request id. */
    public final long id;
    /** Application the request belongs to. */
    public final Application app;
    /** Device whose reading (or attack) produced the request. */
    public final IoTDevice origin;
    /** Simulation time in ns of the originating sensor reading; loop latency is measured from it. */
    public final long sensedAt;
    /** Module that must process this request. */
    public final AppModule module;
    /** Size of the request payload in bytes. */
    public final int bytes;
    /** True for attacker-generated requests, which are excluded from loop metrics. */
    public final boolean attack;
    final Loop loop;                 // shared by all requests derived from one sensor reading
    long enqueuedAt;
    int reroutes;
    double mi;

    /**
     * Creates the root request of a new sensing loop; {@code sensedAt} is in ns and {@code bytes} is the payload
     * size in bytes.
     */
    public Request(Application app, IoTDevice origin, long sensedAt, AppModule module, int bytes, boolean attack) {
        this(app, origin, sensedAt, module, bytes, attack, new Loop());
    }

    private Request(Application app, IoTDevice origin, long sensedAt, AppModule module, int bytes, boolean attack, Loop loop) {
        synchronized (Request.class) { this.id = next++; }
        this.app = app; this.origin = origin; this.sensedAt = sensedAt; this.module = module; this.bytes = bytes; this.attack = attack;
        this.loop = loop;
    }

    /** Request for {@code child}, carrying the output of this request's module ({@code module.outBytes()}). */
    public Request next(AppModule child) { return new Request(app, origin, sensedAt, child, module.outBytes(), attack, loop); }

    /**
     * Book-keeping for one sensing loop (a sensor reading and everything derived from it). The loop completes when every
     * branch that counts for the loop ({@link AppModule#countsForLoop()}) has finished, and fails if any of them is dropped.
     */
    static final class Loop {
        int pending = 1;            // outstanding counting branches (the root request counts)
        boolean failed, closed;
    }
    /**
     * Returns the fixed work of this request in million instructions, or 0 if the module's {@link AppModule#mi()}
     * distribution is sampled instead.
     */
    public double mi() { return mi; }
}
