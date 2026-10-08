package org.iotloopsim.model;

import java.util.List;
import java.util.SplittableRandom;
import org.iotloopsim.core.Component;
import org.iotloopsim.core.Distribution;
import org.iotloopsim.core.SimTime;

/** Position of a device over time. Implementations are components attached to devices. */
public interface MobilityModel extends Component {
    /**
     * Returns the position (m) at simulation time {@code time} (ns); implementations may advance internal state
     * and expect non-decreasing times.
     */
    Position positionAt(long time);

    /** Random waypoint within a rectangle: move at a sampled speed to a random point, pause, repeat. */
    final class RandomWaypoint implements MobilityModel {
        private final double x0, y0, x1, y1;
        private final Distribution speed, pauseS;
        private final SplittableRandom rng;
        private Position from, to;
        private long legStart, legEnd, pauseEnd;

        /**
         * Creates a random-waypoint model starting at {@code start} inside the rectangle [x0,x1] by [y0,y1] (m);
         * {@code speedMps} is sampled per leg in m/s (minimum 0.1) and {@code pauseS} in s.
         */
        public RandomWaypoint(Position start, double x0, double y0, double x1, double y1, Distribution speedMps, Distribution pauseS, SplittableRandom rng) {
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.speed = speedMps; this.pauseS = pauseS; this.rng = rng;
            this.from = start; this.to = start;
        }

        @Override public Position positionAt(long t) {
            while (t >= pauseEnd) newLeg();
            if (t <= legStart) return from;
            if (t >= legEnd) return to;
            double f = (t - legStart) / (double) (legEnd - legStart);
            return new Position(from.x() + (to.x() - from.x()) * f, from.y() + (to.y() - from.y()) * f);
        }

        private void newLeg() {
            from = to;
            to = new Position(x0 + (x1 - x0) * rng.nextDouble(), y0 + (y1 - y0) * rng.nextDouble());
            double v = Math.max(0.1, speed.sample(rng));
            legStart = pauseEnd;
            legEnd = legStart + SimTime.s(from.distance(to) / v);
            pauseEnd = legEnd + SimTime.s(pauseS.sample(rng));
            if (pauseEnd <= legStart) pauseEnd = legStart + SimTime.S;   // degenerate area and no pause: still advance time
        }
    }

    /** Replays a trace of (time, x, y) points with linear interpolation (e.g. exported from SUMO). */
    final class Trace implements MobilityModel {
        /** One trace sample: time {@code t} in ns and coordinates {@code x}, {@code y} in m. */
        public record Point(long t, double x, double y) {}
        private final List<Point> pts;
        /**
         * Creates a trace model; {@code pts} must be sorted by time. Before the first point and after the last the
         * position is held constant; an empty trace yields (0, 0).
         */
        public Trace(List<Point> pts) { this.pts = pts; }
        @Override public Position positionAt(long t) {
            if (pts.isEmpty()) return new Position(0, 0);
            if (t <= pts.get(0).t) return new Position(pts.get(0).x, pts.get(0).y);
            for (int i = 1; i < pts.size(); i++) {
                Point a = pts.get(i - 1), b = pts.get(i);
                if (t <= b.t) {
                    double f = (t - a.t) / (double) Math.max(1, b.t - a.t);
                    return new Position(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
                }
            }
            Point l = pts.get(pts.size() - 1);
            return new Position(l.x, l.y);
        }
    }
}
