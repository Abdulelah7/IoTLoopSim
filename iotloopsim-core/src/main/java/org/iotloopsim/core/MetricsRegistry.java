package org.iotloopsim.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collects counters, gauges and samples during a run. {@link #snapshot()} flattens them
 * into name→value pairs (e.g. {@code loop.latency_ms.mean}, {@code loop.latency_ms.p95}).
 */
public final class MetricsRegistry {
    private final Map<String, Double> counters = new TreeMap<>();
    private final Map<String, Double> gauges = new TreeMap<>();
    private final Map<String, Sample> samples = new TreeMap<>();

    /** Increments counter {@code name} by 1. */
    public void inc(String name) { add(name, 1); }
    /** Adds {@code v} to counter {@code name}, creating it at 0 if absent. */
    public void add(String name, double v) { counters.merge(name, v, Double::sum); }
    /** Sets gauge {@code name} to {@code v}, overwriting the previous value. */
    public void set(String name, double v) { gauges.put(name, v); }
    /** Appends observation {@code v} to sample series {@code name}, creating the series if absent. */
    public void record(String name, double v) { samples.computeIfAbsent(name, k -> new Sample()).add(v); }

    /** Returns the current value of counter {@code name}, or 0 if it was never incremented. */
    public double counter(String name) { return counters.getOrDefault(name, 0.0); }
    /** Returns the last value set for gauge {@code name}, or null if it was never set. */
    public Double gauge(String name) { return gauges.get(name); }
    /** Returns the sample series {@code name}, or null if nothing was recorded under it. */
    public Sample sample(String name) { return samples.get(name); }
    /** Returns an unmodifiable view of all sample series, sorted by name. */
    public Map<String, Sample> samples() { return Collections.unmodifiableMap(samples); }

    /**
     * Flattens counters, gauges and samples into a new name-to-value map; each sample contributes {@code .count}
     * and, if non-empty, {@code .mean}, {@code .p50}, {@code .p95} and {@code .max}.
     */
    public Map<String, Double> snapshot() {
        Map<String, Double> out = new LinkedHashMap<>();
        counters.forEach(out::put);
        gauges.forEach(out::put);
        samples.forEach((k, s) -> {
            out.put(k + ".count", (double) s.count());
            if (s.count() > 0) {
                out.put(k + ".mean", s.mean());
                out.put(k + ".p50", s.percentile(50));
                out.put(k + ".p95", s.percentile(95));
                out.put(k + ".max", s.max());
            }
        });
        return out;
    }

    /**
     * Streaming statistics (Welford mean/variance) plus a bounded value buffer for percentiles: exact up to 2^20
     * values, then a uniform reservoir sample over the whole run (Vitter's algorithm R, fixed seed, so results stay
     * reproducible).
     */
    public static final class Sample {
        private static final int CAP = 1 << 20;
        private long n;
        private double sum, mean, m2, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        private final List<Double> values = new ArrayList<>();
        private final java.util.SplittableRandom reservoir = new java.util.SplittableRandom(0x5EEDL);
        private double[] sorted;

        void add(double v) {
            n++; sum += v;
            double d = v - mean;
            mean += d / n;
            m2 += d * (v - mean);
            if (v < min) min = v;
            if (v > max) max = v;
            if (values.size() < CAP) values.add(v);
            else {
                long j = reservoir.nextLong(n);
                if (j < CAP) values.set((int) j, v);
            }
            sorted = null;
        }

        /** Returns the number of recorded observations. */
        public long count() { return n; }
        /** Returns the sum of all recorded observations. */
        public double sum() { return sum; }
        /** Returns the arithmetic mean of all observations, or NaN if none were recorded. */
        public double mean() { return n == 0 ? Double.NaN : sum / n; }
        /** Returns the smallest observation, or {@code +Infinity} if none were recorded. */
        public double min() { return min; }
        /** Returns the largest observation, or {@code -Infinity} if none were recorded. */
        public double max() { return max; }
        /** Returns the sample standard deviation (n-1 denominator), or 0 with fewer than two observations. */
        public double stddev() { return n < 2 ? 0 : Math.sqrt(Math.max(0, m2 / (n - 1))); }

        /**
         * Returns the {@code p}-th percentile ({@code p} in 0..100) by linear interpolation over the retained
         * values, or NaN if empty; exact up to 2^20 observations, estimated from the reservoir beyond that.
         */
        public double percentile(double p) {
            if (values.isEmpty()) return Double.NaN;
            if (sorted == null) {
                sorted = new double[values.size()];
                for (int i = 0; i < sorted.length; i++) sorted[i] = values.get(i);
                Arrays.sort(sorted);
            }
            double rank = p / 100.0 * (sorted.length - 1);
            int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
        }
    }
}
