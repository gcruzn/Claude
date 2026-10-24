package cl.gcruz.patrontouch;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Botón «Auto», versión simple (v6.8.0, pedido de Gonzalo 23-oct: «simplifica el menú y el
 * comportamiento: disparo automático infinito, lo único que puedo configurar es el tiempo entre cada
 * disparo, no usa visión; lo mismo para gadget, súper e híper. Dirección: o sigue al enemigo o la
 * dirección del lápiz; con lápiz sigue un patrón, por defecto línea recta»).
 *
 * Disparo, súper, gadget e híper son toques simples en instantes fijos contados desde que se encendió
 * el Auto: no miran la pantalla ni la munición (sin carga el juego ignora el toque y dispara apenas
 * recarga). Alrededor de cada súper/gadget/híper se quitan los disparos: el juego atiende un solo
 * botón de ataque a la vez y lo ignoraba (partida de Gonzalo, 23-oct, 09:40).
 *
 * Dirección:
 *  - «sigue al enemigo»: va recto hacia el enemigo que ve la captura de pantalla; el primero que
 *    queda a LOCK_PX se fija y se sigue hasta que deja de verse (ver sit()). Sin enemigo a la vista,
 *    usa la dirección del lápiz con el patrón.
 *  - «dirección del lápiz»: el patrón elegido desplazándose hacia la última dirección del lápiz; si
 *    se tocó el centro del joystick, el patrón se dibuja en el sitio (la recta queda quieta).
 *
 * El joystick va en ventanas de WINDOW_MS (un gesto por ventana); solo se rehace la ventana si
 * cambia el rumbo (Android admite un gesto inyectado a la vez y cada rehecho re-apoya el joystick).
 */
final class AutoController {

    static final String[] HEADINGS = {"sigue al enemigo", "dirección del lápiz"};
    static final String[] SHAPES = {"recta", "zigzag", "círculo", "cuadrado", "triángulo", "óvalo", "rect. horizontal", "rect. vertical"};
    static final String[] SHOT_EVERY = {"no", "0,1 s", "0,12 s", "0,15 s", "0,2 s", "0,25 s", "0,3 s", "0,4 s", "0,5 s", "0,75 s", "1 s",
            "mantener apretado"};
    /** −1 = mantener apretado (v6.8.1, pedido de Gonzalo 24-oct). */
    static final long[] SHOT_EVERY_MS = {0, 100, 120, 150, 200, 250, 300, 400, 500, 750, 1000, -1};
    static final String[] EVERY = {"no", "1 s", "2 s", "3 s", "4 s", "5 s", "6 s", "8 s", "10 s", "15 s"};
    static final long[] EVERY_MS = {0, 1000, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 15000};

    /** Claves de configuración, en el orden de la pantalla de ajustes. */
    static final String[] KEYS = {"heading", "shape", "shotEvery", "superEvery", "gadgetEvery", "hyperEvery"};
    /** Valores de partida: sigue al enemigo, recta, disparo cada 0,12 s, súper/gadget/híper cada 6 s. */
    private static final int[] DEFAULTS = {0, 0, 2, 6, 6, 6};

    static String[] values(String key) {
        switch (key) {
            case "heading": return HEADINGS;
            case "shape": return SHAPES;
            case "shotEvery": return SHOT_EVERY;
            default: return EVERY;
        }
    }

    static String label(String key) {
        switch (key) {
            case "heading": return "Dirección";
            case "shape": return "Patrón con lápiz";
            case "shotEvery": return "Disparo cada";
            case "superEvery": return "Súper cada";
            case "gadgetEvery": return "Gadget cada";
            default: return "Híper cada";
        }
    }

    /** Valor (índice) de una clave; sin guardar, el de partida. */
    int get(String key) {
        int k = java.util.Arrays.asList(KEYS).indexOf(key);
        int n = values(key).length, v = prefs.getInt("auto2." + key, DEFAULTS[k]);
        return v >= 0 && v < n ? v : DEFAULTS[k];
    }

    /** Mueve el valor de una clave `step` posiciones (±1), dando la vuelta. */
    void step(String key, int step) {
        int n = values(key).length;
        prefs.edit().putInt("auto2." + key, ((get(key) + step) % n + n) % n).apply();
    }

    String summary() {
        StringBuilder b = new StringBuilder();
        for (String k : KEYS) b.append(b.length() == 0 ? "" : " · ").append(label(k).toLowerCase()).append(" ").append(values(k)[get(k)]);
        return b.toString();
    }

