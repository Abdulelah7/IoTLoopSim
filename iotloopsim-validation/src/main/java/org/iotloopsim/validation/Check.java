package org.iotloopsim.validation;

import java.util.List;
import org.iotloopsim.util.Stats;

/** One validation comparison: an analytical (or reference) value against simulated replications. */
public record Check(String group, String test, String parameters, double expected, List<Double> simulated, double tolerance, String unit) {

    public double mean() { return Stats.mean(simulated); }
    public double ci95() { return Stats.ci95(simulated); }
    public double relError() { return expected == 0 ? mean() - expected : (mean() - expected) / Math.abs(expected); }

    /** Passes if the expected value lies inside the 95 % CI of the simulated mean, or within the relative tolerance. */
    public boolean pass() {
        double m = mean(), h = ci95();
        boolean inCi = expected >= m - h && expected <= m + h;
        boolean inTol = Math.abs(relError()) <= tolerance;
        return inCi || inTol;
    }

    public String csv() {
        return String.format("%s,\"%s\",\"%s\",%.6g,%.6g,%.3g,%.3f,%d,%.3f,%s,%s", group, test.replace("\"", "'"), parameters, expected, mean(), ci95(),
                relError() * 100, simulated.size(), tolerance * 100, unit, pass() ? "PASS" : "FAIL");
    }

    public static String header() { return "group,test,parameters,expected,simulated_mean,ci95_halfwidth,rel_error_pct,n,tolerance_pct,unit,verdict"; }
}
