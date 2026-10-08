package org.iotloopsim.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;

/**
 * Typed, defaulting view over a parsed scenario mapping, with helpful errors that name the path.
 *
 * <p>Every key looked up through any view derived from the same root is recorded, so that after loading,
 * {@link #unknownKeys()} lists keys present in the scenario that no code ever asked for (typically typos), with a
 * suggestion taken from the keys that were asked for at the same place.
 */
public final class Cfg {
    private final Map<String, Object> m;
    private final String path;
    private final Tracker tracker;

    /** Shared record of which keys were looked up in which mapping (by identity). */
    private static final class Tracker {
        final Map<Map<String, Object>, Set<String>> used = new IdentityHashMap<>();
        final Map<Map<String, Object>, String> paths = new IdentityHashMap<>();
        final Set<Map<String, Object>> free = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Map<String, Object>> order = new ArrayList<>();
    }

    /**
     * Creates a root view over {@code m} (null is treated as empty) with a fresh key-usage tracker; {@code path}
     * is the prefix used in error messages (e.g. {@code "devices[0]."}).
     */
    public Cfg(Map<String, Object> m, String path) { this(m, path, new Tracker()); }
    private Cfg(Map<String, Object> m, String path, Tracker t) {
        this.m = m == null ? Collections.emptyMap() : m; this.path = path; this.tracker = t;
        if (m != null && !t.paths.containsKey(m)) { t.paths.put(m, path); t.order.add(m); t.used.put(m, new LinkedHashSet<>()); }
    }
    /** Creates a root view over {@code m} with an empty path prefix. */
    public static Cfg of(Map<String, Object> m) { return new Cfg(m, ""); }

    private Object get(String k) { Set<String> u = tracker.used.get(m); if (u != null) u.add(k); return m.get(k); }

    /** Returns true if {@code k} is present with a non-null value; marks the key as used. */
    public boolean has(String k) { Object v = get(k); return v != null; }
    /** Returns the unconverted value of {@code k}, or null if absent; marks the key as used. */
    public Object raw(String k) { return get(k); }
    /** The underlying mapping; its keys are treated as free-form (never reported as unknown). */
    public Map<String, Object> asMap() { tracker.free.add(m); return m; }
    /** Returns the path prefix of this view, ending in a dot for nested views (empty for the root). */
    public String path() { return path; }

