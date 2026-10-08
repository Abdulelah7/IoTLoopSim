package org.iotloopsim.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.iotloopsim.model.IoTWorld;
import org.iotloopsim.model.Link;
import org.iotloopsim.util.Json;
import org.iotloopsim.util.Stats;

/**
 * Runs N independent replications of a scenario (seed = base seed + i) in parallel, aggregates every metric
 * (mean, standard deviation, 95% confidence interval) and writes a provenance bundle so the run can be reproduced.
 */
public final class Experiment {
    /** IoTLoopSim version string recorded in provenance. */
    public static final String VERSION = "0.1.0";

    /**
     * Result of one replication: replication index, seed, flattened metrics from {@link IoTWorld#report()}, result
     * hash (see {@link Experiment#hash(Map)}), wall-clock time in s, lifecycle log as CSV, and loader notes (e.g.
     * fidelity fallbacks).
     */
    public record RunResult(int rep, long seed, Map<String, Double> metrics, String hash, double wallSeconds, String lifecycleCsv, List<String> notes) {}
    /**
     * Aggregate of one metric over replications: mean, sample standard deviation, half-width of the 95% confidence
     * interval, and number of replications contributing (NaN values excluded).
     */
    public record Summary(String metric, double mean, double sd, double ci95, int n) {}

    private final Map<String, Object> scenario;
    private final Path scenarioFile;
    private final String scenarioText;

    /**
     * Reads and parses a YAML or JSON scenario file; its directory is the base for relative paths in the scenario.
     * Throws {@link IOException} if the file cannot be read.
     */
    public Experiment(Path scenarioFile) throws IOException {
        this.scenarioFile = scenarioFile;
        this.scenarioText = Files.readString(scenarioFile);
        this.scenario = ScenarioLoader.read(scenarioFile);
    }

    /**
     * Wraps an already parsed scenario (no file; relative paths resolve against the working directory). The map is
     * used, and modified by overrides, in place.
     */
    public Experiment(Map<String, Object> scenario) {
        this.scenarioFile = null;
        this.scenarioText = Json.write(scenario);
        this.scenario = scenario;
    }

    private final List<String> overrides = new ArrayList<>();

    /** Returns the live scenario map, including applied overrides. */
    public Map<String, Object> scenario() { return scenario; }

    /** Applies {@code path=value} overrides (see {@link Overrides}) and records them in the provenance. */
    public Experiment applyOverrides(List<String> sets) {
        Overrides.apply(scenario, sets);
        overrides.addAll(sets);
        return this;
    }

    /** Runs one replication with the given seed; optional fidelity override (null = as in scenario). */
    public RunResult runOne(int rep, long seed, Link.Fidelity override) {
        long t0 = System.nanoTime();
        Map<String, Object> sc = scenario;
        if (override != null) {
            sc = new LinkedHashMap<>(scenario);
            Map<String, Object> fid = new LinkedHashMap<>();
            fid.put("links", override.name());
            fid.put("radio", override.name());
            sc.put("fidelity", fid);
        }
        ScenarioLoader loader = new ScenarioLoader(sc, scenarioFile == null ? Path.of(".") : scenarioFile.toAbsolutePath().getParent());
        IoTWorld w = loader.build(seed);
        Map<String, Double> m = w.run(loader.duration());
        return new RunResult(rep, seed, m, hash(m), (System.nanoTime() - t0) / 1e9, w.lifecycle().toCsv(), new ArrayList<>(loader.notes()));
    }

    /**
     * Replicates a world built in Java (no scenario file): {@code builder} receives each replication's seed and returns a
     * fresh world; each runs for {@code durationNs}. Use {@link #summarize(List)} for means and 95% confidence intervals.
     */
    public static List<RunResult> replicate(int reps, long baseSeed, java.util.function.LongFunction<IoTWorld> builder, long durationNs) {
        List<RunResult> out = new ArrayList<>();
        for (int i = 0; i < reps; i++) {
            long seed = baseSeed + i;
            long t0 = System.nanoTime();
            IoTWorld w = builder.apply(seed);
            Map<String, Double> m = w.run(durationNs);
            out.add(new RunResult(i, seed, m, hash(m), (System.nanoTime() - t0) / 1e9, w.lifecycle().toCsv(), List.of()));
        }
        return out;
    }