    private static final long WINDOW_MS = 3000, TICK_MS = 50;
    private static final float DRIFT = 0.7f;   // peso del desplazamiento frente a la forma

    private final TouchService svc;
    private final Player player;
    private final SharedPreferences prefs;
    private final Handler main;

    private boolean running, paused;
    private long startedAt;

    AutoController(TouchService svc, Player player, SharedPreferences prefs, Handler main) {
        this.svc = svc;
        this.player = player;
        this.prefs = prefs;
        this.main = main;
    }

    boolean running() { return running; }

    boolean paused() { return paused; }

    void start() {
        running = true;
        paused = false;
        startedAt = SystemClock.uptimeMillis();
        lock = null;
        android.util.Log.i(TouchService.TAG, "Auto: " + summary());
        player.start(window(), 0, 0, this::window);
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
    }

    void stop() {
        running = false;
        paused = false;
        main.removeCallbacks(tick);
        player.stop(null);
    }

    /** El lápiz se acercó: Android bloquea lo inyectado; se suelta todo hasta que se aleje. */
    void pause() {
        if (!running || paused) return;
        paused = true;
        pausedAt = SystemClock.uptimeMillis();
        main.removeCallbacks(tick);
        player.stopByUserTouch();
    }

    void resume() {
        if (!running || !paused) return;
        paused = false;
        // La pausa del lápiz no cuenta como «no se ve»: el fijado se conserva al retomar...
        lockSeen += SystemClock.uptimeMillis() - pausedAt;
        // ...salvo que Gonzalo haya dirigido hacia otro lado: durante PEN_INTENT_MS solo cuentan los
        // enemigos a ±60° de esa dirección (23-oct: «lo dirijo directo al enemigo, suelto y se va en
        // sentido contrario»).
        String sd = Script.steerDir;
        if (sd != null && !"center".equals(sd)) {
            penIntentAng = Math.toRadians(dirAngle(sd));
            penIntentUntil = SystemClock.uptimeMillis() + PEN_INTENT_MS;
            if (lock != null && lastPx >= 0
                    && angleDiff(Math.atan2(-(lock[1] - lastPy), lock[0] - lastPx), penIntentAng) > Math.toRadians(60)) {
                android.util.Log.i(TouchService.TAG, "Auto: suelta el fijado, el lápiz apuntó a otro lado");
                lock = null;
            }
        }
        player.start(window(), 0, 0, this::window);
        main.postDelayed(tick, TICK_MS);
    }

    // ---------------------------------------------------------------- enemigo (solo para la dirección)

    /** Lo que se ve en este instante: el jugador y el enemigo a seguir (el fijado, o el más cercano). */
    private static final class Sit {
        float px, py;
        float[] enemy;
        float dist = Float.MAX_VALUE;
    }

    // Fijado: el primer enemigo a LOCK_PX se sigue hasta que deja de verse LOCK_LOST_MS (6 s si se lo
    // dejó de ver encima: pegado a él su barra no se ve). Se busca donde DEBERÍA estar (última
    // posición + su velocidad) con radio de 140 a 300 px, para que otro enemigo de paso no lo reemplace.
    private static final float LOCK_PX = 320, LOCK_MATCH_PX = 140, LOCK_MATCH_MAX_PX = 300;
    private static final long LOCK_LOST_MS = 4000, LOCK_LOST_CLOSE_MS = 6000;
    private static final long PEN_INTENT_MS = 2500, PLAYER_HOLD_MS = 700, FOE_HOLD_MS = 500;
    private float[] lock, lastFoe;
    private float lockVx, lockVy, lockLastDist = Float.MAX_VALUE;   // velocidad (px/ms) y distancia al verlo
    private long lockSeen, pausedAt, penIntentUntil, lastFoeAt, lastPAt;
    private double penIntentAng;
    // Última posición vista del jugador: en plena pelea la etiqueta se pierde cuadros enteros.
    private float lastPx = -1, lastPy = -1;

    /**
     * ¿Es el número rojo de daño sobre la cabeza del propio jugador (p. ej. «2000»)? La visión lo
     * tomaba por barra enemiga y se quedaba fijado en ese fantasma (partida de Gonzalo, 23-oct, 09:40).
     */
    private static boolean selfGhost(float[] en, float px, float py) {
        return Math.abs(en[0] - px) < 130 && en[1] > py - 130 && en[1] < py + 60;
    }

