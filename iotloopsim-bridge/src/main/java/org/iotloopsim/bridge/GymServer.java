package org.iotloopsim.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iotloopsim.experiment.ScenarioLoader;
import org.iotloopsim.model.AppModule;
import org.iotloopsim.model.Application;
import org.iotloopsim.model.ComputeNode;
import org.iotloopsim.model.IoTWorld;
import org.iotloopsim.model.ServiceInstance;
import org.iotloopsim.model.policy.SelectionContext;
import org.iotloopsim.util.Json;

/**
 * Gym-style bridge over TCP (one JSON object per line). The agent decides on which compute node each request of one
 * module is served (an offloading/placement decision); the simulation pauses at every decision.
 *
 * <pre>
 * → {"cmd":"reset","scenario":"scenarios/smart-city.yaml","module":"traffic/detect","seed":1}
 * ← {"obs":[...],"reward":0,"done":false,"info":{...}}
 * → {"cmd":"step","action":2}
 * ← {"obs":[...],"reward":1.0,"done":false,"info":{...}}
 * → {"cmd":"spaces"}   ← {"n_actions":6,"obs_dim":25,"nodes":[...],"features":[...]}
 * → {"cmd":"close"}
 * </pre>
 * Observation per compute node: [up, utilisation, load of the module's instances there (-1 = none),
 * estimated latency from the request source in units of 100 ms (-1 = unreachable)], followed by [request kB].
 * Action: index of the compute node. If no instance runs there, one is launched (cold start) if capacity allows,
 * otherwise the request is dropped. Reward for the step: completed loops − 2 × deadline misses − drops of the
 * module's application since the previous decision.
 */
public final class GymServer {
    private IoTWorld world;
    private ExternalSelector ext;
    private AppModule module;
    private List<ComputeNode> nodes = List.of();
    private double lastCompleted, lastMiss, lastDropped;
    private boolean done;

    /**
     * Listens on 127.0.0.1 at {@code --port} (default 5555) and serves one agent connection at a time, forever; a
     * failed session is logged to stderr and the server keeps accepting.
     */
    public static void main(String[] args) throws Exception {
        int port = 5555;
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
        try (ServerSocket ss = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            System.out.println("IoTLoopSim Gym bridge listening on 127.0.0.1:" + ss.getLocalPort());
            System.out.flush();
            while (true) {
                try (Socket s = ss.accept()) {
                    new GymServer().serve(s);
                } catch (Exception e) {
                    System.err.println("session ended: " + e.getMessage());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    void serve(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        String line;
        while ((line = in.readLine()) != null) {
            Map<String, Object> req;
            try { req = (Map<String, Object>) Json.parse(line); }
            catch (RuntimeException e) {   // malformed request: report it and keep the session open
                out.println(Json.writeCompact(Map.of("error", "bad request: " + e.getMessage())));
                continue;
            }
            if ("close".equals(req.get("cmd"))) { out.println("{\"ok\":true}"); return; }
            Map<String, Object> resp;
            try {
                resp = switch (String.valueOf(req.get("cmd"))) {
                    case "reset" -> reset(req);
                    case "step" -> step(((Number) req.get("action")).intValue());
                    case "spaces" -> spaces();
                    case "report" -> new LinkedHashMap<>(Map.of("report", world == null ? Map.of() : world.report()));
                    default -> Map.of("error", "unknown cmd " + req.get("cmd"));
                };
            } catch (Exception e) {
                resp = Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            out.println(Json.writeCompact(resp));
        }
    }

    Map<String, Object> reset(Map<String, Object> req) throws Exception {
        Path file = Path.of(String.valueOf(req.get("scenario")));
        ScenarioLoader l = new ScenarioLoader(ScenarioLoader.read(file), file.toAbsolutePath().getParent());
        long seed = req.get("seed") == null ? l.seed() : ((Number) req.get("seed")).longValue();
        world = l.build(seed);
        String[] t = String.valueOf(req.get("module")).split("/");
        Application app = world.application(t[0]);
        module = app == null ? null : app.module(t[1]);
        if (module == null) throw new IllegalArgumentException("unknown module " + req.get("module"));
        ext = new ExternalSelector(world.selector(), module);
        world.setSelector(ext);
        nodes = new ArrayList<>(world.computeNodes());
        world.sim().setEndTime(l.duration());
        world.start();
        lastCompleted = lastMiss = lastDropped = 0;
        done = false;
        advance();
        return result(0);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> step(int action) {
        if (world == null) throw new IllegalStateException("call reset first");
        if (done) return result(0);
        SelectionContext ctx = ext.poll();
        String outcome;
        if (action < 0 || action >= nodes.size()) { ctx.drop("invalid-action"); outcome = "invalid"; }
        else {
            ComputeNode n = nodes.get(action);
            ServiceInstance inst = ctx.candidates().stream().filter(i -> i.node() == n).min(Comparator.comparingDouble(ServiceInstance::load)).orElse(null);
            if (inst != null) { ctx.dispatch(inst); outcome = "dispatched"; }
            else if (n.canHost(module) && world.runtime().instancesOf(module).size() < module.maxInstances()) { ctx.launchOn(n); outcome = "launched"; }
            else { ctx.drop("invalid-action"); outcome = "dropped"; }
        }
        advance();
        Map<String, Object> r = result(reward());
        ((Map<String, Object>) r.get("info")).put("outcome", outcome);
        return r;
    }

    private void advance() {
        boolean paused = world.sim().runUntilPaused(ext::hasPending);
        if (!paused) { world.sim().finish(); done = true; }
    }

    private double reward() {
        var mx = world.sim().metrics();
        String a = "app." + module.app().name() + ".";
        double c = mx.counter(a + "completed"), m = mx.counter(a + "deadline_miss"), d = mx.counter(a + "dropped");
        double r = (c - lastCompleted) - 2 * (m - lastMiss) - (d - lastDropped);
        lastCompleted = c; lastMiss = m; lastDropped = d;
        return r;
    }

    private Map<String, Object> result(double reward) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("obs", observation());
        r.put("reward", reward);
        r.put("done", done);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("time_s", world.sim().nowSeconds());
        if (done) info.put("report", world.report());
        r.put("info", info);
        return r;
    }

    private List<Double> observation() {
        List<Double> o = new ArrayList<>();
        SelectionContext ctx = ext == null ? null : ext.peek();
        for (ComputeNode n : nodes) {
            o.add(n.isUp() ? 1.0 : 0.0);
            o.add(n.utilization());
            double load = -1;
            if (ctx != null) for (ServiceInstance i : ctx.candidates()) if (i.node() == n) load = load < 0 ? i.load() : Math.min(load, i.load());
            o.add(load);
            double lat = -1;
            if (ctx != null) {
                long l = world.network().estimateLatency(ctx.from(), n, ctx.request().bytes);
                lat = l == Long.MAX_VALUE ? -1 : l / 1e8;
            }
            o.add(lat);
        }
        o.add(ctx == null ? 0 : ctx.request().bytes / 1000.0);
        return o;
    }

    private Map<String, Object> spaces() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n_actions", nodes.size());
        m.put("obs_dim", nodes.size() * 4 + 1);
        m.put("nodes", nodes.stream().map(n -> n.name() + " (" + n.tier().name().toLowerCase() + ")").toList());
        m.put("features", List.of("up", "utilization", "instance_load", "latency_100ms"));
        return m;
    }
}
