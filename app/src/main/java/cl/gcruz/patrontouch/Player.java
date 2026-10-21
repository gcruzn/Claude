package cl.gcruz.patrontouch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproduce un Pattern con dispatchGesture.
 *
 * Cada dedo es UN stroke continuo (sin encadenar gestos, que es frágil). Los strokes de un mismo
 * bloque van juntos en un solo GestureDescription, así que el multi-touch es real y exacto.
 *
 * Android recorre cada stroke a velocidad constante a lo largo del trazo. Para respetar los
 * tiempos grabados (p. ej. mantener el joystick quieto 1,5 s), el trazo se re-muestrea cada
 * STEP_MS con longitud constante: donde el dedo va más lento se agrega un micro zigzag de pocos
 * píxeles, imperceptible para el juego, que "gasta" el tiempo sobrante.
 */
public class Player {

    public interface Listener {
        void onPlayerState(boolean playing, String status);

        /** Punto inofensivo donde inyectar un toque mínimo para soltar los dedos virtuales. */
        float[] releasePoint();
    }

    private static final long STEP_MS = 10;
    private static final float MIN_SPEED = 0.05f; // px/ms

    /**
     * 20-oct: Android dejó un dedo inyectado apretado para siempre (InputDispatcher: "Invalid DOWN
     * event - pointers already down for device -1") y desde ahí descartó TODO toque inyectado, del
     * patrón y hasta los de adb, hasta reiniciar. Pasaba al lanzar un gesto mientras otro seguía
     * corriendo (Android cancela el anterior): al cortar en bloques, al apoyar el dedo real, al
     * reanudar. Por eso ahora NUNCA se lanza un gesto con otro en curso: la vuelta se reproduce en
     * tramos de SLICE_MS encadenados con continueStroke (cada tramo sale cuando terminó el anterior)
     * y detener espera a que acabe el tramo en curso y levanta cada dedo con un UP limpio.
     */
    private static final long SLICE_MS = 100;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private Pattern pattern;
    private int repeats;      // 0 = infinito
    private long pauseMs;
    private int rep;
    private boolean playing;
    private int session;      // invalida callbacks viejos al detener
    private long repStartUptime;

    /** Hay un gesto nuestro en curso: no se puede lanzar otro sin cancelarlo. */
    private boolean inFlight;
    /** Dedos que siguen abajo al final del último tramo (stroke a continuar y punto donde quedó). */
    private final Map<Pattern.Touch, StrokeDescription> held = new LinkedHashMap<>();
    private final Map<Pattern.Touch, float[]> heldAt = new HashMap<>();
    /** Lo que espera a que termine el gesto en curso (levantar dedos, arrancar otra vuelta). */
    private final List<Runnable> afterFlight = new ArrayList<>();
    private long slice;       // inicio del tramo en curso, en ms de la vuelta