    /**
     * Runs {@code reps} replications with seeds {@code baseSeed + i} on a pool of {@code min(threads, reps)}
     * threads (at least 1) and returns results in replication order; {@code override} (null = as in scenario)
     * forces link and radio fidelity. Rethrows the first replication failure.
     */
    public List<RunResult> run(int reps, long baseSeed, int threads, Link.Fidelity override) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, reps)));
        try {
            List<Future<RunResult>> fs = new ArrayList<>();
            for (int i = 0; i < reps; i++) {
                final int rep = i;
                fs.add(ex.submit(() -> runOne(rep, baseSeed + rep, override)));
            }
            List<RunResult> out = new ArrayList<>();
            for (Future<RunResult> f : fs) out.add(f.get());
            return out;
        } finally { ex.shutdownNow(); }
    }

    /**
     * Aggregates every metric across runs, sorted by name; a counter missing from a run counts as 0, while missing
     * or NaN sample statistics and ratios are excluded. Metrics with no values are omitted.
     */
    public static List<Summary> summarize(List<RunResult> runs) {
        TreeSet<String> keys = new TreeSet<>();
        for (RunResult r : runs) keys.addAll(r.metrics().keySet());
        List<Summary> out = new ArrayList<>();
        for (String k : keys) {
            List<Double> v = new ArrayList<>();
            boolean sampleStat = k.endsWith(".mean") || k.endsWith(".p50") || k.endsWith(".p95") || k.endsWith(".max") || k.endsWith("_ratio") || k.equals("net.pdr");
            for (RunResult r : runs) {
                Double x = r.metrics().get(k);
                if (x == null) { if (!sampleStat) v.add(0.0); }   // a counter that never fired in this run counts as zero
                else if (!x.isNaN()) v.add(x);
            }
            if (v.isEmpty()) continue;
            out.add(new Summary(k, Stats.mean(v), Stats.sd(v), Stats.ci95(v), v.size()));
        }
        return out;
    }

    /** Writes summary.csv, runs.csv, provenance.json and the lifecycle log of replication 0. */
    public void write(Path dir, List<RunResult> runs, long baseSeed, Instant started) throws IOException {
        Files.createDirectories(dir);
        List<Summary> sum = summarize(runs);
        StringBuilder s = new StringBuilder("metric,mean,sd,ci95_low,ci95_high,n\n");
        for (Summary x : sum) s.append(String.format("%s,%.6g,%.6g,%.6g,%.6g,%d%n", x.metric(), x.mean(), x.sd(), x.mean() - x.ci95(), x.mean() + x.ci95(), x.n()));
        Files.writeString(dir.resolve("summary.csv"), s);
        TreeSet<String> keys = new TreeSet<>();
        for (RunResult r : runs) keys.addAll(r.metrics().keySet());
        StringBuilder rc = new StringBuilder("rep,seed,hash,wall_s");
        for (String k : keys) rc.append(',').append(k);
        rc.append('\n');
        for (RunResult r : runs) {
            rc.append(r.rep()).append(',').append(r.seed()).append(',').append(r.hash()).append(',').append(String.format("%.3f", r.wallSeconds()));
            for (String k : keys) { Double v = r.metrics().get(k); rc.append(',').append(v == null ? "" : String.format("%.6g", v)); }
            rc.append('\n');
        }
        Files.writeString(dir.resolve("runs.csv"), rc);
        if (!runs.isEmpty()) Files.writeString(dir.resolve("lifecycle_rep0.csv"), runs.get(0).lifecycleCsv());
        Files.writeString(dir.resolve("provenance.json"), Json.write(provenance(runs, baseSeed, started)));
        Files.writeString(dir.resolve("scenario.resolved.json"), Json.write(scenario));
    }

    private Map<String, Object> provenance(List<RunResult> runs, long baseSeed, Instant started) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tool", "IoTLoopSim");
        p.put("version", VERSION);
        p.put("startedAt", started.toString());
        p.put("finishedAt", Instant.now().toString());
        p.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        p.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        p.put("gitCommit", gitCommit());
        p.put("scenarioFile", scenarioFile == null ? null : scenarioFile.toAbsolutePath().toString());
        p.put("scenarioSha256", sha256(scenarioText));
        p.put("overrides", overrides);
        p.put("resolvedScenarioSha256", sha256(Json.write(scenario)));   // after overrides; matches scenario.resolved.json
        p.put("baseSeed", baseSeed);
        p.put("replications", runs.size());
        List<Object> rr = new ArrayList<>();
        for (RunResult r : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rep", r.rep()); m.put("seed", r.seed()); m.put("resultHash", r.hash()); m.put("wallSeconds", r.wallSeconds());
            rr.add(m);
        }
        p.put("runs", rr);
        p.put("notes", runs.isEmpty() ? List.of() : runs.get(0).notes());
        p.put("metricDefinitions", MetricCatalog.DEFINITIONS);
        return p;
    }

    /** SHA-256 of the sorted metric map: identical seeds and versions must give identical hashes. */
    public static String hash(Map<String, Double> m) {
        StringBuilder b = new StringBuilder();
        new TreeMap<>(m).forEach((k, v) -> b.append(k).append('=').append(v == null || v.isNaN() ? "nan" : String.format("%.9e", v)).append(';'));
        return sha256(b.toString()).substring(0, 16);
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (byte x : d) h.append(String.format("%02x", x));
            return h.toString();
        } catch (Exception e) { return "n/a"; }
    }

    private static String gitCommit() {
        try {
            Process pr = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return pr.waitFor() == 0 ? out : null;
        } catch (Exception e) { return null; }
    }
}
