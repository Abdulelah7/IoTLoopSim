package org.iotloopsim.model;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.policy.BuiltinPolicies;
import org.iotloopsim.model.policy.PolicyRegistry;
import org.iotloopsim.util.Cfg;

public class WorldTest {

    /** Small world: 2 gateways, 2 edge nodes, 1 cloud, one actuating application. */
    static IoTWorld world(long seed, int devices, Battery battery) {
        IoTWorld w = new IoTWorld(seed);
        Gateway g1 = w.addGateway("gw1", new Position(0, 0), Set.of("wifi"), 0);
        Gateway g2 = w.addGateway("gw2", new Position(150, 0), Set.of("wifi"), 0);
        ComputeNode e1 = w.addComputeNode("edge1", new Position(0, 0), Tier.EDGE, 4, 2000, 8192, 20, 60, false);
        ComputeNode e2 = w.addComputeNode("edge2", new Position(150, 0), Tier.EDGE, 4, 2000, 8192, 20, 60, false);
        ComputeNode c = w.addComputeNode("cloud", new Position(0, 0), Tier.CLOUD, 32, 4000, 65536, 100, 300, true);
        e1.tag("zone", "A");
        g1.tag("zone", "A");
        w.addLink("g1e1", g1, e1, 1e9, SimTime.ms(1), 0, 1 << 22, 1e-9, 0, Link.Fidelity.QUEUE);
        w.addLink("g2e2", g2, e2, 1e9, SimTime.ms(1), 0, 1 << 22, 1e-9, 0, Link.Fidelity.QUEUE);
        w.addLink("e1c", e1, c, 1e9, SimTime.ms(20), 0, 1 << 22, 1e-8, 0.09, Link.Fidelity.QUEUE);
        w.addLink("e2c", e2, c, 1e9, SimTime.ms(20), 0, 1 << 22, 1e-8, 0.09, Link.Fidelity.QUEUE);
        w.addLink("e1e2", e1, e2, 1e9, SimTime.ms(2), 0, 1 << 22, 1e-9, 0, Link.Fidelity.QUEUE);
        Application app = new Application("app", "temp", null, "valve", SimTime.ms(200));
        AppModule ctl = app.addModule(new AppModule(app, "ctl", Distribution.constant(40), 64, new ContainerImage("ctl:1", 50), 1, 256,
                1, 8, 64 * 1024, null, true, Distribution.constant(1), 0, 100));
        w.addApplication(app);
        for (int i = 0; i < devices; i++) {
            IoTDevice d = w.addDevice("d" + i, "room", new Position(i * 140.0 / Math.max(1, devices - 1), 5), AccessTechnology.preset("wifi"),
                    Protocol.preset("mqtt-qos1"), battery == null ? null : new Battery(battery.capacityJ()), 0.01, "v1");
            d.addSensor(new Sensor("temp", Distribution.exponential(2), 32, 0.001)).addActuator(new Actuator("valve", Distribution.constant(10), 0.01));
        }
        w.setDeviceStart(SimTime.s(5));
        return w;
    }

    @Test public void controlLoopsComplete() {
        IoTWorld w = world(1, 10, null);
        Map<String, Double> r = w.run(SimTime.MIN * 5);
        assertTrue(r.get("loop.completed") > 1000);
        assertTrue(r.get("loop.success_ratio") > 0.99);
        assertTrue(r.get("loop.latency_ms.p50") > 20 && r.get("loop.latency_ms.p50") < 100);
        assertTrue(r.get("energy.edge_J") > 0 && r.get("energy.device_J") > 0);
        assertEquals(2.0, r.get("instance.cold_starts"), 0); // one per edge node
    }

    @Test public void sameSeedSameResultsDifferentSeedDifferentResults() {
        Map<String, Double> a = world(7, 8, null).run(SimTime.MIN * 3);
        Map<String, Double> b = world(7, 8, null).run(SimTime.MIN * 3);
        Map<String, Double> c = world(8, 8, null).run(SimTime.MIN * 3);
        assertEquals(a, b);
        assertNotEquals(a.get("loop.latency_ms.mean"), c.get("loop.latency_ms.mean"));
    }

    @Test public void batteryDepletionFailsDevice() {
        IoTWorld w = world(1, 2, new Battery(5.0));
        Map<String, Double> r = w.run(SimTime.MIN * 30);
        assertEquals(2.0, r.get("devices.depleted"), 0);
        assertEquals(0.0, r.get("battery.min_remaining"), 1e-9);
    }

