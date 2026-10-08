package org.iotloopsim.model;

/** Processing tier of a compute node. */
public enum Tier {
    EDGE, FOG, CLOUD;
    /** Parses a tier name, case-insensitive and trimmed; throws {@link IllegalArgumentException} for unknown names. */
    public static Tier parse(String s) { return valueOf(s.trim().toUpperCase()); }
}
