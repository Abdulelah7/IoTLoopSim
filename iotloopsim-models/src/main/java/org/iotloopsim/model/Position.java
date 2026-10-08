package org.iotloopsim.model;

/** Planar position in metres. */
public record Position(double x, double y) {
    /** Returns the Euclidean distance to {@code o} in m. */
    public double distance(Position o) { return Math.hypot(x - o.x, y - o.y); }
    /**
     * Returns the point {@code metres} m from this position towards {@code target}, or {@code target} itself if it
     * is closer than that.
     */
    public Position towards(Position target, double metres) {
        double d = distance(target);
        if (d <= metres || d == 0) return target;
        double f = metres / d;
        return new Position(x + (target.x - x) * f, y + (target.y - y) * f);
    }
}
