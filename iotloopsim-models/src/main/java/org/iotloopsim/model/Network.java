package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.iotloopsim.core.MetricsRegistry;
import org.iotloopsim.core.Simulation;

/**
 * End-to-end message transport: device radio hop, wired multi-hop route (shortest latency, recomputed after
 * failures), gateway processing (forwarding, IDS, compromised-gateway drops) and protocol-level retransmission.
 */
public final class Network {
    private final IoTWorld world;
    private final Simulation sim;
    private final Map<NetNode, List<Link>> adj = new LinkedHashMap<>();
    private final List<Link> links = new ArrayList<>();
    private final Map<Long, List<Link>> routeCache = new HashMap<>();
    private final Map<Long, List<NetNode>> nodeCache = new HashMap<>();
    private PacketLevelAdapter packetAdapter;

    Network(IoTWorld world) { this.world = world; this.sim = world.sim(); }

    /** Adds {@code l} to the topology, clears the route caches and returns {@code l}. */
    public Link addLink(Link l) {
        links.add(l);
        adj.computeIfAbsent(l.a(), k -> new ArrayList<>()).add(l);
        adj.computeIfAbsent(l.b(), k -> new ArrayList<>()).add(l);
        topologyChanged();
        return l;
    }

    /** Returns an unmodifiable list of all links in insertion order. */
    public List<Link> links() { return Collections.unmodifiableList(links); }
    /** Returns the links incident to {@code n} (empty if none); the list is live. */
    public List<Link> linksOf(NetNode n) { return adj.getOrDefault(n, List.of()); }
    /** Installs the adapter that carries transfers on links at {@code PACKET} fidelity; null disables it. */
    public void setPacketAdapter(PacketLevelAdapter a) { this.packetAdapter = a; }
    /** Returns the packet-level adapter, or null if none is installed. */
    public PacketLevelAdapter packetAdapter() { return packetAdapter; }

    /** Clears cached routes; call after links or nodes fail, recover or are added. */
    public void topologyChanged() { routeCache.clear(); nodeCache.clear(); }