    /**
     * Keys present in the scenario that were never looked up, as "path.key" strings with a "did you mean" hint when a
     * similar key was looked up at the same place. Call after the scenario has been fully loaded.
     */
    public List<String> unknownKeys() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> map : tracker.order) {
            if (tracker.free.contains(map)) continue;
            Set<String> used = tracker.used.get(map);
            for (String k : map.keySet()) {
                if (used.contains(k)) continue;
                String best = null; int bd = Integer.MAX_VALUE;
                for (String u : used) { int d = distance(k.toLowerCase(), u.toLowerCase()); if (d < bd) { bd = d; best = u; } }
                String hint = best != null && bd <= Math.max(2, k.length() / 3) ? " (did you mean '" + best + "'?)" : "";
                out.add(tracker.paths.get(map) + k + hint);
            }
        }
        return out;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** Returns {@code k} as a string ({@code toString()} of the value), or {@code def} if absent. */
    public String str(String k, String def) { Object v = get(k); return v == null ? def : v.toString(); }
    /** Returns {@code k} as a string; throws {@link IllegalArgumentException} naming the path if absent. */
    public String str(String k) { String v = str(k, null); if (v == null) throw missing(k); return v; }
    /**
     * Returns {@code k} as a double, or {@code def} if absent; throws {@link IllegalArgumentException} if the
     * value is not numeric.
     */
    public double num(String k, double def) { Object v = get(k); return v == null ? def : toDouble(v, k); }
    /** Returns {@code k} as a double; throws {@link IllegalArgumentException} if absent or not numeric. */
    public double num(String k) { if (!has(k)) throw missing(k); return toDouble(m.get(k), k); }
    /** Returns {@code k} as an int rounded to the nearest integer, or {@code def} if absent. */
    public int integer(String k, int def) { return (int) Math.round(num(k, def)); }
    /** Returns {@code k} as a long rounded to the nearest integer, or {@code def} if absent. */
    public long lng(String k, long def) { Object v = get(k); return v == null ? def : Math.round(toDouble(v, k)); }
    /**
     * Returns {@code k} as a boolean (accepts true/false, yes/no, on/off, case-insensitive), or {@code def} if
     * absent; throws {@link IllegalArgumentException} for any other value.
     */
    public boolean bool(String k, boolean def) {
        Object v = get(k);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String t = v.toString().trim().toLowerCase();
        if (t.equals("true") || t.equals("yes") || t.equals("on")) return true;
        if (t.equals("false") || t.equals("no") || t.equals("off")) return false;
        throw new IllegalArgumentException(path + k + " must be true or false, got '" + v + "'");
    }
    /** Duration in ns; a bare number is read as seconds (write units, e.g. 250ms, 2s, 5min, 1h). */
    public long duration(String k, String def) { Object v = get(k); return parseDuration(k, v == null ? def : v.toString()); }
    /**
     * Returns required duration {@code k} in ns (bare numbers are seconds); throws {@link
     * IllegalArgumentException} if absent or unparseable.
     */
    public long duration(String k) { if (!has(k)) throw missing(k); return parseDuration(k, m.get(k).toString()); }
    /**
     * Parses {@code k} with {@link Distribution#parse(Object)}, falling back to {@code def} if absent; parse
     * errors are rethrown as {@link IllegalArgumentException} prefixed with the path.
     */
    public Distribution dist(String k, Object def) {
        Object v = get(k);
        try { return Distribution.parse(v == null ? def : v); }
        catch (RuntimeException e) { throw new IllegalArgumentException(path + k + ": " + e.getMessage(), e); }
    }

    private long parseDuration(String k, String v) {
        try { return SimTime.parse(v); }
        catch (RuntimeException e) { throw new IllegalArgumentException(path + k + ": invalid duration '" + v + "' (use e.g. 250ms, 2s, 5min, 1h)", e); }
    }

    /**
     * Returns a view over nested mapping {@code k} sharing this root's tracker; an absent key yields an empty
     * view. Throws {@link IllegalArgumentException} if the value is not a mapping.
     */
    @SuppressWarnings("unchecked")
    public Cfg sub(String k) {
        Object v = get(k);
        if (v == null) return new Cfg(new LinkedHashMap<>(), path + k + ".", tracker);
        if (!(v instanceof Map)) throw new IllegalArgumentException(path + k + " must be a mapping");
        return new Cfg((Map<String, Object>) v, path + k + ".", tracker);
    }

    /**
     * Returns views over each mapping in list {@code k} (empty list if absent); throws {@link
     * IllegalArgumentException} if the value is not a list or an element is not a mapping.
     */
    @SuppressWarnings("unchecked")
    public List<Cfg> list(String k) {
        Object v = get(k);
        List<Cfg> out = new ArrayList<>();
        if (v == null) return out;
        if (!(v instanceof List<?> l)) throw new IllegalArgumentException(path + k + " must be a list");
        for (int i = 0; i < l.size(); i++) {
            Object o = l.get(i);
            if (!(o instanceof Map)) throw new IllegalArgumentException(path + k + "[" + i + "] must be a mapping");
            out.add(new Cfg((Map<String, Object>) o, path + k + "[" + i + "].", tracker));
        }
        return out;
    }

    /** Returns list {@code k} as strings; a scalar value yields a one-element list and an absent key an empty list. */
    public List<String> strings(String k) {
        Object v = get(k);
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        if (v instanceof List<?> l) { for (Object o : l) out.add(String.valueOf(o)); }
        else out.add(v.toString());
        return out;
    }

    private double toDouble(Object v, String k) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(path + k + " must be a number, got '" + v + "'"); }
    }

    private IllegalArgumentException missing(String k) { return new IllegalArgumentException("missing required field " + path + k); }
}
