package org.iotloopsim.model.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import org.iotloopsim.util.Cfg;

/**
 * Name-based registry of policy factories. Built-ins are registered by {@link BuiltinPolicies}; third-party JARs add
 * their own by implementing {@link PolicyProvider} and listing it in
 * {@code META-INF/services/org.iotloopsim.model.policy.PolicyProvider}. Scenario files then refer to policies by name.
 */
public final class PolicyRegistry {
    /**
     * Kind of extension point; factories of each kind must produce the matching interface: {@code PLACEMENT}
     * {@link PlacementPolicy}, {@code SELECTOR} {@link InstanceSelector}, {@code SCALING} {@link ScalingPolicy},
     * {@code MIGRATION} {@link MigrationPolicy}, {@code RECOVERY} {@link RecoveryPolicy}, {@code ROLLOUT} {@link
     * RolloutPolicy}.
     */
    public enum Kind { PLACEMENT, SELECTOR, SCALING, MIGRATION, RECOVERY, ROLLOUT }

    private static final Map<Kind, Map<String, Function<Cfg, ?>>> FACTORIES = new LinkedHashMap<>();
    private static boolean loaded;

    private PolicyRegistry() {}

    /**
     * Registers a factory under a case-insensitive name. Prefer the typed helpers ({@link #placement}, {@link #selector},
     * ...), which check the product type at compile time. Replacing an existing name prints a warning.
     */
    public static synchronized void register(Kind kind, String name, Function<Cfg, ?> factory) {
        Function<Cfg, ?> old = FACTORIES.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(name.toLowerCase(), factory);
        if (old != null) System.err.println("warning: " + kind.name().toLowerCase() + " policy '" + name + "' was registered twice; the later one wins");
    }

    /** Registers a placement-policy factory under {@code name}. */
    public static void placement(String name, Function<Cfg, PlacementPolicy> f) { register(Kind.PLACEMENT, name, f); }
    /** Registers an instance-selector factory under {@code name}. */
    public static void selector(String name, Function<Cfg, InstanceSelector> f) { register(Kind.SELECTOR, name, f); }
    /** Registers a scaling-policy factory under {@code name}. */
    public static void scaling(String name, Function<Cfg, ScalingPolicy> f) { register(Kind.SCALING, name, f); }
    /** Registers a migration-policy factory under {@code name}. */
    public static void migration(String name, Function<Cfg, MigrationPolicy> f) { register(Kind.MIGRATION, name, f); }
    /** Registers a recovery-policy factory under {@code name}. */
    public static void recovery(String name, Function<Cfg, RecoveryPolicy> f) { register(Kind.RECOVERY, name, f); }
    /** Registers a rollout-policy factory under {@code name}. */
    public static void rollout(String name, Function<Cfg, RolloutPolicy> f) { register(Kind.ROLLOUT, name, f); }

    /**
     * Creates a policy of the given kind by case-insensitive name, passing {@code cfg} (an empty view if null) to
     * its factory; loads built-in and {@link java.util.ServiceLoader} providers on first use. Throws {@link
     * IllegalArgumentException} listing the available names if {@code name} is unknown; the caller must request
     * the type matching {@code kind}.
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> T create(Kind kind, String name, Cfg cfg) {
        load();
        Function<Cfg, ?> f = FACTORIES.getOrDefault(kind, Map.of()).get(name.toLowerCase());
        if (f == null) throw new IllegalArgumentException("unknown " + kind.name().toLowerCase() + " policy '" + name
                + "'; available: " + FACTORIES.getOrDefault(kind, Map.of()).keySet());
        return (T) f.apply(cfg == null ? Cfg.of(null) : cfg);
    }

    /**
     * Returns the registered policy names per kind (lower-case), after loading providers; the name sets are live
     * views.
     */
    public static synchronized Map<Kind, java.util.Set<String>> available() {
        load();
        Map<Kind, java.util.Set<String>> out = new LinkedHashMap<>();
        FACTORIES.forEach((k, v) -> out.put(k, v.keySet()));
        return out;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        new BuiltinPolicies().register();
        for (PolicyProvider p : ServiceLoader.load(PolicyProvider.class)) p.register();
    }
}
