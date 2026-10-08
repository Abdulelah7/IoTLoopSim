package org.iotloopsim.model;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.policy.PolicyRegistry;
import org.iotloopsim.util.Cfg;

/** Regression tests for previously fixed defects and for the measured radio profile. */
public class RegressionTest {

    static IoTWorld base(long seed) {
        IoTWorld w = new IoTWorld(seed);
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("wifi", "lorawan"), 0);
        ComputeNode e = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 4, 2000, 8192, 20, 60, false);
        ComputeNode c = w.addComputeNode("cloud", new Position(0, 0), Tier.CLOUD, 32, 4000, 65536, 100, 300, true);
        w.addLink("ge", g, e, 1e9, SimTime.ms(1), 0, 1 << 22, 1e-9, 0, Link.Fidelity.QUEUE);
        w.addLink("ec", e, c, 1e9, SimTime.ms(20), 0, 1 << 22, 1e-8, 0, Link.Fidelity.QUEUE);
        return w;
    }

    static AppModule module(Application app, String name, int out, Tier tier, boolean actuate) {
        return app.addModule(new AppModule(app, name, Distribution.constant(1), out, new ContainerImage(name, 1), 1, 256,
                1, 1, 0, tier, actuate, Distribution.constant(0.1), 0, 100));
    }

    @Test public void reportIsIdempotent() {
        IoTWorld w = base(1);
        w.cost().setEnergyPrice(0.15);
        double a = w.run(SimTime.MIN * 10).get("cost.energy");
        assertEquals(a, w.report().get("cost.energy"), 1e-15);
        assertEquals(a, w.report().get("cost.energy"), 1e-15);
    }

    @Test public void tinyBaselineDoesNotOverflowTheClock() {
        IoTWorld w = base(1);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"),
                Battery.fromMah(2600, 3.6), 1e-6, "v1");
        d.addSensor(new Sensor("temp", Distribution.constant(60), 16, 0.0));
        w.run(SimTime.H);   // used to throw "cannot schedule in the past"
        assertEquals(DeviceState.ACTIVE, d.state());
    }

    @Test public void childRequestsCarryTheParentsOutput() {
        IoTWorld w = base(1);
        Application app = new Application("app", "temp", null, null, 0);
        AppModule big = module(app, "big", 100_000, Tier.EDGE, false);
        big.addChild(module(app, "sink", 10, Tier.CLOUD, false));
        w.addApplication(app);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"), null, 0, "v1");
        d.addSensor(new Sensor("temp", Distribution.constant(1), 16, 0.0));
        w.setDeviceStart(SimTime.s(30));
        Map<String, Double> r = w.run(SimTime.s(130));
        Link ec = (Link) w.sim().entity("ec");
        assertTrue("edge->cloud bytes per request should be ~100 kB", ec.bytesCarried() / r.get("app.app.sink_completed") > 90_000);
        assertEquals("sink-only app: loops complete at the sink", 1.0, r.get("loop.success_ratio"), 0.02);
    }

    @Test public void loopWithTwoActuatingModulesCountsOnce() {
        IoTWorld w = base(1);
        Application app = new Application("app", "temp", null, "valve", 0);
        AppModule a = module(app, "a", 64, Tier.EDGE, true);
        a.addChild(module(app, "b", 64, Tier.EDGE, true));
        w.addApplication(app);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"), null, 0, "v1");
        d.addSensor(new Sensor("temp", Distribution.constant(1), 16, 0.0));
        d.addActuator(new Actuator("valve", Distribution.constant(1), 0));
        w.setDeviceStart(SimTime.s(30));
        Map<String, Double> r = w.run(SimTime.s(130));
        assertTrue(r.get("loop.completed") <= r.get("loop.generated"));
        assertEquals(1.0, r.get("loop.success_ratio"), 0.02);
    }

    @Test public void archivingBranchDoesNotDelayOrFailTheControlLoop() {
        IoTWorld w = base(1);
        Application app = new Application("app", "temp", null, "valve", 0);
        AppModule root = module(app, "detect", 64, Tier.EDGE, false);
        root.addChild(module(app, "decide", 32, Tier.EDGE, true));
        root.addChild(module(app, "archive", 0, Tier.CLOUD, false));
        w.addApplication(app);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"), null, 0, "v1");
        d.addSensor(new Sensor("temp", Distribution.constant(1), 16, 0.0));
        d.addActuator(new Actuator("valve", Distribution.constant(1), 0));
        w.setDeviceStart(SimTime.s(30));
        Map<String, Double> r = w.run(SimTime.s(130));
        assertEquals(1.0, r.get("loop.success_ratio"), 0.02);
        assertTrue("loop latency excludes the 20 ms cloud hop", r.get("loop.latency_ms.mean") < 20);
        assertTrue(r.get("app.app.sink_completed") > 90);
    }

    @Test public void otaDownloadsShareTheGatewayDownlinkUnderItsDutyCycle() {
        IoTWorld w = base(1);
        AccessTechnology lora = AccessTechnology.preset("lorawan");
        for (int i = 0; i < 3; i++)
            w.addDevice("d" + i, "soil", new Position(1 + i, 1), lora, Protocol.preset("udp"), null, 0.001, "v1");
        w.updates().campaign("c", e -> e instanceof IoTDevice, "v2", 4, SimTime.s(10), PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "all-at-once", null),
                Distribution.constant(10), 0.0, true, SimTime.MIN);
        Map<String, Double> r = w.run(SimTime.H * 6);
        int bytes = 4 * 1024;
        double occupancyS = lora.airtimeNs(bytes + bytes / 50) / 0.1 / 1e9;   // default LoRa gateway downlink duty cycle 10 %
        double extraS = lora.latencyNs() * lora.frames(bytes) / 1e9;
        assertEquals(3.0, r.get("update.succeeded"), 0);
        assertEquals(2 * occupancyS + extraS, r.get("update.download_s.mean"), 1e-3);  // served one after another: 1, 2 and 3 occupancies
        assertEquals(3 * occupancyS + extraS, r.get("update.download_s.max"), 1e-3);   // third waits for the first two
    }

    @Test public void batchCampaignEndsWithItsLastBatchNotAfterAnotherInterval() {
        IoTWorld w = base(1);
        for (int i = 0; i < 4; i++)
            w.addDevice("d" + i, "soil", new Position(1 + i, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"), null, 0.001, "v1");
        w.updates().campaign("c", e -> e instanceof IoTDevice, "v2", 1, SimTime.s(10),
                PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "batch", Cfg.of(Map.of("batchSize", 2, "interval", "10min"))),
                Distribution.constant(5), 0.0, true, SimTime.MIN);
        Map<String, Double> r = w.run(SimTime.H);
        // two batches and one 10-minute pause between them; no pause after the last batch
        double d = r.get("update.campaign_duration_s.mean");
        assertTrue("duration " + d, d > 600 && d < 700);
    }

    @Test public void deviceDepletedDuringOtaStaysDepleted() {
        IoTWorld w = base(1);
        IoTDevice d = w.addDevice("d", "soil", new Position(1, 1), AccessTechnology.preset("lorawan"), Protocol.preset("udp"), new Battery(0.05), 0, "v1");
        w.updates().campaign("c", e -> e == d, "v2", 4, SimTime.s(10), PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "all-at-once", null),
                Distribution.constant(10), 0.0, true, SimTime.MIN);
        Map<String, Double> r = w.run(SimTime.H);
        assertEquals(DeviceState.DEPLETED, d.state());
        assertEquals("v1", d.firmware());
        assertNull(r.get("update.succeeded"));
        assertEquals(1.0, r.get("update.interrupted"), 0);
    }

    @Test public void deviceRetiredDuringOtaStaysRetired() {
        IoTWorld w = base(1);
        IoTDevice d = w.addDevice("d", "soil", new Position(1, 1), AccessTechnology.preset("lorawan"), Protocol.preset("udp"), null, 0.001, "v1");
        w.updates().campaign("c", e -> e == d, "v2", 4, SimTime.s(10), PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "all-at-once", null),
                Distribution.constant(60), 0.0, true, SimTime.MIN);
        w.at(SimTime.s(30), d::decommission);
        w.run(SimTime.H);
        assertEquals(DeviceState.DECOMMISSIONED, d.state());
    }

    @Test public void carbonOfLazilyIntegratedEnergyFollowsTheHourlyProfile() {
        IoTWorld w = new IoTWorld(1);
        w.addGateway("gw", new Position(0, 0), Set.of("wifi"), 0);
        w.addDevice("d", "g", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"), null, 1.0, "v1");
        List<Double> prof = new ArrayList<>();
        for (int i = 0; i < 24; i++) prof.add(i == 0 ? 0.0 : 1000.0);
        w.carbon().setDefault(prof);
        Map<String, Double> r = w.run(SimTime.H * 24);
        double kwh = r.get("energy.device_J") / 3.6e6;
        assertEquals(kwh * 1000 * 23 / 24, r.get("carbon.device_g"), 1e-6);
    }

    @Test public void deviceCannotConsumeMoreThanItsBattery() {
        IoTWorld w = base(1);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"), new Battery(1.0), 0, "v1");
        d.addSensor(new Sensor("temp", Distribution.constant(1), 16, 10.0));
        Map<String, Double> r = w.run(SimTime.MIN);
        assertEquals(1.0, r.get("energy.device_J"), 1e-12);
        assertEquals(DeviceState.DEPLETED, d.state());
    }

    @Test public void rollingUpdateWithoutCapacityKeepsTheOldInstance() {
        IoTWorld w = new IoTWorld(1);
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("wifi"), 0);
        ComputeNode e = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 1, 2000, 8192, 20, 60, true);
        w.addLink("ge", g, e, 1e9, SimTime.ms(1), 0, 1 << 22, 1e-9, 0, Link.Fidelity.QUEUE);
        Application app = new Application("app", "temp", null, null, 0);
        AppModule m = app.addModule(new AppModule(app, "m", Distribution.constant(1), 64, new ContainerImage("m:1", 1), 1, 256,
                1, 4, 0, null, false, Distribution.constant(1), 0, 100));
        w.addApplication(app);
        int[] alive = {-1};
        w.at(SimTime.s(60), () -> w.runtime().rollingUpdate(m, new ContainerImage("m:2", 1), SimTime.s(1)));
        w.at(SimTime.s(119), () -> alive[0] = w.runtime().instancesOf(m).size());
        Map<String, Double> r = w.run(SimTime.s(120));
        assertEquals(1, alive[0]);
        assertEquals(1.0, r.get("update.replace_blocked"), 0);
    }

    @Test public void oneMovementGivesOneHandover() {
        IoTWorld w = new IoTWorld(1);
        w.addGateway("gw1", new Position(0, 0), Set.of("wifi"), 0);
        w.addGateway("gw2", new Position(150, 0), Set.of("wifi"), 0);
        w.attachment().setHandoverDelay(SimTime.s(3));
        IoTDevice d = w.addDevice("d", "g", new Position(0, 0), AccessTechnology.preset("wifi"), Protocol.preset("udp"), null, 0, "v1");
        d.add(new MobilityModel.Trace(List.of(new MobilityModel.Trace.Point(0, 0, 0), new MobilityModel.Trace.Point(SimTime.s(10), 0, 0),
                new MobilityModel.Trace.Point(SimTime.s(11), 150, 0))));
        Map<String, Double> r = w.run(SimTime.s(30));
        assertEquals(1.0, r.get("mobility.handovers"), 0);
        assertEquals("gw2", d.gateway().name());
    }

    @Test public void imagePullFailsWhenNoRegistryIsReachable() {
        IoTWorld w = base(1);
        ComputeNode cloud = (ComputeNode) w.sim().entity("cloud");
        cloud.fail("down");
        Application app = new Application("app", "temp", null, null, 0);
        app.addModule(new AppModule(app, "m", Distribution.constant(1), 64, new ContainerImage("m:1", 500), 1, 256,
                1, 1, 0, Tier.EDGE, false, Distribution.constant(1), 0, 100));
        w.addApplication(app);
        Map<String, Double> r = w.run(SimTime.s(60));
        assertTrue(r.get("instance.pull_failed") >= 1);
        assertNull(r.get("instance.image_pull_s.mean"));
    }

    @Test public void zeroCoreNodeDoesNotHideTierUtilization() {
        IoTWorld w = base(1);
        w.addComputeNode("registry", new Position(0, 0), Tier.CLOUD, 0, 2000, 0, 5, 5, true);
        assertNotNull(w.run(SimTime.s(60)).get("utilization.cloud"));
    }

    // ------------------------------------------------------------------ measured radio profile (LoRaWAN class A)
    @Test public void radioProfileChargesMeasuredStatesAndKeepsClassATiming() {
        IoTWorld w = new IoTWorld(1);
        w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
        AccessTechnology b = AccessTechnology.preset("lorawan-dr5");
        AccessTechnology t = new AccessTechnology(b.name(), b.bitrateBps(), 0, 0, 0, b.rangeM(), 0, b.frameHeader(), b.maxFrame(),
                1.0, b.loraSf(), b.loraBwHz(), RadioProfile.preset("mdot-classa"));
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of(t.name()), 0);
        ComputeNode n = w.addComputeNode("ns", new Position(0, 0), Tier.EDGE, 4, 1e6, 1 << 20, 0, 0, true);
        w.addLink("l", g, n, 1e9, 0, 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), t, Protocol.preset("none"), null, 0, "v1");
        long[] doneAt = {-1};
        w.at(SimTime.s(10), () -> d.radioUp(242, (ok, why) -> doneAt[0] = w.sim().now()));
        Map<String, Double> r = w.run(SimTime.s(20));
        // Casals et al. Table 5 / Eq. (1)-(3): charge = sum T_i * I_i over the active states, at 3.6 V
        double ttx = t.airtimeNs(242) / 1e6, trx1 = 12 * 128 / 125e3 * 1e3;
        double mAms = 168.2 * 22.1 + 83.8 * 13.3 + ttx * 83.0 + 983.3 * 27.0 + trx1 * 38.1 + (1000 - trx1) * 27.1 + 33.0 * 35.0
                + 147.4 * 13.2 + 268.0 * 21.0 + 38.6 * 13.3;
        assertEquals(mAms / 1e6 * 3.6, r.get("energy.device_J"), 1e-6);
        assertEquals(399.6, ttx, 0.05);   // Table 6, DR5, 242 B
        // delivered at the end of the transmission, which starts after wake-up and radio preparation
        assertEquals(SimTime.s(10) + SimTime.ms(168.2 + 83.8) + t.airtimeNs(242), doneAt[0], 1000);
    }

    @Test public void radioProfileSubtractsBaselineSoTotalsMatchMeasurements() {
        AccessTechnology t = AccessTechnology.preset("lorawan-dr0").withProfile(RadioProfile.preset("mdot-classa"));
        double base = 45e-6 * 3.6;
        double withBase = t.profile().overheadJ(t, base) + t.profile().overheadNs(t) / 1e9 * base;
        assertEquals(t.profile().overheadJ(t, 0), withBase, 1e-12);
    }

    @Test public void rebalancingWithinANodeMovesTheSessionToAnotherInstance() {
        IoTWorld w = new IoTWorld(3);
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("wifi"), 0);
        ComputeNode f = w.addComputeNode("fog", new Position(0, 0), Tier.FOG, 8, 2000, 8192, 20, 60, true);
        w.addLink("gf", g, f, 1e9, SimTime.ms(1), 0, 1 << 22, 0, 0, Link.Fidelity.QUEUE);
        Application app = new Application("app", "temp", null, "valve", SimTime.ms(200));
        AppModule m = app.addModule(new AppModule(app, "ctl", Distribution.constant(40), 64, new ContainerImage("ctl:1", 50), 1, 256,
                2, 4, 64 * 1024, null, true, Distribution.constant(1), 0, 100));
        w.addApplication(app);
        IoTDevice d = w.addDevice("d", "room", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("mqtt-qos1"), null, 0, "v1");
        d.addSensor(new Sensor("temp", Distribution.constant(1), 32, 0)).addActuator(new Actuator("valve", Distribution.constant(5), 0));
        w.run(SimTime.s(10));
        List<ServiceInstance> on = w.runtime().accepting(m);
        assertEquals(2, on.size());
        ServiceInstance a = on.get(0), b = on.get(1);
        w.runtime().setAffinity(d, m, a);
        w.runtime().migrateSession(d, m, f);
        assertSame(b, w.runtime().affinity(d, m));
        // and back again: the destination is always an instance other than the current one
        w.runtime().migrateSession(d, m, f);
        assertSame(a, w.runtime().affinity(d, m));
    }
}
