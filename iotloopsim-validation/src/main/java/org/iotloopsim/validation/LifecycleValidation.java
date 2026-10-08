package org.iotloopsim.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongFunction;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.MetricsRegistry;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.*;
import org.iotloopsim.model.policy.PolicyRegistry;
import org.iotloopsim.model.policy.RolloutPolicy;
import org.iotloopsim.util.Cfg;

/**
 * V1 (lifecycle group) – closed-form checks of the lifecycle models: OTA campaigns, keep-alive scale-to-zero, session
 * migration, correlated faults and hourly carbon accounting. Same conventions as {@link AnalyticalValidation}: each
 * check compares the simulated mean (95 % t-CI over replications) with an analytical value and passes if the
 * analytical value lies inside the CI or within the relative tolerance. Two extra columns report a stricter,
 * equivalence-style (TOST-like) criterion: {@code equivalent = yes} only if the whole 95 % CI of the relative error
 * lies inside ±tolerance.
 *
 * <p>"verification" rows are deterministic set-ups for which the theory is exact (one run, n = 1, CI = 0); the
 * stochastic rows compare a steady-state expectation with {@code REPS} independent replications.
 *
 * usage: java -cp iotloopsim-all.jar org.iotloopsim.validation.LifecycleValidation [outDir] [reps]
 */
