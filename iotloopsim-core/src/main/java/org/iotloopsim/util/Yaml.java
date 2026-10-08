package org.iotloopsim.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small YAML-subset reader sufficient for IoTLoopSim scenario files, without external libraries.
 * Supported: block mappings, block sequences ("- "), sequences of mappings, inline flow lists
 * ({@code [a, b]}) and flow maps ({@code {k: v}}), quoted/unquoted scalars, comments (#),
 * booleans, null and numbers. Not supported: anchors, multi-line strings, tags.
 * Files whose first non-blank character is '{' are parsed as JSON.
 */
public final class Yaml {
    private record Line(int indent, String text, int no) {}

    private final List<Line> lines = new ArrayList<>();
    private int pos;

    private Yaml(String text) {
        String[] raw = text.replace("\t", "  ").split("\r?\n");
        for (int n = 0; n < raw.length; n++) {
            String l = stripComment(raw[n]);
            if (l.isBlank() || l.trim().equals("---")) continue;
            int ind = 0;
            while (ind < l.length() && l.charAt(ind) == ' ') ind++;
            lines.add(new Line(ind, l.substring(ind).stripTrailing(), n + 1));
        }
    }

    /**
     * Parses scenario text into maps ({@code LinkedHashMap}), lists, strings, {@code Long}, {@code Double}, {@code
     * Boolean} and null; text whose first non-blank character is an opening brace or bracket is parsed as JSON and empty text yields an
     * empty map. Throws {@link IllegalArgumentException} with the line number on syntax errors.
     */
    public static Object parse(String text) {
        String t = text.stripLeading();
        if (t.startsWith("{") || t.startsWith("[")) return Json.parse(text);
        Yaml y = new Yaml(text);
        if (y.lines.isEmpty()) return new LinkedHashMap<String, Object>();
        Object v = y.block(y.lines.get(0).indent);
        if (y.pos < y.lines.size()) throw y.err(y.lines.get(y.pos), "unexpected indentation");
        return v;
    }

    /**
     * Parses like {@link #parse(String)} and requires the root to be a mapping; throws {@link
     * IllegalArgumentException} otherwise.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("scenario root must be a mapping");
        return (Map<String, Object>) o;
    }

    private Object block(int indent) {
        Line first = lines.get(pos);
        if (first.text.startsWith("- ") || first.text.equals("-")) return seq(indent);
        return map(indent);
    }

    private Map<String, Object> map(int indent) {
        Map<String, Object> m = new LinkedHashMap<>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent < indent) break;
            if (l.indent > indent) throw err(l, "bad indentation");
            if (l.text.startsWith("- ")) break;
            int c = keyColon(l.text);
            if (c < 0) throw err(l, "expected 'key: value'");
            String key = unquote(l.text.substring(0, c).trim());
            String rest = l.text.substring(c + 1).trim();
            pos++;
            if (rest.isEmpty()) {
                if (pos < lines.size() && (lines.get(pos).indent > indent
                        || (lines.get(pos).indent == indent && lines.get(pos).text.startsWith("- ")))) {
                    m.put(key, block(lines.get(pos).indent));
                } else m.put(key, null);
            } else m.put(key, scalarOrFlow(rest, l));
        }
        return m;
    }

    private List<Object> seq(int indent) {
        List<Object> list = new ArrayList<>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent != indent || !(l.text.startsWith("- ") || l.text.equals("-"))) {
                if (l.indent > indent) throw err(l, "bad indentation in list");
                break;
            }
            String rest = l.text.equals("-") ? "" : l.text.substring(2).trim();
            if (rest.isEmpty()) {
                pos++;
                list.add(pos < lines.size() && lines.get(pos).indent > indent ? block(lines.get(pos).indent) : null);
            } else if (keyColon(rest) >= 0 && !rest.startsWith("{") && !rest.startsWith("[") && !rest.startsWith("\"")) {
                // "- key: value" starts a mapping item; re-read it as a map at the item's content indent
                int contentIndent = indent + (l.text.length() - l.text.substring(1).stripLeading().length());
                lines.set(pos, new Line(contentIndent, rest, l.no));
                list.add(map(contentIndent));
            } else {
                pos++;
                list.add(scalarOrFlow(rest, l));
            }
        }
        return list;
    }

    private Object scalarOrFlow(String v, Line l) {
        if (v.startsWith("[") || v.startsWith("{")) {
            try { return Json.parse(toJsonFlow(v)); } catch (RuntimeException e) { throw err(l, "bad flow collection: " + e.getMessage()); }
        }
        return scalar(v);
    }

    /** Converts YAML flow syntax with bare words into JSON. */
    private static String toJsonFlow(String v) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < v.length()) {
            char c = v.charAt(i);
            if ("[]{},:".indexOf(c) >= 0 || Character.isWhitespace(c)) { out.append(c); i++; continue; }
            if (c == '"' || c == '\'') {
                int j = v.indexOf(c, i + 1);
                if (j < 0) throw new IllegalArgumentException("unterminated quoted string");
                out.append(Json.writeCompact(v.substring(i + 1, j)));
                i = j + 1;
                continue;
            }
            int j = i, depth = 0;   // commas inside parentheses belong to the value, e.g. normal(120,15)
            while (j < v.length()) {
                char d = v.charAt(j);
                if (d == '(') depth++;
                else if (d == ')') depth = Math.max(0, depth - 1);
                else if (depth == 0 && ("[]{},".indexOf(d) >= 0 || (d == ':' && (j + 1 == v.length() || v.charAt(j + 1) == ' ')))) break;
                j++;
            }
            String word = v.substring(i, j).trim();
            boolean isKey = j < v.length() && v.charAt(j) == ':';
            Object s = isKey ? word : scalar(word);   // keys stay strings (on/yes/null are not booleans as keys)
            out.append(s instanceof String ? Json.writeCompact(s) : String.valueOf(s));
            out.append(" ".repeat(Math.max(0, (j - i) - word.length())));
            i = j;
        }
        return out.toString();
    }

    static Object scalar(String v) {
        if ((v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) || (v.startsWith("'") && v.endsWith("'") && v.length() >= 2))
            return v.substring(1, v.length() - 1);
        switch (v) {
            case "true", "True", "yes", "on": return Boolean.TRUE;
            case "false", "False", "no", "off": return Boolean.FALSE;
            case "null", "~", "Null": return null;
            default:
        }
        try { if (v.matches("[-+]?\\d+")) return Long.parseLong(v); } catch (NumberFormatException ignored) { }
        try { if (v.matches("[-+]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][-+]?\\d+)?")) return Double.parseDouble(v); } catch (NumberFormatException ignored) { }
        return v;
    }

    private static int keyColon(String t) {
        boolean q = false;
        char qc = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (q) { if (c == qc) q = false; continue; }
            if (c == '"' || c == '\'') { q = true; qc = c; continue; }
            if (c == '{' || c == '[') return -1;
            if (c == ':' && (i + 1 == t.length() || t.charAt(i + 1) == ' ')) return i;
        }
        return -1;
    }

    private static String unquote(String k) { Object s = scalar(k); return String.valueOf(s); }

    private static String stripComment(String l) {
        boolean q = false;
        char qc = 0;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (q) { if (c == qc) q = false; continue; }
            if (c == '"' || c == '\'') { q = true; qc = c; continue; }
            if (c == '#' && (i == 0 || Character.isWhitespace(l.charAt(i - 1)))) return l.substring(0, i);
        }
        return l;
    }

    private IllegalArgumentException err(Line l, String m) { return new IllegalArgumentException("YAML line " + l.no + ": " + m + " -> '" + l.text + "'"); }
}
