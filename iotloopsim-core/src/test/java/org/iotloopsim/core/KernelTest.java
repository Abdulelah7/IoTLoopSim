package org.iotloopsim.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.iotloopsim.util.Json;
import org.iotloopsim.util.Stats;
import org.iotloopsim.util.Yaml;

public class KernelTest {

    @Test public void eventsRunInTimePriorityAndInsertionOrder() {
        Simulation sim = new Simulation(1);
        List<String> log = new ArrayList<>();
        sim.schedule(10, Simulation.PRIO_NORMAL, "b", () -> log.add("b"));
        sim.schedule(10, Simulation.PRIO_HIGH, "a", () -> log.add("a"));
        sim.schedule(10, Simulation.PRIO_NORMAL, "c", () -> log.add("c"));
        sim.schedule(5, "first", () -> log.add("first"));
        sim.run();
        assertEquals(List.of("first", "a", "b", "c"), log);
        assertEquals(10, sim.now());
    }

    @Test public void cancelledEventsDoNotRun() {
        Simulation sim = new Simulation(1);
        int[] n = {0};
        EventHandle h = sim.schedule(5, () -> n[0]++);
        h.cancel();
        sim.run();
        assertEquals(0, n[0]);
    }

    @Test public void periodicEventsStopAtEndTime() {
        Simulation sim = new Simulation(1);
        int[] n = {0};
        sim.every(SimTime.S, SimTime.S, "tick", () -> n[0]++);
        sim.setEndTime(SimTime.s(10));
        sim.run();
        assertEquals(10, n[0]);
        assertEquals(SimTime.s(10), sim.now());
    }

    @Test public void runUntilPausedStopsAtCondition() {
        Simulation sim = new Simulation(1);
        int[] n = {0};
        sim.every(1, 1, "tick", () -> n[0]++);
        sim.setEndTime(100);
        assertTrue(sim.runUntilPaused(() -> n[0] == 7));
        assertEquals(7, n[0]);
        assertTrue(sim.runUntilPaused(() -> n[0] == 20));
        assertEquals(20, n[0]);
    }

    @Test public void rngStreamsDependOnNameNotCreationOrder() {
        RngStreams a = new RngStreams(42), b = new RngStreams(42);
        a.stream("x");
        double ay = a.stream("y").nextDouble();
        double by = b.stream("y").nextDouble();
        assertEquals(ay, by, 0.0);
        assertNotEquals(new RngStreams(43).stream("y").nextDouble(), by, 0.0);
    }

    @Test public void distributionsParse() {
        java.util.SplittableRandom r = new java.util.SplittableRandom(1);
        assertEquals(5.0, Distribution.parse("const(5)").sample(r), 0);
        assertEquals(3.0, Distribution.parse(3).sample(r), 0);
        double s = 0;
        Distribution e = Distribution.parse("exp(10)");
        for (int i = 0; i < 20000; i++) s += e.sample(r);
        assertEquals(10.0, s / 20000, 0.3);
        double u = Distribution.parse("uniform(2, 4)").sample(r);
        assertTrue(u >= 2 && u <= 4);
    }

    @Test public void timeParsing() {
        assertEquals(SimTime.MS * 500, SimTime.parse("500ms"));
        assertEquals(SimTime.MIN * 10, SimTime.parse("10min"));
        assertEquals(SimTime.H, SimTime.parse("1h"));
        assertEquals(SimTime.s(2.5), SimTime.parse("2.5s"));
        assertEquals(SimTime.s(7), SimTime.parse("7"));
    }

    @Test @SuppressWarnings("unchecked")
    public void yamlSubsetParses() {
        String y = """
            name: demo   # comment
            count: 3
            ratio: 0.5
            flag: true
            list: [a, 2, "c d"]
            map: {x: 1, y: two}
            items:
              - name: first
                value: 1
                inner:
                  - 1
                  - 2
              - name: second
            """;
        Map<String, Object> m = Yaml.parseMap(y);
        assertEquals("demo", m.get("name"));
        assertEquals(3L, m.get("count"));
        assertEquals(0.5, m.get("ratio"));
        assertEquals(Boolean.TRUE, m.get("flag"));
        assertEquals(List.of("a", 2L, "c d"), m.get("list"));
        assertEquals("two", ((Map<String, Object>) m.get("map")).get("y"));
        List<Object> items = (List<Object>) m.get("items");
        assertEquals(2, items.size());
        assertEquals(List.of(1L, 2L), ((Map<String, Object>) items.get(0)).get("inner"));
        assertEquals("second", ((Map<String, Object>) items.get(1)).get("name"));
    }

    @Test public void jsonRoundTrip() {
        Object o = Json.parse("{\"a\":[1,2.5,\"x\"],\"b\":{\"c\":null,\"d\":true}}");
        assertEquals(o, Json.parse(Json.write(o)));
    }

    @Test public void confidenceInterval() {
        List<Double> v = List.of(10.0, 12.0, 11.0, 13.0, 9.0);
        assertEquals(11.0, Stats.mean(v), 1e-9);
        assertEquals(2.776 * Stats.sd(v) / Math.sqrt(5), Stats.ci95(v), 1e-9);
    }
}
