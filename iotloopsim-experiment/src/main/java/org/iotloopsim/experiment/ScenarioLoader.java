package org.iotloopsim.experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.Entity;
import org.iotloopsim.core.SimTime;
import org.iotloopsim.model.*;
import org.iotloopsim.model.policy.PolicyRegistry;
import org.iotloopsim.model.policy.PolicyRegistry.Kind;
import org.iotloopsim.model.policy.RolloutPolicy;
import org.iotloopsim.util.Cfg;
import org.iotloopsim.util.Yaml;

/**
 * Builds an {@link IoTWorld} from a scenario (YAML or JSON).
 * One canonical model: the same structure can be produced by the Java API, a GUI, or an importer.
 */
public final class ScenarioLoader {
    private final Cfg root;
    private final Path baseDir;
    private final List<String> notes = new ArrayList<>();

    /**
     * Creates a loader over a parsed scenario; {@code baseDir} resolves relative file references (e.g. mobility
     * traces).
     */
    public ScenarioLoader(Map<String, Object> scenario, Path baseDir) {
        this.root = Cfg.of(scenario);
        this.baseDir = baseDir;
    }

    /**
     * Reads a YAML or JSON scenario file into a map; throws {@link IOException} on read errors and {@link
     * IllegalArgumentException} on syntax errors or a non-mapping root.
     */
    public static Map<String, Object> read(Path file) throws IOException {
        return Yaml.parseMap(Files.readString(file));
    }

    /** Returns the scenario {@code name} (default {@code "scenario"}). */
    public String name() { return root.str("name", "scenario"); }
    /** Returns the scenario {@code seed} (default 1). */
    public long seed() { return root.lng("seed", 1); }
    /** Returns the simulated duration in ns from the scenario {@code duration} key (default 10 min). */
    public long duration() { return root.duration("duration", "10min"); }
    /** Returns the live list of notes produced by the last {@link #build(long)} (e.g. fidelity fallbacks). */
    public List<String> notes() { return notes; }

    /** Scenario keys that IoTLoopSim never read (typos), with "did you mean" hints; valid after {@link #build(long)}. */
    public List<String> unknownKeys() {
        name(); seed(); duration();
        return root.unknownKeys();
    }

    /** Creates a fresh world for one replication. */
    public IoTWorld build(long seed) {
        notes.clear();
        IoTWorld w = new IoTWorld(seed);
        Cfg fid = root.sub("fidelity");
        Link.Fidelity linkFid = Link.Fidelity.parse(fid.str("links", "queue"));
        w.setRadioFidelity(Link.Fidelity.parse(fid.str("radio", "queue")));
        if (linkFid == Link.Fidelity.PACKET) notes.add("links requested at PACKET fidelity: no packet-level adapter registered, using QUEUE (L1) fallback");
        w.setDeviceStart(root.duration("deviceStart", "0s"));
        if (root.has("internalProtocol")) w.setInternalProtocol(Protocol.preset(root.str("internalProtocol")));
        Cfg mob = root.sub("mobilitySettings");
        w.attachment().setHandoverDelay(mob.duration("handoverDelay", "50ms"));
        w.attachment().setHysteresis(mob.num("hysteresis", 0.15));
        w.mobility().setInterval(mob.duration("updateInterval", "1s"));

        security(w, root.sub("security"));
        carbonAndCost(w);
        policies(w, root.sub("policies"));
        gateways(w);
        nodes(w);
        links(w, linkFid);
        devices(w);
        applications(w);
        lifecycle(w, root.sub("lifecycle"));
        faults(w, root.sub("faults"));
        attacks(w);
        sanityChecks(w);
        return w;
    }

