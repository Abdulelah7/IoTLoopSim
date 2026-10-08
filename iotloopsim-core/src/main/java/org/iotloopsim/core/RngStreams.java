package org.iotloopsim.core;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Named, independent random-number streams derived from one master seed.
 * The seed of a stream depends only on the master seed and the stream name, never on
 * creation order, so adding a model (and its stream) leaves other streams unchanged.
 */
public final class RngStreams {
    private final long master;
    private final Map<String, SplittableRandom> streams = new HashMap<>();

    /** Creates a stream factory from the master seed. */
    public RngStreams(long master) { this.master = master; }

    /**
     * Returns the stream for {@code name}, creating it on first use from a seed derived only from the master seed
     * and the name; repeated calls return the same instance.
     */
    public SplittableRandom stream(String name) {
        return streams.computeIfAbsent(name, n -> new SplittableRandom(mix64(master ^ fnv1a64(n))));
    }

    /** Returns the master seed. */
    public long master() { return master; }

    static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) { h ^= (b & 0xff); h *= 0x100000001b3L; }
        return h;
    }

    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
