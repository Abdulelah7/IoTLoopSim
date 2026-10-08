package org.iotloopsim.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class of every simulated thing (device, gateway, link, compute node...).
 * Behaviour can be added by attaching {@link Component}s instead of subclassing,
 * e.g. a battery, a mobility model or a fault state.
 */
public abstract class Entity {
    private final int id;
    private final String name;
    protected final Simulation sim;
    private final Map<Class<?>, Component> components = new LinkedHashMap<>();
    private final Map<String, String> tags = new LinkedHashMap<>();

    /**
     * Creates the entity, assigns the next entity id and registers it with {@code sim}; throws {@link
     * IllegalArgumentException} if {@code name} is already registered.
     */
    protected Entity(Simulation sim, String name) {
        this.sim = sim;
        this.id = sim.nextEntityId();
        this.name = name;
        sim.register(this);
    }

    /** Returns the id assigned at construction (0-based registration order within the simulation). */
    public int id() { return id; }
    /** Returns the unique entity name within its simulation. */
    public String name() { return name; }
    /** Returns the simulation this entity belongs to. */
    public Simulation sim() { return sim; }

    /**
     * Attaches {@code component}, replacing any component of the same concrete class, calls {@link
     * Component#onAttach(Entity)} and returns the component.
     */
    public <C extends Component> C add(C component) {
        components.put(component.getClass(), component);
        component.onAttach(this);
        return component;
    }

    /** Returns the attached component assignable to {@code type}, or null. */
    @SuppressWarnings("unchecked")
    public <C extends Component> C get(Class<C> type) {
        Component c = components.get(type);
        if (c != null) return (C) c;
        for (Component x : components.values()) if (type.isInstance(x)) return (C) x;
        return null;
    }

    /** Returns true if a component assignable to {@code type} is attached. */
    public boolean has(Class<? extends Component> type) { return get(type) != null; }

    /** Free-form labels such as zone, firmware version or vendor; used by fault correlation groups and selectors. */
    public Entity tag(String key, String value) { tags.put(key, value); return this; }
    /** Returns the tag value for {@code key}, or null if not set. */
    public String tag(String key) { return tags.get(key); }
    /** Returns the live (mutable) tag map in insertion order. */
    public Map<String, String> tags() { return tags; }

    @Override public String toString() { return getClass().getSimpleName() + "(" + name + ")"; }
}
