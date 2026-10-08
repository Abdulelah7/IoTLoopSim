package org.iotloopsim.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.iotloopsim.util.Cfg;

/**
 * Parameter profile of a wireless or wired access technology at the analytical/queue fidelity levels.
 * Values are typical published figures and can be overridden in the scenario.
 *
 * @param bitrateBps   effective application bitrate
 * @param latencyNs    one-way access latency excluding serialisation
 * @param txPowerW     radio power while transmitting (device side)
 * @param rxPowerW     radio power while receiving
 * @param rangeM       coverage radius of a gateway/base station
 * @param loss         residual frame loss probability after link-layer retransmissions (ARQ/HARQ)
 * @param frameHeader  link-layer header bytes per frame
 * @param maxFrame     maximum payload per frame (larger messages are fragmented)
 * @param dutyCycle    regulatory duty cycle (1.0 = unrestricted; LoRaWAN EU868 = 0.01)
 * @param loraSf       LoRa spreading factor (7..12); 0 for non-LoRa technologies. When set, airtime follows the
 *                     Semtech SX127x time-on-air formula (CR 4/5, 8-symbol preamble, explicit header, CRC on,
 *                     low-data-rate optimisation for SF11/12 at 125 kHz) instead of payload / bitrate.
 * @param loraBwHz     LoRa bandwidth in Hz (125e3, 250e3, 500e3)
 * @param profile      optional measured per-frame energy/timing profile ({@link RadioProfile}); when set it replaces
 *                     {@code txPowerW} for uplinks and adds the pre/post phases (e.g. LoRaWAN class-A receive windows).
 *                     {@code null} = simple model (tx power x airtime).
 */
