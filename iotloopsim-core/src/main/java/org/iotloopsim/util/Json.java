package org.iotloopsim.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal dependency-free JSON reader/writer (objects → LinkedHashMap, arrays → ArrayList, numbers → Long/Double). */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /**
     * Parses a complete JSON document; objects become {@code LinkedHashMap}, arrays {@code ArrayList}, integers
     * {@code Long}, other numbers {@code Double}. Throws {@link IllegalArgumentException} with the character
     * offset on malformed input or trailing characters.
     */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing characters");
        return v;
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> lit("true", Boolean.TRUE);
            case 'f' -> lit("false", Boolean.FALSE);
            case 'n' -> lit("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws(); expect(':');
            m.put(k, value());
            ws();
            if (peek() == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            l.add(value());
            ws();
            if (peek() == ',') { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            } else b.append(c);
        }
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(st, i);
        if (t.isEmpty()) throw err("unexpected character '" + s.charAt(st) + "'");
        if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
        return Long.parseLong(t);
    }

    private Object lit(String word, Object v) {
        if (!s.startsWith(word, i)) throw err("expected " + word);
        i += word.length();
        return v;
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
    private void expect(char c) { if (peek() != c) throw err("expected '" + c + "'"); i++; }
    private IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON: " + m + " at " + i); }

    // ---------------------------------------------------------------- writing
    /**
     * Serialises maps, iterables, {@code double[]}, strings, numbers and booleans to indented JSON (2 spaces); NaN
     * and infinite doubles become {@code null}, other objects are written as their {@code toString()} string.
     */
    public static String write(Object v) { StringBuilder b = new StringBuilder(); write(b, v, 0, true); return b.toString(); }
    /** Same as {@link #write(Object)} but without whitespace or newlines. */
    public static String writeCompact(Object v) { StringBuilder b = new StringBuilder(); write(b, v, 0, false); return b.toString(); }

    private static void write(StringBuilder b, Object v, int ind, boolean pretty) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(b, str);
        else if (v instanceof Double d) b.append(d.isNaN() || d.isInfinite() ? "null" : fmt(d));
        else if (v instanceof Float f) b.append(fmt(f.doubleValue()));
        else if (v instanceof Number || v instanceof Boolean) b.append(v);
        else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, ind + 1, pretty);
                quote(b, String.valueOf(e.getKey()));
                b.append(pretty ? ": " : ":");
                write(b, e.getValue(), ind + 1, pretty);
            }
            if (!m.isEmpty()) nl(b, ind, pretty);
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) b.append(',');
                first = false;
                nl(b, ind + 1, pretty);
                write(b, o, ind + 1, pretty);
            }
            if (!first) nl(b, ind, pretty);
            b.append(']');
        } else if (v instanceof double[] arr) {
            List<Object> l = new ArrayList<>();
            for (double d : arr) l.add(d);
            write(b, l, ind, pretty);
        } else quote(b, v.toString());
    }

    private static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    private static void nl(StringBuilder b, int ind, boolean pretty) {
        if (!pretty) return;
        b.append('\n');
        b.append("  ".repeat(ind));
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        b.append('"');
    }
}