    private void sanityChecks(IoTWorld w) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (IoTDevice d : w.devices()) {
            if (!seen.add(d.group() + "|" + d.tech().name())) continue;
            boolean any = w.gateways().stream().anyMatch(g -> g.supports(d.tech()));
            if (!any) notes.add("WARNING: no gateway supports '" + d.tech().name() + "' used by device group '" + d.group() + "'; these devices will never connect");
        }
        for (Application a : w.applications()) {
            boolean trig = w.devices().stream().anyMatch(d -> (a.deviceGroup() == null || a.deviceGroup().equals(d.group()))
                    && d.sensors().stream().anyMatch(s -> s.type().equals(a.sensorType())));
            if (!trig) notes.add("WARNING: application '" + a.name() + "' has no device with sensor '" + a.sensorType() + "'");
            if (a.actuatorType() != null && w.devices().stream().noneMatch(d -> d.actuator(a.actuatorType()) != null))
                notes.add("WARNING: application '" + a.name() + "' actuates '" + a.actuatorType() + "' but no device has that actuator");
            for (AppModule m : a.modules().values()) {
                boolean anyNode = w.computeNodes().stream().anyMatch(n -> (m.pinnedTier() == null || n.tier() == m.pinnedTier())
                        && n.cores() >= m.cores() && n.ramMB() >= m.ramMB());
                if (!anyNode) notes.add("WARNING: module '" + m.qualifiedName() + "' fits on no compute node (tier " + m.pinnedTier()
                        + ", " + m.cores() + " cores, " + m.ramMB() + " MB); its requests will be dropped");
            }
        }
        for (ComputeNode n : w.computeNodes()) {
            boolean reachable = w.gateways().isEmpty() || w.gateways().stream().anyMatch(g -> w.network().estimateLatency(g, n, 100) < Long.MAX_VALUE);
            if (!reachable) notes.add("WARNING: compute node '" + n.name() + "' is not reachable from any gateway (missing link?)");
        }
    }

    // ------------------------------------------------------------------ sections
    private void security(IoTWorld w, Cfg s) {
        w.setChannelSecurity(SecurityProfile.preset(s.str("channel", "none")));
        if (s.has("ids")) {
            Cfg i = s.sub("ids");
            w.security().enableIds(i.duration("delay", "0.2ms"), i.num("energy", 0.0001), i.num("detectProbability", 0.9),
                    i.dist("detectionTime", "exp(30)"));
        }
    }

    @SuppressWarnings("unchecked")
    private void carbonAndCost(IoTWorld w) {
        Cfg c = root.sub("carbon");
        for (String k : c.asMap().keySet()) {
            Object v = c.raw(k);
            List<Double> prof = new ArrayList<>();
            if (v instanceof List<?> l) for (Object o : l) prof.add(((Number) o).doubleValue());
            else prof.add(((Number) v).doubleValue());
            if (k.equals("default")) w.carbon().setDefault(prof); else w.carbon().setLayer(k, prof);
        }
        Cfg cost = root.sub("cost");
        Cfg ch = cost.sub("coreHour");
        for (Tier t : Tier.values()) if (ch.has(t.name().toLowerCase())) w.cost().setCorePrice(t, ch.num(t.name().toLowerCase()));
        w.cost().setEnergyPrice(cost.num("energyPerKWh", 0));
    }

    private void policies(IoTWorld w, Cfg p) {
        if (p.has("placement")) w.setPlacement(PolicyRegistry.create(Kind.PLACEMENT, polName(p, "placement"), polCfg(p, "placement")));
        if (p.has("selector")) w.setSelector(PolicyRegistry.create(Kind.SELECTOR, polName(p, "selector"), polCfg(p, "selector")));
        if (p.has("scaling")) w.setScaling(PolicyRegistry.create(Kind.SCALING, polName(p, "scaling"), polCfg(p, "scaling")));
        if (p.has("migration")) w.setMigration(PolicyRegistry.create(Kind.MIGRATION, polName(p, "migration"), polCfg(p, "migration")));
        if (p.has("recovery")) w.setRecovery(PolicyRegistry.create(Kind.RECOVERY, polName(p, "recovery"), polCfg(p, "recovery")));
    }

    static String polName(Cfg p, String k) { Object v = p.raw(k); return v instanceof Map ? p.sub(k).str("name") : v.toString(); }
    static Cfg polCfg(Cfg p, String k) { return p.raw(k) instanceof Map ? p.sub(k) : Cfg.of(null); }

    private List<Position> positions(Cfg c, int count, String what) {
        List<Position> out = new ArrayList<>();
        if (c.has("positions")) {
            for (Object o : (List<?>) c.raw("positions")) { List<?> p = (List<?>) o; out.add(new Position(num(p.get(0)), num(p.get(1)))); }
        } else if (c.has("grid")) {
            Cfg g = c.sub("grid");
            int cols = g.integer("columns", count);
            for (int i = 0; i < count; i++) out.add(new Position(g.num("x0", 0) + (i % cols) * g.num("dx", 0), g.num("y0", 0) + (i / cols) * g.num("dy", 0)));
        } else if (c.has("position")) {
            List<?> p = (List<?>) c.raw("position");
            for (int i = 0; i < count; i++) out.add(new Position(num(p.get(0)), num(p.get(1))));
        } else for (int i = 0; i < count; i++) out.add(new Position(0, 0));
        if (out.size() < count) throw new IllegalArgumentException(what + ": " + count + " items but only " + out.size() + " positions");
        return out;
    }

    private static double num(Object o) { return ((Number) o).doubleValue(); }

    private static List<String> names(Cfg c, String def) {
        String base = c.str("name", def);
        int n = c.integer("count", 1);
        List<String> out = new ArrayList<>();
        if (n == 1 && !c.has("count")) out.add(base);
        else for (int i = 0; i < n; i++) out.add(base + "-" + i);
        return out;
    }

    private void tags(Entity e, Cfg c) {
        if (c.has("zone")) e.tag("zone", c.str("zone"));
        Cfg t = c.sub("tags");
        for (String k : t.asMap().keySet()) e.tag(k, t.str(k));
    }

    private void gateways(IoTWorld w) {
        for (Cfg g : root.list("gateways")) {
            List<String> ns = names(g, "gw");
            if (ns.isEmpty()) { g.asMap(); continue; }          // count: 0 (e.g. in a sweep): nothing to build
            List<Position> ps = positions(g, ns.size(), "gateways " + ns.get(0));
            List<String> techs = g.strings("techs");
            if (techs.isEmpty()) techs = List.of("wifi");
            for (int i = 0; i < ns.size(); i++) tags(w.addGateway(ns.get(i), ps.get(i), new LinkedHashSet<>(techs), g.num("range", 0)), g);
        }
    }

    private void nodes(IoTWorld w) {
        for (Cfg n : root.list("nodes")) {
            List<String> ns = names(n, "node");
            if (ns.isEmpty()) { n.asMap(); continue; }
            List<Position> ps = positions(n, ns.size(), "nodes " + ns.get(0));
            Tier t = Tier.parse(n.str("tier", "edge"));
            for (int i = 0; i < ns.size(); i++) {
                ComputeNode c = w.addComputeNode(ns.get(i), ps.get(i), t, n.integer("cores", 4), n.num("mips", 2000), n.integer("ram", 8192),
                        n.num("idlePower", t == Tier.CLOUD ? 200 : 20), n.num("maxPower", t == Tier.CLOUD ? 500 : 60), n.bool("registry", t == Tier.CLOUD));
                tags(c, n);
            }
        }
    }

    /** Expands a name pattern ("edge-*", "cloud", "gw-0") to the network nodes it matches. */
    private List<NetNode> match(IoTWorld w, String pattern) {
        List<NetNode> out = new ArrayList<>();
        List<NetNode> all = new ArrayList<>();
        all.addAll(w.gateways());
        all.addAll(w.computeNodes());
        for (NetNode n : all) {
            boolean ok = pattern.endsWith("*") ? n.name().startsWith(pattern.substring(0, pattern.length() - 1)) : n.name().equals(pattern);
            if (ok) out.add(n);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("links: no gateway/node matches '" + pattern + "'; names are "
                + all.stream().map(NetNode::name).toList() + " (with count > 1 or count: 1, names get an index suffix: gw-0, gw-1, ...; use 'gw-*' for all)");
        return out;
    }

    private void links(IoTWorld w, Link.Fidelity fid) {
        int k = 0;
        for (Cfg l : root.list("links")) {
            List<String> between = l.strings("between");
            if (between.size() != 2) throw new IllegalArgumentException(l.path() + "between must list two name patterns");
            List<NetNode> a = match(w, between.get(0)), b = match(w, between.get(1));
            List<NetNode[]> pairs = new ArrayList<>();
            if (a.size() == b.size() && a.size() > 1) for (int i = 0; i < a.size(); i++) pairs.add(new NetNode[]{a.get(i), b.get(i)});
            else if (a.size() == 1) for (NetNode x : b) pairs.add(new NetNode[]{a.get(0), x});
            else if (b.size() == 1) for (NetNode x : a) pairs.add(new NetNode[]{x, b.get(0)});
            else if (l.bool("fullMesh", false)) { for (NetNode x : a) for (NetNode y : b) if (x != y) pairs.add(new NetNode[]{x, y}); }
            else throw new IllegalArgumentException(l.path() + "between: sizes " + a.size() + " and " + b.size() + " cannot be paired (use equal sizes, a single node, or fullMesh: true)");
            Link.Fidelity f = l.has("fidelity") ? Link.Fidelity.parse(l.str("fidelity")) : fid;
            for (NetNode[] p : pairs) {
                Link link = w.addLink(l.str("name", "link") + "-" + (k++) + ":" + p[0].name() + "-" + p[1].name(), p[0], p[1],
                        Units.bitrate(l.raw("bandwidth") == null ? "1Gbps" : l.raw("bandwidth")), l.duration("latency", "1ms"),
                        l.num("loss", 0), l.has("buffer") ? Units.bytes(l.raw("buffer")) : 4L * 1024 * 1024,
                        l.num("joulesPerByte", 1e-8), l.num("costPerGB", 0), f);
                tags(link, l);
            }
        }
    }

    private void devices(IoTWorld w) {
        int idx = 0;
        for (Cfg d : root.list("devices")) {
            String group = d.str("group", "devices" + idx);
            int count = d.integer("count", 1);
            SplittableRandom r = new SplittableRandom(seed() * 31 + (idx++));
            AccessTechnology tech = d.raw("tech") instanceof Map ? AccessTechnology.fromConfig(d.sub("tech").str("base"), d.sub("tech"))
                    : AccessTechnology.preset(d.str("tech", "wifi"));
            Protocol proto = Protocol.preset(d.str("protocol", "mqtt-qos1"));
            List<Position> ps;
            if (d.has("area")) {
                Cfg a = d.sub("area");
                ps = new ArrayList<>();
                for (int i = 0; i < count; i++)
                    ps.add(new Position(a.num("x0", 0) + (a.num("x1", 100) - a.num("x0", 0)) * r.nextDouble(),
                            a.num("y0", 0) + (a.num("y1", 100) - a.num("y0", 0)) * r.nextDouble()));
            } else if (d.has("nearGateways")) {
                double rad = d.num("nearGateways");
                ps = new ArrayList<>();
                List<Gateway> gws = w.gateways();
                for (int i = 0; i < count; i++) {
                    Gateway g = gws.get(i % gws.size());
                    double ang = r.nextDouble() * 2 * Math.PI, dist = Math.sqrt(r.nextDouble()) * rad;
                    ps.add(new Position(g.position().x() + dist * Math.cos(ang), g.position().y() + dist * Math.sin(ang)));
                }
            } else ps = positions(d, count, "devices " + group);
            Cfg b = d.sub("battery");
            for (int i = 0; i < count; i++) {
                Battery bat = d.has("battery") ? (b.has("joules") ? new Battery(b.num("joules")) : Battery.fromMah(b.num("mAh", 2000), b.num("volts", 3.7))) : null;
                IoTDevice dev = w.addDevice(group + "-" + i, group, ps.get(i), tech, proto, bat, d.num("baselinePower", 0.005), d.str("firmware", "v1"));
                tags(dev, d);
                for (Cfg s : d.list("sensors"))
                    dev.addSensor(new Sensor(s.str("type"), s.dist("interval", "exp(10)"), (int) Units.bytes(s.raw("bytes") == null ? 64 : s.raw("bytes")), s.num("energy", 0.0005)));
                for (Cfg a : d.list("actuators"))
                    dev.addActuator(new Actuator(a.str("type"), a.dist("time", "const(10)"), a.num("energy", 0.01)));
                if (d.has("mobility")) dev.add(mobility(d.sub("mobility"), dev, w));
            }
        }
    }

    private MobilityModel mobility(Cfg m, IoTDevice dev, IoTWorld w) {
        String model = m.str("model", "random-waypoint");
        switch (model) {
            case "random-waypoint" -> {
                Cfg a = m.sub("area");
                double x1 = a.num("x1", 1000), y1 = a.num("y1", 1000);
                return new MobilityModel.RandomWaypoint(dev.position(), a.num("x0", 0), a.num("y0", 0), x1, y1,
                        m.dist("speed", "uniform(1,10)"), m.dist("pause", "exp(30)"), w.sim().stream("mobility:" + dev.name()));
            }
            case "trace" -> {
                Path f = baseDir.resolve(m.str("file"));
                List<MobilityModel.Trace.Point> pts = new ArrayList<>();
                try {
                    for (String line : Files.readAllLines(f)) {
                        if (line.isBlank() || line.startsWith("#") || line.startsWith("time")) continue;
                        String[] p = line.split(",");
                        if (p.length >= 4 && !p[0].trim().equals(dev.name())) continue; // optional device column: device,time,x,y
                        int o = p.length >= 4 ? 1 : 0;
                        pts.add(new MobilityModel.Trace.Point(SimTime.s(Double.parseDouble(p[o].trim())), Double.parseDouble(p[o + 1].trim()), Double.parseDouble(p[o + 2].trim())));
                    }
                } catch (IOException e) { throw new IllegalArgumentException("cannot read mobility trace " + f + ": " + e.getMessage()); }
                return new MobilityModel.Trace(pts);
            }
            default -> throw new IllegalArgumentException("unknown mobility model '" + model + "' (random-waypoint|trace)");
        }
    }

    private void applications(IoTWorld w) {
        for (Cfg a : root.list("applications")) {
            Application app = new Application(a.str("name"), a.str("trigger"), a.str("group", null), a.str("actuator", null),
                    a.has("deadline") ? a.duration("deadline") : 0);
            Map<String, List<String>> next = new LinkedHashMap<>();
            for (Cfg m : a.list("modules")) {
                Cfg img = m.sub("image");
                ContainerImage image = new ContainerImage(img.str("name", app.name() + "-" + m.str("name") + ":1"),
                        img.has("size") ? Units.bytes(img.raw("size")) / (1024.0 * 1024) : 100);
                AppModule mod = new AppModule(app, m.str("name"), m.dist("mi", 100), (int) Units.bytes(m.raw("out") == null ? 64 : m.raw("out")), image,
                        m.integer("cores", 1), m.integer("ram", 512), m.integer("min", 1), m.integer("max", 16),
                        m.has("state") ? Units.bytes(m.raw("state")) : 0, m.has("tier") ? Tier.parse(m.str("tier")) : null,
                        m.bool("actuate", false), m.dist("startup", "normal(1.5,0.3)"), m.has("keepAlive") ? m.duration("keepAlive") : 0,
                        m.integer("maxQueue", 1000));
                app.addModule(mod);
                next.put(mod.name(), m.strings("next"));
            }
            for (Map.Entry<String, List<String>> e : next.entrySet())
                for (String c : e.getValue()) {
                    AppModule child = app.module(c);
                    if (child == null) throw new IllegalArgumentException("application " + app.name() + ": unknown module '" + c + "' in next of " + e.getKey());
                    app.module(e.getKey()).addChild(child);
                }
            if (a.has("root")) app.setRoot(a.str("root"));
            w.addApplication(app);
            if (a.has("retireAt")) w.at(a.duration("retireAt"), () -> w.runtime().retire(app));
        }
    }

    private Predicate<Entity> selector(Cfg c, FaultInjector.TargetType defType) {
        FaultInjector.TargetType t = c.has("target") ? FaultInjector.TargetType.parse(c.str("target")) : defType;
        Map<String, String> tags = new LinkedHashMap<>();
        Cfg tg = c.sub("tags");
        for (String k : tg.asMap().keySet()) tags.put(k, tg.str(k));
        if (c.has("group")) tags.put("group", c.str("group"));
        if (c.has("firmware")) tags.put("firmware", c.str("firmware"));
        if (c.has("zone")) tags.put("zone", c.str("zone"));
        if (c.has("names")) tags.put("name", c.str("names"));
        return FaultInjector.selector(t, tags);
    }

    private void lifecycle(IoTWorld w, Cfg l) {
        for (Cfg u : l.list("updates")) {
            Cfg target = u.sub("target");
            RolloutPolicy ro = PolicyRegistry.create(Kind.ROLLOUT, u.has("rollout") ? polName(u, "rollout") : "all-at-once", u.has("rollout") ? polCfg(u, "rollout") : null);
            w.updates().campaign(u.str("name", "ota"), selector(target, FaultInjector.TargetType.DEVICE), u.str("version"),
                    Units.bytes(u.raw("size") == null ? "256KB" : u.raw("size")) / 1024.0, u.duration("start", "0s"), ro,
                    u.dist("install", "uniform(10,30)"), u.num("failProb", 0.0), u.bool("rollback", true), u.duration("rollbackAfter", "5min"),
                    u.num("gatewayDutyCycle", -1));
        }
        for (Cfg s : l.list("serviceUpdates")) {
            Application app = w.application(s.str("app"));
            if (app == null) throw new IllegalArgumentException("serviceUpdates: unknown app " + s.str("app"));
            AppModule m = app.module(s.str("module"));
            if (m == null) throw new IllegalArgumentException("serviceUpdates: unknown module " + s.str("module"));
            Cfg img = s.sub("image");
            ContainerImage ni = new ContainerImage(img.str("name"), img.has("size") ? Units.bytes(img.raw("size")) / (1024.0 * 1024) : m.image().sizeMB());
            long pause = s.duration("pause", "5s");
            w.at(s.duration("at"), () -> w.runtime().rollingUpdate(m, ni, pause));
        }
        for (Cfg d : l.list("decommission")) {
            Predicate<Entity> sel = selector(d, FaultInjector.TargetType.DEVICE);
            double frac = d.num("fraction", 1.0);
            w.at(d.duration("at"), () -> {
                SplittableRandom r = w.sim().stream("decommission");
                for (IoTDevice dev : w.devices()) if (sel.test(dev) && dev.state() != DeviceState.DECOMMISSIONED && r.nextDouble() < frac) dev.decommission();
            });
        }
    }

    private void faults(IoTWorld w, Cfg f) {
        int i = 0;
        for (Cfg c : f.list("independent"))
            w.faults().independent(c.str("name", "independent-" + (i++)), selector(c, FaultInjector.TargetType.NODE), c.dist("ttf", "exp(3600)"), c.dist("repair", "exp(300)"));
        for (Cfg c : f.list("correlated")) {
            List<Long> at = new ArrayList<>();
            for (String s : c.strings("at")) at.add(SimTime.parse(s));
            w.faults().correlated(c.str("name", "correlated-" + (i++)), selector(c, FaultInjector.TargetType.NODE), at,
                    c.has("every") ? c.dist("every", null) : null, c.num("p", 1.0), c.dist("duration", "const(300)"));
        }
    }

    private void attacks(IoTWorld w) {
        for (Cfg a : root.list("attacks")) {
            String type = a.str("type");
            Gateway g = (Gateway) w.sim().entity(a.str("gateway"));
            if (g == null) throw new IllegalArgumentException("attack " + a.str("name", type) + ": unknown gateway " + a.str("gateway"));
            switch (type) {
                case "flood" -> {
                    String[] t = a.str("target").split("/");
                    Application app = w.application(t[0]);
                    AppModule m = app == null ? null : app.module(t[1]);
                    if (m == null) throw new IllegalArgumentException("flood attack: unknown target module " + a.str("target"));
                    w.security().flood(a.str("name", "flood"), a.integer("attackers", 1), g, a.str("tech", "wifi"), m, a.num("rate", 10),
                            (int) Units.bytes(a.raw("bytes") == null ? 512 : a.raw("bytes")), a.num("mi", 100), a.duration("start", "0s"),
                            a.duration("end", "100d"));
                }
                case "blackhole" -> w.security().blackhole(a.str("name", "blackhole"), g, a.num("drop", 1.0), a.duration("start", "0s"), a.duration("end", "100d"));
                default -> throw new IllegalArgumentException("unknown attack type '" + type + "' (flood|blackhole)");
            }
        }
    }
}