    public Player(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    public boolean isPlaying() { return playing; }

    public Pattern pattern() { return pattern; }

    /** ms transcurridos desde que partió la repetición actual (para dibujar los dedos virtuales). */
    public long elapsedInRep() { return SystemClock.uptimeMillis() - repStartUptime; }

    /** Si no es null, entrega el patrón de cada repetición siguiente (botón Random). */
    private java.util.function.Supplier<Pattern> next;

    public void start(Pattern p, int repeats, long pauseMs) {
        start(p, repeats, pauseMs, null);
    }

    public void start(Pattern p, int repeats, long pauseMs, java.util.function.Supplier<Pattern> next) {
        stop(null);
        if (p == null || p.touches.isEmpty() || p.duration <= 0) {
            listener.onPlayerState(false, "No hay patrón grabado");
            return;
        }
        this.next = next;
        this.pattern = p;
        this.repeats = repeats;
        this.pauseMs = pauseMs;
        this.rep = 0;
        this.playing = true;
        final int s = ++session;
        whenIdle(() -> beginRepetition(s));
    }

    public void stop(String reason) {
        stop(reason, true);
    }

    /** Igual que stop: los dedos virtuales siempre se levantan con un UP limpio (ver SLICE_MS). */
    public void stopByUserTouch() {
        stop(null, false);
    }

    private void stop(String reason, boolean announce) {
        boolean was = playing;
        playing = false;
        session++;
        handler.removeCallbacksAndMessages(null);
        afterFlight.clear();
        whenIdle(this::liftHeld);
        if (was || reason != null) listener.onPlayerState(false, reason == null ? "Detenido" : reason);
    }

    /** Los dedos ya se levantan al detener; queda por compatibilidad con Escape. */
    public void releaseNow() {
        if (!playing) whenIdle(this::liftHeld);
    }

    /** Corre r ahora si no hay gesto en curso, o apenas termine el que hay. */
    private void whenIdle(Runnable r) {
        if (inFlight) afterFlight.add(r);
        else r.run();
    }

    private void flightDone() {
        inFlight = false;
        List<Runnable> rs = new ArrayList<>(afterFlight);
        afterFlight.clear();
        for (Runnable r : rs) whenIdle(r);
    }

    /** Levanta (UP) cada dedo que quedó abajo, en el mismo punto donde estaba. */
    private void liftHeld() {
        if (held.isEmpty()) return;
        GestureDescription.Builder b = new GestureDescription.Builder();
        for (Map.Entry<Pattern.Touch, StrokeDescription> e : held.entrySet()) {
            float[] at = heldAt.get(e.getKey());
            Path path = new Path();
            path.moveTo(c(at[0]), c(at[1]));
            b.addStroke(e.getValue().continueStroke(path, 0, 1, false));
        }
        held.clear();
        heldAt.clear();
        send(b.build(), null);
    }

    private void send(GestureDescription g, Runnable onDone) {
        inFlight = true;
        boolean ok = service.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                flightDone();
                if (onDone != null) onDone.run();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                android.util.Log.w("PatronTouch", "gesto cancelado por Android");
                held.clear();
                heldAt.clear();
                flightDone();
                if (playing) stop("Interrumpido (un toque u otra app de accesibilidad cortó el gesto)");
            }
        }, handler);
        if (!ok) {
            inFlight = false;
            held.clear();
            heldAt.clear();
            if (playing) stop("Android rechazó el gesto");
        }
    }

    private void beginRepetition(final int s) {
        if (!playing || s != session) return;
        repStartUptime = SystemClock.uptimeMillis();
        listener.onPlayerState(true, "Repetición " + (rep + 1) + (repeats > 0 ? "/" + repeats : " (∞)")
                + (pattern.name != null ? " · " + pattern.name : ""));
        slice = 0;
        sendSlice(s);
    }

    /** Tramo [slice, slice + SLICE_MS) de la vuelta; al terminar sale el siguiente. */
    private void sendSlice(final int s) {
        if (!playing || s != session) return;
        long a = slice, e = a + SLICE_MS;
        if (a >= pattern.duration) {
            endRepetition(s);
            return;
        }
        GestureDescription.Builder b = new GestureDescription.Builder();
        Map<Pattern.Touch, StrokeDescription> nextHeld = new LinkedHashMap<>();
        Map<Pattern.Touch, float[]> nextAt = new HashMap<>();
        int n = 0;
        for (Pattern.Touch t : pattern.touches) {
            long td = t.down(), tu = Math.max(t.up(), td + 1);
            if (tu <= a || td >= e) continue;
            long sa = Math.max(td, a), se = Math.min(tu, e);
            boolean cont = tu > e;
            StrokeDescription prev = held.get(t);
            Pattern.Touch part = sub(t, sa, se);
            if (prev != null) {
                // Android solo acepta continuar si el tramo parte EXACTAMENTE (float) donde terminó.
                float[] h = heldAt.get(t);
                part.pts.set(0, new Pattern.Point(sa, h[0], h[1]));
            }
            long dur = Math.max(1, se - sa);
            Path path = timedPath(part);
            StrokeDescription sd = prev != null
                    ? prev.continueStroke(path, sa - a, dur, cont)
                    : new StrokeDescription(path, sa - a, dur, cont);
            b.addStroke(sd);
            n++;
            if (cont) {
                nextHeld.put(t, sd);
                nextAt.put(t, endPoint(path, dur));
            }
        }
        slice = e;
        held.clear();
        heldAt.clear();
        held.putAll(nextHeld);
        heldAt.putAll(nextAt);
        if (n == 0) {
            // Tramo vacío (una espera): se respeta el tiempo sin inyectar nada.
            handler.postAtTime(() -> sendSlice(s), repStartUptime + e);
            return;
        }
        send(b.build(), () -> sendSlice(s));
    }

    private void endRepetition(int s) {
        if (!playing || s != session) return;
        rep++;
        if (next != null && (repeats == 0 || rep < repeats)) {
            Pattern n = next.get();
            if (n != null && !n.touches.isEmpty() && n.duration > 0) pattern = n;
        }
        if (repeats == 0 || rep < repeats) {
            handler.postDelayed(() -> whenIdle(() -> beginRepetition(s)), Math.max(pauseMs, 15));
        } else {
            stop("Listo: " + rep + " repeticiones");
        }
    }

    /**
     * Punto donde Android deja el dedo al final del stroke, con su misma aritmética
     * (StrokeDescription.getPosition: largo/duración × duración sobre PathMeasure). Un punto
     * calculado de otra forma difiere en decimales y Android cancela la continuación (20-oct).
     */
    static float[] endPoint(Path path, long dur) {
        android.graphics.PathMeasure pm = new android.graphics.PathMeasure(path, false);
        float len = 0;
        do {
            len += pm.getLength();
        } while (pm.nextContour());
        float[] pos = new float[2];
        if (len == 0) {
            pm.setPath(path, false);
            pm.getPosTan(0, pos, null);
            return pos;
        }
        float conv = len / dur;
        pm.setPath(path, false);
        pm.getPosTan(dur * conv, pos, null);
        return pos;
    }

    /** El toque t recortado a [a, e], con extremos interpolados exactos (continueStroke exige continuidad). */
    static Pattern.Touch sub(Pattern.Touch t, long a, long e) {
        Pattern.Touch r = new Pattern.Touch();
        float[] p0 = t.at(a), p1 = t.at(e);
        r.pts.add(new Pattern.Point(a, p0[0], p0[1]));
        for (Pattern.Point q : t.pts) if (q.t > a && q.t < e) r.pts.add(q);
        r.pts.add(new Pattern.Point(e, p1[0], p1[1]));
        return r;
    }

    /** Trazo de longitud proporcional al tiempo, para que la velocidad constante de Android respete los tiempos. */
    static Path timedPath(Pattern.Touch t) {
        long down = t.down(), up = t.up();
        List<float[]> pts = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (long time = down; time < up; time += STEP_MS) {
            pts.add(t.at(time));
            times.add(time);
        }
        pts.add(t.at(up));
        times.add(up);

        float speed = MIN_SPEED;
        for (int i = 1; i < pts.size(); i++) {
            long dt = times.get(i) - times.get(i - 1);
            if (dt <= 0) continue;
            speed = Math.max(speed, dist(pts.get(i - 1), pts.get(i)) / dt);
        }

        Path path = new Path();
        float[] p0 = pts.get(0);
        path.moveTo(c(p0[0]), c(p0[1]));
        int side = 1;
        for (int i = 1; i < pts.size(); i++) {
            float[] a = pts.get(i - 1), b = pts.get(i);
            long dt = times.get(i) - times.get(i - 1);
            float need = speed * dt;
            float d = dist(a, b);
            if (d + 0.01f < need) {
                float dx = b[0] - a[0], dy = b[1] - a[1];
                float nx, ny;
                if (d > 0.001f) {
                    nx = -dy / d;
                    ny = dx / d;
                } else {
                    nx = 1f;
                    ny = 0f;
                }
                float h = (float) Math.sqrt(Math.max(0, (need / 2) * (need / 2) - (d / 2) * (d / 2)));
                float mx = (a[0] + b[0]) / 2 + nx * h * side;
                float my = (a[1] + b[1]) / 2 + ny * h * side;
                side = -side;
                path.lineTo(c(mx), c(my));
            }
            path.lineTo(c(b[0]), c(b[1]));
        }
        return path;
    }

    private static float dist(float[] a, float[] b) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float c(float v) { return Math.max(0f, v); }
}
