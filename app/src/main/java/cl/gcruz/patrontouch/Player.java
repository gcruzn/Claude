package cl.gcruz.patrontouch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

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

    private static class Chunk {
        long start, end;
        final List<Pattern.Touch> touches = new ArrayList<>();
    }

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private Pattern pattern;
    private List<Chunk> chunks;
    private int repeats;      // 0 = infinito
    private long pauseMs;
    private int rep;
    private boolean playing;
    private int session;      // invalida callbacks viejos al detener
    private int gestureSeq;   // id del último gesto despachado
    private long repStartUptime;

    public Player(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    public boolean isPlaying() { return playing; }

    public Pattern pattern() { return pattern; }

    /** ms transcurridos desde que partió la repetición actual (para dibujar los dedos virtuales). */
    public long elapsedInRep() { return SystemClock.uptimeMillis() - repStartUptime; }

    public void start(Pattern p, int repeats, long pauseMs) {
        stop(null);
        if (p == null || p.touches.isEmpty() || p.duration <= 0) {
            listener.onPlayerState(false, "No hay patrón grabado");
            return;
        }
        this.pattern = p;
        this.chunks = buildChunks(p);
        this.repeats = repeats;
        this.pauseMs = pauseMs;
        this.rep = 0;
        this.playing = true;
        session++;
        beginRepetition(session);
    }

    public void stop(String reason) {
        stop(reason, true);
    }

    /**
     * Detiene sin inyectar el toque de liberación. Se usa cuando el propio dedo del usuario ya
     * está en la pantalla (Android cancela los gestos inyectados ante cualquier toque real) y un
     * toque inyectado podría cancelar el toque real sobre el panel.
     */
    public void stopByUserTouch() {
        stop(null, false);
    }

    private void stop(String reason, boolean release) {
        boolean was = playing;
        playing = false;
        session++;
        handler.removeCallbacksAndMessages(null);
        if (was && release) releaseFingers();
        if (was || reason != null) listener.onPlayerState(false, reason == null ? "Detenido" : reason);
    }

    /** Un gesto nuevo cancela el anterior y suelta cualquier dedo virtual que siga abajo. */
    private void releaseFingers() {
        try {
            float[] pt = listener.releasePoint();
            Path path = new Path();
            path.moveTo(Math.max(0, pt[0]), Math.max(0, pt[1]));
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new StrokeDescription(path, 0, 1))
                    .build();
            gestureSeq++;
            service.dispatchGesture(g, null, null);
        } catch (Exception ignored) {
        }
    }

    private void beginRepetition(final int s) {
        if (!playing || s != session) return;
        repStartUptime = SystemClock.uptimeMillis();
        listener.onPlayerState(true, "Repetición " + (rep + 1) + (repeats > 0 ? "/" + repeats : " (∞)"));
        for (Chunk c : chunks) {
            handler.postAtTime(() -> dispatchChunk(s, c), repStartUptime + c.start);
        }
        handler.postAtTime(() -> endRepetition(s), repStartUptime + pattern.duration + 30);
    }

    private void endRepetition(int s) {
        if (!playing || s != session) return;
        rep++;
        if (repeats == 0 || rep < repeats) {
            handler.postDelayed(() -> beginRepetition(s), Math.max(pauseMs, 15));
        } else {
            stop("Listo: " + rep + " repeticiones");
        }
    }

    private void dispatchChunk(final int s, Chunk c) {
        if (!playing || s != session) return;
        GestureDescription.Builder b = new GestureDescription.Builder();
        int n = 0;
        for (Pattern.Touch t : c.touches) {
            long dur = Math.max(1, t.up() - t.down());
            try {
                b.addStroke(new StrokeDescription(timedPath(t), t.down() - c.start, dur));
                n++;
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (n == 0) return;
        final int id = ++gestureSeq;
        boolean ok = service.dispatchGesture(b.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCancelled(GestureDescription g) {
                android.util.Log.w("PatronTouch", "gesto " + id + " cancelado (" + (s == session && id == gestureSeq ? "vigente" : "reemplazado") + ")");
                // Solo cuenta si es el gesto vigente (uno nuevo nuestro también "cancela" el anterior).
                if (s == session && id == gestureSeq) {
                    handler.post(() -> stop("Interrumpido (un toque u otra app de accesibilidad cortó el gesto)"));
                }
            }
        }, handler);
        if (!ok) stop("Android rechazó el gesto");
    }

    /**
     * Agrupa los toques en bloques que no se solapan en el tiempo y caben en un gesto
     * (máx. strokes y duración de Android).
     */
    private static List<Chunk> buildChunks(Pattern p) {
        int maxStrokes = GestureDescription.getMaxStrokeCount();
        long maxDur = GestureDescription.getMaxGestureDuration() - 500;
        List<Pattern.Touch> sorted = new ArrayList<>(p.touches);
        sorted.sort((a, b) -> Long.compare(a.down(), b.down()));

        List<Chunk> out = new ArrayList<>();
        Chunk cur = null;
        for (Pattern.Touch t : sorted) {
            boolean overlaps = cur != null && t.down() < cur.end;
            boolean fits = cur != null && cur.touches.size() < maxStrokes
                    && Math.max(cur.end, t.up()) - cur.start <= maxDur;
            boolean merge = cur != null && fits && (overlaps || Math.max(cur.end, t.up()) - cur.start <= maxDur);
            if (merge) {
                cur.touches.add(t);
                cur.end = Math.max(cur.end, t.up());
            } else {
                cur = new Chunk();
                cur.start = t.down();
                cur.end = t.up();
                cur.touches.add(t);
                out.add(cur);
            }
        }
        return out;
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
