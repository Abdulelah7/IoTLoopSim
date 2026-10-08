package org.iotloopsim.model.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.iotloopsim.model.AppModule;
import org.iotloopsim.model.ComputeNode;
import org.iotloopsim.model.Gateway;
import org.iotloopsim.model.IoTDevice;
import org.iotloopsim.model.IoTWorld;
import org.iotloopsim.model.NetNode;
import org.iotloopsim.model.ServiceInstance;
import org.iotloopsim.model.Tier;
import org.iotloopsim.model.policy.PolicyRegistry.Kind;
import org.iotloopsim.util.Cfg;

/** Built-in policies. Each is small on purpose and serves as a template for user-defined ones. */
public final class BuiltinPolicies implements PolicyProvider {

    /**
     * Registers the built-in policies: placement edge-first, fog-first, cloud-only, latency-aware; selectors
     * nearest-least-loaded, round-robin, random; scaling none, threshold; migration none, follow-me; recovery
     * none, replace; rollout all-at-once, batch, canary.
     */
    @Override
    public void register() {
        PolicyRegistry.register(Kind.PLACEMENT, "edge-first", c -> new TierFirst(new Tier[]{Tier.EDGE, Tier.FOG, Tier.CLOUD}, c.bool("everyNode", true)));
        PolicyRegistry.register(Kind.PLACEMENT, "fog-first", c -> new TierFirst(new Tier[]{Tier.FOG, Tier.CLOUD, Tier.EDGE}, c.bool("everyNode", true)));
        PolicyRegistry.register(Kind.PLACEMENT, "cloud-only", c -> new TierFirst(new Tier[]{Tier.CLOUD}, false));
        PolicyRegistry.register(Kind.PLACEMENT, "latency-aware", c -> new LatencyAware());
        PolicyRegistry.register(Kind.SELECTOR, "nearest-least-loaded", c -> new NearestLeastLoaded(c.num("loadWeightMs", -1), c.num("rebalanceLoad", 3.0)));
        PolicyRegistry.register(Kind.SELECTOR, "round-robin", c -> new RoundRobin());
        PolicyRegistry.register(Kind.SELECTOR, "random", c -> new RandomSelector());
        PolicyRegistry.register(Kind.SCALING, "none", c -> new NoScaling());
        PolicyRegistry.register(Kind.SCALING, "threshold", c -> new ThresholdScaling(c.duration("interval", "10s"),
                c.num("upLoad", 2.0), c.num("downLoad", 0.2), c.duration("cooldown", "30s")));
        PolicyRegistry.register(Kind.MIGRATION, "none", c -> (MigrationPolicy) (d, f, t, w) -> { });
        PolicyRegistry.register(Kind.MIGRATION, "follow-me", c -> new FollowMe(c.str("mode", "prewarm"), c.dist("checkpoint", 0)));
        PolicyRegistry.register(Kind.RECOVERY, "none", c -> (RecoveryPolicy) (n, lost, w) -> { });
        PolicyRegistry.register(Kind.RECOVERY, "replace", c -> new Replace());
        PolicyRegistry.register(Kind.ROLLOUT, "all-at-once", c -> new Batched(Integer.MAX_VALUE, 0, 1.0));
        PolicyRegistry.register(Kind.ROLLOUT, "batch", c -> new Batched(c.integer("batchSize", 10), c.duration("interval", "10min"), c.num("abortAbove", 1.0)));
        PolicyRegistry.register(Kind.ROLLOUT, "canary", c -> new Canary(c.num("canaryFraction", 0.05), c.integer("batchSize", 50),
                c.duration("interval", "10min"), c.num("abortAbove", 0.1)));
    }

    // ------------------------------------------------------------------ placement
    static List<ComputeNode> nodesOf(IoTWorld w, Tier t, AppModule m) {
        List<ComputeNode> l = new ArrayList<>();
        for (ComputeNode n : w.computeNodes()) if (n.tier() == t && n.canHost(m)) l.add(n);
        l.sort(Comparator.comparingInt(ComputeNode::freeCores).reversed().thenComparing(ComputeNode::name));
        return l;
    }

    /** Places instances on the first tier that has capacity; on the edge, one instance per edge node by default. */
    public static final class TierFirst implements PlacementPolicy {
        private final Tier[] order;
        private final boolean everyNode;
        /**
         * Creates a placement that tries the tiers in {@code order}; if {@code everyNode} is set, initial
         * placement on edge and fog puts one instance on each node with capacity (up to {@code maxInstances}).
         */
        public TierFirst(Tier[] order, boolean everyNode) { this.order = order; this.everyNode = everyNode; }

