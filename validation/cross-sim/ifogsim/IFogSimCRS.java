import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.Config;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;
import org.fog.utils.distribution.Distribution;

/**
 * iFogSim2 implementation of the Common Reference System (CRS). Time unit: ms. Bandwidth: bytes per ms.
 * args: exp sensors payloadBytes responseBytes bandwidthBps gwEdgeLatencyMs sensorLatencyMs mi mips intervalMs(mean) poisson(0/1) horizonMs seed
 * The loop delay is printed by iFogSim's Controller ("APPLICATION LOOP DELAYS").
 */
public class IFogSimCRS {
    /** Seeded exponential inter-transmission times (iFogSim ships no exponential distribution). */
    static class Exponential extends Distribution {
        final double mean;
        Exponential(double mean, long seed) { this.mean = mean; setRandom(new Random(seed)); }
        public double getNextValue() { return -mean * Math.log(1.0 - getRandom().nextDouble()); }
        public int getDistributionType() { return 99; }
        public double getMeanInterTransmitTime() { return mean; }
    }

    public static void main(String[] a) throws Exception {
        int sensors = Integer.parseInt(a[1]);
        int payload = Integer.parseInt(a[2]), response = Integer.parseInt(a[3]);
        double bwBytesPerMs = Double.parseDouble(a[4]) / 8 / 1000;
        double gwEdgeLat = Double.parseDouble(a[5]), sensorLat = Double.parseDouble(a[6]);
        double mi = Double.parseDouble(a[7]);
        int mips = Integer.parseInt(a[8]);
        double interval = Double.parseDouble(a[9]);
        boolean poisson = a[10].equals("1");
        int horizon = Integer.parseInt(a[11]);
        long seed = Long.parseLong(a[12]);

        Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);
        Config.MAX_SIMULATION_TIME = horizon;
        FogBroker broker = new FogBroker("broker");
        String appId = "crs";
        Application app = Application.createApplication(appId, broker.getId());
        app.setUserId(broker.getId());
        app.addAppModule("Service", 10, mips, 10000);
        // iFogSim's clock is in ms while CloudSim divides cloudlet length by MIPS: entering MI x 1000 gives the intended ms
        app.addAppEdge("S", "Service", (long) Math.max(1, Math.round(mi * 1000)), payload, "S", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("Service", "ACT", 1, response, "R", Tuple.DOWN, AppEdge.ACTUATOR);
        app.addTupleMapping("Service", "S", "R", new FractionalSelectivity(1.0));
        List<AppLoop> loops = new ArrayList<>();
        List<String> loop = new ArrayList<>();
        loop.add("S"); loop.add("Service"); loop.add("ACT");
        loops.add(new AppLoop(loop));
        app.setLoops(loops);

        List<FogDevice> devices = new ArrayList<>();
        long bw = Math.max(1, Math.round(bwBytesPerMs));
        FogDevice edge = createFogDevice("cloud", mips, 1 << 20, bw, bw, 0, 0.0, 100, 50);   // root of the hierarchy
        edge.setParentId(-1);
        FogDevice gw = createFogDevice("gateway", 1_000_000_000L, 1 << 20, bw, bw, 1, 0.0, 10, 5);
        gw.setParentId(edge.getId());
        gw.setUplinkLatency(gwEdgeLat);
        devices.add(edge);
        devices.add(gw);
        List<Sensor> ss = new ArrayList<>();
        List<Actuator> as = new ArrayList<>();
        for (int i = 0; i < sensors; i++) {
            Distribution d = poisson ? new Exponential(interval, seed * 1000 + i) : new DeterministicDistribution(interval);
            Sensor s = new Sensor("s-" + i, "S", broker.getId(), appId, d);
            s.setGatewayDeviceId(gw.getId());
            s.setLatency(sensorLat);
            s.setApp(app);
            ss.add(s);
        }
        Actuator act = new Actuator("a-0", broker.getId(), appId, "ACT");
        act.setGatewayDeviceId(gw.getId());
        act.setLatency(sensorLat);
        act.setApp(app);
        as.add(act);

        ModuleMapping mm = ModuleMapping.createModuleMapping();
        mm.addModuleToDevice("Service", "cloud");
        Controller controller = new Controller("master-controller", devices, ss, as);
        controller.submitApplication(app, 0, new ModulePlacementMapping(devices, app, mm));
        TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
    }

    private static FogDevice createFogDevice(String nodeName, long mips, int ram, long upBw, long downBw, int level,
                                             double ratePerMips, double busyPower, double idlePower) throws Exception {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerOverbooking(mips)));
        int hostId = FogUtils.generateEntityId();
        PowerHost host = new PowerHost(hostId, new RamProvisionerSimple(ram), new BwProvisionerOverbooking(10000), 1000000, peList,
                new StreamOperatorScheduler(peList), new FogLinearPowerModel(busyPower, idlePower));
        List<Host> hostList = new ArrayList<>();
        hostList.add(host);
        FogDeviceCharacteristics ch = new FogDeviceCharacteristics("x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);
        FogDevice d = new FogDevice(nodeName, ch, new AppModuleAllocationPolicy(hostList), new LinkedList<Storage>(), 10, upBw, downBw, 0, ratePerMips);
        d.setLevel(level);
        return d;
    }
}
