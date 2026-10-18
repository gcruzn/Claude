package cl.gcruz.patrontouch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * Reproduce un Pattern con dispatchGesture. El patrón se corta en tramos cortos (SLICE ms);
 * cada tramo es un GestureDescription con un stroke por dedo activo, todos en paralelo
 * (multi-touch real). Un dedo que sigue abajo al final del tramo se encadena al siguiente con
 * continueStroke, así el joystick se mantiene presionado sin levantar el dedo.
 */
public class Player {

    public interface Listener {
        void onPlayerState(boolean playing, String status);
    }

    private static final long SLICE_MS = 120;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private Pattern pattern;
    private int repeats;      // 0 = infinito
    private long pauseMs;
    private int rep;
    private long sliceStart;
    private Map<Pattern.Touch, StrokeDescription> pending = new HashMap<>();
    private boolean playing;
    private int session;      // invalida callbacks viejos al detener

    public Player(AccessibilityService service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    private long repStartUptime;

    public boolean isPlaying() { return playing; }

    public Pattern pattern() { return pattern; }

    /** ms transcurridos desde que partió la repetición actual (para dibujar los dedos virtuales). */
    public long elapsedInRep() { return android.os.SystemClock.uptimeMillis() - repStartUptime; }

    public void start(Pattern p, int repeats, long pauseMs) {
        stop(null);
        if (p == null || p.touches.isEmpty() || p.duration <= 0) {
            listener.onPlayerState(false, "No hay patrón grabado");
            return;
        }
        this.pattern = p;
        this.repeats = repeats;
        this.pauseMs = pauseMs;
        this.rep = 0;
        this.playing = true;
        session++;
        beginRepetition(session);
    }

    public void stop(String reason) {
        boolean was = playing;
        playing = false;
        session++;
        handler.removeCallbacksAndMessages(null);
        pending.clear();
        if (was || reason != null) listener.onPlayerState(false, reason == null ? "Detenido" : reason);
    }

    private void beginRepetition(int s) {
        if (!playing || s != session) return;
        sliceStart = 0;
        pending = new HashMap<>();
        repStartUptime = android.os.SystemClock.uptimeMillis();
        listener.onPlayerState(true, "Repetición " + (rep + 1) + (repeats > 0 ? "/" + repeats : " (∞)"));
        nextSlice(s);
    }

    private void nextSlice(final int s) {
        if (!playing || s != session) return;

        if (sliceStart >= pattern.duration) {
            rep++;
            if (repeats == 0 || rep < repeats) {
                handler.postDelayed(() -> beginRepetition(s), Math.max(pauseMs, 15));
            } else {
                stop("Listo: " + rep + " repeticiones");
            }
            return;
        }

        long s0 = sliceStart;
        long s1 = Math.min(s0 + SLICE_MS, pattern.duration);
        long sliceLen = s1 - s0;

        GestureDescription.Builder builder = new GestureDescription.Builder();
        Map<Pattern.Touch, StrokeDescription> next = new HashMap<>();
        int strokes = 0;
        int maxStrokes = GestureDescription.getMaxStrokeCount();

        for (Pattern.Touch t : pattern.touches) {
            long down = t.down(), up = t.up();
            if (up <= s0 || down >= s1) continue;
            long a = Math.max(down, s0);
            long e = Math.min(up, s1);
            if (e <= a) continue;
            if (strokes >= maxStrokes) break;

            boolean willContinue = up > s1;
            Path path = t.pathBetween(a, e);
            StrokeDescription prev = pending.get(t);
            StrokeDescription sd;
            try {
                if (prev != null) {
                    sd = prev.continueStroke(path, a - s0, e - a, willContinue);
                } else {
                    sd = new StrokeDescription(path, a - s0, e - a, willContinue);
                }
            } catch (IllegalArgumentException ex) {
                continue;
            }
            builder.addStroke(sd);
            strokes++;
            if (willContinue) next.put(t, sd);
        }

        pending = next;
        sliceStart = s1;

        if (strokes == 0) {
            // Tramo sin dedos abajo: solo esperar.
            handler.postDelayed(() -> nextSlice(s), sliceLen);
            return;
        }

        boolean ok = service.dispatchGesture(builder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (s == session) handler.post(() -> nextSlice(s));
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (s == session) handler.post(() -> stop("Interrumpido (tocaste la pantalla u otra app tomó el control)"));
            }
        }, handler);

        if (!ok) stop("Android rechazó el gesto");
    }
}
