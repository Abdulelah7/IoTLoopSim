package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;

/**
 * Security models whose costs and effects appear in QoS and energy metrics:
 * <ul>
 *   <li>channel protection (DTLS/TLS) – bytes, device crypto energy, server instructions, handshakes;</li>
 *   <li>flood (DoS) attacks – attacker devices send junk work to a module, loading radio, links and CPU;</li>
 *   <li>blackhole attacks – a compromised gateway silently drops a fraction of traffic;</li>
 *   <li>an IDS at gateways – per-packet delay and energy, probabilistic detection that blocks attackers or
 *       isolates a compromised gateway.</li>
 * </ul>
 */
public final class SecurityManager {
    private final IoTWorld world;
    private final Set<String> blocked = new HashSet<>();
    private final List<Runnable> starters = new ArrayList<>();
    private boolean ids;
    private double idsDetectProb = 0.9;
    private Distribution idsDetectS = Distribution.exponential(30);

    SecurityManager(IoTWorld world) { this.world = world; }

    /** Enables an IDS on all gateways. */
    public void enableIds(long delayNs, double joulesPerPacket, double detectProbability, Distribution detectionTimeS) {
        this.ids = true;
        this.idsDetectProb = detectProbability;
        this.idsDetectS = detectionTimeS;
        starters.add(() -> { for (Gateway g : world.gateways()) g.setIds(delayNs, joulesPerPacket); });
    }

    String filter(Gateway g, Message m) {
        if (!"legit".equals(m.flow) && blocked.contains(m.flow)) { world.sim().metrics().inc("security.blocked_packets"); return "blocked"; }
        return null;
    }

    /** {@code attackers} devices near {@code nearGateway} send {@code ratePerS} junk requests each to {@code target} during [start, end). */
    public void flood(String name, int attackers, Gateway nearGateway, String techName, AppModule target, double ratePerS,
                      int bytes, double miPerRequest, long start, long end) {
        starters.add(() -> {
            SplittableRandom r = world.sim().stream("attack:" + name);
            for (int k = 0; k < attackers; k++) {
                Position p = new Position(nearGateway.position().x() + r.nextDouble() * 10, nearGateway.position().y() + r.nextDouble() * 10);
                IoTDevice a = new IoTDevice(world, name + "-attacker-" + k, "attacker", p, AccessTechnology.preset(techName),
                        Protocol.preset("udp"), null, 0, "n/a");
                world.registerAttacker(a);
                String flow = "attack:" + a.name();
                world.sim().schedule(start, "attack-start", () -> {
                    a.activateAttacker();
                    if (ids) scheduleDetection(name, () -> { blocked.add(flow); world.sim().metrics().inc("security.attackers_blocked"); }, start);
                    long gap = Math.max(1, Math.round(1e9 / ratePerS));
                    world.sim().every(r.nextLong(gap), gap, "attack", () -> {
                        if (world.sim().now() >= end || !a.isConnected()) return;
                        world.sim().metrics().inc("security.attack_messages");
                        world.runtime().injectAttack(a, target, bytes, miPerRequest);
                    });
                });
            }
        });
    }

    /** Gateway {@code g} drops {@code dropFraction} of the traffic it relays during [start, end). */
    public void blackhole(String name, Gateway g, double dropFraction, long start, long end) {
        starters.add(() -> {
            world.sim().schedule(start, "blackhole-start", () -> {
                g.setBlackhole(dropFraction);
                world.lifecycle().record("attack", name, "none", "blackhole", g.name());
                if (ids) scheduleDetection(name, () -> {
                    g.setBlackhole(0);
                    g.fail("isolated-by-ids");
                    world.sim().metrics().inc("security.gateways_isolated");
                    world.sim().schedule(SimTime.MIN * 10, "reinstate", g::repair);
                }, start);
            });
            world.sim().schedule(end, "blackhole-end", () -> g.setBlackhole(0));
        });
    }

    private void scheduleDetection(String name, Runnable onDetect, long start) {
        SplittableRandom r = world.sim().stream("ids:" + name);
        if (r.nextDouble() >= idsDetectProb) { world.sim().metrics().inc("security.undetected"); return; }
        long d = SimTime.s(idsDetectS.sample(r));
        world.sim().schedule(d, "ids-detect", () -> {
            world.sim().metrics().inc("security.detections");
            world.sim().metrics().record("security.detection_s", SimTime.toSeconds(world.sim().now() - start));
            onDetect.run();
        });
    }

    void start() { for (Runnable r : starters) r.run(); }
}
