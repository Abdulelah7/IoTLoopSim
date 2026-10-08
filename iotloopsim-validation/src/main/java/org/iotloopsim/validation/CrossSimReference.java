package org.iotloopsim.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.*;
import org.iotloopsim.util.Json;

/**
 * IoTLoopSim implementation of the Common Reference System (CRS) shared with YAFS and iFogSim2
 * (validation/cross-sim/crs_params.json). Prints one JSON line per run with the mean control-loop latency.
 *
 * usage: java -cp iotloopsim-all.jar org.iotloopsim.validation.CrossSimReference crs_params.json X2 5
 */
public final class CrossSimReference {
    static final Protocol RAW = new Protocol("raw", 0, false, 0, 0, 0);   // no header bytes, like YAFS/iFogSim tuples

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, Object> p = (Map<String, Object>) Json.parse(Files.readString(Path.of(args[0])));
        String exp = args[1];
        int reps = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        Map<String, Object> c = (Map<String, Object>) ((Map<String, Object>) p.get("experiments")).get(exp);
        List<Object> levels = exp.equals("X1") ? java.util.Collections.singletonList(null)
                : (List<Object>) (c.containsKey("rho") ? c.get("rho") : c.get("rho_link"));
        for (Object level : levels) {
            int n = level == null ? 1 : reps;
            for (int r = 0; r < n; r++) {
                IoTWorld w = build(p, c, exp, level == null ? 0 : ((Number) level).doubleValue(), 1000 + r);
                Map<String, Double> m = w.run(SimTime.s(num(c, "horizon_s")) + SimTime.s(5));
                System.out.println(Json.writeCompact(new java.util.LinkedHashMap<>(Map.of("sim", "IoTLoopSim", "exp", exp,
                        "rho", level == null ? "null" : level, "rep", r, "loop_ms", m.get("loop.latency_ms.mean"),
                        "n", m.get("loop.completed")))));
            }
        }
    }

    static double num(Map<String, Object> m, String k) { return ((Number) m.get(k)).doubleValue(); }

    static IoTWorld build(Map<String, Object> p, Map<String, Object> c, String exp, double rho, long seed) {
        IoTWorld w = new IoTWorld(seed);
        w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
        w.setInternalProtocol(RAW);
        // sensor/actuator access: fixed latency, negligible airtime, no loss, no headers
        AccessTechnology acc = new AccessTechnology("crs-access", 1e15, SimTime.ms(num(p, "sensor_link_latency_ms")), 0, 0, 1000, 0, 0, 1 << 30, 1.0);
        Gateway g = w.addGateway("gateway", new Position(0, 0), Set.of("crs-access"), 0, 0);
        double mips = num(c, "mips");
        ComputeNode e = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 1, mips, 1 << 20, 0, 0, true);
        double lat = c.containsKey("gw_edge_latency_ms") ? num(c, "gw_edge_latency_ms") : num(p, "gw_edge_latency_ms");
        w.addLink("gw-edge", g, e, num(c, "bandwidth_bps"), SimTime.ms(lat), 0, 0, 0, 0, Link.Fidelity.QUEUE);
        Application app = new Application("crs", "s", null, "act", 0);
        app.addModule(new AppModule(app, "Service", Distribution.constant(num(c, "mi")), (int) num(p, "response_bytes"),
                new ContainerImage("svc", 1), 1, 64, 1, 1, 0, null, true, Distribution.constant(0.1), 0, 0));
        w.addApplication(app);
        int sensors = (int) num(c, "sensors");
        Distribution interval;
        if (exp.equals("X1")) interval = Distribution.constant(num(c, "interval_ms") / 1000.0);
        else if (exp.equals("X2")) {
            double lamTotal = rho / (num(c, "mi") / mips);                    // per second
            interval = Distribution.exponential(sensors / lamTotal);
        } else {
            double tx = num(c, "payload_bytes") * 8 / num(c, "bandwidth_bps");
            interval = Distribution.exponential(sensors / (rho / tx));
        }
        for (int i = 0; i < sensors; i++) {
            IoTDevice d = w.addDevice("s" + i, "g", new Position(1, 1), acc, RAW, null, 0, "v1");
            d.addSensor(new Sensor("s", interval, (int) num(c, "payload_bytes"), 0));
            d.addActuator(new Actuator("act", Distribution.constant(0), 0));
        }
        w.setDeviceStart(SimTime.s(5));
        return w;
    }
}
