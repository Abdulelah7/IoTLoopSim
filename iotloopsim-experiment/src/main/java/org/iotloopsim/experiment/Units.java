package org.iotloopsim.experiment;

import java.util.Locale;

/** Parses human-friendly quantities used in scenario files. */
final class Units {
    private Units() {}

    /**
     * Bit rate in bit/s. Accepts a number (bit/s) or "9600bps", "250kbps", "100Mbps", "1Gbps" (case-insensitive,
     * decimal multipliers), or a byte rate written with a capital B: "10MBps", "10MB/s" (= 80 Mbit/s).
     */
    static double bitrate(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String raw = v.toString().trim().replace(" ", "");
        String s = raw;
        double mul = 1;
        boolean bytesPerS = s.endsWith("Bps") || s.endsWith("B/s");
        if (bytesPerS) { mul = 8; s = s.substring(0, s.length() - 3); }
        else {
            s = s.toLowerCase(Locale.ROOT);
            if (s.endsWith("bps")) s = s.substring(0, s.length() - 3);
            else if (s.endsWith("bit/s")) s = s.substring(0, s.length() - 5);
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("g")) { mul *= 1e9; s = s.substring(0, s.length() - 1); }
        else if (lower.endsWith("m")) { mul *= 1e6; s = s.substring(0, s.length() - 1); }
        else if (lower.endsWith("k")) { mul *= 1e3; s = s.substring(0, s.length() - 1); }
        try { return Double.parseDouble(s) * mul; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("invalid bit rate '" + raw + "' (use e.g. 250kbps, 100Mbps, 1Gbps, or 10MBps for bytes/s)"); }
    }

    /**
     * Size in bytes. Accepts a number (bytes) or "64B", "512KB", "1.5MB", "2GB". KB/MB/GB are binary multiples
     * (1024, 1024^2, 1024^3), as are the synonyms KiB/MiB/GiB.
     */
    static long bytes(Object v) {
        if (v instanceof Number n) return Math.round(n.doubleValue());
        String raw = v.toString().trim().replace(" ", "");
        String s = raw.toUpperCase(Locale.ROOT).replace("IB", "B");
        double mul = 1;
        if (s.endsWith("GB")) { mul = 1024.0 * 1024 * 1024; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("MB")) { mul = 1024.0 * 1024; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("KB")) { mul = 1024; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("B")) { s = s.substring(0, s.length() - 1); }
        try { return Math.round(Double.parseDouble(s) * mul); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("invalid size '" + raw + "' (use e.g. 64B, 512KB, 1.5MB, 2GB)"); }
    }
}
