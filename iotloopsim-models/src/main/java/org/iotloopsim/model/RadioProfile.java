package org.iotloopsim.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iotloopsim.util.Cfg;

/**
 * Measured state-machine energy profile of one uplink transmission, as obtained with a power analyser on real hardware
 * (e.g. Casals et al., "Modeling the Energy Performance of LoRaWAN", Sensors 17(10):2364, 2017, Tables 5-6).
 *
 * <p>An uplink frame is modelled as {@code pre} phases (wake-up, radio preparation), the transmission itself (duration =
 * time on air of the access technology, current {@link #txCurrentMa()}), and {@code post} phases (receive windows, waits,
 * post-processing, turn-off). Each phase has a current in mA and a duration given as one of:
 * <ul>
 *   <li>{@code ms} - a fixed duration;</li>
 *   <li>{@code symbols: [n, nLowDr]} - n LoRa symbols (nLowDr when SF &ge; 11), e.g. a receive window with no preamble;</li>
 *   <li>{@code untilAfterTxMs} - lasts until the given offset after the end of the transmission (e.g. the wait before
 *       the LoRaWAN class-A second receive window, which opens at a fixed RECEIVE_DELAY2).</li>
 * </ul>
 *
 * <p><b>Energy accounting.</b> Currents are the device's total current in that phase. Because IoTLoopSim already charges the
 * device's {@code baselinePower} continuously, a phase is charged {@code max(0, I*V - baselinePower) * duration}, so
 * baseline + profile equals the measured total. Set the device's {@code baselinePower} to the measured sleep power
 * (I_sleep * V).
 *
 * <p><b>Timing.</b> The transmission starts after the pre phases, and the radio is busy (cannot start another uplink)
 * until the post phases end, as in LoRaWAN class A. Messages larger than the technology's {@code maxFrame} are sent
 * as several frames, each with the full profile.
 *
 * @param name        profile name
 * @param supplyV     supply voltage in volts (converts mA to W)
 * @param pre         phases before the transmission
 * @param txCurrentMa current while transmitting, in mA
 * @param post        phases after the transmission
 */
public record RadioProfile(String name, double supplyV, List<Phase> pre, double txCurrentMa, List<Phase> post) {

    /** Kind of duration specification of a {@link Phase}. */
    public enum DurationKind { FIXED, SYMBOLS, UNTIL_AFTER_TX }

    /**
     * One phase of the profile.
     *
     * @param name      phase name (reported in energy categories as {@code radio-<name>})
     * @param kind      how the duration is specified
     * @param a         FIXED: duration in ms; SYMBOLS: symbols for SF &lt; 11; UNTIL_AFTER_TX: offset after the end of tx in ms
     * @param b         SYMBOLS: symbols for SF &ge; 11 (otherwise unused)
     * @param currentMa current drawn in this phase, in mA
     */
    public record Phase(String name, DurationKind kind, double a, double b, double currentMa) {
        /** Returns a phase of fixed duration {@code ms} in ms drawing {@code mA} mA. */
        public static Phase fixed(String name, double ms, double mA) { return new Phase(name, DurationKind.FIXED, ms, 0, mA); }
        /**
         * Returns a phase lasting {@code n} LoRa symbols ({@code nLowDr} when SF is 11 or more) drawing {@code mA}
         * mA; usable only with LoRa technologies.
         */
        public static Phase symbols(String name, double n, double nLowDr, double mA) { return new Phase(name, DurationKind.SYMBOLS, n, nLowDr, mA); }
        /**
         * Returns a phase that lasts until {@code offsetMs} ms after the end of the transmission (0 if already
         * passed), drawing {@code mA} mA.
         */
        public static Phase untilAfterTx(String name, double offsetMs, double mA) { return new Phase(name, DurationKind.UNTIL_AFTER_TX, offsetMs, 0, mA); }
    }

    /**
     * Validates that {@code supplyV} is positive (otherwise throws {@link IllegalArgumentException}) and copies
     * the phase lists into immutable lists.
     */
    public RadioProfile {
        if (supplyV <= 0) throw new IllegalArgumentException("radio profile '" + name + "': supplyV must be > 0");
        pre = List.copyOf(pre); post = List.copyOf(post);
    }

    /** Durations (ns) of the pre phases, in order. */
    public long[] preNs(AccessTechnology t) { return durations(pre, t, 0); }
    /** Durations (ns) of the post phases, in order. */
    public long[] postNs(AccessTechnology t) { return durations(post, t, 0); }

    private static long[] durations(List<Phase> ps, AccessTechnology t, long ignored) {
        long[] d = new long[ps.size()];
        long sinceTx = 0;
        for (int i = 0; i < ps.size(); i++) {
            Phase p = ps.get(i);
            long ns = switch (p.kind()) {
                case FIXED -> Math.round(p.a() * 1e6);
                case SYMBOLS -> {
                    if (t.loraSf() <= 0) throw new IllegalArgumentException("phase '" + p.name() + "' uses symbols but technology '" + t.name() + "' is not LoRa");
                    double n = t.loraSf() >= 11 ? p.b() : p.a();
                    yield Math.round(n * Math.pow(2, t.loraSf()) / t.loraBwHz() * 1e9);
                }
                case UNTIL_AFTER_TX -> Math.max(0, Math.round(p.a() * 1e6) - sinceTx);
            };
            d[i] = ns;
            sinceTx += ns;
        }
        return d;
    }

