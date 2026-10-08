import java.util.List;
import java.util.Map;
import java.util.Set;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.experiment.Experiment;
import org.iotloopsim.model.*;

/**
 * Building a scenario with the Java API instead of YAML.
 *   javac -cp dist/iotloopsim-all-0.1.0.jar examples/java/QuickStartApi.java -d build/examples
 *   java  -cp dist/iotloopsim-all-0.1.0.jar:build/examples QuickStartApi
 */
public class QuickStartApi {
    /** Builds a fresh world for one replication (units: positions in m, MIPS, RAM in MB, power in W, times in ns via SimTime). */
    static IoTWorld build(long seed) {
        IoTWorld w = new IoTWorld(seed);
        Gateway gw = w.addGateway("gw", new Position(0, 0), Set.of("wifi"), 0);
        ComputeNode edge = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, 4, 2000, 8192, 20, 60, false);
        ComputeNode cloud = w.addComputeNode("cloud", new Position(0, 0), Tier.CLOUD, 64, 4000, 262144, 200, 500, true);
        w.addLink("gw-edge", gw, edge, 1e9, SimTime.ms(1), 0, 4 << 20, 1e-9, 0, Link.Fidelity.QUEUE);
        w.addLink("edge-cloud", edge, cloud, 100e6, SimTime.ms(20), 0, 4 << 20, 5e-8, 0.09, Link.Fidelity.QUEUE);

        Application app = new Application("hvac", "temp", null, "valve", SimTime.ms(200));
        app.addModule(new AppModule(app, "control", Distribution.constant(50), 64, new ContainerImage("ctl:1", 80),
                1, 256, 1, 4, 0, null, true, Distribution.constant(1), 0, 100));
        w.addApplication(app);

        for (int i = 0; i < 10; i++) {
            IoTDevice d = w.addDevice("room-" + i, "room", new Position(5 * i, 5), AccessTechnology.preset("wifi"),
                    Protocol.preset("mqtt-qos1"), Battery.fromMah(2000, 3.7), 0.01, "v1");
            d.addSensor(new Sensor("temp", Distribution.exponential(5), 32, 0.001));
            d.addActuator(new Actuator("valve", Distribution.constant(20), 0.05));
        }
        w.setDeviceStart(SimTime.s(10));
        return w;
    }

    public static void main(String[] args) {
        // one run
        Map<String, Double> r = build(1).run(SimTime.MIN * 10);
        System.out.printf("one run: loops %.0f, success %.4f, latency p50 %.1f ms, energy %.1f J%n",
                r.get("loop.completed"), r.get("loop.success_ratio"), r.get("loop.latency_ms.p50"), r.get("energy.total_J"));
        // 10 replications with 95% confidence intervals, as the CLI does for YAML scenarios
        List<Experiment.Summary> s = Experiment.summarize(Experiment.replicate(10, 1, QuickStartApi::build, SimTime.MIN * 10));
        for (Experiment.Summary x : s)
            if (x.metric().equals("loop.latency_ms.mean") || x.metric().equals("loop.success_ratio"))
                System.out.printf("%-22s %.4f +/- %.4f (n=%d)%n", x.metric(), x.mean(), x.ci95(), x.n());
    }
}