public record AccessTechnology(String name, double bitrateBps, long latencyNs, double txPowerW, double rxPowerW,
                               double rangeM, double loss, int frameHeader, int maxFrame, double dutyCycle,
                               int loraSf, double loraBwHz, RadioProfile profile) {

    /** Constructor without a measured radio profile. */
    public AccessTechnology(String name, double bitrateBps, long latencyNs, double txPowerW, double rxPowerW,
                            double rangeM, double loss, int frameHeader, int maxFrame, double dutyCycle,
                            int loraSf, double loraBwHz) {
        this(name, bitrateBps, latencyNs, txPowerW, rxPowerW, rangeM, loss, frameHeader, maxFrame, dutyCycle, loraSf, loraBwHz, null);
    }

    /** Constructor for non-LoRa technologies. */
    public AccessTechnology(String name, double bitrateBps, long latencyNs, double txPowerW, double rxPowerW,
                            double rangeM, double loss, int frameHeader, int maxFrame, double dutyCycle) {
        this(name, bitrateBps, latencyNs, txPowerW, rxPowerW, rangeM, loss, frameHeader, maxFrame, dutyCycle, 0, 0, null);
    }

    private static final Map<String, AccessTechnology> PRESETS = new LinkedHashMap<>();
    static {
        add(new AccessTechnology("wifi", 20e6, 2_000_000, 0.80, 0.50, 100, 0.001, 34, 1500, 1.0));
        add(new AccessTechnology("ble", 1e6, 7_500_000, 0.03, 0.03, 30, 0.005, 14, 244, 1.0));
        add(new AccessTechnology("zigbee", 250e3, 10_000_000, 0.06, 0.06, 75, 0.005, 25, 100, 1.0));
        add(new AccessTechnology("802.15.4", 250e3, 10_000_000, 0.06, 0.06, 75, 0.005, 25, 100, 1.0));
        add(new AccessTechnology("lorawan", 5.47e3, 50_000_000, 0.13, 0.04, 5000, 0.05, 13, 222, 0.01, 7, 125e3));
        add(new AccessTechnology("lorawan-sf9", 1.76e3, 50_000_000, 0.13, 0.04, 8000, 0.05, 13, 115, 0.01, 9, 125e3));
        add(new AccessTechnology("lorawan-sf12", 0.25e3, 50_000_000, 0.13, 0.04, 14000, 0.05, 13, 51, 0.01, 12, 125e3));
        // EU868 LoRaWAN data rates (LoRaWAN Regional Parameters): DR0..DR5 = SF12..SF7 at 125 kHz, DR6 = SF7 at 250 kHz;
        // maxFrame = maximum FRMPayload N (no repeater). Ranges are indicative.
        int[] drSf = {12, 11, 10, 9, 8, 7, 7};
        int[] drMax = {51, 51, 51, 115, 242, 242, 242};
        double[] drRate = {250, 440, 980, 1760, 3125, 5470, 11000};
        double[] drRange = {14000, 12000, 10000, 8000, 6500, 5000, 4000};
        for (int i = 0; i < 7; i++)
            add(new AccessTechnology("lorawan-dr" + i, drRate[i], 50_000_000, 0.13, 0.04, drRange[i], 0.05, 13, drMax[i], 0.01,
                    drSf[i], i == 6 ? 250e3 : 125e3));
        add(new AccessTechnology("nbiot", 60e3, 300_000_000, 0.70, 0.20, 10000, 0.001, 30, 1600, 1.0));
        add(new AccessTechnology("lte", 10e6, 30_000_000, 1.50, 1.00, 5000, 0.001, 40, 1500, 1.0));
        add(new AccessTechnology("5g", 100e6, 5_000_000, 1.80, 1.20, 500, 0.0005, 40, 1500, 1.0));
        add(new AccessTechnology("ethernet", 1e9, 100_000, 0.0, 0.0, 0, 0.0, 38, 1500, 1.0));
    }

    private static void add(AccessTechnology t) { PRESETS.put(t.name, t); }

    /**
     * Returns a built-in technology by case-insensitive name (e.g. wifi, ble, zigbee, lorawan, lorawan-dr0 to
     * lorawan-dr6, nbiot, lte, 5g, ethernet); throws {@link IllegalArgumentException} for unknown names.
     */
    public static AccessTechnology preset(String name) {
        AccessTechnology t = PRESETS.get(name.toLowerCase());
        if (t == null) throw new IllegalArgumentException("unknown access technology '" + name + "'; known: " + PRESETS.keySet());
        return t;
    }

    /** Read-only view of the registered technologies. */
    public static Map<String, AccessTechnology> presets() { return java.util.Collections.unmodifiableMap(PRESETS); }

    /**
     * Registers a new access technology (or replaces one) so that scenarios can use it by name, e.g. from a
     * {@code PolicyProvider} plugin or before loading a scenario. Names are case-insensitive.
     */
    public static void register(AccessTechnology t) { PRESETS.put(t.name.toLowerCase(), t); }

    /** Copy of this technology with a measured radio profile (or {@code null} to remove it). */
    public AccessTechnology withProfile(RadioProfile p) {
        return new AccessTechnology(name, bitrateBps, latencyNs, txPowerW, rxPowerW, rangeM, loss, frameHeader, maxFrame, dutyCycle, loraSf, loraBwHz, p);
    }

    /**
     * Preset with any fields overridden by the given config: bitrate (bit/s), latency (duration), txPower and rxPower (W),
     * range (m), loss (probability), frameHeader and maxFrame (bytes), dutyCycle (fraction), sf, bw (Hz), and
     * profile (a {@link RadioProfile} name or map).
     */
    public static AccessTechnology fromConfig(String base, Cfg c) {
        AccessTechnology t = preset(base);
        if (c == null) return t;
        return new AccessTechnology(t.name, c.num("bitrate", t.bitrateBps), c.has("latency") ? c.duration("latency") : t.latencyNs,
                c.num("txPower", t.txPowerW), c.num("rxPower", t.rxPowerW), c.num("range", t.rangeM), c.num("loss", t.loss),
                c.integer("frameHeader", t.frameHeader), c.integer("maxFrame", t.maxFrame), c.num("dutyCycle", t.dutyCycle),
                c.integer("sf", t.loraSf), c.num("bw", t.loraBwHz),
                c.has("profile") ? RadioProfile.fromConfig(c.raw("profile"), c.raw("profile") instanceof Map ? c.sub("profile") : null) : t.profile);
    }

    /** Number of frames needed for a message of {@code bytes}. */
    public int frames(int bytes) { return Math.max(1, (bytes + maxFrame - 1) / maxFrame); }

    /** Time on air in ns for {@code bytes} of payload, including per-frame headers. */
    public long airtimeNs(int bytes) {
        if (loraSf > 0) {
            int n = frames(bytes);
            long t = 0;
            int left = bytes;
            for (int i = 0; i < n; i++) {
                int pl = Math.min(left, maxFrame);
                left -= pl;
                t += loraFrameNs(pl + frameHeader, loraSf, loraBwHz);
            }
            return t;
        }
        long total = bytes + (long) frames(bytes) * frameHeader;
        return Math.round(total * 8.0 / bitrateBps * 1e9);
    }

    /** Semtech SX1272/76 time on air of one LoRa frame with {@code phyPayload} bytes (CR 4/5, preamble 8, explicit header, CRC). */
    public static long loraFrameNs(int phyPayload, int sf, double bwHz) {
        int de = (sf >= 11 && bwHz <= 125e3) ? 1 : 0;
        double tsym = Math.pow(2, sf) / bwHz;
        double tpre = (8 + 4.25) * tsym;
        double num = 8.0 * phyPayload - 4.0 * sf + 28 + 16;
        int nsym = 8 + Math.max((int) Math.ceil(num / (4.0 * (sf - 2 * de))) * 5, 0);
        return Math.round((tpre + nsym * tsym) * 1e9);
    }
}
