package org.iotloopsim.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongFunction;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.MetricsRegistry;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.*;

/**
 * V1 – operational validation against closed-form models (queueing theory, protocol reliability, renewal theory,
 * battery arithmetic, LoRa time-on-air). Each test runs independent replications and compares the simulated mean
 * (with 95 % CI) to the analytical value.
 *
 * usage: java -cp iotloopsim-all.jar:iotloopsim-validation.jar org.iotloopsim.validation.AnalyticalValidation [outDir] [reps]
 */
public final class AnalyticalValidation {
    static int REPS = 10;
    static ExecutorService pool;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "validation-results");
        if (args.length > 1) REPS = Integer.parseInt(args[1]);
        pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Check> checks = new ArrayList<>();
        try {
            checks.addAll(mm1());
            checks.addAll(mmc());
            checks.addAll(md1Link());
            checks.addAll(utilisationAndEnergy());
            checks.addAll(reliability());
            checks.addAll(availability());
            checks.addAll(battery());
            checks.addAll(loraAirtime());
            checks.addAll(dutyCycle());
            checks.addAll(conservation());
        } finally { pool.shutdown(); }
        Files.createDirectories(out);
        StringBuilder b = new StringBuilder(Check.header()).append('\n');
        for (Check c : checks) b.append(c.csv()).append('\n');
        Files.writeString(out.resolve("v1_analytical.csv"), b);
        long pass = checks.stream().filter(Check::pass).count();
        System.out.printf("%n%-14s %-34s %-26s %12s %12s %10s %8s %s%n", "group", "test", "parameters", "expected", "simulated", "CI95", "err%", "verdict");
        for (Check c : checks)
            System.out.printf("%-14s %-34s %-26s %12.5g %12.5g %10.3g %8.2f %s%n", c.group(), c.test(), c.parameters(), c.expected(), c.mean(), c.ci95(), c.relError() * 100, c.pass() ? "PASS" : "FAIL");
        System.out.printf("%n%d/%d checks passed; written %s%n", pass, checks.size(), out.resolve("v1_analytical.csv"));
    }

    /** Runs {@code REPS} replications in parallel; each returns one number. */
    static List<Double> reps(LongFunction<Double> run) throws Exception {
        List<Future<Double>> f = new ArrayList<>();
        for (int i = 0; i < REPS; i++) { final long seed = 1000 + i; f.add(pool.submit(() -> run.apply(seed))); }
        List<Double> v = new ArrayList<>();
        for (Future<Double> x : f) v.add(x.get());
        return v;
    }

    static MetricsRegistry.Sample sample(IoTWorld w, String name) { return w.sim().metrics().sample(name); }

    // ------------------------------------------------------------------ queueing at service instances
    static List<Check> mm1() throws Exception {
        List<Check> c = new ArrayList<>();
        double mips = 1000, meanMi = 100, mu = mips / meanMi; // 10 /s
        for (double rho : new double[]{0.3, 0.5, 0.7, 0.8, 0.9}) {
            double lambda = rho * mu;
            double wq = rho / (mu - lambda) * 1000;   // ms
            long horizon = SimTime.s(rho >= 0.85 ? 60000 : 20000);
            List<Double> v = reps(seed -> {
                IoTWorld w = Worlds.queue(seed, lambda, Distribution.exponential(meanMi), mips, 1, 10, 10, 30);
                w.run(horizon);
                return sample(w, "instance.wait_ms").mean();
            });
            c.add(new Check("queueing", "M/M/1 mean waiting time Wq", "rho=" + rho, wq, v, 0.03, "ms"));
        }
        return c;
    }

    static double erlangC(int c, double a) {
        double sum = 0, term = 1;
        for (int k = 0; k < c; k++) { if (k > 0) term *= a / k; sum += term; }
        double last = term * a / c;                // a^c / c!
        double pc = last * c / (c - a);
        return pc / (sum + pc);
    }

    static List<Check> mmc() throws Exception {
        List<Check> c = new ArrayList<>();
        int cores = 4;
        double mips = 1000, meanMi = 100, mu = mips / meanMi;
        for (double rho : new double[]{0.5, 0.8, 0.9}) {
            double lambda = rho * cores * mu, a = lambda / mu;
            double wq = erlangC(cores, a) / (cores * mu - lambda) * 1000;
            List<Double> v = reps(seed -> {
                IoTWorld w = Worlds.queue(seed, lambda, Distribution.exponential(meanMi), mips, cores, 20, 10, 30);
                w.run(SimTime.s(20000));
                return sample(w, "instance.wait_ms").mean();
            });
            c.add(new Check("queueing", "M/M/4 mean waiting time (Erlang C)", "rho=" + rho, wq, v, 0.03, "ms"));
        }
        // M/D/1 at the instance (deterministic service)
        for (double rho : new double[]{0.5, 0.8}) {
            double s = 0.1, lambda = rho / s, wq = rho * s / (2 * (1 - rho)) * 1000;
            List<Double> v = reps(seed -> {
                IoTWorld w = Worlds.queue(seed, lambda, Distribution.constant(100), 1000, 1, 10, 10, 30);
                w.run(SimTime.s(20000));
                return sample(w, "instance.wait_ms").mean();
            });
            c.add(new Check("queueing", "M/D/1 mean waiting time (P-K)", "rho=" + rho, wq, v, 0.03, "ms"));
        }
        return c;
    }

    /** Poisson packets of fixed size through an L1 (queue) link: M/D/1 waiting time = sojourn - fixed part. */
    static List<Check> md1Link() throws Exception {
        List<Check> c = new ArrayList<>();
        int payload = 972;                                   // + 28 B UDP/IP = 1000 B on the wire
        double bw = 8e6;                                      // 1 ms serialisation per packet
        double s = 1000 * 8 / bw;
        for (double rho : new double[]{0.3, 0.6, 0.9}) {
            double lambda = rho / s, wq = rho * s / (2 * (1 - rho)) * 1000;
            List<Double> v = reps(seed -> {
                IoTWorld w = new IoTWorld(seed);
                w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
                Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("ideal"), 0);
                ComputeNode n = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 64, 1e9, 1 << 20, 0, 0, true);
                w.addLink("bottleneck", g, n, bw, SimTime.ms(5), 0, 0, 0, 0, Link.Fidelity.QUEUE);
                Application app = new Application("a", "s", null, null, 0);
                app.addModule(new AppModule(app, "sink", Distribution.constant(1e-6), 16, new ContainerImage("x", 1), 64, 64, 1, 1, 0,
                        null, false, Distribution.constant(0.1), 0, 0));
                w.addApplication(app);
                for (int i = 0; i < 10; i++) {
                    IoTDevice d = w.addDevice("d" + i, "g", new Position(1, 1), Worlds.idealRadio(0), Protocol.preset("udp"), null, 0, "v1");
                    d.addSensor(new Sensor("s", Distribution.exponential(10 / lambda), payload, 0));
                }
                w.setDeviceStart(SimTime.s(5));
                w.run(SimTime.s(3000));
                MetricsRegistry.Sample sm = sample(w, "app.a.sink_ms");
                return sm.mean() - sm.min();
            });
            c.add(new Check("network", "L1 link M/D/1 queueing delay", "rho=" + rho, wq, v, 0.03, "ms"));
        }
        return c;
    }

    /** Utilisation law U = lambda * S and node energy = T * (Pidle + (Pmax - Pidle) * U). */
    static List<Check> utilisationAndEnergy() throws Exception {
        List<Check> c = new ArrayList<>();
        double rho = 0.6, mu = 10, lambda = rho * mu, idle = 20, max = 60;
        long horizon = SimTime.s(20000);
        List<double[]> r = new ArrayList<>();
        List<Future<double[]>> fs = new ArrayList<>();
        for (int i = 0; i < REPS; i++) {
            final long seed = 2000 + i;
            fs.add(pool.submit(() -> {
                IoTWorld w = Worlds.queue(seed, lambda, Distribution.exponential(100), 1000, 1, 10, idle, max);
                var m = w.run(horizon);
                return new double[]{m.get("utilization.edge"), m.get("energy.edge_J")};
            }));
        }
        for (Future<double[]> f : fs) r.add(f.get());
        double activeFrac = (horizon - SimTime.s(5)) / (double) horizon; // devices start at t = 5 s
        c.add(new Check("compute", "Utilisation law U = lambda*S", "rho=0.6", rho * activeFrac, r.stream().map(x -> x[0]).toList(), 0.01, "-"));
        double e = SimTime.toSeconds(horizon) * (idle + (max - idle) * rho * activeFrac);
        c.add(new Check("energy", "Node energy, linear power model", "rho=0.6", e, r.stream().map(x -> x[1]).toList(), 0.01, "J"));
        return c;
    }

    // ------------------------------------------------------------------ protocol reliability
    static List<Check> reliability() throws Exception {
        List<Check> c = new ArrayList<>();
        for (String proto : new String[]{"coap-non", "coap-con"}) {
            for (double p : new double[]{0.1, 0.3, 0.5}) {
                Protocol pr = Protocol.preset(proto);
                double expected = pr.reliable() ? 1 - Math.pow(p, pr.maxRetries() + 1) : 1 - p;
                List<Double> v = reps(seed -> {
                    IoTWorld w = new IoTWorld(seed);
                    w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
                    Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("ideal"), 0);
                    ComputeNode n = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 16, 1e6, 1 << 20, 0, 0, true);
                    w.addLink("l", g, n, 1e9, SimTime.ms(1), 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
                    Application app = new Application("a", "s", null, null, 0);
                    app.addModule(new AppModule(app, "sink", Distribution.constant(0.001), 16, new ContainerImage("x", 1), 16, 64, 1, 1, 0,
                            null, false, Distribution.constant(0.1), 0, 0));
                    w.addApplication(app);
                    for (int i = 0; i < 20; i++) {
                        IoTDevice d = w.addDevice("d" + i, "g", new Position(1, 1), Worlds.idealRadio(p), pr, null, 0, "v1");
                        d.addSensor(new Sensor("s", Distribution.exponential(5), 32, 0));
                    }
                    w.setDeviceStart(SimTime.s(5));
                    var m = w.run(SimTime.s(20000));
                    double gen = m.get("loop.generated"), done = m.getOrDefault("app.a.sink_completed", 0.0);
                    return done / gen;
                });
                c.add(new Check("protocol", proto + " end-to-end delivery ratio", "p_loss=" + p, expected, v, 0.005, "-"));
            }
        }
        // expected transmissions per message for CoAP CON
        double p = 0.3;
        Protocol con = Protocol.preset("coap-con");
        double expTx = (1 - Math.pow(p, con.maxRetries() + 1)) / (1 - p);
        List<Double> v = reps(seed -> {
            IoTWorld w = new IoTWorld(seed);
            w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
            Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("ideal"), 0);
            ComputeNode n = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 16, 1e6, 1 << 20, 0, 0, true);
            w.addLink("l", g, n, 1e9, SimTime.ms(1), 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
            Application app = new Application("a", "s", null, null, 0);
            app.addModule(new AppModule(app, "sink", Distribution.constant(0.001), 16, new ContainerImage("x", 1), 16, 64, 1, 1, 0,
                    null, false, Distribution.constant(0.1), 0, 0));
            w.addApplication(app);
            for (int i = 0; i < 20; i++) {
                IoTDevice d = w.addDevice("d" + i, "g", new Position(1, 1), Worlds.idealRadio(p), con, null, 0, "v1");
                d.addSensor(new Sensor("s", Distribution.exponential(5), 32, 0));
            }
            w.setDeviceStart(SimTime.s(5));
            var m = w.run(SimTime.s(20000));
            return (m.get("net.messages.sent") + m.getOrDefault("net.retransmissions", 0.0)) / m.get("net.messages.sent");
        });
        c.add(new Check("protocol", "coap-con transmissions per message", "p_loss=0.3", expTx, v, 0.005, "-"));
        return c;
    }

    // ------------------------------------------------------------------ availability (alternating renewal process)
    static List<Check> availability() throws Exception {
        List<Check> c = new ArrayList<>();
        for (double[] mm : new double[][]{{1000, 100}, {3600, 600}, {500, 5}}) {
            double expected = mm[0] / (mm[0] + mm[1]);
            List<Double> v = reps(seed -> {
                IoTWorld w = new IoTWorld(seed);
                for (int i = 0; i < 50; i++) w.addComputeNode("n" + i, new Position(0, 0), Tier.EDGE, 1, 1000, 1024, 0, 0, false);
                w.faults().independent("f", e -> e instanceof ComputeNode, Distribution.exponential(mm[0]), Distribution.exponential(mm[1]));
                return w.run(SimTime.s(mm[0] * 400)).get("availability.nodes");
            });
            c.add(new Check("faults", "Steady-state availability MTBF/(MTBF+MTTR)", "MTBF=" + (int) mm[0] + ",MTTR=" + (int) mm[1], expected, v, 0.005, "-"));
        }
        return c;
    }

    // ------------------------------------------------------------------ battery lifetime
    static List<Check> battery() throws Exception {
        List<Check> c = new ArrayList<>();
        // (a) baseline only: lifetime = E / P
        double e = 100, pw = 0.01;
        c.add(new Check("energy", "Battery lifetime, baseline only", "E=100J,P=10mW", e / pw, reps(seed -> depletionTime(seed, e, pw, null, 0, 0)), 0.001, "s"));
        // (b) baseline + periodic sensing + LoRa uplink (SF7, 24 B): lifetime = E / (P + (e_s + P_tx*ToA)/T)
        AccessTechnology lora = AccessTechnology.preset("lorawan");
        double toa = lora.airtimeNs(24 + 40) / 1e9;   // CoAP-NON header 40 B
        double perMsg = 0.05 + lora.txPowerW() * toa;
        double T = 60;
        double expected = 1000 / (0.0001 + perMsg / T);
        c.add(new Check("energy", "Battery lifetime, LoRa sensing every 60 s", "E=1000J", expected,
                reps(seed -> depletionTime(seed, 1000, 0.0001, lora, T, 0.05)), 0.01, "s"));
        return c;
    }

    static double depletionTime(long seed, double joules, double baselineW, AccessTechnology tech, double interval, double sensJ) {
        IoTWorld w = new IoTWorld(seed);
        w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
        AccessTechnology t = tech == null ? Worlds.idealRadio(0) : tech;
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of(t.name()), 0);
        ComputeNode n = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 4, 1e6, 1 << 20, 0, 0, true);
        w.addLink("l", g, n, 1e9, SimTime.ms(1), 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
        Application app = new Application("a", "s", null, null, 0);
        app.addModule(new AppModule(app, "sink", Distribution.constant(0.001), 16, new ContainerImage("x", 1), 4, 64, 1, 1, 0,
                null, false, Distribution.constant(0.1), 0, 0));
        w.addApplication(app);
        IoTDevice d = w.addDevice("dev", "g", new Position(1, 1), t, Protocol.preset("coap-non"), new Battery(joules), baselineW, "v1");
        if (interval > 0) d.addSensor(new Sensor("s", Distribution.constant(interval), 24, sensJ));
        w.run(SimTime.s(joules / baselineW * 2));
        for (LifecycleLog.Entry en : w.lifecycle().entries())
            if (en.entity().equals("dev") && en.to().equals("DEPLETED")) return en.time() / 1e9;
        return Double.NaN;
    }

    // ------------------------------------------------------------------ LoRa time on air
    /** Reference implementation of Semtech AN1200.13 written independently of the simulator code. */
    static double semtechMs(int phyPayload, int sf, double bw) {
        double tsym = Math.pow(2, sf) / bw * 1000;
        boolean lowDr = sf >= 11 && bw == 125e3;
        double payloadSym = 8 + Math.max(Math.ceil((8.0 * phyPayload - 4 * sf + 28 + 16 - 0) / (4.0 * (sf - (lowDr ? 2 : 0)))) * (1 + 4), 0);
        return (8 + 4.25) * tsym + payloadSym * tsym;
    }

    static List<Check> loraAirtime() {
        List<Check> c = new ArrayList<>();
        int[][] cases = {{7, 10}, {7, 51}, {7, 222}, {9, 51}, {9, 115}, {12, 10}, {12, 51}};
        for (int[] k : cases) {
            String preset = k[0] == 7 ? "lorawan" : k[0] == 9 ? "lorawan-sf9" : "lorawan-sf12";
            AccessTechnology t = AccessTechnology.preset(preset);
            double ref = semtechMs(k[1] + 13, k[0], 125e3);
            double sim = t.airtimeNs(k[1]) / 1e6;
            c.add(new Check("radio", "LoRa time on air vs Semtech formula", "SF" + k[0] + ",app=" + k[1] + "B", ref, List.of(sim), 0.001, "ms"));
        }
        return c;
    }

    /** With saturating traffic, LoRa throughput per device is bounded by the duty cycle: rate = d / ToA. */
    static List<Check> dutyCycle() throws Exception {
        AccessTechnology lora = AccessTechnology.preset("lorawan");
        int payload = 51;
        double toa = lora.airtimeNs(payload + 28) / 1e9;          // UDP header 28 B
        double horizon = 36000;
        double expected = lora.dutyCycle() / toa * (horizon - 5);
        List<Double> v = reps(seed -> {
            IoTWorld w = new IoTWorld(seed);
            w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
            Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("lorawan"), 0);
            ComputeNode n = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 4, 1e6, 1 << 20, 0, 0, true);
            w.addLink("l", g, n, 1e9, SimTime.ms(1), 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
            Application app = new Application("a", "s", null, null, 0);
            app.addModule(new AppModule(app, "sink", Distribution.constant(0.001), 16, new ContainerImage("x", 1), 4, 64, 1, 1, 0,
                    null, false, Distribution.constant(0.1), 0, 0));
            w.addApplication(app);
            AccessTechnology noLoss = new AccessTechnology("lorawan", lora.bitrateBps(), lora.latencyNs(), lora.txPowerW(), lora.rxPowerW(),
                    lora.rangeM(), 0, lora.frameHeader(), lora.maxFrame(), lora.dutyCycle(), lora.loraSf(), lora.loraBwHz());
            IoTDevice d = w.addDevice("dev", "g", new Position(1, 1), noLoss, Protocol.preset("udp"), null, 0, "v1");
            d.addSensor(new Sensor("s", Distribution.constant(1), payload, 0));
            w.setDeviceStart(SimTime.s(5));
            var m = w.run(SimTime.s(horizon));
            return m.getOrDefault("app.a.sink_completed", 0.0);
        });
        return List.of(new Check("radio", "LoRa duty-cycle-limited throughput", "SF7,51B,d=1%", expected, v, 0.002, "msgs"));
    }

    // ------------------------------------------------------------------ conservation
    static List<Check> conservation() throws Exception {
        List<Check> c = new ArrayList<>();
        List<Double> diff = reps(seed -> {
            IoTWorld w = Worlds.queue(seed, 5, Distribution.exponential(100), 1000, 1, 10, 10, 30);
            var m = w.run(SimTime.s(2000));
            double layers = 0, cats = 0;
            for (var e : m.entrySet()) {
                if (e.getKey().startsWith("energy.category.")) cats += e.getValue();
                else if (e.getKey().startsWith("energy.") && e.getKey().endsWith("_J") && !e.getKey().equals("energy.total_J")) layers += e.getValue();
            }
            return Math.abs(layers - cats) + Math.abs(layers - m.get("energy.total_J"));
        });
        c.add(new Check("energy", "Energy conservation (layers = categories = total)", "-", 0, diff, 1e-9, "J"));
        List<Double> flow = reps(seed -> {
            IoTWorld w = Worlds.queue(seed, 5, Distribution.exponential(100), 1000, 1, 10, 10, 30);
            var m = w.run(SimTime.s(2000));
            double gen = m.get("loop.generated");
            double inFlight = gen - m.getOrDefault("app.q.sink_completed", 0.0) - m.getOrDefault("loop.dropped", 0.0);
            if (inFlight < 0) return 1.0;          // more completions than generations would be a bookkeeping error
            return inFlight / gen;                 // share still queued at the end: must be tiny and non-negative
        });
        c.add(new Check("flow", "Request conservation (in flight at end / generated)", "-", 0, flow, 0.001, "-"));
        return c;
    }
}
