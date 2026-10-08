package org.iotloopsim.experiment;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.iotloopsim.model.IoTWorld;

public class ScenarioTest {

    static Path scenario(String name) {
        for (String base : new String[]{"scenarios", "../scenarios"}) {
            Path p = Path.of(base, name);
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("scenario not found: " + name);
    }

    @Test public void bundledScenariosBuild() throws Exception {
        for (String s : List.of("quickstart.yaml", "smart-city.yaml", "lorawan-ota.yaml", "lorawan-battery.yaml")) {
            Path p = scenario(s);
            ScenarioLoader l = new ScenarioLoader(ScenarioLoader.read(p), p.toAbsolutePath().getParent());
            IoTWorld w = l.build(l.seed());
            assertFalse(s, w.devices().isEmpty());
            assertFalse(s, w.applications().isEmpty());
            for (String n : l.notes()) assertFalse(s + ": " + n, n.startsWith("WARNING"));
            assertEquals(s + " has unknown keys", List.of(), l.unknownKeys());
        }
    }

    @Test public void replicationsAreReproducible() throws Exception {
        Experiment e = new Experiment(scenario("quickstart.yaml"));
        List<Experiment.RunResult> a = e.run(3, 100, 3, null);
        List<Experiment.RunResult> b = e.run(3, 100, 1, null);
        for (int i = 0; i < 3; i++) assertEquals("rep " + i, a.get(i).hash(), b.get(i).hash());
        assertNotEquals(a.get(0).hash(), a.get(1).hash());
        List<Experiment.Summary> s = Experiment.summarize(a);
        Experiment.Summary succ = s.stream().filter(x -> x.metric().equals("loop.success_ratio")).findFirst().orElseThrow();
        assertEquals(3, succ.n());
        assertTrue(succ.mean() > 0.99);
    }

    @Test public void missingCountersCountAsZero() {
        var a = new Experiment.RunResult(0, 1, java.util.Map.of("x.failed", 2.0, "lat.mean", 5.0), "h", 0, "", List.of());
        var b = new Experiment.RunResult(1, 2, java.util.Map.of(), "h", 0, "", List.of());
        var s = Experiment.summarize(List.of(a, b));
        var failed = s.stream().filter(x -> x.metric().equals("x.failed")).findFirst().orElseThrow();
        var lat = s.stream().filter(x -> x.metric().equals("lat.mean")).findFirst().orElseThrow();
        assertEquals(1.0, failed.mean(), 1e-12);
        assertEquals(2, failed.n());
        assertEquals(1, lat.n());
    }

    @Test public void writesProvenanceBundle() throws Exception {
        Experiment e = new Experiment(scenario("quickstart.yaml"));
        Path dir = Files.createTempDirectory("iotloopsim-test");
        e.write(dir, e.run(2, 1, 2, null), 1, java.time.Instant.now());
        for (String f : List.of("summary.csv", "runs.csv", "provenance.json", "lifecycle_rep0.csv", "scenario.resolved.json"))
            assertTrue(f, Files.size(dir.resolve(f)) > 0);
        assertTrue(Files.readString(dir.resolve("provenance.json")).contains("scenarioSha256"));
    }

    @Test public void unknownKeysAreReportedWithSuggestions() {
        java.util.Map<String, Object> sc = org.iotloopsim.util.Yaml.parseMap("""
            name: t
            duraton: 1min
            gateways: [{name: gw, techs: [wifi], position: [0, 0]}]
            devices: [{group: s, cuont: 3, tech: wifi, area: {x0: 0, y0: 0, x1: 10, y1: 10}, sensors: [{type: t, interval: "exp(5)"}]}]
            """);
        ScenarioLoader l = new ScenarioLoader(sc, Path.of("."));
        l.build(1);
        List<String> u = l.unknownKeys();
        assertTrue(u.toString(), u.contains("duraton (did you mean 'duration'?)"));
        assertTrue(u.toString(), u.contains("devices[0].cuont (did you mean 'count'?)"));
        assertEquals(2, u.size());
    }

    @Test public void inlineMapsMayContainMultiArgumentDistributions() {
        java.util.Map<String, Object> m = org.iotloopsim.util.Yaml.parseMap("m: {mi: normal(120,15), on: 1}\n");
        @SuppressWarnings("unchecked") java.util.Map<String, Object> in = (java.util.Map<String, Object>) m.get("m");
        assertEquals("normal(120,15)", in.get("mi"));
        assertTrue("flow keys stay strings", in.containsKey("on"));
    }

    @Test public void unitsAreParsedStrictly() {
        assertEquals(100e6, Units.bitrate("100Mbps"), 0);
        assertEquals(100e6, Units.bitrate("100 mbps"), 0);
        assertEquals(800e6, Units.bitrate("100MBps"), 0);   // bytes per second
        assertEquals(1024 * 1024, Units.bytes("1MiB"));
        assertEquals(1024 * 1024, Units.bytes("1MB"));
        assertThrows(IllegalArgumentException.class, () -> Units.bitrate("fast"));
        assertThrows(IllegalArgumentException.class, () -> Units.bytes("12 apples"));
    }

    @Test public void overridesRejectBadIndicesAndAreRecorded() throws Exception {
        Experiment e = new Experiment(scenario("quickstart.yaml"));
        assertThrows(IllegalArgumentException.class, () -> Overrides.apply(e.scenario(), List.of("devices.7.count=1")));
        e.applyOverrides(List.of("duration=1min"));
        Path dir = Files.createTempDirectory("iotloopsim-ov");
        e.write(dir, e.run(1, 1, 1, null), 1, java.time.Instant.now());
        assertTrue(Files.readString(dir.resolve("provenance.json")).contains("duration=1min"));
    }
}