    private Sit sit() {
        Sit st = new Sit();
        Vision.Frame v = CaptureService.vision;
        long now = SystemClock.uptimeMillis();
        if (lock != null && now - lockSeen > (lockLastDist < LOCK_PX * 1.2f ? LOCK_LOST_CLOSE_MS : LOCK_LOST_MS)) {
            android.util.Log.i(TouchService.TAG, "Auto: el fijado ya no se ve (derrotado o perdido)");
            lock = null;
        }
        if (v == null || now - v.at > 600) return st;
        if (v.px >= 0) {
            lastPx = v.px;
            lastPy = v.py;
            lastPAt = v.at;
        } else if (lastPx < 0 || v.at - lastPAt > PLAYER_HOLD_MS) {
            return st;
        }
        final float px = lastPx, py = lastPy;
        st.px = px;
        st.py = py;
        boolean intent = now < penIntentUntil && lock == null;
        List<float[]> foes = new ArrayList<>();
        for (float[] en : v.enemies) if (!selfGhost(en, px, py)) foes.add(en);
        for (float[] en : foes) {
            if (intent && angleDiff(Math.atan2(-(en[1] - py), en[0] - px), penIntentAng) > Math.toRadians(60)) continue;
            float d = (float) Math.hypot(en[0] - px, en[1] - py);
            if (d < st.dist) {
                st.dist = d;
                st.enemy = en;
            }
        }
        if (lock == null && st.enemy != null && st.dist < LOCK_PX) {
            lock = st.enemy.clone();
            lockVx = lockVy = 0;
            lockLastDist = st.dist;
            lockSeen = v.at;
            android.util.Log.i(TouchService.TAG, "Auto: fija enemigo a " + Math.round(st.dist) + " px");
        }
        if (lock != null) {
            long gap = Math.max(0, v.at - lockSeen);
            long g = Math.min(gap, 800);
            float ex = lock[0] + lockVx * g, ey = lock[1] + lockVy * g;   // donde debería estar
            float best = Math.min(LOCK_MATCH_MAX_PX, LOCK_MATCH_PX + 0.15f * Math.max(0, gap - 100));
            float[] match = null;
            for (float[] en : foes) {
                float d = (float) Math.hypot(en[0] - ex, en[1] - ey);
                if (d < best) {
                    best = d;
                    match = en;
                }
            }
            if (match != null && gap > 0) {
                if (gap < 600) {   // velocidad suavizada, tope 0,6 px/ms
                    float vx = (match[0] - lock[0]) / gap, vy = (match[1] - lock[1]) / gap;
                    float sp = (float) Math.hypot(vx, vy);
                    if (sp > 0.6f) { vx *= 0.6f / sp; vy *= 0.6f / sp; }
                    lockVx = 0.5f * lockVx + 0.5f * vx;
                    lockVy = 0.5f * lockVy + 0.5f * vy;
                }
                lock = match.clone();
                lockSeen = v.at;
                lockLastDist = (float) Math.hypot(lock[0] - px, lock[1] - py);
                ex = lock[0];
                ey = lock[1];
            }
            st.enemy = new float[]{ex, ey};
            st.dist = (float) Math.hypot(ex - px, ey - py);
        }
        // Un cuadro sin barra enemiga no cambia el rumbo: se recuerda el último FOE_HOLD_MS.
        if (st.enemy != null) {
            lastFoe = st.enemy.clone();
            lastFoeAt = v.at;
        } else if (lastFoe != null && v.at - lastFoeAt < FOE_HOLD_MS) {
            st.enemy = lastFoe;
            st.dist = (float) Math.hypot(lastFoe[0] - px, lastFoe[1] - py);
        }
        return st;
    }

    /** Rumbo hacia el enemigo (rad, y hacia arriba); NaN = no se sigue a un enemigo ahora. */
    private double chaseAngle(Sit st) {
        if (get("heading") != 0 || st.enemy == null) return Double.NaN;
        return Math.atan2(-(st.enemy[1] - st.py), st.enemy[0] - st.px);
    }

