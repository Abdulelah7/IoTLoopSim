package org.iotloopsim.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.*;

/**
 * Validation case E2: LoRaWAN class-A end-device energy and battery lifetime against the measurement-based model of
 * Casals, Mir, Vidal and Gomez, "Modeling the Energy Performance of LoRaWAN", Sensors 17(10):2364, 2017.
 *
 * <p>The paper measured the current profile of a MultiConnect mDot (SX1272) with an Agilent N6705A power analyser
 * (Table 5), derived the average current I_avg = (sum T_i I_i + I_sleep (T_notif - T_act)) / T_notif (Eqs. 1-3, 6-11)
 * and the lifetime C_battery / I_avg (Eq. 12) for a 2400 mAh battery, and reports that the model matched the measured
 * average current "almost exactly" in 4-minute measurements. IoTLoopSim uses the measured profile ({@code mdot-classa}
 * radio profile) and simulates a device sending one unconfirmed uplink every T_notif until its battery is empty.
 *
 * <p>Two comparisons are written to {@code v3_lorawan_energy.csv}:
 * <ol>
 *   <li>against an independent re-implementation of the paper's equations ({@link #paperModelMa}) over DR0-DR6 and
 *       several notification periods (checks that IoTLoopSim reproduces the measured state machine), and</li>
 *   <li>against the lifetimes printed in the paper's text (Section 5.2), including two printed values that are
 *       inconsistent with the paper's own model.</li>
 * </ol>
 * usage: java -cp iotloopsim-all.jar org.iotloopsim.validation.LoRaWANEnergyValidation [outDir]
 */
