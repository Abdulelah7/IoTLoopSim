package org.iotloopsim.util;

import java.util.List;

/** Small statistics helpers for replication analysis (no external libraries). */
public final class Stats {
    private Stats() {}

    // two-sided 95% Student-t critical values, df = 1..30
    private static final double[] T975 = {12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};

    /**
     * Returns the two-sided 95% Student-t critical value for {@code df} degrees of freedom (tabulated up to 30,
     * stepped approximations above, 1.960 beyond 120), or NaN if {@code df <= 0}.
     */
    public static double t975(int df) {
        if (df <= 0) return Double.NaN;
        if (df <= 30) return T975[df - 1];
        if (df <= 40) return 2.021;
        if (df <= 60) return 2.000;
        if (df <= 120) return 1.980;
        return 1.960;
    }

    /** Returns the arithmetic mean of {@code v}, or NaN if empty. */
    public static double mean(List<Double> v) { double s = 0; for (double x : v) s += x; return v.isEmpty() ? Double.NaN : s / v.size(); }

    /** Returns the sample standard deviation (n-1 denominator), or 0 with fewer than two values. */
    public static double sd(List<Double> v) {
        if (v.size() < 2) return 0;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.size() - 1));
    }

    /** Half-width of the 95% confidence interval of the mean. */
    public static double ci95(List<Double> v) { return v.size() < 2 ? 0 : t975(v.size() - 1) * sd(v) / Math.sqrt(v.size()); }
}
