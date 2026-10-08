package org.iotloopsim.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iotloopsim.model.AccessTechnology;
import org.iotloopsim.model.Link;
import org.iotloopsim.model.Protocol;
import org.iotloopsim.model.policy.PolicyRegistry;

/** Command-line interface. Run without arguments for help. */
public final class Main {
    private static final String[] KEY = {"loop.success_ratio", "loop.latency_ms.mean", "loop.latency_ms.p95", "loop.deadline_miss_ratio",
            "net.pdr", "energy.total_J", "energy.device_J", "carbon.total_g", "cost.total", "availability.nodes",
            "instance.cold_starts", "migration.completed", "update.succeeded", "update.failed", "security.detections"};

    /**
     * CLI entry point: {@code run}, {@code compare-fidelity}, {@code validate} or {@code policies}; prints help
     * without arguments. User errors print one line to stderr and exit with status 2 ({@code --debug true}
     * rethrows instead).
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { help(); return; }
        Map<String, String> o = opts(args);
        try {
            switch (args[0]) {
                case "run" -> run(Path.of(arg(args, 1)), o);
                case "compare-fidelity" -> compare(Path.of(arg(args, 1)), o);
                case "validate" -> validate(Path.of(arg(args, 1)), o);
                case "policies" -> list();
                default -> help();
            }
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException | java.nio.file.NoSuchFileException e) {
            // user errors: one readable line (use --debug true for the stack trace)
            if (Boolean.parseBoolean(o.getOrDefault("debug", "false"))) throw e;
            String msg = e instanceof java.nio.file.NoSuchFileException ? "file not found: " + e.getMessage() : e.getMessage();
            System.err.println("error: " + msg);
            System.exit(2);
        }
    }

    /**
     * Reads the scenario, applies --set overrides, builds it once to check it, and rejects keys that IoTLoopSim does not
     * know (typos) unless {@code --lenient true}.
     */
    static Experiment load(Path file, Map<String, String> o) throws Exception {
        Experiment e = new Experiment(file).applyOverrides(SETS);
        ScenarioLoader l = new ScenarioLoader(e.scenario(), file.toAbsolutePath().getParent());
        l.build(l.seed());
        for (String n : l.notes()) System.out.println("note: " + n);
        List<String> unknown = l.unknownKeys();
        if (!unknown.isEmpty()) {
            String list = String.join("\n  ", unknown);
            if (Boolean.parseBoolean(o.getOrDefault("lenient", "false"))) System.out.println("warning: unknown scenario keys ignored:\n  " + list);
            else throw new IllegalArgumentException(file.getFileName() + ": unknown scenario keys (fix them, or run with --lenient true to ignore):\n  " + list);
        }
        return e;
    }

