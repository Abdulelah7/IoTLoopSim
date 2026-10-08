package org.iotloopsim.validation;

import java.util.Set;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.*;

/** Minimal worlds whose behaviour has a closed-form answer. */
final class Worlds {
    private Worlds() {}

    /** An ideal radio: no loss, fixed latency, very high bitrate (constant, tiny airtime). */
    static AccessTechnology idealRadio(double loss) {
        return new AccessTechnology("ideal", 1e9, SimTime.ms(1), 0, 0, 1000, loss, 0, 100_000, 1.0);
    }

    /**
     * Poisson sensor traffic (n devices, aggregate rate lambda/s) into one module on one edge node.
     * Service demand ~ {@code mi} (million instructions) on a node with {@code mips} per core and {@code cores} cores.
     */
    static IoTWorld queue(long seed, double lambda, Distribution mi, double mips, int cores, int devices, double idleW, double maxW) {
        IoTWorld w = new IoTWorld(seed);
        w.setRadioFidelity(Link.Fidelity.ANALYTICAL);
        Gateway g = w.addGateway("gw", new Position(0, 0), Set.of("ideal"), 0);
        ComputeNode n = w.addComputeNode("edge", new Position(0, 0), Tier.EDGE, cores, mips, 1 << 20, idleW, maxW, true);
        w.addLink("gw-edge", g, n, 1e12, SimTime.ms(1), 0, 0, 0, 0, Link.Fidelity.ANALYTICAL);
        Application app = new Application("q", "s", null, null, 0);
        app.addModule(new AppModule(app, "svc", mi, 16, new ContainerImage("svc", 1), cores, 64, 1, 1, 0, null, false,
                Distribution.constant(0.1), 0, 0));
        w.addApplication(app);
        double perDeviceMean = devices / lambda;
        for (int i = 0; i < devices; i++) {
            IoTDevice d = w.addDevice("d" + i, "g", new Position(1, 1), idealRadio(0), Protocol.preset("udp"), null, 0, "v1");
            d.addSensor(new Sensor("s", Distribution.exponential(perDeviceMean), 16, 0));
        }
        w.setDeviceStart(SimTime.s(5));
        return w;
    }
}
