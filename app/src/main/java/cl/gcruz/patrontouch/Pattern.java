package cl.gcruz.patrontouch;

import android.graphics.Path;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Un patrón grabado: varios "toques" (cada uno = un dedo desde que baja hasta que sube),
 * que pueden superponerse en el tiempo (multi-touch). Tiempos en ms desde el inicio del patrón,
 * coordenadas en píxeles de pantalla.
 */
public class Pattern {

    public static class Point {
        public final long t;
        public final float x, y;

        public Point(long t, float x, float y) {
            this.t = t;
            this.x = x;
            this.y = y;
        }
    }

    public static class Touch {
        public final List<Point> pts = new ArrayList<>();

        public long down() { return pts.get(0).t; }

        public long up() { return pts.get(pts.size() - 1).t; }

        /** Posición interpolada en el instante t (acotado al rango del toque). */
        public float[] at(long t) {
            if (t <= down()) return new float[]{pts.get(0).x, pts.get(0).y};
            if (t >= up()) {
                Point p = pts.get(pts.size() - 1);
                return new float[]{p.x, p.y};
            }
            for (int i = 1; i < pts.size(); i++) {
                Point b = pts.get(i);
                if (b.t >= t) {
                    Point a = pts.get(i - 1);
                    long span = b.t - a.t;
                    float f = span <= 0 ? 1f : (float) (t - a.t) / span;
                    return new float[]{a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f};
                }
            }
            Point p = pts.get(pts.size() - 1);
            return new float[]{p.x, p.y};
        }

        /** Trazo entre los instantes a y e, empezando y terminando en los puntos interpolados exactos. */
        public Path pathBetween(long a, long e) {
            Path path = new Path();
            float[] p0 = at(a);
            path.moveTo(clamp(p0[0]), clamp(p0[1]));
            for (Point p : pts) {
                if (p.t > a && p.t < e) path.lineTo(clamp(p.x), clamp(p.y));
            }
            float[] p1 = at(e);
            path.lineTo(clamp(p1[0]), clamp(p1[1]));
            return path;
        }

        private static float clamp(float v) { return Math.max(0f, v); }
    }

    public String name;
    public final List<Touch> touches = new ArrayList<>();
    public long duration;
    /** Tamaño de pantalla y orientación al grabar, para avisar si se reproduce distinto. */
    public int screenW, screenH;

    /** Normaliza tras grabar: arranca en t=0, toques instantáneos duran al menos 40 ms. */
    public void normalize() {
        touches.removeIf(t -> t.pts.isEmpty());
        if (touches.isEmpty()) {
            duration = 0;
            return;
        }
        long t0 = Long.MAX_VALUE;
        for (Touch t : touches) t0 = Math.min(t0, t.down());
        long end = 0;
        for (Touch t : touches) {
            List<Point> shifted = new ArrayList<>();
            for (Point p : t.pts) shifted.add(new Point(p.t - t0, p.x, p.y));
            t.pts.clear();
            t.pts.addAll(shifted);
            if (t.up() - t.down() < 40) {
                Point last = t.pts.get(t.pts.size() - 1);
                t.pts.add(new Point(t.down() + 40, last.x, last.y));
            }
            end = Math.max(end, t.up());
        }
        touches.sort((a, b) -> Long.compare(a.down(), b.down()));
        duration = end;
    }

    public int maxSimultaneous() {
        int max = 0;
        for (Touch a : touches) {
            int n = 0;
            for (Touch b : touches) if (b.down() <= a.down() && b.up() > a.down()) n++;
            max = Math.max(max, n);
        }
        return max;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("duration", duration);
        o.put("w", screenW);
        o.put("h", screenH);
        JSONArray ts = new JSONArray();
        for (Touch t : touches) {
            JSONArray ps = new JSONArray();
            for (Point p : t.pts) {
                ps.put(p.t);
                ps.put(Math.round(p.x * 10) / 10.0);
                ps.put(Math.round(p.y * 10) / 10.0);
            }
            ts.put(ps);
        }
        o.put("touches", ts);
        return o;
    }

    public static Pattern fromJson(JSONObject o) throws JSONException {
        Pattern p = new Pattern();
        p.name = o.optString("name", "Patrón");
        p.duration = o.getLong("duration");
        p.screenW = o.optInt("w");
        p.screenH = o.optInt("h");
        JSONArray ts = o.getJSONArray("touches");
        for (int i = 0; i < ts.length(); i++) {
            JSONArray ps = ts.getJSONArray(i);
            Touch t = new Touch();
            for (int j = 0; j + 2 < ps.length(); j += 3) {
                t.pts.add(new Point(ps.getLong(j), (float) ps.getDouble(j + 1), (float) ps.getDouble(j + 2)));
            }
            if (!t.pts.isEmpty()) p.touches.add(t);
        }
        return p;
    }
}
