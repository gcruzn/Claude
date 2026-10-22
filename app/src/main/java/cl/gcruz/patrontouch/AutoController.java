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
    static final String[] SHOTS = {"de a 1", "ráfaga", "de a 2", "de a 3", "sin disparo", "mantener cerca", "cuerpo a cuerpo"};
    static final String[] SUPERS = {"con enemigo", "automático", "nunca", "con enemigo (toque)"};
    static final int[] RANGES = {350, 500, 650, 800, 1000};
    static final String[] ABILITIES = {"con enemigo", "cada 4 s", "nunca", "con vida baja"};
    static final String[] PROFILES = {"General", "Nita", "Edgar", "Gigi", "Amber", "Nori"};
    static final String[] HEADINGS = {"lápiz", "enemigo más cercano"};
    static final String[] DISTANCES = {"no", "corta", "media", "larga"};
    static final int[] DIST_PX = {0, 250, 450, 650};
    static final String[] FLEE = {"no", "sí"};

    /** Claves de configuración, en el orden de la pantalla de ajustes. */
    static final String[] KEYS = {"shape", "heading", "shot", "super", "range", "distance", "flee", "abil"};

    /**
     * Valores de partida por perfil (índices en el orden de KEYS). 22-oct, kits buscados en la web
     * (Fandom, GameWith, esports.gg; los de Gigi y Nori son guías de fans):
     *  - Nita: círculo hacia el lápiz, de a 2, súper (oso) a 650, distancia media.
     *  - Edgar: zigzag al enemigo, ráfaga, súper (salto) a 500, sin distancia.
     *  - Gigi: asesina cuerpo a cuerpo; su ataque es un giro que dura mientras se mantiene apretado:
     *    recta al enemigo, «mantener cerca», súper (portal de ida y vuelta) a 650.
     *  - Amber: lanzallamas de largo alcance con mucha munición y poca movilidad: zigzag al
     *    enemigo, ráfaga, súper (frasco) a 650, distancia larga, huye con vida baja.
     *  - Nori, grabado jugando con Gonzalo (22-oct, 68 toques): joystick sostenido sin parar (43 s),
     *    ataque en ráfagas de 5–8 toques de 0,11–0,27 s cada ~0,17 s, ataque mantenido 1,6–4,3 s
     *    apuntando (caña), súper con un toque simple (auto-apuntado), gadget al inicio. Perfil:
     *    recta hacia el lápiz, «cuerpo a cuerpo» (golpes rápidos cerca, caña a media distancia),
     *    súper con toque cuando hay enemigo a 350, gadget (sushi) con vida baja.
     */
    static final int[][] PRESETS = {
            //  forma rumbo disparo súper alcance distancia huir habilidades
            {2, 0, 2, 0, 1, 0, 0, 0},   // General
            {2, 0, 2, 0, 2, 2, 1, 0},   // Nita
            {1, 1, 1, 0, 1, 0, 0, 0},   // Edgar
            {0, 1, 5, 0, 2, 0, 0, 0},   // Gigi
            {1, 1, 1, 0, 2, 3, 1, 0},   // Amber
            {0, 0, 6, 3, 0, 0, 0, 3},   // Nori (de su partida grabada, ver abajo)
    };

    static String[] values(String key) {
        switch (key) {
            case "shape": return SHAPES;
            case "heading": return HEADINGS;
            case "shot": return SHOTS;
            case "super": return SUPERS;
            case "distance": return DISTANCES;
            case "flee": return FLEE;
            case "abil": return ABILITIES;
            default: {
                String[] r = new String[RANGES.length];
                for (int i = 0; i < r.length; i++) r[i] = RANGES[i] + " px";
                return r;
            }
        }
    }

    static String label(String key) {
        switch (key) {
            case "shape": return "Forma";
            case "heading": return "Rumbo";
            case "shot": return "Disparo";
            case "super": return "Súper";
            case "range": return "Alcance del súper";
            case "distance": return "Mantener distancia";
            case "flee": return "Huir con vida baja";
            default: return "Gadget e híper";
        }
    }

    int profile() {
        int v = prefs.getInt("auto_profile", 0);
        return v >= 0 && v < PROFILES.length ? v : 0;
    }

    /** Valor (índice) de una clave en el perfil activo; sin guardar, el de su preset. */
    int get(String key) {
        int k = java.util.Arrays.asList(KEYS).indexOf(key);
        int def = PRESETS[profile()][k], n = values(key).length;
        int v = prefs.getInt("auto." + PROFILES[profile()] + "." + key, def);
        return v >= 0 && v < n ? v : def;
    }

    /** Mueve el valor de una clave `step` posiciones (±1), dando la vuelta. */
    void step(String key, int step) {
        int n = values(key).length;
        prefs.edit().putInt("auto." + PROFILES[profile()] + "." + key, ((get(key) + step) % n + n) % n).apply();
    }

    void stepProfile(int step) {
        int n = PROFILES.length;
        prefs.edit().putInt("auto_profile", ((profile() + step) % n + n) % n).apply();
    }

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
        // 22-oct: el perfil de Nori se rehízo con su partida grabada; se borran los ajustes guardados
        // del perfil anterior para que tome los valores nuevos.
        if (!prefs.getBoolean("auto_nori_v2", false)) {
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : KEYS) ed.remove("auto.Nori." + k);
            ed.putBoolean("auto_nori_v2", true).apply();
        }
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
        StringBuilder b = new StringBuilder("perfil " + PROFILES[profile()]);
        for (String k : KEYS) b.append(" · ").append(label(k).toLowerCase()).append(" ").append(values(k)[get(k)]);
        return b.toString();
    }

    // ---------------------------------------------------------------- lo que se ve

    /** Situación en este instante, a partir del último cuadro analizado (CaptureService.vision). */
    private static final class Sit {
        boolean seen;          // cuadro reciente con el jugador ubicado
        float px, py, ammo = -1, health = -1;   // health: fracción 0–1 (−1 = sin dato)
        float[] enemy;         // el más cercano
        float dist = Float.MAX_VALUE;
    }

    private float maxHealth;   // ancho máximo visto de la barra verde = vida llena

    private Sit sit() {
        Sit st = new Sit();
        Vision.Frame v = CaptureService.vision;
        long now = SystemClock.uptimeMillis();
        if (v == null || now - v.at > 600 || v.px < 0) return st;
        st.seen = true;
        st.px = v.px;
        st.py = v.py;
        st.ammo = v.ammo;
        if (v.health > maxHealth) maxHealth = v.health;
        if (v.health >= 0 && maxHealth >= 20) st.health = Math.min(1f, v.health / maxHealth);
        for (float[] en : v.enemies) {
            float d = (float) Math.hypot(en[0] - v.px, en[1] - v.py);
            if (d < st.dist) {
                st.dist = d;
                st.enemy = en;
            }
        }
        return st;
    }

    /** Ángulo (rad, y hacia arriba) desde el jugador hacia el enemigo. */
    private static double toward(Sit st) {
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

    private void decide() {
        long now = SystemClock.uptimeMillis();
        Sit st = sit();
        int shot = get("shot");
        int taps = 0;
        long hold = 0, aimHold = 0;
        // Munición: «de a N» espera a tener N (de a 1: dispara con 2 o más, así nunca queda en cero).
        if (shot == 0 || shot == 2 || shot == 3) {
            float need = shot == 3 ? 2.85f : 1.9f;
            int burst = shot == 0 ? 1 : shot == 2 ? 2 : 3;
            if (st.ammo >= 0) {
                if (st.ammo >= need && now - lastShot > 350) taps = burst;
            } else if (now - lastShot > 900) {
                taps = burst;   // sin ver la munición: ritmo prudente
            }
        } else if (shot == 5 && st.enemy != null && st.dist < 300 && now - lastShot > 1000) {
            hold = 900;   // Gigi: el giro dura mientras se mantiene apretado
        } else if (shot == 6 && st.enemy != null) {
            // Nori (grabado): cerca, golpes rápidos cada ~0,17 s; a media distancia, la caña (ataque
            // mantenido ~2,5 s apuntando al enemigo, lo tira hacia él).
            if (st.dist < 350 && now - lastShot > 170) taps = 1;
            else if (st.dist >= 350 && st.dist <= 750 && now - lastHook > 4000) aimHold = 2500;
        }
        float range = RANGES[get("range")];
        float[] target = st.enemy != null && st.dist <= range ? st.enemy : null;
        int sup = get("super");
        boolean doSuper = now - lastSuper > 1500 && (sup == 1 || ((sup == 0 || sup == 3) && target != null));
        int ab = get("abil");
        boolean near = target != null;
        boolean low = st.health >= 0 && st.health < 0.5f;
        boolean doGadget = now - lastGadget > 4000 && (ab == 1 || (ab == 0 && near) || (ab == 3 && low));
        boolean doHyper = now - lastHyper > 4000 && (ab == 1 || ((ab == 0 || ab == 3) && near));
        // ¿Cambió el rumbo efectivo (enemigo, distancia, huida)? Entonces se rehace la ventana.
        double b = effectiveBase(st);
        boolean turn = running && windowMode != modeOf(st)
                || (!Double.isNaN(b) && !Double.isNaN(windowBase) && angleDiff(b, windowBase) > Math.toRadians(35) && now - lastTurn > 300);
        if (taps == 0 && hold == 0 && aimHold == 0 && !doSuper && !doGadget && !doHyper && !turn) return;
        if (turn) lastTurn = now;
        Pattern p = window();
        long t = 40;   // después del re-apoyo del joystick (no en el mismo instante: el juego lo ignoraba)
        for (int i = 0; i < taps; i++, t += 130) press(p, "attack", t);
        if (hold > 0) {
            press(p, "attack", t, hold);
            t += hold + 40;
        }
        if (aimHold > 0) {   // caña de Nori: mantener el ataque arrastrado hacia el enemigo
            float[] atk = svc.calib().get("attack");
            if (atk != null) {
                double ang = Math.atan2(st.enemy[1] - st.py, st.enemy[0] - st.px);
                float r = svc.calib().aimRadius() * 1.5f;
                float ex = atk[0] + (float) (r * Math.cos(ang)), ey = atk[1] + (float) (r * Math.sin(ang));
                Pattern.Touch x = new Pattern.Touch();
                x.pts.add(new Pattern.Point(t, atk[0], atk[1]));
                x.pts.add(new Pattern.Point(t + 150, ex, ey));
                x.pts.add(new Pattern.Point(t + aimHold, ex, ey));
                p.touches.add(x);
                t += aimHold + 40;
                lastHook = now;
                android.util.Log.i(TouchService.TAG, "Auto: caña hacia enemigo a " + Math.round(st.dist) + " px");
            }
        }
        if (doHyper) { press(p, "hyper", t); t += 120; lastHyper = now; }
        if (doGadget) { press(p, "gadget", t); t += 120; lastGadget = now; }
        if (doSuper) {
            float[] btn = svc.calib().get("super");
            if (btn != null) {
                if (target != null && sup != 3) {   // «con enemigo (toque)»: toque simple, el juego apunta
                    double ang = Math.atan2(target[1] - st.py, target[0] - st.px);
                    float r = svc.calib().aimRadius() * 1.8f;
                    float ex = btn[0] + (float) (r * Math.cos(ang)), ey = btn[1] + (float) (r * Math.sin(ang));
                    Pattern.Touch x = new Pattern.Touch();
                    x.pts.add(new Pattern.Point(t, btn[0], btn[1]));
                    x.pts.add(new Pattern.Point(t + 120, ex, ey));
                    x.pts.add(new Pattern.Point(t + 200, ex, ey));
                    p.touches.add(x);
                } else {
                    press(p, "super", t);
                }
                lastSuper = now;
                android.util.Log.i(TouchService.TAG, "Auto: súper" + (target != null ? " hacia enemigo a " + Math.round(st.dist) + " px" : ""));
            }
        }
        if (taps > 0 || hold > 0 || aimHold > 0) lastShot = now;
        if (p.touches.isEmpty()) return;
        p.normalize();
        player.replaceNow(p, this::window);
    }

    private long lastTurn, lastHook;

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

    // ---------------------------------------------------------------- rumbo

    private static final int MODE_PEN = 0, MODE_CHASE = 1, MODE_AWAY = 2, MODE_ORBIT = 3, MODE_FLEE = 4;
    private int windowMode = -1;
    private double windowBase = Double.NaN;

    /**
     * Qué manda el rumbo ahora: huir (vida bajo 30 % con un enemigo a la vista), alejarse u orbitar
     * para mantener la distancia, perseguir al enemigo más cercano, o la última dirección del lápiz.
     */
    private int modeOf(Sit st) {
        if (st.enemy == null) return MODE_PEN;
        if (get("flee") == 1 && st.health >= 0 && st.health < 0.3f) return MODE_FLEE;
        int dpx = DIST_PX[get("distance")];
        if (dpx > 0 && st.dist < dpx) return MODE_AWAY;
        if (dpx > 0 && st.dist < dpx * 1.3f) return MODE_ORBIT;
        return get("heading") == 1 ? MODE_CHASE : MODE_PEN;
    }

    /** Rumbo base (rad, y hacia arriba) para el modo actual; NaN = el del lápiz (o quieto). */
    private double effectiveBase(Sit st) {
        switch (modeOf(st)) {
            case MODE_CHASE: return toward(st);
            case MODE_AWAY:
            case MODE_FLEE: return toward(st) + Math.PI;
            case MODE_ORBIT: return toward(st) + Math.PI / 2;
            default: return Double.NaN;
        }
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return d > Math.PI ? 2 * Math.PI - d : d;
    }

    // ---------------------------------------------------------------- movimiento

    /** Ventana de WINDOW_MS desde ahora: el joystick siguiendo la forma (y la ráfaga, si es el modo). */
    Pattern window() {
        Pattern p = new Pattern();
        p.name = "Auto";
        float[] joy = svc.calib().get("joy");
        long t0 = SystemClock.uptimeMillis() - startedAt;
        Sit st = sit();
        int mode = modeOf(st);
        double eb = effectiveBase(st);
        windowMode = mode;
        windowBase = eb;
        String sd = Script.steerDir;
        boolean center = Double.isNaN(eb) && (sd == null || "center".equals(sd));
        double base = Double.isNaN(eb) ? Math.toRadians(dirAngle(sd)) : eb;
        int shape = mode == MODE_FLEE ? 0 : get("shape");   // huir: en línea recta
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
        if (get("shot") == 1) {   // ráfaga: uno cada 0,4 s
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