public final class LifecycleValidation {
    static int REPS = 10;
    static ExecutorService pool;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "validation-results");
        if (args.length > 1) REPS = Integer.parseInt(args[1]);
        pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Check> checks = new ArrayList<>();
        try {
            checks.addAll(otaAllAtOnce());
            checks.addAll(otaBatches());
            checks.addAll(keepAlive());
            checks.addAll(migration());
            checks.addAll(correlatedFaults());
            checks.addAll(carbon());
        } finally { pool.shutdown(); }
        Files.createDirectories(out);
        StringBuilder b = new StringBuilder(header()).append('\n');
        for (Check c : checks) b.append(csv(c)).append('\n');
        Files.writeString(out.resolve("v1_lifecycle.csv"), b);
        long pass = checks.stream().filter(Check::pass).count();
        long eq = checks.stream().filter(LifecycleValidation::equivalent).count();
        System.out.printf("%n%-10s %-46s %12s %12s %10s %8s %s%n", "group", "test", "expected", "simulated", "CI95", "err%", "verdict/equiv");
        for (Check c : checks)
            System.out.printf("%-10s %-46s %12.6g %12.6g %10.3g %8.3f %s/%s%n", c.group(), c.test(), c.expected(), c.mean(), c.ci95(),
                    c.relError() * 100, c.pass() ? "PASS" : "FAIL", equivalent(c) ? "yes" : "no");
        System.out.printf("%n%d/%d checks passed, %d/%d equivalent; written %s%n", pass, checks.size(), eq, checks.size(), out.resolve("v1_lifecycle.csv"));
    }

    // ------------------------------------------------------------------ CSV with the equivalence columns
    static String header() { return Check.header() + ",equiv_margin_pct,equivalent"; }

    static String csv(Check c) { return c.csv() + String.format(",%.3f,%s", c.tolerance() * 100, equivalent(c) ? "yes" : "no"); }

    /**
     * Equivalence (TOST-style) criterion: the whole 95 % CI of the relative error, rel ± h/|expected|, lies inside
     * ±tolerance. For expected = 0 the error and the CI are absolute, as in {@link Check#relError()}.
     */
    static boolean equivalent(Check c) {
        double e = c.expected(), h = c.ci95(), rel = c.relError();
        double hRel = e == 0 ? h : h / Math.abs(e);
        return Math.abs(rel) + hRel <= c.tolerance() + 1e-12;
    }

    /** Runs {@code REPS} replications in parallel (seeds 1000..); each returns one number. */
    static List<Double> reps(LongFunction<Double> run) throws Exception {
        List<Future<Double>> f = new ArrayList<>();
        for (int i = 0; i < REPS; i++) { final long seed = 1000 + i; f.add(pool.submit(() -> run.apply(seed))); }
        List<Double> v = new ArrayList<>();
        for (Future<Double> x : f) v.add(x.get());
        return v;
    }

    static MetricsRegistry.Sample sample(IoTWorld w, String name) { return w.sim().metrics().sample(name); }

    // ------------------------------------------------------------------ 1. OTA, all at once
    // World: one LoRa (SF7 preset) gateway; the image registry is a compute node next to the gateway on a 1 Gbit/s,
    // 1 ms link (ANALYTICAL); k devices in range, no sensors (no other traffic), install time constant, failProb 0.
    //
    // Derivation from UpdateManager.update(): all k devices of the batch send the image (bytes = 1024*sizeKB, no
    // header) registry -> gateway at the same instant. On an L0 link the serialisation time of the i-th concurrent
    // transfer is fixed at its start with i active flows, so the first image reaches the gateway after s + L
    // (s = 8*bytes/B, L = link latency) and the others later, but long before the downlink is free. Each download
    // then reserves the gateway downlink for O = round(A/d) with A = airtimeNs(bytes + bytes/50), FCFS, so the last
    // reservation ends at s + L + k*O. The device waits a further latency*frames(bytes), installs for I, and the
    // all-at-once policy (interval 0) finishes the campaign at once:
    //     D = s + L + k*A/d + latency*frames + I.
    static final double OTA_BW = 1e9;
    static final long OTA_LAT = SimTime.ms(1);

    static double otaExpectedBatchS(int m, double sizeKB, double installS, double duty) {
        AccessTechnology lora = AccessTechnology.preset("lorawan");
        int bytes = (int) Math.round(sizeKB * 1024);
        long s = Math.round(bytes * 8.0 / OTA_BW * 1e9);
        long occ = Math.round(lora.airtimeNs(bytes + bytes / 50) / duty);
        long extra = lora.latencyNs() * lora.frames(bytes);
        return SimTime.toSeconds(s + OTA_LAT + m * occ + extra) + installS;
    }

    static double otaCampaignS(int k, double sizeKB, double installS, double gwDuty, RolloutPolicy rollout) {
        IoTWorld w = new IoTWorld(1);
        w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("lorawan"), 0);
        ComputeNode repo = w.addComputeNode("repo", new Position(0, 0), Tier.EDGE, 4, 1e6, 1 << 20, 0, 0, true);
        w.addLink("gw-repo", g, repo, OTA_BW, OTA_LAT, 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
        AccessTechnology lora = AccessTechnology.preset("lorawan");
        for (int i = 0; i < k; i++) w.addDevice("d" + i, "fleet", new Position(10 + i, 10), lora, Protocol.preset("udp"), null, 0, "v1");
        w.updates().campaign("fw", e -> e instanceof IoTDevice, "v2", sizeKB, SimTime.s(10), rollout, Distribution.constant(installS),
                0.0, false, 0, gwDuty);
        var r = w.run(SimTime.H * 24 * 30);
        if (r.getOrDefault("update.campaigns_completed", 0.0) != 1.0 || r.getOrDefault("update.succeeded", 0.0) != k) return Double.NaN;
        return sample(w, "update.campaign_duration_s").mean();
    }

    static List<Check> otaAllAtOnce() {
        List<Check> c = new ArrayList<>();
        RolloutPolicy all = PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "all-at-once", null);
        // {k, sizeKB, install s, gateway duty (-1 = default 0.1 for LoRa)}
        double[][] cases = {{1, 64, 30, -1}, {10, 64, 30, -1}, {10, 16, 30, 0.01}};
        for (double[] k : cases) {
            int n = (int) k[0];
            double duty = k[3] > 0 ? k[3] : 0.1;
            double exp = otaExpectedBatchS(n, k[1], k[2], duty);
            double sim = otaCampaignS(n, k[1], k[2], k[3], all);
            c.add(new Check("lifecycle", "OTA campaign duration, all at once",
                    "verification; LoRa SF7, k=" + n + ", " + (int) k[1] + " KiB, I=" + (int) k[2] + " s, gw duty=" + duty
                            + (k[3] > 0 ? " (explicit)" : " (default)") + ", registry 1 Gbit/s 1 ms",
                    exp, List.of(sim), 0.001, "s"));
        }
        return c;
    }

    // ------------------------------------------------------------------ 2. OTA, batches
    // b batches of m devices, interval P. UpdateManager.Run.next(): a batch starts when the previous one has finished
    // AND the interval has elapsed (the interval is scheduled after the last device of a batch completes), each
    // batch takes D_m = s + L + m*A/d + latency*frames + I (the downlink is idle again at its start). Theory: the
    // campaign ends when the last batch finishes, D = b*D_m + (b-1)*P. Reading the code, the interval is ALSO
    // scheduled after the LAST batch (next(b+1) -> finish() runs one interval later), so the metric
    // update.campaign_duration_s is expected to be b*D_m + b*P. Both rows are reported; the first is the theory row.
    static List<Check> otaBatches() {
        List<Check> c = new ArrayList<>();
        int b = 4, m = 5;
        double sizeKB = 16, inst = 30, intervalS = 600, duty = 0.1;
        RolloutPolicy batch = PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "batch",
                Cfg.of(Map.<String, Object>of("batchSize", m, "interval", (int) intervalS + "s")));
        double sim = otaCampaignS(b * m, sizeKB, inst, -1, batch);
        double dm = otaExpectedBatchS(m, sizeKB, inst, duty);
        String p = "verification; LoRa SF7, b=" + b + " batches x m=" + m + ", " + (int) sizeKB + " KiB, I=" + (int) inst + " s, P=" + (int) intervalS
                + " s, gw duty=0.1 (default)";
        c.add(new Check("lifecycle", "OTA batch campaign duration b*Dm+(b-1)*P", p, b * dm + (b - 1) * intervalS, List.of(sim), 0.001, "s"));
        return c;
    }

    // ------------------------------------------------------------------ 3. keep-alive scale-to-zero
    // World: one device, Poisson readings (rate lambda) -> one stateless module (minInstances 0, maxInstances 1,
    // keepAlive K, start-up S constant, service 1 us) on an edge node that is an image registry (no image pull).
    //
    // What the code does (AppRuntime.reapIdle, ServiceInstance):
    //  * the keep-alive timer restarts at request COMPLETION (lastActive) or at readiness, not at arrival;
    //  * the reaper is a periodic check every Delta = 1 s (ticks at 1, 2, 3 ... s) that drains a READY, idle instance
    //    with now - lastActive > K. The instance therefore disappears at tau = c + K + U, U = time to the next tick,
    //    U ~ Uniform(0, Delta] because the completion time c is continuous;
    //  * with no instance the selector launches one on demand (instance.cold_starts + 1); requests arriving while it
    //    is PULLING/STARTING are queued at it (accepting()) and do not trigger another cold start;
    //  * a request dispatched to an instance that stops while the request is in flight is rerouted and cold-starts.
    // With network delay delta and service time x (both constant), request j+1 (gap G ~ Exp(lambda) after request j)
    // is cold iff g_{j+1} + delta > c_j + K + U = g_j + delta + x + K + U, i.e. iff G > x + K + U, so for every
    // "idle start" q = P(cold) = E[exp(-lambda (K + x + U))] = exp(-lambda (K + x)) (1 - exp(-lambda Delta))/(lambda Delta).
    // After a cold start the idle period effectively begins at V = g + S - delta (readiness, minus the delay that
    // also applies to the next request); requests in (g, V) join the starting instance (Poisson, mean lambda (S - delta)).
    // Renewal-reward over cycles that begin at a cold start: requests per cycle = 1 + lambda (S - delta) + (1 - q)/q,
    // so the cold-start fraction is
    //     f = q / (1 + lambda (S - delta) q).
    // The naive e^{-lambda K} ignores the 1 s reaper granularity (factor (1 - e^{-lambda})/lambda, up to 5 % here).
    static final double KA_DELTA_S = 0.0021;   // ideal radio 1 ms + gateway forwarding 0.1 ms + link 1 ms (+ ns serialisation)

    static double keepAliveExpected(double lambda, double k, double s, double x) {
        double q = Math.exp(-lambda * (k + x)) * (1 - Math.exp(-lambda)) / lambda;
        return q / (1 + lambda * (s - KA_DELTA_S) * q);
    }

    static double coldFraction(long seed, double lambda, double k, double s, long horizon, long warm) {
        IoTWorld w = new IoTWorld(seed);
        w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("ideal"), 0);
        ComputeNode n = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 4, 1000, 1 << 20, 0, 0, true);
        w.addLink("gw-edge", g, n, 1e9, SimTime.ms(1), 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
        Application app = new Application("f", "s", null, null, 0);
        app.addModule(new AppModule(app, "fn", Distribution.constant(0.001), 16, new ContainerImage("fn", 1), 1, 64, 0, 1, 0, null, false,
                Distribution.constant(s), SimTime.s(k), 0));
        w.addApplication(app);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), Worlds.idealRadio(0), Protocol.preset("udp"), null, 0, "v1");
        d.addSensor(new Sensor("s", Distribution.exponential(1 / lambda), 16, 0));
        MetricsRegistry mx = w.sim().metrics();
        double[] base = new double[2];
        w.at(warm, () -> { base[0] = mx.counter("loop.generated"); base[1] = mx.counter("instance.cold_starts"); });
        var r = w.run(horizon);
        if (r.getOrDefault("loop.dropped", 0.0) > 0) return Double.NaN;
        return (r.get("instance.cold_starts") - base[1]) / (r.get("loop.generated") - base[0]);
    }

    static List<Check> keepAlive() throws Exception {
        List<Check> c = new ArrayList<>();
        double k = 20, s = 0.2, x = 1e-6;
        for (double lk : new double[]{0.5, 1, 2}) {
            double lambda = lk / k;
            long horizon = SimTime.s(40000 / lambda);     // ~40 000 requests per replication
            long warm = SimTime.s(1000);
            double exp = keepAliveExpected(lambda, k, s, x);
            List<Double> v = reps(seed -> coldFraction(seed, lambda, k, s, horizon, warm));
            c.add(new Check("lifecycle", "Keep-alive cold-start fraction", String.format(
                    "lambda*K=%s, K=20 s, S=0.2 s, reaper 1 s; exact f=q/(1+lambda(S-delta)q) (naive e^-lambdaK=%.5f); run %.0f s/rep, warm-up %d s deleted",
                    lk, Math.exp(-lk), SimTime.toSeconds(horizon), 1000), exp, v, 0.03, "-"));
        }
        return c;
    }

    // ------------------------------------------------------------------ 4. session migration downtime
    // World: device -> gateway -> source node A (EDGE, hosts the only instance, sticky session) -> link A-B -> target
    // node B (FOG). At t = 100 s, AppRuntime.migrateSession(device, module, B) is called (no instance on B).
    //  COLD (AppRuntime.migrateSession, cold branch): checkpoint C, then the state (stateBytes, no header) is sent
    //    A -> B over the internal protocol (single flow: 8*S/B + latency on L0 and on L1), then the destination
    //    instance is launched (image pull from the registry A over the same link if B lacks the image: 8*img/B +
    //    latency) and cold-starts for the start-up time T_s; the downtime ends when it is READY:
    //        D_cold = C + (8 S/B + L) [+ (8 img/B + L)] + T_s.
    //  PREWARM: the destination instance is launched at once (B is a registry here: no pull) while the state is sent
    //    in parallel; the downtime ends when both are done, the checkpoint is not used:
    //        D_prewarm = max(T_s, 8 S/B + L).
    enum Dest { REGISTRY, PULL }

    static double migrationDowntimeMs(AppRuntime.MigrationMode mode, double ckptS, long stateBytes, double bw, long lat, double startupS,
                                      Dest dest, double imageMB, Link.Fidelity fid) {
        IoTWorld w = new IoTWorld(1);
        w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("ideal"), 0);
        ComputeNode a = w.addComputeNode("src", new Position(0, 0), Tier.EDGE, 4, 1000, 1 << 20, 0, 0, dest == Dest.PULL);
        ComputeNode b = w.addComputeNode("dst", new Position(0, 0), Tier.FOG, 4, 1000, 1 << 20, 0, 0, dest == Dest.REGISTRY);
        w.addLink("gw-src", g, a, 1e9, SimTime.ms(1), 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
        w.addLink("src-dst", a, b, bw, lat, 0, 0, 0, 0, fid);
        Application app = new Application("m", "s", null, null, 0);
        AppModule mod = app.addModule(new AppModule(app, "svc", Distribution.constant(0.001), 16, new ContainerImage("svc", imageMB), 1, 64, 1, 2,
                stateBytes, null, false, Distribution.constant(startupS), 0, 0));
        w.addApplication(app);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), Worlds.idealRadio(0), Protocol.preset("udp"), null, 0, "v1");
        d.addSensor(new Sensor("s", Distribution.constant(10), 16, 0));
        w.runtime().setMigrationModel(mode, Distribution.constant(ckptS));
        w.at(SimTime.s(100), () -> w.runtime().migrateSession(d, mod, b));
        var r = w.run(SimTime.s(400));
        if (r.getOrDefault("migration.completed", 0.0) != 1.0) return Double.NaN;
        return sample(w, "migration.downtime_ms").mean();
    }

    static List<Check> migration() {
        List<Check> c = new ArrayList<>();
        double bw = 100e6, ckpt = 0.5, ts = 1.0, img = 50;
        long lat = SimTime.ms(5), mib = 1L << 20;
        double latS = SimTime.toSeconds(lat);
        AppRuntime.MigrationMode COLD = AppRuntime.MigrationMode.COLD, PRE = AppRuntime.MigrationMode.PREWARM;
        // COLD, destination has the image, L0 and L1 link
        for (Link.Fidelity f : new Link.Fidelity[]{Link.Fidelity.ANALYTICAL, Link.Fidelity.QUEUE}) {
            double exp = (ckpt + 8.0 * mib / bw + latS + ts) * 1000;
            c.add(new Check("lifecycle", "COLD migration downtime C+8S/B+L+Ts", "verification; S=1 MiB, B=100 Mbit/s, L=5 ms, C=0.5 s, Ts=1 s, link "
                    + (f == Link.Fidelity.ANALYTICAL ? "L0" : "L1") + ", image cached", exp,
                    List.of(migrationDowntimeMs(COLD, ckpt, mib, bw, lat, ts, Dest.REGISTRY, img, f)), 0.001, "ms"));
        }
        // COLD with image pull from the source registry after the state transfer
        double expPull = (ckpt + 8.0 * mib / bw + latS + 8.0 * img * mib / bw + latS + ts) * 1000;
        c.add(new Check("lifecycle", "COLD migration downtime with image pull", "verification; as above + 50 MiB image pulled over the same link (L0) after the state",
                expPull, List.of(migrationDowntimeMs(COLD, ckpt, mib, bw, lat, ts, Dest.PULL, img, Link.Fidelity.ANALYTICAL)), 0.001, "ms"));
        // PREWARM: start-up dominated and transfer dominated
        double expPre1 = Math.max(ts, 8.0 * mib / bw + latS) * 1000;
        c.add(new Check("lifecycle", "PREWARM migration downtime max(Ts, 8S/B+L)", "verification; S=1 MiB, B=100 Mbit/s, L=5 ms, Ts=1 s (start-up dominated), L0",
                expPre1, List.of(migrationDowntimeMs(PRE, ckpt, mib, bw, lat, ts, Dest.REGISTRY, img, Link.Fidelity.ANALYTICAL)), 0.001, "ms"));
        double expPre2 = Math.max(ts, 8.0 * 64 * mib / bw + latS) * 1000;
        c.add(new Check("lifecycle", "PREWARM migration downtime max(Ts, 8S/B+L)", "verification; S=64 MiB, B=100 Mbit/s, L=5 ms, Ts=1 s (transfer dominated), L0",
                expPre2, List.of(migrationDowntimeMs(PRE, ckpt, 64 * mib, bw, lat, ts, Dest.REGISTRY, img, Link.Fidelity.ANALYTICAL)), 0.001, "ms"));
        return c;
    }

    // ------------------------------------------------------------------ 5. correlated-fault availability
    // FaultInjector.correlated with a recurring interval: shocks form a renewal process with Exp(T) gaps (Poisson,
    // rate 1/T); at each shock every member that is UP fails independently with probability p (a shock to an already
    // failed member does nothing: outbreak() only considers isUp() targets); hit members are repaired after the fixed
    // duration R. While a member is up its failures are a Poisson process thinned by p, so its up time is Exp with
    // mean T/p; its down time is exactly R. Alternating renewal process: A = (T/p)/(T/p + R) = 1/(1 + pR/T).
    static List<Check> correlatedFaults() throws Exception {
        List<Check> c = new ArrayList<>();
        double cycles = 5000;
        for (double[] k : new double[][]{{100, 0.5, 20}, {50, 0.2, 100}, {1000, 1.0, 100}}) {
            double t = k[0], p = k[1], rep = k[2];
            double exp = 1 / (1 + p * rep / t);
            double horizonS = cycles * (t / p + rep);
            List<Double> v = reps(seed -> {
                IoTWorld w = new IoTWorld(seed);
                for (int i = 0; i < 50; i++) w.addComputeNode("n" + i, new Position(0, 0), Tier.EDGE, 1, 1000, 1024, 0, 0, false);
                w.faults().correlated("zone", e -> e instanceof ComputeNode, List.of(), Distribution.exponential(t), p, Distribution.constant(rep));
                return w.run(SimTime.s(horizonS)).get("availability.nodes");
            });
            c.add(new Check("lifecycle", "Correlated-fault availability 1/(1+pR/T)", String.format(
                    "T=%.0f s, p=%s, R=%.0f s, 50 members; run %.0f s/rep (~%d up/down cycles per member), no warm-up deletion (start up)",
                    t, p, rep, horizonS, (int) cycles), exp, v, 0.01, "-"));
        }
        return c;
    }

    // ------------------------------------------------------------------ 6. carbon with an hourly profile
    // CarbonModel indexes a 24-value profile by hour of simulation time modulo 24; EnergyLedger charges lazily
    // integrated energy with the time-weighted mean intensity over the charged interval. A constant-power load P
    // over H hours therefore emits P * sum_h I_h * 1 h (partial last hour weighted by its fraction), whatever the
    // charging instants are.
    static final double[] PROFILE = {300, 280, 260, 250, 250, 270, 320, 380, 420, 400, 350, 300,
            250, 220, 210, 230, 280, 350, 420, 450, 430, 400, 360, 330};

    static double carbonExpectedG(double watts, double hours, double[] prof) {
        double g = 0;
        int full = (int) Math.floor(hours);
        for (int h = 0; h < full; h++) g += prof[h % prof.length];
        g += (hours - full) * prof[full % prof.length];
        return watts / 1000.0 * g;   // W * 1 h = W/1000 kWh, times gCO2e/kWh
    }

    static List<Double> profileList(double[] p, double scale) { List<Double> l = new ArrayList<>(); for (double x : p) l.add(x * scale); return l; }

    static List<Check> carbon() {
        List<Check> c = new ArrayList<>();
        double pw = 50;
        // (a) idle node, energy charged once at the end of the run over [0, 24 h]
        for (double hours : new double[]{24, 36.5}) {
            IoTWorld w = new IoTWorld(1);
            w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 1, 1000, 1 << 20, pw, pw, false);
            w.carbon().setDefault(profileList(PROFILE, 1));
            double sim = w.run(SimTime.s(hours * 3600)).get("carbon.edge_g");
            c.add(new Check("lifecycle", "Carbon, hourly profile, lazily charged load", "verification; constant 50 W over " + hours
                    + " h, one charge at the end (time-weighted intensity)", carbonExpectedG(pw, hours, PROFILE), List.of(sim), 0.001, "g"));
        }
        // (b) same constant power (idle = max) but charged at every core-state change of Poisson traffic (~350 000 charges)
        {
            IoTWorld w = Worlds.queue(1, 2, Distribution.exponential(100), 1000, 1, 10, pw, pw);
            w.carbon().setDefault(profileList(PROFILE, 1));
            double sim = w.run(SimTime.H * 24).get("carbon.edge_g");
            c.add(new Check("lifecycle", "Carbon, hourly profile, incrementally charged", "verification; constant 50 W (idle=max) over 24 h, charged at every busy/idle change of Poisson traffic",
                    carbonExpectedG(pw, 24, PROFILE), List.of(sim), 0.001, "g"));
        }
        // (c) device baseline power with a per-layer profile override
        {
            IoTWorld w = new IoTWorld(1);
            w.addDevice("d", "g", new Position(1, 1), Worlds.idealRadio(0), Protocol.preset("udp"), null, 1.0, "v1");
            w.carbon().setDefault(List.of(999.0));
            double[] dev = new double[24];
            for (int i = 0; i < 24; i++) dev[i] = PROFILE[(i + 12) % 24] * 0.5;
            w.carbon().setLayer("device", profileList(dev, 1));
            double sim = w.run(SimTime.H * 24).get("carbon.device_g");
            c.add(new Check("lifecycle", "Carbon, per-layer profile, device baseline", "verification; device baseline 1 W over 24 h, layer override (default 999 g/kWh unused)",
                    carbonExpectedG(1.0, 24, dev), List.of(sim), 0.001, "g"));
        }
        return c;
    }
}