    // ------------------------------------------------------------------ routing
    /** Shortest-latency wired route over links that are up; null if unreachable. */
    public List<Link> route(NetNode from, NetNode to) {
        if (from == to) return List.of();
        long key = ((long) from.id() << 32) | (to.id() & 0xffffffffL);
        if (routeCache.containsKey(key)) return routeCache.get(key);
        Map<NetNode, Long> dist = new HashMap<>();
        Map<NetNode, Link> via = new HashMap<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>((x, y) -> Long.compare((Long) x[1], (Long) y[1]));
        dist.put(from, 0L);
        pq.add(new Object[]{from, 0L});
        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            NetNode u = (NetNode) top[0];
            long d = (Long) top[1];
            if (d > dist.getOrDefault(u, Long.MAX_VALUE)) continue;
            if (u == to) break;
            if (u != from && !u.isUp()) continue;
            for (Link l : adj.getOrDefault(u, List.of())) {
                if (!l.isUp()) continue;
                NetNode v = l.other(u);
                if (v instanceof IoTDevice) continue;
                long nd = d + l.latencyNs() + 1;
                if (nd < dist.getOrDefault(v, Long.MAX_VALUE)) { dist.put(v, nd); via.put(v, l); pq.add(new Object[]{v, nd}); }
            }
        }
        List<Link> path = null;
        if (dist.containsKey(to)) {
            path = new ArrayList<>();
            NetNode cur = to;
            while (cur != from) { Link l = via.get(cur); path.add(l); cur = l.other(cur); }
            Collections.reverse(path);
        }
        routeCache.put(key, path);
        return path;
    }

    /** Wired entry point for a node: devices enter through their gateway. */
    public NetNode anchor(NetNode n) { return n instanceof IoTDevice d ? d.gateway() : n; }

    /** Estimated one-way latency for {@code bytes} from {@code from} to {@code to} (policy helper; Long.MAX_VALUE if unreachable). */
    public long estimateLatency(NetNode from, NetNode to, int bytes) {
        long t = 0;
        NetNode a = from, b = to;
        if (from instanceof IoTDevice d) {
            if (d.gateway() == null) return Long.MAX_VALUE;
            t += d.tech().latencyNs() + d.tech().airtimeNs(bytes);
            a = d.gateway();
        }
        if (to instanceof IoTDevice d) {
            if (d.gateway() == null) return Long.MAX_VALUE;
            t += d.tech().latencyNs() + d.tech().airtimeNs(bytes);
            b = d.gateway();
        }
        List<Link> r = route(a, b);
        if (r == null) return Long.MAX_VALUE;
        NetNode at = a;
        for (Link l : r) { t += l.estimateDelay(bytes, at); at = l.other(at); }
        return t;
    }

    // ------------------------------------------------------------------ sending
    /** Size on the wire of an application payload sent with {@code p} under the world's channel security. */
    public int wireBytes(int payload, Protocol p) { return payload + p.headerBytes() + world.channelSecurity().overheadBytes(); }

    /**
     * Sends a message end to end. Reliable protocols retransmit (exponential back-off) on any drop until the retry
     * budget is spent. The callback fires exactly once.
     */
    public void send(Message m, NetNode src, NetNode dst, Protocol p, Link.Callback cb) {
        MetricsRegistry mx = sim.metrics();
        mx.inc("net.messages.sent");
        if (src instanceof IoTDevice d && world.channelSecurity().deviceJoules() > 0) d.consume(world.channelSecurity().deviceJoules(), "security");
        attempt(m, src, dst, p, 0, cb);
    }

    private void attempt(Message m, NetNode src, NetNode dst, Protocol p, int retry, Link.Callback cb) {
        m.attempts = retry + 1;
        transfer(m, src, dst, (ok, reason) -> {
            MetricsRegistry mx = sim.metrics();
            if (ok) {
                if (p.reliable() && p.ackBytes() > 0) chargeAck(src, dst, p.ackBytes());
                mx.inc("net.messages.delivered");
                cb.done(true, null);
            } else if (p.reliable() && retry < p.maxRetries() && retryable(reason)) {
                mx.inc("net.retransmissions");
                long backoff = p.timeoutNs() << retry;
                sim.schedule(backoff, "retransmit", () -> attempt(m, src, dst, p, retry + 1, cb));
            } else {
                mx.inc("net.messages.dropped");
                mx.inc("net.drop." + reason);
                cb.done(false, reason);
            }
        });
    }

    private static boolean retryable(String reason) { return !"blocked".equals(reason) && !"device-down".equals(reason); }

    private void chargeAck(NetNode src, NetNode dst, int ackBytes) {
        if (src instanceof IoTDevice d && d.isConnected()) d.consume(d.tech().rxPowerW() * d.tech().airtimeNs(ackBytes) / 1e9, "radio-rx");
        if (dst instanceof IoTDevice d && d.isConnected()) d.consume(d.tech().txPowerW() * d.tech().airtimeNs(ackBytes) / 1e9, "radio-tx");
    }

    /** One transmission attempt without retries. */
    public void transfer(Message m, NetNode src, NetNode dst, Link.Callback cb) {
        if (!src.isUp()) { cb.done(false, "device-down"); return; }
        if (src instanceof IoTDevice d) {
            d.radioUp(m.bytes, (ok, reason) -> {
                if (!ok) { cb.done(false, reason); return; }
                arriveAtGateway(m, d.gateway(), dst, cb);
            });
        } else wired(m, src, dst, cb);
    }

    private void arriveAtGateway(Message m, Gateway g, NetNode dst, Link.Callback cb) {
        if (g == null || !g.isUp()) { cb.done(false, "gateway-down"); return; }
        String drop = g.process(m);
        if (drop != null) { cb.done(false, drop); return; }
        sim.schedule(g.forwardNs(), Simulation.PRIO_HIGH, "gw-forward", () -> wired(m, g, dst, cb));
    }

    private void wired(Message m, NetNode from, NetNode dst, Link.Callback cb) {
        NetNode end = dst instanceof IoTDevice d ? d.gateway() : dst;
        if (end == null) { cb.done(false, "no-coverage"); return; }
        List<Link> path = route(from, end);
        if (path == null) { cb.done(false, "no-route"); return; }
        hop(m, path, 0, from, dst, cb);
    }

    private void hop(Message m, List<Link> path, int i, NetNode at, NetNode dst, Link.Callback cb) {
        if (i == path.size()) {
            if (dst instanceof IoTDevice d) {
                if (at instanceof Gateway g && i > 0) {
                    String drop = g.process(m);
                    if (drop != null) { cb.done(false, drop); return; }
                }
                d.radioDown(m.bytes, cb);
            } else if (!dst.isUp()) cb.done(false, "node-down");
            else cb.done(true, null);
            return;
        }
        Link l = path.get(i);
        NetNode next = l.other(at);
        Link.Callback after = (ok, reason) -> {
            if (!ok) { cb.done(false, reason); return; }
            world.energy().charge(l, "network", m.bytes * l.joulesPerByte());
            if (l.costPerGB() > 0) world.cost().add("network", m.bytes / 1e9 * l.costPerGB());
            if (!next.isUp()) { cb.done(false, "node-down"); return; }
            if (next instanceof Gateway g && i + 1 < path.size()) {
                String drop = g.process(m);
                if (drop != null) { cb.done(false, drop); return; }
                sim.schedule(g.forwardNs(), Simulation.PRIO_HIGH, "gw-forward", () -> hop(m, path, i + 1, next, dst, cb));
            } else hop(m, path, i + 1, next, dst, cb);
        };
        if (l.fidelity() == Link.Fidelity.PACKET && packetAdapter != null) packetAdapter.send(at, next, m.bytes, after);
        else l.transmit(m.bytes, at, after);
    }
}
