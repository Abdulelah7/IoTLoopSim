package org.iotloopsim.core;

import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Random variate generator. Scenario files describe distributions as text, e.g.
 * {@code const(5)}, {@code exp(10)} (mean), {@code uniform(1,3)}, {@code normal(5,1)},
 * {@code lognormal(1,0.5)} (mu, sigma of the underlying normal), {@code pareto(1.5,2)} (shape, scale).
 * A bare number means a constant.
 */
@FunctionalInterface
public interface Distribution {
    /**
     * Draws one value using the given random stream; the unit is whatever the distribution's parameters are
     * expressed in.
     */
    double sample(SplittableRandom r);

    /** Returns a distribution that always yields {@code v}. */
    static Distribution constant(double v) { return r -> v; }
    /** Returns an exponential distribution with the given mean (not rate), sampled by inverse transform. */
    static Distribution exponential(double mean) { return r -> -mean * Math.log(1.0 - r.nextDouble()); }
    /** Returns a continuous uniform distribution on {@code [lo, hi)}. */
    static Distribution uniform(double lo, double hi) { return r -> lo + (hi - lo) * r.nextDouble(); }
    /**
     * Returns a normal distribution with the given mean and standard deviation, truncated at 0 (negative draws are
     * returned as 0).
     */
    static Distribution normal(double mean, double sd) { return r -> Math.max(0, mean + sd * gaussian(r)); }
    /**
     * Returns a log-normal distribution; {@code mu} and {@code sigma} are the mean and standard deviation of the
     * underlying normal.
     */
    static Distribution lognormal(double mu, double sigma) { return r -> Math.exp(mu + sigma * gaussian(r)); }
    /** Returns a Pareto distribution with the given shape (alpha) and scale (minimum value). */
    static Distribution pareto(double shape, double scale) { return r -> scale / Math.pow(1.0 - r.nextDouble(), 1.0 / shape); }

    /**
     * Returns one standard-normal variate (mean 0, sd 1) using the Box-Muller transform; consumes two draws from
     * {@code r}.
     */
    static double gaussian(SplittableRandom r) {
        double u1 = 1.0 - r.nextDouble(), u2 = r.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /**
     * Parses a distribution spec: a {@link Number} or numeric string gives a constant, otherwise {@code kind(a,b)}
     * with kinds const, exp (mean), uniform, normal, lognormal or pareto (case-insensitive). Throws {@link
     * IllegalArgumentException} for null, malformed or unknown specs.
     */
    static Distribution parse(Object spec) {
        if (spec == null) throw new IllegalArgumentException("missing distribution");
        if (spec instanceof Number n) return constant(n.doubleValue());
        String s = spec.toString().trim().toLowerCase(Locale.ROOT);
        try { return constant(Double.parseDouble(s)); } catch (NumberFormatException ignored) { }
        int open = s.indexOf('('), close = s.lastIndexOf(')');
        if (open < 0 || close < open) throw new IllegalArgumentException("bad distribution: " + spec);
        String kind = s.substring(0, open).trim();
        String[] parts = s.substring(open + 1, close).split(",");
        double[] a = new double[parts.length];
        for (int i = 0; i < parts.length; i++) a[i] = Double.parseDouble(parts[i].trim());
        return switch (kind) {
            case "const", "constant", "fixed" -> constant(a[0]);
            case "exp", "exponential", "poisson" -> exponential(a[0]);
            case "uniform", "u" -> uniform(a[0], a[1]);
            case "normal", "gauss", "gaussian" -> normal(a[0], a[1]);
            case "lognormal" -> lognormal(a[0], a[1]);
            case "pareto" -> pareto(a[0], a[1]);
            default -> throw new IllegalArgumentException("unknown distribution: " + kind);
        };
    }
}