        @Override public List<ComputeNode> initial(AppModule m, IoTWorld w) {
            Tier[] tiers = m.pinnedTier() != null ? new Tier[]{m.pinnedTier()} : order;
            for (Tier t : tiers) {
                List<ComputeNode> c = nodesOf(w, t, m);
                if (c.isEmpty()) continue;
                List<ComputeNode> out = new ArrayList<>();
                int target = Math.max(m.minInstances(), (everyNode && t != Tier.CLOUD) ? Math.min(c.size(), m.maxInstances()) : m.minInstances());
                for (int i = 0; i < target; i++) out.add(c.get(i % c.size()));
                return out;
            }
            return List.of();
        }

        @Override public ComputeNode forNewInstance(AppModule m, NetNode near, IoTWorld w) {
            Tier[] tiers = m.pinnedTier() != null ? new Tier[]{m.pinnedTier()} : order;
            for (Tier t : tiers) {
                List<ComputeNode> c = nodesOf(w, t, m);
                if (c.isEmpty()) continue;
                if (near == null) return c.get(0);
                ComputeNode best = c.stream().filter(n -> w.network().estimateLatency(near, n, 1000) != Long.MAX_VALUE)
                        .min(Comparator.comparingLong((ComputeNode n) -> w.network().estimateLatency(near, n, 1000))
                        .thenComparing(ComputeNode::name)).orElse(null);
                if (best != null) return best;
            }
            return null;
        }
    }

    /** Greedy placement minimising the mean estimated latency from the gateways serving the module's devices. */
    public static final class LatencyAware implements PlacementPolicy {
        @Override public List<ComputeNode> initial(AppModule m, IoTWorld w) {
            List<ComputeNode> out = new ArrayList<>();
            List<Gateway> gws = w.gateways();
            for (int i = 0; i < Math.max(1, m.minInstances()); i++) {
                ComputeNode best = null;
                double bestCost = Double.MAX_VALUE;
                for (ComputeNode n : w.computeNodes()) {
                    if (!n.canHost(m) || (m.pinnedTier() != null && n.tier() != m.pinnedTier())) continue;
                    double c = 0;
                    for (Gateway g : gws) c += Math.min(1e12, w.network().estimateLatency(g, n, 1000));
                    if (c < bestCost) { bestCost = c; best = n; }
                }
                if (best != null) out.add(best);
            }
            return out;
        }
        @Override public ComputeNode forNewInstance(AppModule m, NetNode near, IoTWorld w) {
            ComputeNode best = null;
            long bl = Long.MAX_VALUE;
            for (ComputeNode n : w.computeNodes()) {
                if (!n.canHost(m) || (m.pinnedTier() != null && n.tier() != m.pinnedTier())) continue;
                long l = near == null ? -n.freeCores() : w.network().estimateLatency(near, n, 1000);
                if (l != Long.MAX_VALUE && l < bl) { bl = l; best = n; }
            }
            return best;
        }
    }

