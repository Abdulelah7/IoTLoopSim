package org.iotloopsim.model;

/** A unit of data moving through the network. {@code payload} carries the application object (e.g. a Request). */
public final class Message {
    /** Traffic class of a message; used for per-kind accounting and by security and update models. */
    public enum Kind { SENSOR_DATA, INTERNAL, COMMAND, CONTROL, IMAGE, STATE, FIRMWARE, ATTACK }

    private static long nextId;
    /** Process-wide unique, increasing message id. */
    public final long id;
    /** Traffic class. */
    public final Kind kind;
    /** Application payload size in bytes, excluding protocol and security overhead. */
    public final int bytes;
    /** Creation time in ns of simulation time. */
    public final long created;
    /** Application object carried by the message (e.g. a {@link Request}); may be null. */
    public final Object payload;
    public final String flow;   // "legit" or attacker id, used by security models
    int attempts;

    /**
     * Creates a message with the next global id; {@code bytes} is the payload size, {@code created} the creation
     * time in ns and {@code flow} is {@code "legit"} or an attacker id.
     */
    public Message(Kind kind, int bytes, long created, Object payload, String flow) {
        synchronized (Message.class) { this.id = nextId++; }
        this.kind = kind;
        this.bytes = bytes;
        this.created = created;
        this.payload = payload;
        this.flow = flow;
    }

    /** Creates a legitimate ({@code flow = "legit"}) message; see {@link #Message(Kind, int, long, Object, String)}. */
    public Message(Kind kind, int bytes, long created, Object payload) { this(kind, bytes, created, payload, "legit"); }
    /**
     * Returns the number of transmission attempts made so far for this message (incremented by the network on each
     * try).
     */
    public int attempts() { return attempts; }
}