    static void run(Path file, Map<String, String> o) throws Exception {
        Experiment e = load(file, o);
        ScenarioLoader l = new ScenarioLoader(e.scenario(), file.toAbsolutePath().getParent());
        int reps = Integer.parseInt(o.getOrDefault("reps", "5"));
        long seed = Long.parseLong(o.getOrDefault("seed", String.valueOf(l.seed())));
        int threads = Integer.parseInt(o.getOrDefault("threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
        Path out = Path.of(o.getOrDefault("out", "results/" + l.name()));
        Instant t0 = Instant.now();
        System.out.printf("IoTLoopSim %s | scenario '%s' | %d replications from seed %d | %d threads%n", Experiment.VERSION, l.name(), reps, seed, threads);
        List<Experiment.RunResult> runs = e.run(reps, seed, threads, null);
        e.write(out, runs, seed, t0);
        print(Experiment.summarize(runs));
        System.out.println("results written to " + out.toAbsolutePath());
    }

    static void compare(Path file, Map<String, String> o) throws Exception {
        Experiment e = load(file, o);
        ScenarioLoader l = new ScenarioLoader(e.scenario(), file.toAbsolutePath().getParent());
        int reps = Integer.parseInt(o.getOrDefault("reps", "5"));
        long seed = Long.parseLong(o.getOrDefault("seed", String.valueOf(l.seed())));
        int threads = Integer.parseInt(o.getOrDefault("threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
        Map<String, Experiment.Summary> a = index(Experiment.summarize(e.run(reps, seed, threads, Link.Fidelity.ANALYTICAL)));
        Map<String, Experiment.Summary> q = index(Experiment.summarize(e.run(reps, seed, threads, Link.Fidelity.QUEUE)));
        StringBuilder csv = new StringBuilder("metric,analytical_mean,queue_mean,relative_difference\n");
        System.out.printf("%-30s %14s %14s %10s%n", "metric (L0 vs L1)", "analytical", "queue", "diff");
        for (String k : KEY) {
            if (!a.containsKey(k) || !q.containsKey(k)) continue;
            double x = a.get(k).mean(), y = q.get(k).mean();
            double rel = x == 0 ? (y == 0 ? 0 : Double.NaN) : (y - x) / Math.abs(x);
            System.out.printf("%-30s %14.4g %14.4g %9.1f%%%n", k, x, y, rel * 100);
            csv.append(String.format("%s,%.6g,%.6g,%.6g%n", k, x, y, rel));
        }
        Path out = Path.of(o.getOrDefault("out", "results/" + l.name() + "-fidelity"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("fidelity_comparison.csv"), csv);
        System.out.println("written " + out.resolve("fidelity_comparison.csv").toAbsolutePath());
    }

    static void validate(Path file, Map<String, String> o) throws Exception {
        Experiment e = load(file, o);
        ScenarioLoader l = new ScenarioLoader(e.scenario(), file.toAbsolutePath().getParent());
        var w = l.build(l.seed());
        System.out.printf("OK '%s': %d devices, %d gateways, %d compute nodes, %d links, %d applications%n", l.name(),
                w.devices().size(), w.gateways().size(), w.computeNodes().size(), w.network().links().size(), w.applications().size());
    }

    static void list() {
        System.out.println("Policies:");
        PolicyRegistry.available().forEach((k, v) -> System.out.println("  " + k.name().toLowerCase() + ": " + v));
        System.out.println("Access technologies: " + AccessTechnology.presets().keySet());
        System.out.println("Protocols: " + Protocol.presets().keySet());
        System.out.println("Channel security: none, dtls, tls");
    }

    static void print(List<Experiment.Summary> s) {
        Map<String, Experiment.Summary> m = index(s);
        System.out.printf("%-30s %14s %14s %5s%n", "metric", "mean", "95% CI +/-", "n");
        for (String k : KEY) {
            Experiment.Summary x = m.get(k);
            if (x != null) System.out.printf("%-30s %14.4g %14.4g %5d%n", k, x.mean(), x.ci95(), x.n());
        }
    }

    static Map<String, Experiment.Summary> index(List<Experiment.Summary> s) {
        Map<String, Experiment.Summary> m = new LinkedHashMap<>();
        for (Experiment.Summary x : s) m.put(x.metric(), x);
        return m;
    }

    private static String arg(String[] a, int i) {
        if (a.length <= i || a[i].startsWith("--")) { help(); System.exit(2); }
        return a[i];
    }

    private static final List<String> SETS = new ArrayList<>();

    private static Map<String, String> opts(String[] a) {
        Map<String, String> m = new LinkedHashMap<>();
        List<String> l = new ArrayList<>(List.of(a));
        for (int i = 0; i < l.size(); i++) {
            if (!l.get(i).startsWith("--") || i + 1 >= l.size()) continue;
            String k = l.get(i).substring(2), v = l.get(++i);
            if (k.equals("set")) SETS.add(v); else m.put(k, v);
        }
        return m;
    }

    static void help() {
        System.out.println("""
            IoTLoopSim %s - full-stack IoT simulator
            usage:
              iotloopsim run <scenario.yaml> [--reps N] [--seed S] [--threads T] [--out DIR] [--set path=value ...]
                  --set overrides a scenario field, e.g. --set policies.placement=cloud-only --set duration=30min
                  --lenient true  ignore unknown scenario keys (default: reject them)   --debug true  show stack traces
              iotloopsim compare-fidelity <scenario.yaml> [--reps N]   compare analytical (L0) vs queue (L1) network models
              iotloopsim validate <scenario.yaml> [--set ...]           parse, build and check the scenario only
              iotloopsim policies                                       list policies, technologies and protocols
              iotloopsim gym [--port 5555]                              start the Gym bridge for Python RL agents
            """.formatted(Experiment.VERSION));
    }
}
