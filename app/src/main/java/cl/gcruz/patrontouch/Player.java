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

    /**
     * 20-oct: Android dejó un dedo inyectado apretado para siempre (InputDispatcher: "Invalid DOWN
     * event - pointers already down for device -1") y desde ahí descartó TODO toque inyectado, del
     * patrón y hasta los de adb, hasta reiniciar. Pasaba al lanzar un gesto mientras otro seguía
     * corriendo (Android cancela el anterior): al cortar en bloques, al apoyar el dedo real, al
     * reanudar. Por eso ahora NUNCA se lanza un gesto con otro en curso: la vuelta se reproduce en
     * tramos de SLICE_MS, cada uno un gesto completo que sale cuando terminó el anterior, y detener
     * espera a que acabe el tramo en curso (todo dedo termina en UP).
     */
    /** Largo de cada tramo; ajustable por ADB («tune slice») para medir. */
    // 21-oct: tramos cortos (100–150 ms) re-apoyaban el joystick tan seguido que el movimiento
    // salía a saltos y las formas no servían (Gonzalo). Ahora un tramo = la vuelta entera; soltar
    // rápido al tocar lo da el corte seguro de stopByUserTouch, no el largo del tramo.
    // 22-oct: con un solo gesto por vuelta, si el S Pen se acerca (Android bloquea los toques
    // inyectados) el juego pierde el apoyo del joystick y no lo retoma hasta la vuelta siguiente
    // (~4 s). Re-apoyar cada 0,5 s: retoma en ≤ 0,5 s sin los saltos de los tramos de 0,15 s.
    static volatile long SLICE_MS = 500;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Aparte: stop() vacía `handler` y no debe perder el aviso de fin de un gesto en curso. */
    private final Handler gestureHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private Pattern pattern;
    private int repeats;      // 0 = infinito
    private long pauseMs;
    private int rep;
    private boolean playing;
    private int session;      // invalida callbacks viejos al detener
    private long repStartUptime;

    /** Gestos nuestros en curso: mientras haya alguno, lanzar otro lo cancela. */
    private int inFlight;
    /** Toques cortos del tramo en curso ([desde, hasta] en ms del tramo) y cuándo salió. */
    private List<long[]> sliceShorts = new ArrayList<>();
    private long sliceSentAt;
    /** Lo que espera a que termine el gesto en curso (arrancar otra vuelta). */
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

    /**
     * El dedo o el lápiz real tocó. Cortar el gesto en curso suelta el joystick al instante, que es
     * lo que permite que el juego tome el dedo de Gonzalo (v4.8.1, v5.0.6: «funciona mejor»). Pero
     * con el dedo real abajo Android rechazó la cancelación cuando había DOS dedos inyectados
     * abajo o el joystick no era el dedo 0 («Got ACTION_CANCEL, but the pointers don't match»,
     * v5.0.6) y el dedo inyectado quedaba trabado hasta reiniciar. Por eso solo se corta si en
     * este instante no hay ningún toque corto (disparo, habilidad) abajo; si lo hay, el tramo
     * termina solo (≤ SLICE_MS).
     */
    public void stopByUserTouch() {
        long now = SystemClock.uptimeMillis() - sliceSentAt;
        boolean shortDown = false;
        for (long[] w : sliceShorts) if (now >= w[0] - 15 && now <= w[1] + 15) shortDown = true;
        boolean cut = inFlight > 0 && !shortDown;
        stop(null, false);
        if (cut) {
            expectCancel = true;   // la cancelación es nuestra: no registrarla como un corte de Android
            float[] pt = listener.releasePoint();
            Path path = new Path();
            path.moveTo(Math.max(0, pt[0]), Math.max(0, pt[1]));
            send(new GestureDescription.Builder().addStroke(new StrokeDescription(path, 0, 1)).build(), null);
        }
        android.util.Log.i("PatronTouch", cut ? "corte inmediato (solo joystick abajo)" : "sin corte: espera el tramo");
    }

    private void stop(String reason, boolean byUser) {
        boolean was = playing;
        playing = false;
        session++;
        handler.removeCallbacksAndMessages(null);
        afterFlight.clear();
        if (was || reason != null) listener.onPlayerState(false, reason == null ? "Detenido" : reason);
    }

    /** Nada que soltar: cada tramo termina con todos sus dedos arriba. Queda por compatibilidad con Escape. */
    public void releaseNow() {
    }

    /** Corre r ahora si no hay gesto en curso, o apenas termine el que hay. */
    private void whenIdle(Runnable r) {
        if (inFlight > 0) afterFlight.add(r);
        else r.run();
    }

    private void flightDone() {
        inFlight = Math.max(0, inFlight - 1);
        if (inFlight > 0) return;
        List<Runnable> rs = new ArrayList<>(afterFlight);
        afterFlight.clear();
        for (Runnable r : rs) whenIdle(r);
    }

    private void send(GestureDescription g, Runnable onDone) {
        inFlight++;
        boolean ok = service.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                flightDone();
                if (onDone != null) onDone.run();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                if (expectCancel) {   // lo cortamos nosotros (onBlocked)
                    expectCancel = false;
                    flightDone();
                    return;
                }
                android.util.Log.w("PatronTouch", "gesto cancelado por Android");
                flightDone();
                if (playing) stop("Interrumpido (un toque u otra app de accesibilidad cortó el gesto)");
            }
        }, gestureHandler);
        if (!ok) {
            inFlight = Math.max(0, inFlight - 1);
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

    /**
     * Tramo [slice, slice + SLICE_MS) de la vuelta; al terminar sale el siguiente. Cada tramo es un
     * gesto completo: todo dedo termina en UP. continueStroke no sirvió: Android cancelaba cada
     * continuación en el S26 (v5.0.0 y v5.0.1). El dedo largo (joystick) que cruza el borde vuelve a
     * apoyar en su punto inicial y en REPRESS_MS retoma su posición, como al empezar un movimiento;
     * los toques cortos que cruzan el borde no se parten: el tramo se alarga hasta que terminen.
     */
    private volatile boolean restartLap;
    private Pattern pending;

    /**
     * Cambia YA al patrón `p` (botón Auto: un disparo o el súper decididos por lo que se ve en
     * pantalla). Corta el gesto en curso solo si en este instante no hay un toque corto abajo (la
     * única cancelación que Android acepta sin trabar el dedo inyectado); si lo hay, reintenta en
     * 30 ms. Sin nada sonando, simplemente arranca.
     */
    public void replaceNow(Pattern p, java.util.function.Supplier<Pattern> nextSupplier) {
        if (p == null || p.touches.isEmpty()) return;
        if (!playing) {
            start(p, 0, 0, nextSupplier);
            return;
        }
        long now = SystemClock.uptimeMillis() - sliceSentAt;
        for (long[] w : sliceShorts) {
            if (now >= w[0] - 15 && now <= w[1] + 15) {
                handler.postDelayed(() -> replaceNow(p, nextSupplier), 30);
                return;
            }
        }
        next = nextSupplier;
        pending = p;
        restartLap = true;
        final int s = session;
        if (inFlight > 0) {
            expectCancel = true;
            float[] pt = listener.releasePoint();
            Path path = new Path();
            path.moveTo(Math.max(0, pt[0]), Math.max(0, pt[1]));
            send(new GestureDescription.Builder().addStroke(new StrokeDescription(path, 0, 1)).build(), null);
        }
        whenIdle(() -> sendSlice(s));
    }

    /**
     * Rehace la vuelta en el próximo tramo (sin cortar el gesto en curso), con el patrón que entregue
     * `next` (se recompila: toma la última dirección nueva). 22-oct: antes la dirección nueva del
     * lápiz recién se aplicaba en la vuelta siguiente (hasta ~3 s).
     */
    public void restartLapSoon() {
        if (playing && next != null) restartLap = true;
    }

    private void sendSlice(final int s) {
        if (!playing || s != session) return;
        if (restartLap) {
            restartLap = false;
            Pattern n = pending != null ? pending : next.get();
            pending = null;
            if (n != null && !n.touches.isEmpty() && n.duration > 0) pattern = n;
            beginRepetition(s);
            return;
        }
        long a = slice;
        if (a >= pattern.duration) {
            endRepetition(s);
            return;
        }
        // 22-oct, Auto Clicker de Gonzalo (medido: toques de 10 ms, uno por gesto, cada ~110 ms):
        // con el S Pen cerca Android bloquea lo inyectado y se pierde el gesto entero; con gestos
        // independientes se retoma en el siguiente. Sin joystick, cada toque es su propio gesto.
        boolean hasLong = false;
        for (Pattern.Touch t : pattern.touches) if (t.up() - t.down() > SHORT_TOUCH_MS) hasLong = true;
        long len = hasLong ? jointSlice() : 1;
        long e = a + len;
        // Los toques cortos (disparos, súper apuntado: hasta SHORT_TOUCH_MS) no se parten: si cruzan
        // el borde, o empiezan hasta BORDER_GAP_MS después de él, el tramo se alarga hasta que terminen. 20-oct, video de Corta: si el
        // joystick se vuelve a apoyar en el mismo instante en que sale un disparo, el juego ignora
        // el joystick hasta el tramo siguiente.
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Pattern.Touch t : pattern.touches) {
                long td = t.down(), tu = Math.max(t.up(), td + 1);
                if (tu - td <= SHORT_TOUCH_MS && td < e + BORDER_GAP_MS && tu > e && td >= a) {
                    e = tu;
                    grew = true;
                }
            }
        }
        // Android admite getMaxStrokeCount() dedos por gesto (20 en el S26): si hay más, el tramo
        // termina antes del primero que no cabe (el joystick se re-apoya ahí una vez).
        int max = GestureDescription.getMaxStrokeCount();
        List<Long> starts = new ArrayList<>();
        for (Pattern.Touch t : pattern.touches) {
            long td = t.down(), tu = Math.max(t.up(), td + 1);
            if (tu > a && td < e) starts.add(Math.max(td, a));
        }
        if (starts.size() > max) {
            java.util.Collections.sort(starts);
            e = Math.max(a + 1, starts.get(max) - BORDER_GAP_MS);
            for (boolean moved = true; moved; ) {   // y sin partir un toque corto
                moved = false;
                for (Pattern.Touch t : pattern.touches) {
                    long td = t.down(), tu = Math.max(t.up(), td + 1);
                    if (tu - td <= SHORT_TOUCH_MS && td < e && tu > e && td > a) {
                        e = td;
                        moved = true;
                    }
                }
            }
        }
        GestureDescription.Builder b = new GestureDescription.Builder();
        int n = 0;
        // El joystick (toque largo) va primero: así es el dedo 0. Cortarlo con solo él abajo es lo
        // único que Android acepta con el dedo real en pantalla (ver stopByUserTouch).
        List<Pattern.Touch> order = new ArrayList<>();
        for (Pattern.Touch t : pattern.touches) if (t.up() - t.down() > SHORT_TOUCH_MS) order.add(t);
        for (Pattern.Touch t : pattern.touches) if (t.up() - t.down() <= SHORT_TOUCH_MS) order.add(t);
        List<long[]> shorts = new ArrayList<>();
        for (Pattern.Touch t : order) {
            long td = t.down(), tu = Math.max(t.up(), td + 1);
            if (tu <= a || td >= e) continue;
            long sa = Math.max(td, a), se = Math.min(tu, e);
            // El dedo largo (joystick) nunca pasa de a + SLICE_MS aunque el tramo se alargue por un
            // toque corto: 21-oct, con gadget/híper/súper el tramo se estiraba, el joystick virtual
            // seguía apretado y el arrastre de Gonzalo a veces no tomaba.
            if (tu - td > SHORT_TOUCH_MS) se = Math.min(se, a + len);
            if (se <= sa) continue;
            Pattern.Touch part = td < a ? repress(t, sa, se) : sub(t, sa, se);
            try {
                b.addStroke(new StrokeDescription(timedPath(part), sa - a, Math.max(1, se - sa)));
                n++;
                if (tu - td <= SHORT_TOUCH_MS) shorts.add(new long[]{sa - a, se - a});
            } catch (IllegalArgumentException ignored) {
            }
        }
        sliceShorts = shorts;
        sliceSentAt = SystemClock.uptimeMillis();
        slice = e;
        final long next = e;
        if (n == 0) {
            // Tramo vacío (una espera): se salta al próximo toque sin inyectar nada.
            long at = pattern.duration;
            for (Pattern.Touch t : pattern.touches) if (t.down() >= next && t.down() < at) at = t.down();
            slice = Math.max(next, at);
            handler.postAtTime(() -> sendSlice(s), repStartUptime + slice);
            return;
        }
        send(b.build(), () -> sendSlice(s));
    }

    /**
     * Largo del tramo con joystick. Medido 22-oct: re-apoyar cada 0,11 s deja al brawler ~45 % más
     * lento (cada re-apoyo pierde ~45 ms de avance), así que no se re-apoya seguido por las dudas.
     * Con la lectura de pantalla activa: si la perilla del joystick inyectado se ve, tramos de
     * LONG_SLICE_MS (~7 % menos velocidad); si no se ve, Android lo está bloqueando (S Pen cerca)
     * y se prueba con tramos de PROBE_SLICE_MS hasta que vuelva a entrar. Sin captura, SLICE_MS.
     */
    private long jointSlice() {
        if (PenShizuku.active()) return LONG_SLICE_MS;   // el lápiz se atiende por sus eventos: sin re-apoyos
        if (!CaptureService.isRunning()) return SLICE_MS;
        if (probing) {
            if (!CaptureService.knobVisible(150)) return PROBE_SLICE_MS;
            probing = false;
            android.util.Log.i("PatronTouch", "retomado: tramos largos");
            return LONG_SLICE_MS;
        }
        if (SystemClock.uptimeMillis() - repStartUptime < 300) return LONG_SLICE_MS; // la perilla aún no aparece
        if (CaptureService.knobVisible(150)) return LONG_SLICE_MS;
        probing = true;
        android.util.Log.i("PatronTouch", "tramos cortos: no se ve la perilla del patrón");
        return PROBE_SLICE_MS;
    }

    private boolean probing;
    private boolean expectCancel;

    /**
     * La captura dejó de ver la perilla del patrón: Android lo está bloqueando (S Pen cerca). Se corta
     * el gesto en curso YA (solo si en este instante está abajo únicamente el joystick, la única
     * cancelación que Android acepta sin trabar el dedo inyectado, ver stopByUserTouch) y se pasa a
     * tramos de PROBE_SLICE_MS hasta que la perilla vuelva a verse. 22-oct: Samsung no admite
     * continueStroke (conttest: el tramo 1 siempre cancelado), así que la fluidez exige un gesto por
     * vuelta y la retomada rápida exige este corte.
     */
    public void onBlocked() {
        if (!playing || inFlight == 0 || probing) return;
        long now = SystemClock.uptimeMillis() - sliceSentAt;
        for (long[] w : sliceShorts) if (now >= w[0] - 15 && now <= w[1] + 15) return; // al terminar el tramo
        probing = true;
        android.util.Log.i("PatronTouch", "bloqueado (lápiz): corte y tramos cortos");
        expectCancel = true;
        float[] pt = listener.releasePoint();
        Path path = new Path();
        path.moveTo(Math.max(0, pt[0]), Math.max(0, pt[1]));
        restartLap = true;
        final int s = session;
        send(new GestureDescription.Builder().addStroke(new StrokeDescription(path, 0, 1)).build(), null);
        whenIdle(() -> sendSlice(s));
    }

    // 22-oct (v6.0): tramo largo = la vuelta entera (sin re-apoyos: cada uno frenaba al brawler; la
    // captura mostró «perilla suelta» cada 0,6 s). El lápiz se atiende con onBlocked.
    static volatile long LONG_SLICE_MS = 60_000, PROBE_SLICE_MS = 100;

    /** ms del centro del joystick a su posición al re-apoyar; ajustable por ADB («tune repress»). */
    static volatile long REPRESS_MS = 40;
    private static final long BORDER_GAP_MS = 60;
    /** Más largo que esto se parte en tramos (el joystick); más corto va entero (toques, apuntados). */
    private static final long SHORT_TOUCH_MS = 800;

    /** Continuación de un dedo largo desde sa: apoya en su punto inicial y en REPRESS_MS vuelve a donde iba. */
    static Pattern.Touch repress(Pattern.Touch t, long sa, long se) {
        if (REPRESS_MS < 0) return sub(t, sa, se);   // prueba: apoyar directo donde iba, sin pasar por el centro
        Pattern.Touch r = new Pattern.Touch();
        Pattern.Point first = t.pts.get(0);
        r.pts.add(new Pattern.Point(sa, first.x, first.y));
        long re = Math.min(sa + REPRESS_MS, se);
        float[] back = t.at(re);
        r.pts.add(new Pattern.Point(re, back[0], back[1]));
        for (Pattern.Point q : t.pts) if (q.t > re && q.t < se) r.pts.add(q);
        if (se > re) {
            float[] end = t.at(se);
            r.pts.add(new Pattern.Point(se, end[0], end[1]));
        }
        return r;
    }

    private void endRepetition(int s) {
        if (!playing || s != session) return;
        rep++;
        if (next != null && (repeats == 0 || rep < repeats)) {
            Pattern n = next.get();
            if (n != null && n.touches.isEmpty()) {   // el botón Auto quieto y sin disparos: nada que tocar
                stop(null);
                return;
            }
            if (n != null && !n.touches.isEmpty() && n.duration > 0) pattern = n;
        }
        if (repeats == 0 || rep < repeats) {
            handler.postDelayed(() -> whenIdle(() -> beginRepetition(s)), Math.max(pauseMs, 15));
        } else {
            stop("Listo: " + rep + " repeticiones");
        }
    }

    /** El toque t recortado a [a, e], con los extremos interpolados. */
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