public final class LoRaWANEnergyValidation {
    static final double V = 3.6, C_MAH = 2400, I_SLEEP_MA = 45e-3, HOURS_PER_YEAR = 8760;
    static final int[] DR_SF = {12, 11, 10, 9, 8, 7, 7};
    static final int[] DR_MAXPAY = {51, 51, 51, 115, 242, 242, 242};

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "validation-results");
        Files.createDirectories(out);
        StringBuilder csv = new StringBuilder("comparison,dr,tnotif_min,payload_B,reference,reference_years,iotloopsim_years,iotloopsim_avg_current_mA,model_avg_current_mA,error_pct\n");
        // (1) IoTLoopSim vs the paper's model over a grid
        int[] tn = {5, 15, 60, 360, 1440};
        double maxErr = 0;
        for (int dr = 0; dr <= 6; dr++)
            for (int t : tn)
                for (int pay : new int[]{DR_MAXPAY[dr], 1}) {
                    double modelMa = paperModelMa(dr, t, pay);
                    double modelY = C_MAH / modelMa / HOURS_PER_YEAR;
                    double simY = simulatedLifetimeYears(dr, t, pay);
                    double simMa = C_MAH / (simY * HOURS_PER_YEAR);
                    double err = (simY - modelY) / modelY * 100;
                    maxErr = Math.max(maxErr, Math.abs(err));
                    csv.append(String.format("model,%d,%d,%d,Casals Eqs.1-12 (re-implemented),%.4f,%.4f,%.5f,%.5f,%.3f%n", dr, t, pay, modelY, simY, simMa, modelMa, err));
                }
        System.out.printf("IoTLoopSim vs paper model: max |error| = %.3f %% over %d cases%n", maxErr, 7 * tn.length * 2);
        // (2) IoTLoopSim vs lifetimes printed in the paper (Section 5.2, BER = 0, max payload, unconfirmed)
        double[][] printed = {{0, 5, 0.26}, {5, 5, 0.83}, {0, 60, 2.13}, {5, 60, 3.76}, {0, 360, 4.55}, {5, 360, 5.52}, {6, 1440, 5.96}};
        for (double[] p : printed) {
            int dr = (int) p[0], t = (int) p[1];
            double simY = simulatedLifetimeYears(dr, t, DR_MAXPAY[dr]);
            double err = (simY - p[2]) / p[2] * 100;
            csv.append(String.format("paper-text,%d,%d,%d,Casals Sec.5.2 printed value,%.2f,%.4f,%.5f,%.5f,%.3f%n", dr, t, DR_MAXPAY[dr], p[2], simY,
                    C_MAH / (simY * HOURS_PER_YEAR), paperModelMa(dr, t, DR_MAXPAY[dr]), err));
            System.out.printf("DR%d T=%4d min: IoTLoopSim %.3f y, paper text %.2f y (%+.1f %%)%n", dr, t, simY, p[2], err);
        }
        // the paper's printed DR0/DR5 current ratio at T_notif = 5 min ("a factor of 2.76")
        double ratio = simulatedLifetimeYears(5, 5, 242) / simulatedLifetimeYears(0, 5, 51);
        csv.append(String.format("paper-text,0/5,5,max,I(DR0)/I(DR5) printed factor,2.76,%.4f,,,%.3f%n", ratio, (ratio - 2.76) / 2.76 * 100));
        System.out.printf("I(DR0)/I(DR5) at 5 min: IoTLoopSim %.3f, paper 2.76%n", ratio);
        Files.writeString(out.resolve("v3_lorawan_energy.csv"), csv);
        System.out.println("written " + out.resolve("v3_lorawan_energy.csv"));
    }

    /** Simulated lifetime (years of 8760 h) of an mDot sending one unconfirmed uplink of {@code payload} B every {@code tnMin}. */
    static double simulatedLifetimeYears(int dr, int tnMin, int payload) {
        IoTWorld w = new IoTWorld(7);
        w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
        AccessTechnology base = AccessTechnology.preset("lorawan-dr" + dr);
        AccessTechnology t = new AccessTechnology(base.name(), base.bitrateBps(), base.latencyNs(), 0, 0, base.rangeM(), 0,
                base.frameHeader(), base.maxFrame(), base.dutyCycle(), base.loraSf(), base.loraBwHz(), RadioProfile.preset("mdot-classa"));
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of(t.name()), 0);
        ComputeNode n = w.addComputeNode("ns", new Position(0, 0), Tier.EDGE, 4, 1e6, 1 << 20, 0, 0, true);
        w.addLink("l", g, n, 1e9, SimTime.ms(1), 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
        Application app = new Application("a", "s", null, null, 0);
        app.addModule(new AppModule(app, "sink", Distribution.constant(0.001), 16, new ContainerImage("x", 1), 4, 64, 1, 1, 0,
                null, false, Distribution.constant(0.1), 0, 0));
        w.addApplication(app);
        double baselineW = I_SLEEP_MA / 1000 * V;
        IoTDevice d = w.addDevice("dev", "g", new Position(1, 1), t, Protocol.preset("none"), Battery.fromMah(C_MAH, V), baselineW, "v1");
        d.addSensor(new Sensor("s", Distribution.constant(tnMin * 60.0), payload, 0));
        w.run(SimTime.s(C_MAH / I_SLEEP_MA * 3600 * 1.1));
        for (LifecycleLog.Entry en : w.lifecycle().entries())
            if (en.entity().equals("dev") && en.to().equals("DEPLETED")) return en.time() / 1e9 / 3600 / HOURS_PER_YEAR;
        return Double.NaN;
    }

    // ------------------------------------------------------------------ independent implementation of the paper's model
    /** Casals et al. Eq. (9): LoRa time on air (ms) of a PHY payload, CR 4/5, 8-symbol preamble, CRC on. */
    static double toaMs(int phyPayload, int sf, double bw) {
        int de = sf >= 11 && bw <= 125e3 ? 1 : 0;
        double tsym = Math.pow(2, sf) / bw * 1000;
        double nphy = 8 + Math.max(Math.ceil((28 + 8.0 * phyPayload + 16 - 4.0 * sf) / (4.0 * (sf - 2 * de))) * 5, 0);
        return (8 + 4.25) * tsym + nphy * tsym;
    }

    /** Casals et al. Eqs. (1)-(4), (10) with Table 5 values: average current (mA) for unconfirmed uplinks. */
    static double paperModelMa(int dr, double tnMin, int payload) {
        int sf = DR_SF[dr];
        double bw = dr == 6 ? 250e3 : 125e3, tsym = Math.pow(2, sf) / bw * 1000;
        double ttx = toaMs(payload + 13, sf, bw);                          // FRMPayload + MHDR/FHDR/FPort/MIC (13 B)
        double trx1 = (sf >= 11 ? 8 : 12) * tsym;                          // Eq. (10)
        double tw2 = 1000 - trx1;                                          // Eq. (4): RECEIVE_DELAY2 - RECEIVE_DELAY1 - Trx1w
        double[][] st = {{168.2, 22.1}, {83.8, 13.3}, {ttx, 83.0}, {983.3, 27.0}, {trx1, 38.1}, {tw2, 27.1}, {33.0, 35.0},
                {147.4, 13.2}, {268.0, 21.0}, {38.6, 13.3}};
        double q = 0, tact = 0;
        for (double[] s : st) { q += s[0] * s[1]; tact += s[0]; }
        double tn = tnMin * 60_000;
        return (q + I_SLEEP_MA * (tn - tact)) / tn;
    }

    private LoRaWANEnergyValidation() {}
}
