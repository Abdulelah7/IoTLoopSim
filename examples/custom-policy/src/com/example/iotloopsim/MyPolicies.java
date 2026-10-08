package com.example.iotloopsim;

import java.util.Comparator;
import org.iotloopsim.model.ComputeNode;
import org.iotloopsim.model.IoTWorld;
import org.iotloopsim.model.ServiceInstance;
import org.iotloopsim.model.Tier;
import org.iotloopsim.model.policy.InstanceSelector;
import org.iotloopsim.model.policy.PolicyProvider;
import org.iotloopsim.model.policy.PolicyRegistry;
import org.iotloopsim.model.policy.SelectionContext;

/**
 * Example third-party extension: a "green" selector that prefers the instance on the node whose layer has the
 * lowest carbon intensity right now, breaking ties by load. Package this class in a JAR together with
 * META-INF/services/org.iotloopsim.model.policy.PolicyProvider, put it on the classpath, and use
 * {@code policies: {selector: {name: greenest, maxLoad: 3}}} in a scenario.
 */
public final class MyPolicies implements PolicyProvider {
    @Override public void register() {
        PolicyRegistry.selector("greenest", cfg -> new Greenest(cfg.num("maxLoad", 3.0)));
    }

    static final class Greenest implements InstanceSelector {
        private final double maxLoad;
        Greenest(double maxLoad) { this.maxLoad = maxLoad; }

        @Override public void select(SelectionContext ctx) {
            IoTWorld w = ctx.world();
            long now = w.sim().now();
            ServiceInstance best = ctx.candidates().stream()
                    .filter(i -> i.load() < maxLoad)
                    .filter(i -> w.network().estimateLatency(ctx.from(), i.node(), 1000) != Long.MAX_VALUE)
                    .min(Comparator.comparingDouble((ServiceInstance i) -> w.carbon().intensity(layer(i.node()), now))
                            .thenComparingDouble(ServiceInstance::load))
                    .orElse(null);
            if (best != null) ctx.dispatch(best);
            else ctx.drop("overloaded");
        }

        private static String layer(ComputeNode n) { return n.tier() == Tier.CLOUD ? "cloud" : n.tier().name().toLowerCase(); }
    }
}