    // ------------------------------------------------------------------ selection
    /** Minimises estimated network latency + queueing; respects sticky sessions of stateful modules. */
    public static final class NearestLeastLoaded implements InstanceSelector {
        private final double loadWeightMs, rebalanceLoad;
        /**
         * Creates the selector; {@code loadWeightMs} is the queueing penalty in ms per unit of instance load (0 or
         * less uses the module's mean service time), and a sticky instance whose load exceeds {@code
         * rebalanceLoad} has its session migrated in the background to a lightly loaded instance.
         */
        public NearestLeastLoaded(double loadWeightMs, double rebalanceLoad) { this.loadWeightMs = loadWeightMs; this.rebalanceLoad = rebalanceLoad; }
        @Override public void select(SelectionContext ctx) {
            IoTWorld w = ctx.world();
            ServiceInstance sticky = w.runtime().affinity(ctx.request().origin, ctx.module());
            if (sticky != null && sticky.accepting()) {
                // keep the session where its state lives; if that instance is overloaded, move the session in the background
                if (sticky.load() > rebalanceLoad) {
                    ServiceInstance alt = null;
                    for (ServiceInstance i : ctx.candidates())
                        if (i != sticky && i.isReady() && i.load() < rebalanceLoad / 2 && (alt == null || i.load() < alt.load())
                                && w.network().estimateLatency(ctx.from(), i.node(), 1000) != Long.MAX_VALUE) alt = i;
                    if (alt != null) w.runtime().migrateSession(ctx.request().origin, ctx.module(), alt.node());
                }
                ctx.dispatch(sticky);
                return;
            }
            ServiceInstance best = null;
            double bestScore = Double.MAX_VALUE;
            boolean unreachable = false;
            for (ServiceInstance i : ctx.candidates()) {
                long lat = w.network().estimateLatency(ctx.from(), i.node(), ctx.request().bytes + 100);
                if (lat == Long.MAX_VALUE) { unreachable = true; continue; }
                double svcMs = w.runtime().meanMi(ctx.module()) / i.node().mipsPerCore() * 1000;
                double wait = (i.isReady() ? 0 : 1000) + i.load() * (loadWeightMs > 0 ? loadWeightMs : svcMs);
                double score = lat / 1e6 + wait;
                if (score < bestScore) { bestScore = score; best = i; }
            }
            if (best != null) { ctx.dispatch(best); return; }
            NetNode near = ctx.from();
            if (unreachable && w.network().anchor(near) == null) { ctx.drop("no-coverage"); return; }
            ComputeNode n = w.placement().forNewInstance(ctx.module(), near, w);
            if (n == null) ctx.drop(unreachable ? "no-route" : "no-instance");
            else if (w.runtime().instancesOf(ctx.module()).size() < ctx.module().maxInstances()) ctx.launchOn(n);
            else ctx.drop("no-instance");
        }
    }

    /**
     * Instance selector that cycles through the accepting instances in order; drops with {@code no-instance} if
     * there are none.
     */
    public static final class RoundRobin implements InstanceSelector {
        private int next;
        @Override public void select(SelectionContext ctx) {
            if (ctx.candidates().isEmpty()) { ctx.drop("no-instance"); return; }
            ctx.dispatch(ctx.candidates().get(Math.floorMod(next++, ctx.candidates().size())));
        }
    }

    /**
     * Instance selector that picks a uniformly random accepting instance from the {@code random-selector} stream;
     * drops with {@code no-instance} if there are none.
     */
    public static final class RandomSelector implements InstanceSelector {
        @Override public void select(SelectionContext ctx) {
            if (ctx.candidates().isEmpty()) { ctx.drop("no-instance"); return; }
            ctx.dispatch(ctx.candidates().get(ctx.world().sim().stream("random-selector").nextInt(ctx.candidates().size())));
        }
    }

    // ------------------------------------------------------------------ scaling
    /** Scaling policy that never scales (interval 0 disables the autoscaling loop). */
    public static final class NoScaling implements ScalingPolicy {
        @Override public long intervalNs() { return 0; }
        @Override public int decide(AppModule m, List<ServiceInstance> a, IoTWorld w) { return 0; }
    }

    /** Scale out when mean load per core exceeds {@code upLoad}; scale in below {@code downLoad}; with cooldown. */
    public static final class ThresholdScaling implements ScalingPolicy {
        private final long interval, cooldown;
        private final double up, down;
        private final java.util.Map<AppModule, Long> last = new java.util.HashMap<>();
        ThresholdScaling(long interval, double up, double down, long cooldown) { this.interval = interval; this.up = up; this.down = down; this.cooldown = cooldown; }
        @Override public long intervalNs() { return interval; }
        @Override public int decide(AppModule m, List<ServiceInstance> a, IoTWorld w) {
            long now = w.sim().now();
            if (now - last.getOrDefault(m, Long.MIN_VALUE / 2) < cooldown) return 0;
            List<ServiceInstance> ready = a.stream().filter(ServiceInstance::isReady).toList();
            if (ready.isEmpty() || ready.size() < a.size()) return 0;   // wait until pending instances are up
            double load = ready.stream().mapToDouble(ServiceInstance::load).average().orElse(0);
            int d = 0;
            if (load > up && a.size() < m.maxInstances()) d = 1;
            else if (load < down && a.size() > Math.max(1, m.minInstances())) d = -1;
            if (d != 0) last.put(m, now);
            return d;
        }
    }

