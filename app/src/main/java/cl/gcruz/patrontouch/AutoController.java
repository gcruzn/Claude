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
    static final String[] SHOTS = {"de a 1", "ráfaga", "de a 2", "de a 3", "sin disparo", "mantener cerca", "cuerpo a cuerpo", "cazar (Edgar)"};
    static final int SHOT_HUNT = 7;
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
     *  - Edgar, pedido de Gonzalo (23-oct): «cazar». Con un enemigo a la vista va recto hacia él; si
     *    no lo alcanza a golpear usa el gadget para acercarse; al alcance golpea sin parar y desde el
     *    primer golpe queda FIJADO en ese jugador hasta que muere (deja de verse 2,5 s), aunque haya
     *    otro más cerca. Con el súper cargado salta sobre el fijado si se le escapó o si la pelea
     *    sigue (más de 1,5 s pegándole).
     */
    static final int[][] PRESETS = {
            //  forma rumbo disparo súper alcance distancia huir habilidades
            {2, 0, 2, 0, 1, 0, 0, 0},   // General
            {2, 0, 2, 0, 2, 2, 1, 0},   // Nita
            {0, 1, 7, 0, 2, 0, 0, 0},   // Edgar (cazar, ver arriba)
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
        if (!prefs.getBoolean("auto_edgar_v2", false)) {   // 23-oct: Edgar pasa a «cazar»
            SharedPreferences.Editor ed = prefs.edit();
            for (String k : KEYS) ed.remove("auto.Edgar." + k);
            ed.putBoolean("auto_edgar_v2", true).apply();
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
        // La pausa del lápiz no cuenta como «no se ve»: el fijado se conserva al retomar.
        long d = SystemClock.uptimeMillis() - pausedAt;
        lockSeen += d;
        fightSince += d;
        // Hacia donde Gonzalo dirigió con el lápiz manda (23-oct: «lo dirijo directo al enemigo, suelto
        // y se va en sentido contrario»: fijaba al más cercano en cualquier dirección). Durante
        // PEN_INTENT_MS solo cuentan los enemigos a ±60° de esa dirección; un fijado fuera de ella se suelta.
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
        int superReady = -1;   // 1 cargado, 0 no, −1 sin dato
        float[] enemy;         // el más cercano (o el fijado, al cazar)
        float dist = Float.MAX_VALUE;
    }

    private float maxHealth;   // ancho máximo visto de la barra verde = vida llena

    // Cazar (Edgar): el jugador al que se le pegó queda fijado hasta que deja de verse LOCK_LOST_MS.
    // 23-oct (partida de Gonzalo): soltaba al fijado y pasaba a otro sin eliminar a ninguno. Causas:
    // la pausa del lápiz contaba como «no se ve» (lo soltaba al retomar), y con 300 px de radio otro
    // enemigo cercano lo reemplazaba en los cuadros en que no se detectaba su barra. Ahora el radio
    // parte en 140 px y crece con el tiempo sin verlo, y se pierde a los 4 s.
    // 23-oct, v6.6.4 (Gonzalo: «sigo sintiendo que suelta al enemigo»): el radio crecía hasta 450 px y
    // otro enemigo de paso lo reemplazaba; ahora se busca donde DEBERÍA estar (última posición + su
    // velocidad) con radio de 140 a 300 px. Encima de él la barra no se ve (partida manual: «enemigos
    // 0» peleando), así que si se lo dejó de ver al alcance del golpe se espera 6 s antes de soltarlo.
    // Y los golpes se apuntan hacia el fijado: el toque simple pega al más cercano, que puede ser otro.
    // v6.6.7 (partida de Gonzalo 09:50, video seg. 80–89): golpeaba con el enemigo a 200–300 px,
    // donde el puño de Edgar no llega (≈1,7 casillas ≈ 180 px): gastaba la munición al aire y al quedar
    // pegado no tenía balas. Ahora golpea solo a < HIT_PX y fija/persigue desde LOCK_PX.
    private static final float LOCK_PX = 320;
    private static final float HIT_PX = 200, LOCK_MATCH_PX = 140, LOCK_MATCH_MAX_PX = 300, GADGET_REACH_PX = 1000;
    private static final long LOCK_LOST_MS = 4000, LOCK_LOST_CLOSE_MS = 6000;
    private float[] lock;
    private float lockVx, lockVy, lockLastDist = Float.MAX_VALUE;   // velocidad (px/ms) y distancia al verlo
    private long lockSeen, fightSince, pausedAt;

    private static final long PEN_INTENT_MS = 2500, PLAYER_HOLD_MS = 700;
    private double penIntentAng;
    private long penIntentUntil;
    // Última posición vista del jugador: en plena pelea la etiqueta se pierde cuadros enteros
    // («jugador —») y el Auto quedaba sin enemigo, dejaba de pegar y rehacía el movimiento.
    private float lastPx = -1, lastPy = -1;
    private long lastPAt;

    /**
     * ¿Es el número rojo de daño sobre la cabeza del propio jugador (p. ej. «2000»)? Rojo con borde
     * oscuro y blanco cerca: la visión lo tomaba por barra enemiga a 56–94 px y Edgar se quedaba
     * pegando al aire, fijado en ese fantasma (partida de Gonzalo, 23-oct, 09:40:40–09:40:50).
     */
    private static boolean selfGhost(float[] en, float px, float py) {
        return Math.abs(en[0] - px) < 130 && en[1] > py - 130 && en[1] < py + 60;
    }

    /** Fija a un enemigo nuevo. */
    private void setLock(float[] en, float dist, long now) {
        lock = en.clone();
        lockVx = lockVy = 0;
        lockLastDist = dist;
        lockSeen = now;
        fightSince = now;
    }

    private Sit sit() {
        Sit st = new Sit();
        Vision.Frame v = CaptureService.vision;
        long now = SystemClock.uptimeMillis();
        if (lock != null && now - lockSeen > (lockLastDist < LOCK_PX * 1.2f ? LOCK_LOST_CLOSE_MS : LOCK_LOST_MS)) {
            android.util.Log.i(TouchService.TAG, "Auto: objetivo fijado ya no se ve (derrotado o perdido), visto por última vez a "
                    + Math.round(lockLastDist) + " px");
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
        boolean intent = now < penIntentUntil && lock == null;
        st.seen = true;
        st.px = px;
        st.py = py;
        st.ammo = v.ammo;
        if (v.superYellow >= 0) st.superReady = v.superYellow > 0.2f ? 1 : 0;
        if (v.health > maxHealth) maxHealth = v.health;
        if (v.health >= 0 && maxHealth >= 20) st.health = Math.min(1f, v.health / maxHealth);
        java.util.List<float[]> foes = new java.util.ArrayList<>();
        for (float[] en : v.enemies) if (!selfGhost(en, px, py)) foes.add(en);
        for (float[] en : foes) {
            float d = (float) Math.hypot(en[0] - px, en[1] - py);
            if (intent && angleDiff(Math.atan2(-(en[1] - py), en[0] - px), penIntentAng) > Math.toRadians(60)) continue;
            if (d < st.dist) {
                st.dist = d;
                st.enemy = en;
            }
        }
        if (lock != null) {
            // La barra detectada más cerca de donde estaba el fijado es él (en 100 ms no se mueve más
            // de LOCK_MATCH_PX); si no aparece en este cuadro se sigue hacia su última posición.
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
                if (gap > 700) android.util.Log.i(TouchService.TAG, "Auto: fijado reencontrado tras " + gap + " ms");
                lock = match.clone();
                lockSeen = v.at;
                lockLastDist = (float) Math.hypot(lock[0] - px, lock[1] - py);
                ex = lock[0];
                ey = lock[1];
            }
            st.enemy = new float[]{ex, ey};
            st.dist = (float) Math.hypot(ex - px, ey - py);
        }
        // Sin fijado, un cuadro sin barra enemiga no cambia el modo: se recuerda el último 0,5 s
        // (antes alternaba perseguir ↔ lápiz y rehacía el gesto).
        if (st.enemy != null) {
            lastFoe = st.enemy.clone();
            lastFoeAt = v.at;
        } else if (lastFoe != null && v.at - lastFoeAt < 500) {
            st.enemy = lastFoe;
            st.dist = (float) Math.hypot(lastFoe[0] - px, lastFoe[1] - py);
        }
        return st;
    }

    private float[] lastFoe;
    private long lastFoeAt;

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
        boolean hunt = shot == SHOT_HUNT;
        // Mientras corre un súper o gadget no se rehace el gesto: el reemplazo lo cortaba a medias y el
        // juego no lo lanzaba (partida de Gonzalo con Edgar, 23-oct: 2 súpers, el botón siguió amarillo).
        if (now < actionUntil) return;
        if (hunt && st.enemy != null && st.dist < LOCK_PX) {
            // Edgar: los golpes van DENTRO de la ventana (window()), no un reemplazo por golpe: eso
            // re-apoyaba el joystick cada 0,2 s y lo dejaba casi quieto.
            if (lock == null) {
                setLock(st.enemy, st.dist, now);
                android.util.Log.i(TouchService.TAG, "Auto: fija objetivo a " + Math.round(st.dist) + " px");
            }
        }
        float range = RANGES[get("range")];
        float[] target = st.enemy != null && st.dist <= range ? st.enemy : null;
        int sup = get("super");
        // Súper: solo cargado (botón amarillo); sin dato de la pantalla, como máximo cada 6 s. Antes se
        // intentaba cada 1,5 s y cada intento re-apoyaba el joystick (partida de Gonzalo, 22-oct).
        boolean charged = st.superReady == 1 || (st.superReady < 0 && now - lastSuper > 6000);
        // Mínimo 5 s entre súpers: en la partida grabada el botón seguía amarillo tras presionarlo y se
        // reintentaba cada 1,5 s (26 intentos en ~52 s de Auto).
        boolean doSuper = charged && now - lastSuper > 5000 && (sup == 1 || ((sup == 0 || sup == 3) && target != null));
        int ab = get("abil");
        boolean near = target != null;
        boolean low = st.health >= 0 && st.health < 0.5f;
        boolean doGadget = now - lastGadget > 4000 && (ab == 1 || (ab == 0 && near) || (ab == 3 && low));
        boolean doHyper = now - lastHyper > 4000 && (ab == 1 || ((ab == 0 || ab == 3) && near));
        String why = null;
        if (hunt) {
            // Gadget: solo para alcanzar a un enemigo que está a la vista pero fuera del golpe.
            doGadget = st.enemy != null && st.dist >= LOCK_PX * 1.25f && st.dist < GADGET_REACH_PX && now - lastGadget > 6000;
            // Súper (salto) solo sobre el fijado: si se escapó del golpe, o si la pelea sigue.
            boolean escaped = lock != null && st.dist > LOCK_PX;
            boolean fighting = lock != null && st.dist < LOCK_PX && now - fightSince > 1500;
            // Apertura: así juega Gonzalo a mano (partida grabada, 23-oct): súper al enemigo a ~640 px y
            // la ráfaga apenas cae. Sin fijado, salta sobre el más cercano y lo fija.
            boolean opener = lock == null && st.enemy != null && st.dist > LOCK_PX;
            doSuper = charged && now - lastSuper > 1500 && sup != 2 && st.enemy != null && st.dist <= range && (escaped || fighting || opener);
            if (doSuper) {
                if (opener) setLock(st.enemy, st.dist, now);
                target = st.enemy;
                why = escaped ? "se escapó" : opener ? "apertura" : "sigue la pelea";
            }
        }
        // ¿Cambió el rumbo efectivo (enemigo, distancia, huida)? Entonces se rehace la ventana.
        double b = effectiveBase(st);
        // Cambio de rumbo con histéresis: la posición del enemigo tiembla de un cuadro a otro y rehacía el
        // movimiento 13 veces en 2 s (partida de Gonzalo); ahora > 50° y como máximo cada 0,8 s.
        // Al cazar se corrige antes (30°, cada 0,3 s): cuerpo a cuerpo, un rumbo viejo lo deja atrás.
        // v6.6.5 (partida de Gonzalo, 23-oct: «pausas y demoras fatales»): con 30°/0,3 s el gesto se
        // rehízo 89 veces, 66 a menos de 0,45 s; cada rehecho re-apoya el joystick y reinicia la ráfaga.
        // Ahora al cazar: persiguiendo 45° cada ≥ 0,6 s; al alcance del golpe solo si el ángulo se va
        // más de 80° (de cerca cualquier paso lo mueve mucho) y como mucho cada 1 s.
        boolean hitNow = hunt && inHit(st);
        long turnGap = !hunt ? 800 : hitNow && windowHit ? 1000 : 600;
        double turnAng = Math.toRadians(!hunt ? 50 : hitNow && windowHit ? 80 : 45);
        // v6.6.10 (partida de 10:17–10:26): aún 158 rehechos, mediana 0,54 s. Se registra el motivo.
        String why2 = null;
        if (hunt && windowHit != hitNow && now - lastTurn > 400) why2 = hitNow ? "entra al golpe" : "sale del golpe";
        else if (now - lastTurn > turnGap && windowMode != modeOf(st)) why2 = "modo " + windowMode + "→" + modeOf(st);
        else if (now - lastTurn > turnGap && !Double.isNaN(b) && !Double.isNaN(windowBase) && angleDiff(b, windowBase) > turnAng)
            why2 = "giro " + Math.round(Math.toDegrees(angleDiff(b, windowBase))) + "°";
        boolean turn = why2 != null;
        if (taps == 0 && hold == 0 && aimHold == 0 && !doSuper && !doGadget && !doHyper && !turn) return;
        if (turn) {
            lastTurn = now;
            if (hunt) android.util.Log.i(TouchService.TAG, "Auto: rehace (" + why2 + ")");
        }
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
        if (doGadget) {
            press(p, "gadget", t);
            t += 120;
            lastGadget = now;
            if (hunt) {
                android.util.Log.i(TouchService.TAG, "Auto: gadget para alcanzar enemigo a " + Math.round(st.dist) + " px");
                actionUntil = now + t + 100;
            }
        }
        if (doSuper) {
            float[] btn = svc.calib().get("super");
            if (btn != null) {
                long end;
                if (hunt) {
                    // Edgar: pelea encima → toque (el juego salta sobre el más cercano, que es él);
                    // se escapó → arrastre lento hacia él, largo según la distancia (círculo entero =
                    // alcance). Empieza ≥ 150 ms tras el re-apoyo del joystick y nada lo corta.
                    t = Math.max(t, 150);
                    // El juego atiende un solo control apuntando a la vez: un golpe apuntado en curso
                    // hacía ignorar el súper (09:40:31–09:40:46, 4 súpers, el botón siguió amarillo).
                    // Se quitan los golpes de la ventana que se cruzan con el súper.
                    float[] atk = svc.calib().get("attack");
                    if (atk != null) {
                        final long s0 = t - 140, s1 = t + 460;
                        p.touches.removeIf(x -> {
                            Pattern.Point a = x.pts.get(0), z = x.pts.get(x.pts.size() - 1);
                            return a.x == atk[0] && a.y == atk[1] && z.t > s0 && a.t < s1;
                        });
                    }
                    if ("sigue la pelea".equals(why)) {
                        press(p, "super", t, 150);
                        end = t + 150;
                    } else {
                        double ang = Math.atan2(target[1] - st.py, target[0] - st.px);
                        float f = Math.max(0.5f, Math.min(1f, st.dist / range));
                        float r = f >= 0.95f ? svc.calib().aimRadius() * 1.8f : svc.calib().aimRadius() * f;
                        float ex = btn[0] + (float) (r * Math.cos(ang)), ey = btn[1] + (float) (r * Math.sin(ang));
                        Pattern.Touch x = new Pattern.Touch();
                        x.pts.add(new Pattern.Point(t, btn[0], btn[1]));
                        x.pts.add(new Pattern.Point(t + 80, btn[0], btn[1]));
                        x.pts.add(new Pattern.Point(t + 280, ex, ey));
                        x.pts.add(new Pattern.Point(t + 400, ex, ey));
                        p.touches.add(x);
                        end = t + 400;
                    }
                    actionUntil = now + end + 150;
                } else if (target != null && sup != 3) {   // «con enemigo (toque)»: toque simple, el juego apunta
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
                android.util.Log.i(TouchService.TAG, "Auto: súper" + (target != null ? " hacia enemigo a " + Math.round(st.dist) + " px" : "")
                        + (why != null ? " (" + why + ")" : ""));
            }
        }
        if (taps > 0 || hold > 0 || aimHold > 0) lastShot = now;
        if (p.touches.isEmpty()) return;
        p.normalize();
        player.replaceNow(p, this::window);
    }

    private long lastTurn, lastHook, actionUntil;
    private boolean windowHit;

    /** Al cazar: ¿el enemigo está al alcance del golpe? (con margen para no titubear en el borde). */
    private boolean inHit(Sit st) {
        if (st.enemy == null) return false;
        return st.dist < (windowHit ? HIT_PX * 1.4f : HIT_PX);   // entra a 200, sale a 280: sin titubeo
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
        if (get("shot") == SHOT_HUNT) return MODE_CHASE;   // Edgar: siempre encima del enemigo (o del fijado)
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
        // huir, y cazar con un enemigo a la vista: en línea recta
        int shape = mode == MODE_FLEE || (mode == MODE_CHASE && get("shot") == SHOT_HUNT) ? 0 : get("shape");
        // Edgar sin enemigo a la vista y con el lápiz en el centro: antes quedaba quieto (el toque del
        // lápiz al apoyarse en el joystick deja «centro»); ahora recorre un círculo en el sitio.
        if (get("shot") == SHOT_HUNT && mode == MODE_PEN && center && shape <= 1) shape = 2;
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
        boolean hit = get("shot") == SHOT_HUNT && inHit(st);
        windowHit = hit;
        if (hit) {   // Edgar al alcance: golpes seguidos cada 0,17 s (el juego ignora los sin munición)
            // Toque simple (el juego apunta solo): Gonzalo, 23-oct, «el ataque dirigido no sirve, basta con
            // mantener cerca al brawler objetivo y atacar auto». El seguimiento del fijado lo da el joystick.
            for (long t = 40; t < WINDOW_MS; t += 170) press(p, "attack", t);
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
