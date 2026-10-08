package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.Entity;
import org.iotloopsim.core.SimTime;

/**
 * Fault injection. Independent faults draw time-to-failure and repair time per target; correlated faults fail a
 * whole group at once (same zone, same backhaul, same firmware version...), each member with probability
 * {@code pAffect}, then repair them after a sampled outage.
 */
public final class FaultInjector {
    /** Kind of entity a fault applies to: devices, gateways, compute nodes or links. */
    public enum TargetType { DEVICE, GATEWAY, NODE, LINK;
        /**
         * Parses device, gateway, node (also server or compute) or link, case-insensitive, singular or plural;
         * throws {@link IllegalArgumentException} otherwise.
         */
        public static TargetType parse(String s) {
            String t = s.trim().toLowerCase();
            if (t.endsWith("s")) t = t.substring(0, t.length() - 1);
            return switch (t) {
                case "device" -> DEVICE;
                case "gateway" -> GATEWAY;
                case "node", "server", "compute" -> NODE;
                case "link" -> LINK;
                default -> throw new IllegalArgumentException("unknown fault target '" + s + "' (device|gateway|node|link)");
            };
        }
    }

    private final IoTWorld world;
    private final List<Runnable> starters = new ArrayList<>();

    FaultInjector(IoTWorld world) { this.world = world; }

    /** Builds a selector: entity type plus all given tag=value pairs (tag "name" matches entity names). */
    public static Predicate<Entity> selector(TargetType type, Map<String, String> tags) {
        return e -> {
            boolean typeOk = switch (type) {
                case DEVICE -> e instanceof IoTDevice;
                case GATEWAY -> e instanceof Gateway;
                case NODE -> e instanceof ComputeNode;
                case LINK -> e instanceof Link;
            };
            if (!typeOk) return false;
            for (Map.Entry<String, String> t : tags.entrySet()) {
                String v = t.getKey().equals("name") ? e.name() : e.tag(t.getKey());
                if (v == null || !(v.equals(t.getValue()) || (t.getValue().endsWith("*") && v.startsWith(t.getValue().substring(0, t.getValue().length() - 1))))) return false;
            }
            return true;
        };
    }

    List<Entity> targets(Predicate<Entity> sel) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : world.sim().entities()) if (sel.test(e)) out.add(e);
        return out;
    }

    /**
     * Registers an independent fault process started with the world: each matching entity alternates up time
     * sampled from {@code ttfS} (s) and down time sampled from {@code repairS} (s), on its own random stream.
     * Increments {@code faults.independent} on each failure.
     */
    public void independent(String name, Predicate<Entity> sel, Distribution ttfS, Distribution repairS) {
        starters.add(() -> {
            for (Entity e : targets(sel)) {
                SplittableRandom r = world.sim().stream("fault:" + name + ":" + e.name());
                cycle(e, r, ttfS, repairS, name);
            }
        });
    }

    private void cycle(Entity e, SplittableRandom r, Distribution ttf, Distribution rep, String name) {
        world.sim().schedule(SimTime.s(ttf.sample(r)), "fault", () -> {
            if (!isUp(e)) { cycle(e, r, ttf, rep, name); return; }
            fail(e, name);
            world.sim().metrics().inc("faults.independent");
            world.sim().schedule(SimTime.s(rep.sample(r)), "repair", () -> { repair(e); cycle(e, r, ttf, rep, name); });
        });
    }

    /** Correlated outage at fixed times (or recurring with a sampled interval when {@code intervalS} is non-null). */
    public void correlated(String name, Predicate<Entity> sel, List<Long> at, Distribution intervalS, double pAffect, Distribution durationS) {
        starters.add(() -> {
            SplittableRandom r = world.sim().stream("cfault:" + name);
            for (long t : at) world.sim().schedule(Math.max(0, t - world.sim().now()), "cfault", () -> outbreak(name, sel, pAffect, durationS, r));
            if (intervalS != null) recurring(name, sel, intervalS, pAffect, durationS, r);
        });
    }

    private void recurring(String name, Predicate<Entity> sel, Distribution iv, double p, Distribution dur, SplittableRandom r) {
        world.sim().schedule(SimTime.s(iv.sample(r)), "cfault", () -> { outbreak(name, sel, p, dur, r); recurring(name, sel, iv, p, dur, r); });
    }

    private void outbreak(String name, Predicate<Entity> sel, double p, Distribution dur, SplittableRandom r) {
        List<Entity> hit = new ArrayList<>();
        for (Entity e : targets(sel)) if (isUp(e) && r.nextDouble() < p) hit.add(e);
        world.sim().metrics().inc("faults.correlated_events");
        world.sim().metrics().add("faults.correlated_targets", hit.size());
        world.lifecycle().record("fault", name, "ok", "outage", hit.size() + " targets");
        for (Entity e : hit) fail(e, name);
        long d = SimTime.s(dur.sample(r));
        world.sim().schedule(d, "cfault-repair", () -> { for (Entity e : hit) repair(e); world.lifecycle().record("fault", name, "outage", "ok", "repaired"); });
    }

    private static boolean isUp(Entity e) {
        if (e instanceof NetNode n) return n.isUp();
        if (e instanceof Link l) return l.linkUp();
        return true;
    }

    private void fail(Entity e, String reason) {
        world.sim().metrics().inc("faults.failures." + EnergyLedger.layerOf(e));
        if (e instanceof NetNode n) n.fail(reason);
        else if (e instanceof Link l) { l.fail(); world.network().topologyChanged(); world.lifecycle().record("link", l.name(), "UP", "DOWN", reason); }
    }

    private void repair(Entity e) {
        if (e instanceof IoTDevice d && (d.state() == DeviceState.DEPLETED || d.state() == DeviceState.DECOMMISSIONED)) return;
        if (e instanceof NetNode n) n.repair();
        else if (e instanceof Link l) { l.repair(); world.network().topologyChanged(); world.lifecycle().record("link", l.name(), "DOWN", "UP", "repaired"); }
    }

    void start() { for (Runnable r : starters) r.run(); }
}
