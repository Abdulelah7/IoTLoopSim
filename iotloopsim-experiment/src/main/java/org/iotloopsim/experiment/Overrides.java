package org.iotloopsim.experiment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iotloopsim.util.Yaml;

/**
 * Applies "dotted.path=value" overrides to a parsed scenario (for parameter sweeps from the command line).
 * List elements are addressed by index, e.g. {@code devices.0.count=500}. Values use YAML scalar/flow syntax.
 */
public final class Overrides {
    private Overrides() {}

    /**
     * Applies each {@code path=value} override to {@code scenario} in place, creating missing intermediate maps;
     * list elements are addressed by index. Throws {@link IllegalArgumentException} for malformed entries or
     * out-of-range indices.
     */
    @SuppressWarnings("unchecked")
    public static void apply(Map<String, Object> scenario, List<String> sets) {
        for (String s : sets) {
            int eq = s.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("--set expects path=value, got " + s);
            String[] path = s.substring(0, eq).trim().split("\\.");
            Object value = ((Map<String, Object>) Yaml.parse("v: " + s.substring(eq + 1).trim())).get("v");
            Object cur = scenario;
            for (int i = 0; i < path.length - 1; i++) {
                cur = child(cur, path[i], true);
            }
            String last = path[path.length - 1];
            if (cur instanceof Map) ((Map<String, Object>) cur).put(last, value);
            else if (cur instanceof List<?> l) {
                int i = Integer.parseInt(last);
                if (i < 0 || i >= l.size()) throw new IllegalArgumentException("--set " + s + ": index out of range (list has " + l.size() + " entries)");
                ((List<Object>) cur).set(i, value);
            }
            else throw new IllegalArgumentException("cannot set " + s);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object child(Object cur, String key, boolean create) {
        if (cur instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) cur;
            Object c = m.get(key);
            if (c == null || !(c instanceof Map || c instanceof List)) {
                if (!create) throw new IllegalArgumentException("no field " + key);
                c = new LinkedHashMap<String, Object>();
                m.put(key, c);
            }
            return c;
        }
        if (cur instanceof List<?> l) {
            int i;
            try { i = Integer.parseInt(key); } catch (NumberFormatException e) { throw new IllegalArgumentException("--set: '" + key + "' must be a list index"); }
            if (i < 0 || i >= l.size()) throw new IllegalArgumentException("--set: index " + i + " out of range (list has " + l.size() + " entries)");
            return l.get(i);
        }
        throw new IllegalArgumentException("cannot descend into " + key);
    }
}
