package org.iotloopsim.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.*;
import org.iotloopsim.model.policy.BuiltinPolicies;

/**
 * V3 – comparison with published testbed measurements.
 *
 * E1: cold container migration between two Raspberry Pi cloudlets (Puliafito et al., MobFogSim, SIMPAT 101 (2020),
 * Section 5.4, Figs. 16–17): ~115 MB of checkpointed state moved with rsync under two emulated network conditions,
 * A = 11.6 Mbps / 61.48 ms one-way and B = 74.1 Mbps / 3.47 ms one-way. Measured total migration time (digitised from
 * Fig. 16a): A = 87.1 ± 2.4 s, B = 16.8 ± 1.5 s. IoTLoopSim reproduces the experiment with its COLD migration model
 * (checkpoint → transfer → restore); the fixed checkpoint+restore overhead is calibrated on one condition and used
 * to predict the other (two-fold cross-validation).
 *
 * usage: java -cp iotloopsim-all.jar org.iotloopsim.validation.EmpiricalValidation [outDir]
 */
public final class EmpiricalValidation {
    record Condition(String name, double bps, double oneWayMs, double measuredS, double measuredCi) {}

    static final Condition A = new Condition("A", 11.6e6, 61.48, 87.1, 2.4);
    static final Condition B = new Condition("B", 74.1e6, 3.47, 16.8, 1.5);

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "validation-results");
        Files.createDirectories(out);
        StringBuilder csv = new StringBuilder("case,condition,state_MB_definition,measured_s,measured_ci_s,simulated_no_overhead_s,overhead_calibrated_on,overhead_s,predicted_s,error_pct,within_measured_ci\n");
        for (boolean mib : new boolean[]{true, false}) {
            long bytes = mib ? 115L * 1024 * 1024 : 115_000_000L;
            String def = mib ? "115 MiB" : "115 MB (10^6)";
            double rawA = migrate(A, bytes, 0), rawB = migrate(B, bytes, 0);
            double ohFromB = B.measuredS - rawB, ohFromA = A.measuredS - rawA;
            double predA = migrate(A, bytes, ohFromB), predB = migrate(B, bytes, ohFromA);
            row(csv, def, A, rawA, "B", ohFromB, predA);
            row(csv, def, B, rawB, "A", ohFromA, predB);
            System.out.printf("%-14s A: raw %.2f s, overhead(B) %.2f s -> predicted %.2f s vs measured %.1f ± %.1f s%n", def, rawA, ohFromB, predA, A.measuredS, A.measuredCi);
            System.out.printf("%-14s B: raw %.2f s, overhead(A) %.2f s -> predicted %.2f s vs measured %.1f ± %.1f s%n", def, rawB, ohFromA, predB, B.measuredS, B.measuredCi);
        }
        Files.writeString(out.resolve("v3_empirical_migration.csv"), csv);
        System.out.println("written " + out.resolve("v3_empirical_migration.csv"));
    }

    static void row(StringBuilder b, String def, Condition c, double raw, String calOn, double oh, double pred) {
        double err = (pred - c.measuredS) / c.measuredS * 100;
        boolean in = Math.abs(pred - c.measuredS) <= c.measuredCi;
        b.append(String.format("E1 cold migration,%s,%s,%.1f,%.1f,%.3f,%s,%.3f,%.3f,%.2f,%s%n", c.name, def, c.measuredS, c.measuredCi, raw, calOn, oh, pred, err, in));
    }

    /** Simulated cold-migration time for one handover; {@code overheadS} = checkpoint + restore (split evenly). */
    static double migrate(Condition c, long stateBytes, double overheadS) {
        IoTWorld w = new IoTWorld(1);
        w.setPlacement(new BuiltinPolicies.TierFirst(new Tier[]{Tier.EDGE}, false));
        w.setMigration(new BuiltinPolicies.FollowMe("cold", Distribution.constant(overheadS / 2)));
        w.setInternalProtocol(new Protocol("rsync-tcp", 0, true, 0, 3, SimTime.s(5)));
        AccessTechnology wifi = new AccessTechnology("wifi", 100e6, SimTime.ms(1), 0, 0, 60, 0, 0, 1500, 1.0);
        Gateway g1 = w.addGateway("ap1", new Position(0, 0), Set.of("wifi"), 0);
        Gateway g2 = w.addGateway("ap2", new Position(100, 0), Set.of("wifi"), 0);
        ComputeNode c1 = w.addComputeNode("pi1", new Position(0, 0), Tier.EDGE, 4, 1000, 4096, 2, 6, true);
        ComputeNode c2 = w.addComputeNode("pi2", new Position(100, 0), Tier.EDGE, 4, 1000, 4096, 2, 6, true);
        w.addLink("ap1-pi1", g1, c1, 1e9, SimTime.us(100), 0, 0, 0, 0, Link.Fidelity.QUEUE);
        w.addLink("ap2-pi2", g2, c2, 1e9, SimTime.us(100), 0, 0, 0, 0, Link.Fidelity.QUEUE);
        w.addLink("pi1-pi2", c1, c2, c.bps, SimTime.ms(c.oneWayMs), 0, 0, 0, 0, Link.Fidelity.QUEUE);   // tc-netem/tc-htb emulated path
        Application app = new Application("coap-app", "req", null, null, 0);
        app.addModule(new AppModule(app, "server", Distribution.constant(10), 64, new ContainerImage("server-bundle", 412), 1, 512,
                1, 2, stateBytes, null, false, Distribution.constant(overheadS / 2), 0, 0));
        w.addApplication(app);
        IoTDevice user = w.addDevice("user", "u", new Position(0, 0), wifi, Protocol.preset("coap-con"), null, 0, "v1");
        user.addSensor(new Sensor("req", Distribution.constant(1), 64, 0));
        user.add(new MobilityModel.Trace(List.of(new MobilityModel.Trace.Point(0, 0, 0), new MobilityModel.Trace.Point(SimTime.s(20), 0, 0),
                new MobilityModel.Trace.Point(SimTime.s(21), 100, 0))));
        w.setDeviceStart(SimTime.s(5));
        w.run(SimTime.s(400));
        var s = w.sim().metrics().sample("migration.downtime_ms");
        if (s == null || s.count() != 1) {
            for (var e : w.lifecycle().entries()) if (!e.kind().equals("device")) System.err.println(e);
            throw new IllegalStateException("expected exactly one migration, got " + (s == null ? 0 : s.count()));
        }
        return s.mean() / 1000.0;
    }
}
