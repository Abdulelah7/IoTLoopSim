package org.iotloopsim.model;

/**
 * Extension point for L2 (packet-level) co-simulation, e.g. delegating a subnet to ns-3 or OMNeT++.
 * An adapter receives a message for a path segment and reports delivery time or loss back to the kernel.
 * IoTLoopSim 0.1 ships the interface only; links configured as PACKET fall back to the L1 queue model
 * and the run's provenance records the fallback.
 */
public interface PacketLevelAdapter {
    /** Delivers {@code bytes} from {@code from} to {@code to}; must invoke {@code cb} exactly once. */
    void send(NetNode from, NetNode to, int bytes, Link.Callback cb);
    /** Returns the adapter name, recorded in run provenance. */
    String name();
}