    @Test public void baselinePowerAloneDepletesBatteryAtExactTime() {
        IoTWorld w = new IoTWorld(1);
        w.addGateway("gw", new Position(0, 0), Set.of("wifi"), 0);
        w.addDevice("idle", "g", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"), new Battery(100), 0.01, "v1");
        w.run(SimTime.s(20000));
        LifecycleLog.Entry dead = w.lifecycle().entries().stream().filter(e -> e.to().equals("DEPLETED")).findFirst().orElseThrow();
        assertEquals(10000.0, dead.time() / 1e9, 1e-6);
    }

    @Test public void loraTimeOnAirFollowsSemtechFormula() {
        // reference values of the Semtech SX127x formula (CR 4/5, preamble 8, explicit header, CRC on)
        assertEquals(118.016, AccessTechnology.preset("lorawan").airtimeNs(51) / 1e6, 0.01);       // SF7, 64-byte PHY payload
        assertEquals(2793.472, AccessTechnology.preset("lorawan-sf12").airtimeNs(51) / 1e6, 0.01); // SF12, low data rate optimisation
    }

    @Test public void linksAreFullDuplex() {
        IoTWorld w = new IoTWorld(1);
        ComputeNode a = w.addComputeNode("a", new Position(0, 0), Tier.FOG, 1, 1000, 1024, 0, 0, false);
        ComputeNode b = w.addComputeNode("b", new Position(0, 0), Tier.FOG, 1, 1000, 1024, 0, 0, false);
        w.addLink("ab", a, b, 8e6, 0, 0, 0, 0, 0, Link.Fidelity.QUEUE);
        long[] t = new long[2];
        w.network().transfer(new Message(Message.Kind.INTERNAL, 1_000_000, 0, null), a, b, (d, r) -> t[0] = w.sim().now());
        w.network().transfer(new Message(Message.Kind.INTERNAL, 1_000_000, 0, null), b, a, (d, r) -> t[1] = w.sim().now());
        w.sim().run();
        assertEquals(SimTime.S, t[0]);
        assertEquals(SimTime.S, t[1]);   // opposite directions do not queue behind each other
    }

    @Test public void mobilityDoesNotActivateDevicesBeforeStart() {
        IoTWorld w = new IoTWorld(1);
        w.addGateway("g", new Position(0, 0), Set.of("wifi"), 0);
        IoTDevice d = w.addDevice("d", "g", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("udp"), null, 0, "v1");
        d.add(new MobilityModel.Trace(List.of(new MobilityModel.Trace.Point(0, 1, 1))));
        w.setDeviceStart(SimTime.s(10));
        w.run(SimTime.s(5));
        assertEquals(DeviceState.PROVISIONED, d.state());
    }

    @Test public void sensorsStartAtRandomPhases() {
        IoTWorld w = world(3, 50, null);
        java.util.Set<Long> firstTimes = new java.util.HashSet<>();
        w.sim().setTracer((time, label) -> { if ("sense".equals(label) && time < SimTime.s(10)) firstTimes.add(time); });
        w.run(SimTime.s(10));
        assertTrue("first readings must not coincide", firstTimes.size() > 40);
    }

    @Test public void queueOverflowDropsPackets() {
        IoTWorld w = new IoTWorld(1);
        ComputeNode a = w.addComputeNode("a", new Position(0, 0), Tier.FOG, 1, 1000, 1024, 0, 0, false);
        ComputeNode b = w.addComputeNode("b", new Position(0, 0), Tier.FOG, 1, 1000, 1024, 0, 0, false);
        w.addLink("ab", a, b, 1e6, SimTime.ms(1), 0, 3000, 0, 0, Link.Fidelity.QUEUE);
        int[] ok = {0}, drop = {0};
        for (int i = 0; i < 10; i++)
            w.network().transfer(new Message(Message.Kind.INTERNAL, 1000, 0, null), a, b, (d, reason) -> { if (d) ok[0]++; else drop[0]++; });
        w.sim().run();
        assertEquals(3, ok[0]);
        assertEquals(7, drop[0]);
    }

    @Test public void analyticalLinkSharesBandwidth() {
        IoTWorld w = new IoTWorld(1);
        ComputeNode a = w.addComputeNode("a", new Position(0, 0), Tier.FOG, 1, 1000, 1024, 0, 0, false);
        ComputeNode b = w.addComputeNode("b", new Position(0, 0), Tier.FOG, 1, 1000, 1024, 0, 0, false);
        w.addLink("ab", a, b, 8e6, 0, 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
        long[] t = new long[2];
        w.network().transfer(new Message(Message.Kind.INTERNAL, 1_000_000, 0, null), a, b, (d, r) -> t[0] = w.sim().now());
        w.network().transfer(new Message(Message.Kind.INTERNAL, 1_000_000, 0, null), a, b, (d, r) -> t[1] = w.sim().now());
        w.sim().run();
        assertEquals(SimTime.S, t[0]);       // 1 MB alone at 8 Mbit/s = 1 s
        assertEquals(2 * SimTime.S, t[1]);   // second flow sees half the bandwidth
    }

    @Test public void correlatedFaultTakesDownZoneAndRecovers() {
        IoTWorld w = world(1, 6, null);
        w.faults().correlated("zoneA", e -> "A".equals(e.tag("zone")), List.of(SimTime.MIN), null, 1.0, Distribution.constant(60));
        Map<String, Double> r = w.run(SimTime.MIN * 4);
        assertEquals(1.0, r.get("faults.correlated_events"), 0);
        assertEquals(2.0, r.get("faults.correlated_targets"), 0);   // gw1 and edge1
        assertTrue(r.get("availability.nodes") < 1.0 && r.get("availability.nodes") > 0.8);
        assertTrue(r.get("loop.success_ratio") > 0.9);              // traffic fails over to gw2/edge2
    }

    @Test public void otaCampaignWithFailuresAndRollback() {
        IoTWorld w = world(3, 20, null);
        w.updates().campaign("fw", e -> e instanceof IoTDevice, "v2", 64, SimTime.MIN,
                PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "batch", Cfg.of(Map.of("batchSize", 5, "interval", "10s"))),
                Distribution.constant(5), 0.3, true, SimTime.s(30));
        Map<String, Double> r = w.run(SimTime.MIN * 10);
        double ok = r.getOrDefault("update.succeeded", 0.0), failed = r.getOrDefault("update.failed", 0.0);
        assertEquals(20.0, ok + failed, 0);
        assertTrue(failed > 0);
        assertEquals(failed, r.getOrDefault("update.rolled_back", 0.0), 0);
        assertEquals(0.0, r.get("devices.failed"), 0);
        assertEquals(1.0, r.get("update.campaigns_completed"), 0);
    }

    @Test public void canaryAbortsBadCampaign() {
        IoTWorld w = world(3, 40, null);
        w.updates().campaign("bad", e -> e instanceof IoTDevice, "v2", 64, SimTime.MIN,
                PolicyRegistry.create(PolicyRegistry.Kind.ROLLOUT, "canary", Cfg.of(Map.of("canaryFraction", 0.1, "abortAbove", 0.2))),
                Distribution.constant(5), 1.0, true, SimTime.s(30));
        Map<String, Double> r = w.run(SimTime.MIN * 20);
        assertEquals(1.0, r.get("update.campaigns_aborted"), 0);
        assertEquals(4.0, r.get("update.failed"), 0);   // only the canary batch was attempted
    }

    @Test public void followMeMigratesStatefulSessionOnHandover() {
        IoTWorld w = world(5, 0, null);
        w.setMigration(new BuiltinPolicies.FollowMe());
        IoTDevice car = w.addDevice("car", "room", new Position(0, 5), AccessTechnology.preset("wifi"), Protocol.preset("mqtt-qos1"), null, 0.01, "v1");
        car.addSensor(new Sensor("temp", Distribution.constant(0.5), 32, 0)).addActuator(new Actuator("valve", Distribution.constant(5), 0));
        car.add(new MobilityModel.Trace(List.of(new MobilityModel.Trace.Point(0, 0, 5), new MobilityModel.Trace.Point(SimTime.s(60), 0, 5),
                new MobilityModel.Trace.Point(SimTime.s(90), 150, 5))));
        Map<String, Double> r = w.run(SimTime.MIN * 3);
        assertTrue(r.get("mobility.handovers") >= 1);
        assertTrue(r.get("migration.completed") >= 1);
        assertTrue(r.get("migration.downtime_ms.mean") > 0);
        assertTrue(r.get("loop.success_ratio") > 0.95);
    }

    @Test public void coldMigrationIsCheckpointPlusTransferPlusRestore() {
        IoTWorld w = new IoTWorld(4);
        w.setPlacement(new BuiltinPolicies.TierFirst(new Tier[]{Tier.EDGE}, false));
        w.setMigration(new BuiltinPolicies.FollowMe("cold", Distribution.constant(2)));
        w.setInternalProtocol(new Protocol("raw", 0, true, 0, 3, SimTime.s(5)));
        Gateway g1 = w.addGateway("g1", new Position(0, 0), Set.of("wifi"), 0);
        Gateway g2 = w.addGateway("g2", new Position(150, 0), Set.of("wifi"), 0);
        ComputeNode c1 = w.addComputeNode("c1", new Position(0, 0), Tier.EDGE, 4, 1000, 4096, 0, 0, true);
        ComputeNode c2 = w.addComputeNode("c2", new Position(150, 0), Tier.EDGE, 4, 1000, 4096, 0, 0, true);
        w.addLink("g1c1", g1, c1, 1e9, 0, 0, 0, 0, 0, Link.Fidelity.QUEUE);
        w.addLink("g2c2", g2, c2, 1e9, 0, 0, 0, 0, 0, Link.Fidelity.QUEUE);
        w.addLink("c1c2", c1, c2, 8e6, SimTime.ms(10), 0, 0, 0, 0, Link.Fidelity.QUEUE);     // 1 MB/s
        Application app = new Application("a", "s", null, null, 0);
        app.addModule(new AppModule(app, "m", Distribution.constant(1), 16, new ContainerImage("i", 1), 1, 64, 1, 2,
                10_000_000L, null, false, Distribution.constant(3), 0, 0));                      // 10 MB state, 3 s restore
        w.addApplication(app);
        IoTDevice d = w.addDevice("d", "g", new Position(0, 0), AccessTechnology.preset("wifi"), Protocol.preset("udp"), null, 0, "v1");
        d.addSensor(new Sensor("s", Distribution.constant(1), 16, 0));
        d.add(new MobilityModel.Trace(List.of(new MobilityModel.Trace.Point(0, 0, 0), new MobilityModel.Trace.Point(SimTime.s(10), 0, 0),
                new MobilityModel.Trace.Point(SimTime.s(11), 150, 0))));
        w.setDeviceStart(SimTime.s(5));
        w.run(SimTime.s(60));
        var s = w.sim().metrics().sample("migration.downtime_ms");
        assertEquals(1, s.count());
        assertEquals(2000 + 10_000 + 10 + 3000, s.mean(), 1.0);   // checkpoint + 10 MB at 1 MB/s + latency + restore
    }

    @Test public void floodAttackIsDetectedAndBlockedByIds() {
        IoTWorld w = world(9, 10, null);
        w.security().enableIds(SimTime.us(200), 1e-4, 1.0, Distribution.constant(20));
        AppModule ctl = w.application("app").module("ctl");
        w.security().flood("dos", 3, w.gateways().get(0), "wifi", ctl, 50, 1000, 200, SimTime.MIN, SimTime.MIN * 4);
        Map<String, Double> r = w.run(SimTime.MIN * 5);
        assertTrue(r.get("security.attack_messages") > 100);
        assertEquals(3.0, r.get("security.attackers_blocked"), 0);
        assertTrue(r.get("security.blocked_packets") > 0);
        assertEquals(20.0, r.get("security.detection_s.mean"), 0.01);
    }

    @Test public void serverlessScalesToZeroAndColdStartsOnDemand() {
        IoTWorld w = new IoTWorld(2);
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("wifi"), 0);
        ComputeNode f = w.addComputeNode("fog", new Position(0, 0), Tier.FOG, 8, 2000, 8192, 20, 60, true);
        w.addLink("gf", g, f, 1e9, SimTime.ms(1), 0, 1 << 22, 0, 0, Link.Fidelity.QUEUE);
        Application app = new Application("fn", "evt", null, null, 0);
        app.addModule(new AppModule(app, "handler", Distribution.constant(10), 32, new ContainerImage("fn:1", 20), 1, 128, 0, 4, 0,
                null, false, Distribution.constant(0.5), SimTime.s(10), 100));
        w.addApplication(app);
        IoTDevice d = w.addDevice("d", "x", new Position(1, 1), AccessTechnology.preset("wifi"), Protocol.preset("coap-con"), null, 0, "v1");
        d.addSensor(new Sensor("evt", Distribution.constant(60), 16, 0));
        Map<String, Double> r = w.run(SimTime.MIN * 5);
        assertTrue(r.get("instance.scaled_to_zero") >= 3);
        assertTrue(r.get("instance.on_demand") >= 3);
        // every reading is served; the one generated exactly at the end time cannot complete
        assertEquals(r.get("loop.generated"), r.get("app.fn.sink_completed"), 1.0);
    }
}