    // ------------------------------------------------------------------ migration & recovery
    /** Moves a device's stateful sessions to the instance/node closest to its new gateway. */
    public static final class FollowMe implements MigrationPolicy {
        private final String mode;
        private final org.iotloopsim.core.Distribution checkpoint;
        private boolean configured;
        /** Creates a follow-me policy with {@code PREWARM} migration and zero checkpoint time. */
        public FollowMe() { this("prewarm", org.iotloopsim.core.Distribution.constant(0)); }
        /**
         * Creates a follow-me policy; {@code mode} {@code "cold"} (case-insensitive) selects {@code COLD}
         * migration, anything else {@code PREWARM}; {@code checkpoint} is the checkpoint-time distribution in s.
         * The mode is applied to the runtime on the first handover.
         */
        public FollowMe(String mode, org.iotloopsim.core.Distribution checkpoint) { this.mode = mode; this.checkpoint = checkpoint; }
        @Override public void onHandover(IoTDevice d, Gateway from, Gateway to, IoTWorld w) {
            if (!configured) {
                w.runtime().setMigrationModel(mode.equalsIgnoreCase("cold") ? org.iotloopsim.model.AppRuntime.MigrationMode.COLD
                        : org.iotloopsim.model.AppRuntime.MigrationMode.PREWARM, checkpoint);
                configured = true;
            }
            for (AppModule m : w.runtime().statefulModulesOf(d)) {
                ServiceInstance cur = w.runtime().affinity(d, m);
                if (cur == null) continue;      // no session yet: nothing to migrate, the next request picks the nearest instance
                ComputeNode target = null;
                long best = Long.MAX_VALUE;
                for (ServiceInstance i : w.runtime().instancesOf(m)) {
                    long l = w.network().estimateLatency(to, i.node(), 1000);
                    if (i.accepting() && l < best) { best = l; target = i.node(); }
                }
                ComputeNode fresh = w.placement().forNewInstance(m, to, w);
                if (fresh != null && w.network().estimateLatency(to, fresh, 1000) < best) target = fresh;
                if (target != null && (cur == null || cur.node() != target)) w.runtime().migrateSession(d, m, target);
            }
        }
    }

    /** Replaces instances lost on a failed node so that each module keeps at least its minimum instance count. */
    public static final class Replace implements RecoveryPolicy {
        @Override public void onNodeFailure(ComputeNode node, List<ServiceInstance> lost, IoTWorld w) {
            for (ServiceInstance i : lost) {
                AppModule m = i.module();
                int alive = (int) w.runtime().instancesOf(m).stream().filter(ServiceInstance::accepting).count();
                if (alive >= Math.max(1, m.minInstances())) continue;
                ComputeNode n = w.placement().forNewInstance(m, node, w);
                if (n != null) w.runtime().launch(m, n, "recovery");
            }
        }
    }

    // ------------------------------------------------------------------ OTA rollout
    /**
     * OTA rollout in consecutive batches of a fixed size, separated by a fixed interval in ns; the campaign aborts
     * after a batch if the cumulative failure rate exceeds {@code abortAbove}.
     */
    public static class Batched implements RolloutPolicy {
        final int size;
        final long interval;
        final double abortAbove;
        Batched(int size, long interval, double abortAbove) { this.size = size; this.interval = interval; this.abortAbove = abortAbove; }
        @Override public List<List<IoTDevice>> batches(List<IoTDevice> t) {
            List<List<IoTDevice>> out = new ArrayList<>();
            for (int i = 0; i < t.size(); i += size) out.add(new ArrayList<>(t.subList(i, Math.min(t.size(), i + size))));
            return out;
        }
        @Override public long batchIntervalNs() { return interval; }
        @Override public boolean proceed(int done, double failureRate) { return failureRate <= abortAbove; }
    }

    /**
     * Batched rollout whose first batch is a canary of {@code ceil(n * canaryFraction)} devices (at least one);
     * the remaining devices follow in fixed-size batches.
     */
    public static final class Canary extends Batched {
        private final double fraction;
        Canary(double fraction, int size, long interval, double abortAbove) { super(size, interval, abortAbove); this.fraction = fraction; }
        @Override public List<List<IoTDevice>> batches(List<IoTDevice> t) {
            int c = Math.max(1, (int) Math.ceil(t.size() * fraction));
            List<List<IoTDevice>> out = new ArrayList<>();
            out.add(new ArrayList<>(t.subList(0, Math.min(c, t.size()))));
            out.addAll(super.batches(t.subList(Math.min(c, t.size()), t.size())));
            return out;
        }
    }
}