    /** Total duration (ns) of pre and post phases for one frame. */
    public long overheadNs(AccessTechnology t) {
        long s = 0;
        for (long x : preNs(t)) s += x;
        for (long x : postNs(t)) s += x;
        return s;
    }

    /** Energy (J) above baseline for one frame: pre + post phases, excluding the transmission itself. */
    public double overheadJ(AccessTechnology t, double baselineW) {
        double j = 0;
        long[] a = preNs(t), b = postNs(t);
        for (int i = 0; i < a.length; i++) j += Math.max(0, pre.get(i).currentMa() / 1000 * supplyV - baselineW) * a[i] / 1e9;
        for (int i = 0; i < b.length; i++) j += Math.max(0, post.get(i).currentMa() / 1000 * supplyV - baselineW) * b[i] / 1e9;
        return j;
    }

    /** Power (W) above baseline while transmitting. */
    public double txPowerAboveBaselineW(double baselineW) { return Math.max(0, txCurrentMa / 1000 * supplyV - baselineW); }

    // ------------------------------------------------------------------ presets and configuration
    private static final Map<String, RadioProfile> PRESETS = new LinkedHashMap<>();
    static {
        // MultiConnect mDot (SX1272), LoRaWAN class A, unconfirmed uplink, no downlink received; Casals et al. 2017, Table 5.
        // RX1 stays open 12 symbols (8 for SF11/12); RX2 uses CAD at DR0 (measured 33.0 ms); RX2 opens 1 s after RX1.
        register(new RadioProfile("mdot-classa", 3.6,
                List.of(Phase.fixed("wakeup", 168.2, 22.1), Phase.fixed("prep", 83.8, 13.3)),
                83.0,
                List.of(Phase.fixed("wait-rx1", 983.3, 27.0), Phase.symbols("rx1", 12, 8, 38.1),
                        Phase.untilAfterTx("wait-rx2", 1983.3, 27.1), Phase.fixed("rx2", 33.0, 35.0),
                        Phase.fixed("radio-off", 147.4, 13.2), Phase.fixed("post", 268.0, 21.0), Phase.fixed("turn-off", 38.6, 13.3))));
    }

    /** Registers (or replaces) a named profile so that scenarios can refer to it with {@code profile: <name>}. */
    public static void register(RadioProfile p) { PRESETS.put(p.name(), p); }

    /**
     * Returns a registered profile by exact name (built-in: {@code mdot-classa}); throws {@link
     * IllegalArgumentException} for unknown names.
     */
    public static RadioProfile preset(String name) {
        RadioProfile p = PRESETS.get(name);
        if (p == null) throw new IllegalArgumentException("unknown radio profile '" + name + "'; known: " + PRESETS.keySet());
        return p;
    }

    /** Returns a read-only view of the registered profiles. */
    public static Map<String, RadioProfile> presets() { return java.util.Collections.unmodifiableMap(PRESETS); }

    /**
     * Profile from a scenario value: a preset name, or a map
     * {@code {base?, supplyV, txMa, pre: [{name, ms|symbols|untilAfterTxMs, mA}], post: [...]}}.
     */
    public static RadioProfile fromConfig(Object raw, Cfg c) {
        if (raw == null) return null;
        if (raw instanceof String s) return preset(s);
        RadioProfile base = c.has("base") ? preset(c.str("base")) : null;
        List<Phase> pre = c.has("pre") ? phases(c.list("pre")) : base != null ? base.pre : List.of();
        List<Phase> post = c.has("post") ? phases(c.list("post")) : base != null ? base.post : List.of();
        return new RadioProfile(c.str("name", base != null ? base.name : "custom"), c.num("supplyV", base != null ? base.supplyV : 3.3),
                pre, c.num("txMa", base != null ? base.txCurrentMa : 0), post);
    }

    private static List<Phase> phases(List<Cfg> list) {
        List<Phase> out = new ArrayList<>();
        for (Cfg p : list) {
            String n = p.str("name", "phase" + out.size());
            double mA = p.num("mA");
            if (p.has("ms")) out.add(Phase.fixed(n, p.num("ms"), mA));
            else if (p.has("untilAfterTxMs")) out.add(Phase.untilAfterTx(n, p.num("untilAfterTxMs"), mA));
            else if (p.has("symbols")) {
                Object s = p.raw("symbols");
                if (s instanceof List<?> l) out.add(Phase.symbols(n, ((Number) l.get(0)).doubleValue(), ((Number) l.get(l.size() - 1)).doubleValue(), mA));
                else out.add(Phase.symbols(n, p.num("symbols"), p.num("symbols"), mA));
            } else throw new IllegalArgumentException("radio profile phase '" + n + "' needs one of ms, symbols, untilAfterTxMs");
        }
        return out;
    }
}