    // ---------------------------------------------------------------- decisiones

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || paused) return;
            decide();
            main.postDelayed(this, TICK_MS);
        }
    };

    private long lastTurn;
    private double windowBase = Double.NaN;

    /**
     * Solo al seguir al enemigo: rehace la ventana si empieza o deja de haber enemigo, o si el rumbo
     * hacia él se fue más de 45°, como mucho cada 0,6 s (con menos, el joystick se re-apoyaba sin parar).
     */
    private void decide() {
        if (get("heading") != 0) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTurn < 600) return;
        double b = chaseAngle(sit());
        String why = null;
        if (Double.isNaN(b) != Double.isNaN(windowBase)) why = Double.isNaN(b) ? "sin enemigo" : "enemigo a la vista";
        else if (!Double.isNaN(b) && angleDiff(b, windowBase) > Math.toRadians(45))
            why = "giro " + Math.round(Math.toDegrees(angleDiff(b, windowBase))) + "°";
        if (why == null) return;
        lastTurn = now;
        android.util.Log.i(TouchService.TAG, "Auto: rehace (" + why + ")");
        Pattern p = window();
        if (!p.touches.isEmpty()) player.replaceNow(p, this::window);
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return d > Math.PI ? 2 * Math.PI - d : d;
    }

    // ---------------------------------------------------------------- ventana

    /** Ventana de WINDOW_MS desde ahora: joystick, disparos y súper/gadget/híper en su ciclo. */
    Pattern window() {
        Pattern p = new Pattern();
        p.name = "Auto";
        long now = SystemClock.uptimeMillis();
        long t0 = now - startedAt;
        // --- joystick
        float[] joy = svc.calib().get("joy");
        double chase = chaseAngle(sit());
        windowBase = chase;
        String sd = Script.steerDir;
        boolean center = Double.isNaN(chase) && (sd == null || "center".equals(sd));
        double base = Double.isNaN(chase) ? Math.toRadians(dirAngle(sd)) : chase;
        int shape = Double.isNaN(chase) ? get("shape") : 0;   // al enemigo, en línea recta
        if (joy != null && !(shape <= 1 && center)) {
            float r = svc.calib().joyRadius();
            Pattern.Touch js = new Pattern.Touch();
            js.pts.add(new Pattern.Point(0, joy[0], joy[1]));
            for (long t = 20; t <= WINDOW_MS; t += 40) {
                double a = heading(shape, (t0 + t) / 1000.0, base, center);
                js.pts.add(new Pattern.Point(t, joy[0] + (float) (r * Math.cos(a)), joy[1] - (float) (r * Math.sin(a))));
            }
            p.touches.add(js);
        }
        // --- súper, gadget e híper: cada uno en múltiplos de su período desde que se encendió el Auto
        // (gadget desfasado medio período, híper un cuarto: con los tres en 6 s quedan súper y gadget
        // alternados cada 3 s). Si dos coinciden, el segundo va 0,35 s después.
        List<long[]> ev = new ArrayList<>();   // {instante en la ventana, 0 súper / 1 gadget / 2 híper}
        String[] ek = {"superEvery", "gadgetEvery", "hyperEvery"};
        long[] div = {0, 2, 4};
        for (int i = 0; i < 3; i++) {
            long per = EVERY_MS[get(ek[i])];
            if (per <= 0) continue;
            long ph = div[i] == 0 ? 0 : per / div[i];
            for (long k = Math.max(0, (t0 - ph) / per); ; k++) {
                long at = ph + k * per;   // desde que se encendió
                long off = at - t0;
                if (off >= WINDOW_MS) break;
                if (off >= 30 && at > 500) ev.add(new long[]{off, i});
            }
        }
        ev.sort((x, y) -> Long.compare(x[0], y[0]));
        List<long[]> abil = new ArrayList<>();   // {instante, botón}, ya separados 0,35 s
        long last = -1000;
        for (long[] e : ev) {
            long off = Math.max(e[0], last + 350);
            if (off >= WINDOW_MS) break;
            last = off;
            abil.add(new long[]{off, e[1]});
        }
        // --- disparo, siempre. Alrededor de cada súper/gadget/híper (‒130 a +250 ms) no hay disparo:
        // el juego atiende un solo botón de ataque a la vez y los ignoraba.
        long shot = SHOT_EVERY_MS[get("shotEvery")];
        if (shot > 0) {
            for (long t = 40; t < WINDOW_MS; t += shot) {
                boolean clash = false;
                for (long[] e : abil) if (t + 70 > e[0] - 130 && t < e[0] + 250) clash = true;
                if (!clash) press(p, "attack", t);
            }
        } else if (shot < 0) {
            // Mantener apretado, ARRASTRADO hacia el enemigo (o hacia donde camina): con el dedo quieto
            // sobre el botón el juego solo apunta y dispara al soltar; Gonzalo con Amber (24-oct): «no
            // logro vaciar su cartucho y en un momento ni siquiera disparó». Se suelta solo en cada
            // súper/gadget/híper.
            double aim = Double.isNaN(chase) ? base : chase;
            long from = 40;
            for (long[] e : abil) {
                long to = e[0] - 130;
                if (to - from >= 150) holdAimed(p, from, to, aim);
                from = Math.max(from, e[0] + 250);
            }
            if (WINDOW_MS - from >= 150) holdAimed(p, from, WINDOW_MS, aim);
        }
        String[] btn = {"super", "gadget", "hyper"};
        for (long[] e : abil) press(p, btn[(int) e[1]], e[0], 120);
        if (p.touches.isEmpty()) {   // quieto y sin nada que tocar: el reproductor espera la ventana
            p.duration = WINDOW_MS;
            return p;
        }
        p.normalize();
        p.duration = Math.max(p.duration, WINDOW_MS);
        return p;
    }

    /** Ataque apretado de `from` a `to` arrastrado hacia `ang` (rad, y hacia arriba). */
    private void holdAimed(Pattern p, long from, long to, double ang) {
        float[] b = svc.calib().get("attack");
        if (b == null) return;
        float r = svc.calib().aimRadius() * 1.2f;
        float ax = b[0] + (float) (r * Math.cos(ang)), ay = b[1] - (float) (r * Math.sin(ang));
        Pattern.Touch x = new Pattern.Touch();
        x.pts.add(new Pattern.Point(from, b[0], b[1]));
        x.pts.add(new Pattern.Point(from + 60, ax, ay));
        x.pts.add(new Pattern.Point(to, ax, ay));
        p.touches.add(x);
    }

    private void press(Pattern p, String key, long t) {
        press(p, key, t, 70);
    }

    private void press(Pattern p, String key, long t, long ms) {
        float[] b = svc.calib().get(key);
        if (b == null) return;
        Pattern.Touch x = new Pattern.Touch();
        x.pts.add(new Pattern.Point(t, b[0], b[1]));
        x.pts.add(new Pattern.Point(t + ms, b[0], b[1]));
        p.touches.add(x);
    }

    // ---------------------------------------------------------------- patrones

    /** Ángulo (rad, y hacia arriba) del joystick en el instante s para la forma, orientada a `base`. */
    static double heading(int shape, double s, double base, boolean center) {
        double c;   // dirección de la forma
        switch (shape) {
            case 0: return base;                                                     // recta
            case 1: return base + Math.toRadians(((long) (s / 0.5)) % 2 == 0 ? 45 : -45);   // zigzag
            case 2: c = base + 2 * Math.PI * s / 2.4; break;                         // círculo
            case 3: c = base + Math.toRadians(90 * ((long) (s / 0.6) % 4)); break;   // cuadrado
            case 4: c = base + Math.toRadians(120 * ((long) (s / 0.7) % 3)); break;  // triángulo
            case 5: {                                                                // óvalo (2:1)
                double w = 2 * Math.PI * s / 3.0;
                c = base + Math.atan2(Math.cos(w), -2 * Math.sin(w));
                break;
            }
            case 6: c = base + Math.toRadians(90 * rectSide(s, 0.9, 0.45)); break;   // rect. horizontal
            default: c = base + Math.toRadians(90 * rectSide(s, 0.45, 0.9)); break;  // rect. vertical
        }
        if (center) return c;
        double x = Math.cos(c) + DRIFT * Math.cos(base), y = Math.sin(c) + DRIFT * Math.sin(base);
        return Math.atan2(y, x);
    }

    /** Lado (0–3) de un rectángulo con lados alternos de duración `a` (a lo largo) y `b`. */
    private static int rectSide(double s, double a, double b) {
        double per = 2 * (a + b), m = s % per;
        if (m < a) return 0;
        if (m < a + b) return 1;
        if (m < 2 * a + b) return 2;
        return 3;
    }

    static double dirAngle(String d) {
        if (d == null) return 0;
        switch (d) {
            case "right": return 0;
            case "upright": return 45;
            case "up": return 90;
            case "upleft": return 135;
            case "left": return 180;
            case "downleft": return 225;
            case "down": return 270;
            case "downright": return 315;
        }
        return 0;
    }
}
