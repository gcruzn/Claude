package cl.gcruz.patrontouch;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;

/**
 * Botón «Auto» (22-oct, pedido de Gonzalo): un solo botón que se mueve con una forma, dispara según
 * la munición que ve en pantalla y tira el súper solo con un enemigo al alcance. Todo se configura
 * desde la botonera (TouchService.showAutoConfig).
 *
 * Movimiento: la forma (recta, zigzag, círculo, cuadrado, triángulo, óvalo, rectángulos) se dibuja
 * DESPLAZÁNDOSE hacia la última dirección del lápiz; si Gonzalo tocó el centro del joystick
 * (Script.steerDir = "center") la forma se dibuja en el sitio y la recta/zigzag quedan quietos.
 * El joystick va en ventanas de WINDOW_MS (un gesto por ventana). Las decisiones (disparos según
 * munición, súper, gadget, híper) rehacen la ventana desde ya con el toque incluido: Android admite
 * un solo gesto inyectado a la vez y el joystick se re-apoya en cada una.
 */
final class AutoController {

    static final String[] SHAPES = {"recta", "zigzag", "círculo", "cuadrado", "triángulo", "óvalo", "rect. horizontal", "rect. vertical"};
    static final String[] SHOTS = {"de a 1", "ráfaga", "de a 2", "de a 3", "sin disparo"};
    static final String[] SUPERS = {"con enemigo", "automático", "nunca"};
    static final int[] RANGES = {350, 500, 650, 800, 1000};
    static final String[] ABILITIES = {"con enemigo", "cada 4 s", "nunca"};

    private static final long WINDOW_MS = 3000, TICK_MS = 50;
    private static final float DRIFT = 0.7f;   // peso del desplazamiento frente a la forma

    private final TouchService svc;
    private final Player player;
    private final SharedPreferences prefs;
    private final Handler main;

    private boolean running, paused;
    private long startedAt, lastShot, lastSuper, lastGadget, lastHyper;

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
        main.removeCallbacks(tick);
        player.stopByUserTouch();
    }

    void resume() {
        if (!running || !paused) return;
        paused = false;
        player.start(window(), 0, 0, this::window);
        main.postDelayed(tick, TICK_MS);
    }

    String summary() {
        return "forma " + SHAPES[idx("auto_shape", 2, SHAPES.length)] + " · disparo " + SHOTS[idx("auto_shot", 2, SHOTS.length)]
                + " · súper " + SUPERS[idx("auto_super", 0, SUPERS.length)] + " (" + RANGES[idx("auto_range", 1, RANGES.length)] + " px)"
                + " · gadget/híper " + ABILITIES[idx("auto_abil", 0, ABILITIES.length)];
    }

    int idx(String key, int def, int n) {
        int v = prefs.getInt(key, def);
        return v >= 0 && v < n ? v : def;
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

    private void decide() {
        long now = SystemClock.uptimeMillis();
        Vision.Frame v = CaptureService.vision;
        boolean seen = v != null && now - v.at < 400;
        float ammo = seen ? v.ammo : -1;
        int shot = idx("auto_shot", 2, SHOTS.length);
        int taps = 0;
        // Munición: «de a N» espera a tener N (de a 1: dispara con 2 o más, así nunca queda en cero).
        if (shot == 0 || shot == 2 || shot == 3) {
            float need = shot == 0 ? 1.9f : shot == 2 ? 1.9f : 2.85f;
            int burst = shot == 0 ? 1 : shot == 2 ? 2 : 3;
            if (ammo >= 0) {
                if (ammo >= need && now - lastShot > 350) taps = burst;
            } else if (now - lastShot > 900) {
                taps = burst;   // sin ver la munición: ritmo prudente
            }
        }
        float[] target = null;
        float range = RANGES[idx("auto_range", 1, RANGES.length)];
        if (seen && v.px >= 0) {
            float best = Float.MAX_VALUE;
            for (float[] e : v.enemies) {
                float d = (float) Math.hypot(e[0] - v.px, e[1] - v.py);
                if (d < best) {
                    best = d;
                    target = e;
                }
            }
            if (best > range) target = null;
        }
        int sup = idx("auto_super", 0, SUPERS.length);
        boolean doSuper = now - lastSuper > 1500 && (sup == 1 || (sup == 0 && target != null));
        int ab = idx("auto_abil", 0, ABILITIES.length);
        boolean abilityTime = ab == 1 || (ab == 0 && target != null);
        boolean doGadget = abilityTime && now - lastGadget > 4000;
        boolean doHyper = abilityTime && now - lastHyper > 4000;
        if (taps == 0 && !doSuper && !doGadget && !doHyper) return;
        Pattern p = window();
        long t = 40;   // después del re-apoyo del joystick (no en el mismo instante: el juego lo ignoraba)
        for (int i = 0; i < taps; i++, t += 130) press(p, "attack", t);
        if (doHyper) { press(p, "hyper", t); t += 120; lastHyper = now; }
        if (doGadget) { press(p, "gadget", t); t += 120; lastGadget = now; }
        if (doSuper) {
            float[] btn = svc.calib().get("super");
            if (btn != null) {
                if (target != null && v.px >= 0) {
                    double a = Math.atan2(target[1] - v.py, target[0] - v.px);
                    float r = svc.calib().aimRadius() * 1.8f;
                    Pattern.Touch x = new Pattern.Touch();
                    x.pts.add(new Pattern.Point(t, btn[0], btn[1]));
                    x.pts.add(new Pattern.Point(t + 120, btn[0] + (float) (r * Math.cos(a)), btn[1] + (float) (r * Math.sin(a))));
                    x.pts.add(new Pattern.Point(t + 200, btn[0] + (float) (r * Math.cos(a)), btn[1] + (float) (r * Math.sin(a))));
                    p.touches.add(x);
                } else {
                    press(p, "super", t);
                }
                lastSuper = now;
                android.util.Log.i(TouchService.TAG, "Auto: súper" + (target != null ? " hacia enemigo" : ""));
            }
        }
        if (taps > 0) lastShot = now;
        p.normalize();
        player.replaceNow(p, this::window);
    }

    private void press(Pattern p, String key, long t) {
        float[] b = svc.calib().get(key);
        if (b == null) return;
        Pattern.Touch x = new Pattern.Touch();
        x.pts.add(new Pattern.Point(t, b[0], b[1]));
        x.pts.add(new Pattern.Point(t + 70, b[0], b[1]));
        p.touches.add(x);
    }

    // ---------------------------------------------------------------- movimiento

    /** Ventana de WINDOW_MS desde ahora: el joystick siguiendo la forma (y la ráfaga, si es el modo). */
    Pattern window() {
        Pattern p = new Pattern();
        p.name = "Auto";
        float[] joy = svc.calib().get("joy");
        long t0 = SystemClock.uptimeMillis() - startedAt;
        String sd = Script.steerDir;
        boolean center = sd == null || "center".equals(sd);
        double base = Math.toRadians(dirAngle(sd));
        int shape = idx("auto_shape", 2, SHAPES.length);
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
        if (idx("auto_shot", 2, SHOTS.length) == 1) {   // ráfaga: uno cada 0,4 s
            for (long t = 40; t < WINDOW_MS; t += 400) press(p, "attack", t);
        }
        if (p.touches.isEmpty()) {   // quieto y sin disparos: el reproductor se detiene y decide() lo retoma
            p.duration = WINDOW_MS;
            return p;
        }
        p.normalize();
        p.duration = Math.max(p.duration, WINDOW_MS);
        return p;
    }

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
