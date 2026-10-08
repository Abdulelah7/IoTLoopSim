package org.iotloopsim.core;

/**
 * Simulation time helpers. Time is an integer count of nanoseconds, which avoids
 * floating-point drift and makes event ordering exact and reproducible.
 */
public final class SimTime {
    public static final long NS = 1L;
    public static final long US = 1_000L;
    public static final long MS = 1_000_000L;
    public static final long S = 1_000_000_000L;
    public static final long MIN = 60 * S;
    public static final long H = 60 * MIN;

    private SimTime() {}

    /** Converts microseconds to simulation time in ns, rounded to the nearest ns. */
    public static long us(double v) { return Math.round(v * US); }
    /** Converts milliseconds to simulation time in ns, rounded to the nearest ns. */
    public static long ms(double v) { return Math.round(v * MS); }
    /** Converts seconds to simulation time in ns, rounded to the nearest ns. */
    public static long s(double v) { return Math.round(v * S); }
    /** Converts simulation time in ns to seconds. */
    public static double toSeconds(long t) { return t / (double) S; }
    /** Converts simulation time in ns to milliseconds. */
    public static double toMillis(long t) { return t / (double) MS; }

    /** Parses durations such as "500ms", "2.5s", "10min", "1h", "250us" or a bare number of seconds. */
    public static long parse(String text) {
        String t = text.trim().toLowerCase();
        if (t.endsWith("ms")) return ms(Double.parseDouble(t.substring(0, t.length() - 2).trim()));
        if (t.endsWith("us")) return us(Double.parseDouble(t.substring(0, t.length() - 2).trim()));
        if (t.endsWith("ns")) return Math.round(Double.parseDouble(t.substring(0, t.length() - 2).trim()));
        if (t.endsWith("min")) return Math.round(Double.parseDouble(t.substring(0, t.length() - 3).trim()) * MIN);
        if (t.endsWith("h")) return Math.round(Double.parseDouble(t.substring(0, t.length() - 1).trim()) * H);
        if (t.endsWith("d")) return Math.round(Double.parseDouble(t.substring(0, t.length() - 1).trim()) * 24 * H);
        if (t.endsWith("s")) return s(Double.parseDouble(t.substring(0, t.length() - 1).trim()));
        return s(Double.parseDouble(t));
    }

    /**
     * Formats a duration in ns as a human-readable string using the largest fitting unit (ns, us, ms or s, three
     * decimals).
     */
    public static String format(long t) {
        if (t < US) return t + "ns";
        if (t < MS) return String.format("%.3fus", t / (double) US);
        if (t < S) return String.format("%.3fms", t / (double) MS);
        return String.format("%.3fs", t / (double) S);
    }
}
