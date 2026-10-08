package org.iotloopsim.core;

/** A unit of state/behaviour attached to an {@link Entity}. */
public interface Component {
    /**
     * Called by {@link Entity#add(Component)} right after this component is attached to {@code owner}; the default
     * does nothing.
     */
    default void onAttach(Entity owner) {}
}
