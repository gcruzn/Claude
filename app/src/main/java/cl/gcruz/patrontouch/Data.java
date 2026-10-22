package cl.gcruz.patrontouch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datos del usuario: patrones (escritos como instrucciones), perfiles de disparo y sets
 * (perfil + patrón y repeticiones para cada flecha de la cruceta).
 */
public class Data {

    public static final String[] ARROWS = {"up", "down", "left", "right"};
    public static final String[] ARROW_ICONS = {"▲", "▼", "◀", "▶"};

    public static class PatternDef {
        public String id, name, script;
    }

    public static class Profile {
        public String id, name;
        /** tap | hold | aim */
        public String attack = "tap";
        /** ms entre disparos (toque/apuntado). */
        public int interval = 550;
        /** ms que se mantiene apretado (hold). */
        public int hold = 1000;
        /** Dirección del disparo apuntado: "move" (hacia donde te mueves) o una dirección. */
        public String aimDir = "move";
        /** Súper: "auto" (toque) | "move" | una dirección. */
        public String superMode = "auto";

        public String summary() {
            String a;
            switch (attack) {
                case "hold": a = "mantener " + fmt(hold) + " s"; break;
                case "aim": a = "apuntado " + Script.dirLabel(aimDir) + " cada " + fmt(interval) + " s"; break;
                default: a = "toque cada " + fmt(interval) + " s";
            }
            String s = "auto".equals(superMode) ? "súper auto" : "súper apuntado " + Script.dirLabel(superMode);
            return a + " · " + s;
        }
    }

    public static class Arrow {
        public String patternId;
        public int reps = 1;
    }

    /** Máximo de botones de patrón en la botonera (más el Random). */
    public static final int MAX_SLOTS = 6;

    public static class PadSet {
        public String id, name;
        /** Brawler y modo de batalla: el set es «Edgar · Supervivencia». */
        public String brawler = "", mode = "";
        /** Patrones en el orden de los botones de la botonera (1..N). */
        public final List<String> slots = new ArrayList<>();
        /** Legado v3 (cruceta): perfil del set y patrón por flecha; solo se leen para migrar. */
        public String profileId = "";
        public final Map<String, Arrow> arrows = new LinkedHashMap<>();

        public String title() {
            if (brawler.isEmpty()) return name;
            return mode.isEmpty() ? brawler : brawler + " · " + mode;
        }
    }

    public final List<PatternDef> patterns = new ArrayList<>();
    public final List<Profile> profiles = new ArrayList<>();
    public final List<PadSet> sets = new ArrayList<>();
    public String activeSet = "", globalProfile = "normal";
    /** Lotes de patrones de fábrica ya agregados (para no volver a sembrar lo que el usuario borró). */
    public final List<String> seeds = new ArrayList<>();

    private final SharedPreferences prefs;

    private Data(Context ctx) {
        prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    public static Data load(Context ctx) {
        Data d = new Data(ctx);
        String s = d.prefs.getString("data", null);
        boolean ok = false;
        if (s != null) {
            try {
                d.fromJson(new JSONObject(s));
                ok = !d.sets.isEmpty() && !d.profiles.isEmpty();
            } catch (Exception ignored) {
            }
        }
        if (!ok) {
            d.patterns.clear();
            d.profiles.clear();
            d.sets.clear();
            d.seeds.clear();
            d.seed();
            d.save();
        }
        boolean changed = d.applySeeds();
        for (String f : new String[]{"brawlers1.json"}) changed |= d.applyAssetSeed(ctx, f);
        if (!d.seeds.contains("completos1")) {
            d.completeBrawlerPatterns();
            d.seeds.add("completos1");
            changed = true;
        }
        if (changed) d.save();
        return d;
    }

    public void save() {
        try {
            prefs.edit().putString("data", toJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- consultas

    public PatternDef pattern(String id) {
        for (PatternDef p : patterns) if (p.id.equals(id)) return p;
        return null;
    }

    public Profile profile(String id) {
        for (Profile p : profiles) if (p.id.equals(id)) return p;
        return null;
    }

    public PadSet set(String id) {
        for (PadSet s : sets) if (s.id.equals(id)) return s;
        return null;
    }

    public PadSet active() {
        PadSet s = set(activeSet);
        if (s == null && !sets.isEmpty()) {
            s = sets.get(0);
            activeSet = s.id;
        }
        return s;
    }

    /** Perfil efectivo de un set (el propio o el global). */
    public Profile profileFor(PadSet s) {
        Profile p = s != null && !s.profileId.isEmpty() ? profile(s.profileId) : null;
        if (p == null) p = profile(globalProfile);
        if (p == null && !profiles.isEmpty()) p = profiles.get(0);
        if (p == null) p = new Profile();
        return p;
    }

    public static String newId(String prefix) {
        return prefix + Long.toString(System.currentTimeMillis(), 36) + (int) (Math.random() * 1000);
    }

    // ---------------------------------------------------------------- valores iniciales

    private PatternDef addPattern(String id, String name, String script) {
        PatternDef p = new PatternDef();
        p.id = id;
        p.name = name;
        p.script = script;
        patterns.add(p);
        return p;
    }

    private void addProfile(String id, String name, String attack, int interval, int hold, String aimDir, String superMode) {
        Profile p = new Profile();
        p.id = id;
        p.name = name;
        p.attack = attack;
        p.interval = interval;
        p.hold = hold;
        p.aimDir = aimDir;
        p.superMode = superMode;
        profiles.add(p);
    }

    private void seed() {
        String[][] dirs = {{"up", "arriba", "↑"}, {"down", "abajo", "↓"}, {"left", "izquierda", "←"}, {"right", "derecha", "→"}};
        for (String[] d : dirs) {
            addPattern("p_" + d[0], "Completo " + d[2],
                    "mover " + d[1] + "\ngadget\ndisparar 2\nhiper\nsúper\ndisparar 2\nparar");
        }
        addPattern("p_kite", "Kite lateral",
                "mover izquierda 0.8\ndisparar\nmover derecha 0.8\ndisparar\nmover abajo 0.4\ndisparar\nparar");
        addPattern("p_entrada", "Entrada con súper",
                "mover arriba 0.6\nhiper\nsúper apuntado arriba\nmover arriba\ndisparar 3\nparar");
        addPattern("p_escape", "Escape",
                "mover abajo\ngadget\nesperar 1.2\nmover abajo-izquierda 0.5\nparar");

        addProfile("rapido", "Rápido", "tap", 300, 1000, "move", "auto");
        addProfile("normal", "Normal", "tap", 550, 1000, "move", "auto");
        addProfile("lento", "Lento", "tap", 800, 1000, "move", "auto");
        addProfile("mantener", "Mantener", "hold", 550, 1200, "move", "auto");
        addProfile("apuntado", "Apuntado", "aim", 550, 1000, "move", "move");
        addProfile("lanzador", "Lanzador al frente", "aim", 750, 1000, "up", "up");

        PadSet s = new PadSet();
        s.id = "general";
        s.name = "General";
        for (String a : ARROWS) {
            Arrow ar = new Arrow();
            ar.patternId = "p_" + a;
            s.arrows.put(a, ar);
        }
        sets.add(s);
        activeSet = "general";
        globalProfile = "normal";
    }

    /**
     * Lotes que se agregan una sola vez a los datos existentes, sin pisar nada del usuario:
     * solo entra lo que falta por id, y el lote queda anotado aunque después se borre.
     */
    boolean applySeeds() {
        boolean changed = false;
        if (!seeds.contains("edgar1")) {
            seedEdgar();
            seeds.add("edgar1");
            changed = true;
        }
        if (!seeds.contains("edgar2")) {
            // Solo se reemplaza lo que sigue igual que en edgar1: lo editado por el usuario se respeta.
            for (String[] v2 : EDGAR2) {
                PatternDef p = pattern(v2[0]);
                String v1 = null;
                for (String[] x : EDGAR1) if (x[0].equals(v2[0])) v1 = x[2];
                if (p != null && p.script.equals(v1)) {
                    p.name = v2[1];
                    p.script = v2[2];
                }
            }
            seeds.add("edgar2");
            changed = true;
        }
        if (!seeds.contains("charlie1")) {
            seedCharlie();
            seeds.add("charlie1");
            changed = true;
        }
        if (!seeds.contains("v4")) {
            migrateV4();
            seeds.add("v4");
            changed = true;
        }
        if (!seeds.contains("kenji1")) {
            seedKenji();
            seeds.add("kenji1");
            changed = true;
        }
        if (!seeds.contains("kenji2")) {
            seedKenji2();
            seeds.add("kenji2");
            changed = true;
        }
        if (!seeds.contains("formas1")) {
            seedFormas();
            seeds.add("formas1");
            changed = true;
        }
        if (!seeds.contains("circular1")) {
            seedCircular();
            seeds.add("circular1");
            changed = true;
        }
        if (!seeds.contains("aleatorio1")) {
            seedAleatorio();
            seeds.add("aleatorio1");
            changed = true;
        }
        if (!seeds.contains("corta1")) {
            // Vuelta corta (~1,4 s): al levantar el S Pen la ráfaga sale de inmediato y, aunque el
            // lápiz haya tapado una vuelta, la siguiente llega en poco más de 1 s.
            if (pattern("ale_corta") == null) addPattern("ale_corta", "Aleatorio · Corta",
                    "# Ráfaga de 3 de inmediato hacia donde movías el S Pen; luego gadget, hiper y súper (~1,4 s)\n"
                            + "disparo toque cada 0.15\n"
                            + "mover dirigido\ndisparar 3\n"
                            + "gadget\nhiper\nsúper auto\n"
                            + "esperar 0.1\nparar");
            PadSet s = set("aleatorio");
            if (s != null && !s.slots.contains("ale_corta") && s.slots.size() < MAX_SLOTS) s.slots.add(0, "ale_corta");
            seeds.add("corta1");
            changed = true;
        }
        if (!seeds.contains("corta2")) {
            // 20-oct, tras probarlo Gonzalo: el primer tramo va EXACTO hacia donde movía el lápiz
            // («mover ultima») y después aleatorio hacia ese lado. Solo si sigue como venía.
            PatternDef c = pattern("ale_corta");
            if (c != null && c.script.contains("mover dirigido\ndisparar 3\ngadget")) {
                c.script = "# Primero exacto hacia donde movías el S Pen, luego ±45° al azar; ráfagas de 3 y habilidades (~1,7 s)\n"
                        + "disparo toque cada 0.15\n"
                        + "mover ultima\ndisparar 3\n"
                        + "mover dirigido\ngadget\nhiper\nsúper auto\n"
                        + "disparar 3\nparar";
            }
            PatternDef d = pattern("ale_dirigido");
            String from = "disparo toque cada 0.15\nmover dirigido\n", to = "disparo toque cada 0.15\nmover ultima\n";
            if (d != null && d.script.contains(from)) d.script = d.script.replace(from, to);
            seeds.add("corta2");
            changed = true;
        }
        if (!seeds.contains("agresiva1")) {
            // 20-oct, pedido de Gonzalo: Corta más agresiva. En la grabación con Nita el primer
            // disparo salía a 0,1–0,3 s, pero la ráfaga de 3 en 0,3 s gastaba la munición y el resto
            // del ciclo eran habilidades (el gadget de Nita ni siquiera aplica). Ahora: 1 disparo al
            // instante y luego 1 cada 0,4 s mientras se mueve; habilidades al final de la vuelta.
            // Se quitan Ráfagas y Dirigido del set y se borran.
            PatternDef c = pattern("ale_corta");
            if (c != null) {
                c.name = "Aleatorio · Corta";
                c.script = "# Disparo al instante hacia tu última dirección y luego 1 cada 0,4 s; habilidades al final (~3,6 s)\n"
                        + "disparo toque cada 0.4\n"
                        + "mover ultima\ndisparar 3\n"
                        + "mover dirigido\ndisparar 3\n"
                        + "mover dirigido\ndisparar 2\n"
                        + "gadget\nhiper\nsúper auto\nparar";
            }
            PadSet s = set("aleatorio");
            for (String id : new String[]{"ale_rafagas", "ale_dirigido"}) {
                if (s != null) s.slots.remove(id);
                boolean used = false;
                for (PadSet o : sets) if (o.slots.contains(id)) used = true;
                PatternDef p = pattern(id);
                if (!used && p != null) patterns.remove(p);
            }
            seeds.add("agresiva1");
            changed = true;
        }
        if (!seeds.contains("pruebas1")) {
            // 20-oct, pedido de Gonzalo: patrones aislados para probar por separado qué falla al
            // soltar el dedo (el disparo o la dirección). Set propio y activo.
            if (pattern("prueba_disparo") == null) addPattern("prueba_disparo", "Prueba · Disparo",
                    "# Solo dispara, sin moverse: 3 tiros, 1 cada 0,4 s\n"
                            + "disparo toque cada 0.4\ndisparar 3\nesperar 0.3");
            if (pattern("prueba_mover") == null) addPattern("prueba_mover", "Prueba · Dirección",
                    "# Solo se mueve hacia tu última dirección, sin disparar\n"
                            + "mover ultima 1.5\nparar\nesperar 0.3");
            if (set("pruebas") == null) {
                PadSet s = new PadSet();
                s.id = "pruebas";
                s.brawler = "General";
                s.mode = "Pruebas";
                s.name = "Pruebas";
                s.slots.add("prueba_disparo");
                s.slots.add("prueba_mover");
                sets.add(s);
            }
            activeSet = "pruebas";
            seeds.add("pruebas1");
            changed = true;
        }
        if (!seeds.contains("pruebas2")) {
            // 20-oct: Corta como botón 3 de Pruebas, para probarla sin cambiar de set. Medido en video:
            // al soltar el dedo ya no hay corte; el que se sentía era el «parar» y la espera al final
            // de cada vuelta. Dirección y Corta ya no sueltan el joystick entre vueltas.
            PatternDef m = pattern("prueba_mover");
            if (m != null) m.script = "# Solo se mueve hacia tu última dirección, sin disparar ni soltar el joystick\n"
                    + "mover ultima 2";
            PatternDef c = pattern("ale_corta");
            if (c != null) c.script = "# Disparo al instante hacia tu última dirección y luego 1 cada 0,4 s; habilidades al final (~3,6 s)\n"
                    + "disparo toque cada 0.4\n"
                    + "mover ultima\ndisparar 3\n"
                    + "mover dirigido\ndisparar 3\n"
                    + "mover dirigido\ndisparar 2\n"
                    + "gadget\nhiper\nsúper auto";
            PadSet s = set("pruebas");
            if (s != null && pattern("ale_corta") != null && !s.slots.contains("ale_corta")
                    && s.slots.size() < MAX_SLOTS) s.slots.add("ale_corta");
            seeds.add("pruebas2");
            changed = true;
        }
        if (!seeds.contains("pruebas3")) {
            // 21-oct, pedido de Gonzalo: Edgar según los videos pro (tramos cortos de 0,5 s con giros
            // variados, golpe seguido, salto al final). Botón 4 de Pruebas.
            if (pattern("edgar_pro") == null) addPattern("edgar_pro", "Edgar · Pro",
                    "# Tramos de 0,5 s hacia tu última dirección con giros de ±45°, golpe cada 0,3 s; gadget, híper y salto (súper auto) cada vuelta (~2,2 s)\n"
                            + "disparo toque cada 0.3\n"
                            + "mover ultima 0.5 disparando\nmover dirigido 0.5 disparando\n"
                            + "mover ultima 0.5 disparando\nmover dirigido 0.5 disparando\n"
                            + "gadget\nhiper\nsúper auto");
            PadSet s = set("pruebas");
            if (s != null && !s.slots.contains("edgar_pro") && s.slots.size() < MAX_SLOTS) s.slots.add("edgar_pro");
            seeds.add("pruebas3");
            changed = true;
        }
        if (!seeds.contains("pruebas4")) {
            // 21-oct, pedido de Gonzalo: Corta 2, en línea recta hacia la última dirección, disparos de
            // a 2 y tras cada par híper y súper auto. Botón 5 de Pruebas.
            if (pattern("ale_corta2") == null) addPattern("ale_corta2", "Aleatorio · Corta 2",
                    "# Recto hacia tu última dirección; 2 disparos + híper + súper, 3 veces; gadget al final (~3,8 s)\n"
                            + "disparo toque cada 0.4\nmover ultima\n"
                            + "disparar 2\nhiper\nsúper auto\n" + "disparar 2\nhiper\nsúper auto\n" + "disparar 2\nhiper\nsúper auto\n"
                            + "gadget");
            PadSet s = set("pruebas");
            if (s != null && !s.slots.contains("ale_corta2") && s.slots.size() < MAX_SLOTS) s.slots.add("ale_corta2");
            seeds.add("pruebas4");
            changed = true;
        }
        if (!seeds.contains("pruebas5")) {
            // 21-oct, pedido de Gonzalo: multipatrón con relojes separados de ataque, híper + súper y
            // gadget (instrucción «cada»). Botón 6 de Pruebas.
            if (pattern("multi") == null) addPattern("multi", "Multipatrón",
                    "# Recto hacia tu última dirección; disparo cada 0,4 s, híper + súper cada 1,2 s, gadget cada 3,6 s\n"
                            + "mover ultima 3.6\n"
                            + "cada 0.4 disparar\n"
                            + "cada 1.2 hiper super\n"
                            + "cada 3.6 gadget");
            PadSet s = set("pruebas");
            if (s != null && !s.slots.contains("multi") && s.slots.size() < MAX_SLOTS) s.slots.add("multi");
            seeds.add("pruebas5");
            changed = true;
        }
        if (!seeds.contains("pruebas6")) {
            // 22-oct: Auto-tap al estilo del Auto Clicker de Gonzalo (toques sueltos de 10 ms): ataque
            // cada 0,11 s, híper + súper cada 1,2 s, gadget cada 3,6 s, sin mover (mueve él con el
            // lápiz). Reemplaza a «Prueba · Disparo» en el botón 1 (el set ya tiene 6).
            if (pattern("autotap") == null) addPattern("autotap", "Auto-tap",
                    "# Solo toques sueltos, sin mover: ataque cada 0,11 s, híper + súper cada 1,2 s, gadget cada 3,6 s\n"
                            + "esperar 3.6\n"
                            + "cada 0.11 disparar\n"
                            + "cada 1.2 hiper super\n"
                            + "cada 3.6 gadget");
            PadSet s = set("pruebas");
            if (s != null && !s.slots.contains("autotap")) {
                int i = s.slots.indexOf("prueba_disparo");
                if (i >= 0) s.slots.set(i, "autotap");
                else if (s.slots.size() < MAX_SLOTS) s.slots.add("autotap");
            }
            seeds.add("pruebas6");
            changed = true;
        }
        if (!seeds.contains("pruebas7")) {
            // 22-oct, pedido de Gonzalo: Pruebas con un solo patrón, golpe y avance hacia la última
            // dirección, para medir qué tan fluido retoma esas dos acciones al levantar el lápiz.
            if (pattern("golpe_avance") == null) addPattern("golpe_avance", "Golpe + avance",
                    "# Avanza hacia tu última dirección y golpea cada 0,11 s; nada más\n"
                            + "mover ultima 3\n"
                            + "cada 0.11 disparar");
            PadSet s = set("pruebas");
            if (s != null) {
                s.slots.clear();
                s.slots.add("golpe_avance");
                activeSet = "pruebas";
            }
            seeds.add("pruebas7");
            changed = true;
        }
        if (!seeds.contains("pruebas8")) {
            // 22-oct, pedido de Gonzalo: dos botones que se encienden por separado y corren a la vez
            // (canales, ver TouchService): disparo cada 0,4 s y joystick sostenido hacia la última
            // dirección.
            if (pattern("canal_disparo") == null) addPattern("canal_disparo", "Disparo 0,4",
                    "# Canal: golpe cada 0,4 s (se junta con los otros canales encendidos)\n"
                            + "cada 0.4 disparar");
            if (pattern("canal_joystick") == null) addPattern("canal_joystick", "Joystick",
                    "# Canal: joystick sostenido hacia tu última dirección\n"
                            + "mover ultima 3");
            PadSet s = set("pruebas");
            if (s != null) {
                s.slots.clear();
                s.slots.add("canal_disparo");
                s.slots.add("canal_joystick");
                activeSet = "pruebas";
            }
            seeds.add("pruebas8");
            changed = true;
        }
        if (!seeds.contains("auto1")) {
            // 22-oct, pedido de Gonzalo: un solo botón «Auto» (forma + disparo según munición + súper con
            // enemigo al alcance), configurable desde la botonera. El script no se usa: lo maneja
            // AutoController.
            if (pattern("auto_main") == null) addPattern("auto_main", "Auto",
                    "# Botón Auto: se configura desde la botonera (⚙ Configurar Auto)\nesperar 1");
            if (set("auto") == null) {
                PadSet s = new PadSet();
                s.id = "auto";
                s.brawler = "General";
                s.mode = "Auto";
                s.name = "Auto";
                s.slots.add("auto_main");
                sets.add(s);
            }
            activeSet = "auto";
            seeds.add("auto1");
            changed = true;
        }
        if (!seeds.contains("dirigido1") && !seeds.contains("agresiva1")) { // agresiva1 lo reemplaza
            // Sigue la última dirección del S Pen (±45° al azar). Parte disparando: al levantar el
            // lápiz se reanuda al instante y la primera ráfaga sale sin esperar al gadget.
            if (pattern("ale_dirigido") == null) addPattern("ale_dirigido", "Aleatorio · Dirigido",
                    "# Hacia donde movías el S Pen (±45° al azar), ráfagas de 3; gadget, hiper y súper cada vuelta\n"
                            + "disparo toque cada 0.15\n"
                            + "mover dirigido\n" + RAFAGA_TRAMO
                            + "gadget\n"
                            + "mover dirigido\n" + RAFAGA_TRAMO
                            + "mover dirigido\n" + RAFAGA_TRAMO
                            + "hiper\nsúper auto\n"
                            + "mover dirigido\n" + RAFAGA_TRAMO
                            + "parar");
            PadSet s = set("aleatorio");
            if (s != null && !s.slots.contains("ale_dirigido") && s.slots.size() < MAX_SLOTS) s.slots.add("ale_dirigido");
            seeds.add("dirigido1");
            changed = true;
        }
        return changed;
    }

    /**
     * Formas (20-oct, pedido de Gonzalo): presión constante (disparo con toque cada 0,4 s durante
     * todo el recorrido) y evasión (el joystick dibuja una figura cerrada que vuelve al punto de
     * partida, así no se aleja del objetivo). Gadget, hiper y súper una vez por vuelta, en esquinas
     * distintas y con la hiper antes del súper; si no están cargados, el toque no hace nada.
     * Direcciones de pantalla (arriba = hacia arriba en la pantalla). Pensados para el bucle.
     * Gadget (0,2 s), hiper (0,25 s) y súper (0,3 s) corren el reloj con el joystick empujado: van
     * dentro de un lado («mover X» sin tiempo) y ese lado se acorta en lo que duran, para que cada
     * lado mida lo que debe y la figura cierre (si no, cada vuelta se desplaza hacia un costado).
     */
    static final String[][] FORMAS1 = {
            {"f_cuadrado", "Formas · Cuadrado",
                    "# Cuatro lados de 0,6 s (~2,5 s por vuelta)\ndisparo toque cada 0.4\n"
                            + "mover derecha\ngadget\nmover derecha 0.4 disparando\n"
                            + "mover arriba 0.6 disparando\n"
                            + "mover izquierda\nhiper\nsúper auto\nmover izquierda 0.05\n"
                            + "mover abajo 0.6 disparando\nparar"},
            {"f_circulo", "Formas · Círculo",
                    "# Ocho tramos de 0,3 s girando (~2,5 s por vuelta)\ndisparo toque cada 0.4\n"
                            + "mover derecha\ngadget\nmover derecha 0.1 disparando\n"
                            + "mover arriba-derecha 0.3 disparando\nmover arriba 0.3 disparando\n"
                            + "mover arriba-izquierda\nhiper\nmover arriba-izquierda 0.05\n"
                            + "mover izquierda\nsúper auto\n"
                            + "mover abajo-izquierda 0.3 disparando\nmover abajo 0.3 disparando\n"
                            + "mover abajo-derecha 0.3 disparando\nparar"},
            {"f_triangulo", "Formas · Triángulo",
                    "# Dos diagonales de 0,7 s y la base de 1 s (~2,5 s por vuelta)\ndisparo toque cada 0.4\n"
                            + "mover arriba-derecha\ngadget\nmover arriba-derecha 0.5 disparando\n"
                            + "mover abajo-derecha\nhiper\nsúper auto\nmover abajo-derecha 0.15\n"
                            + "mover izquierda 1 disparando\nparar"},
            {"f_rect_h", "Formas · Rectángulo horizontal",
                    "# Lados de 1 s a lo ancho y 0,4 s a lo alto (~2,9 s por vuelta)\ndisparo toque cada 0.4\n"
                            + "mover derecha\ngadget\nmover derecha 0.8 disparando\n"
                            + "mover arriba 0.4 disparando\n"
                            + "mover izquierda\nhiper\nsúper auto\nmover izquierda 0.45 disparando\n"
                            + "mover abajo 0.4 disparando\nparar"},
            {"f_rect_v", "Formas · Rectángulo vertical",
                    "# Lados de 1 s a lo alto y 0,4 s a lo ancho (~2,9 s por vuelta)\ndisparo toque cada 0.4\n"
                            + "mover arriba\ngadget\nmover arriba 0.8 disparando\n"
                            + "mover derecha 0.4 disparando\n"
                            + "mover abajo\nhiper\nsúper auto\nmover abajo 0.45 disparando\n"
                            + "mover izquierda 0.4 disparando\nparar"},
    };

    /**
     * Círculo de 8 tramos de 0,25 s disparando (cada toque se auto-apunta), con gadget en el primer
     * tramo y hiper + súper en el 5.º y 6.º. Arriba-derecha dura 0,3 s para compensar los 0,05 s
     * que el súper (0,3 s) estira el tramo abajo-izquierda: así la vuelta cierra.
     */
    static final String CIRCULO = "disparo toque cada 0.4\n"
            + "mover derecha\ngadget\nmover derecha 0.05 disparando\n"
            + "mover arriba-derecha 0.3 disparando\nmover arriba 0.25 disparando\n"
            + "mover arriba-izquierda 0.25 disparando\n"
            + "mover izquierda\nhiper\n"
            + "mover abajo-izquierda\nsúper auto\n"
            + "mover abajo 0.25 disparando\nmover abajo-derecha 0.25 disparando\n";

    /**
     * Circulares (20-oct, pedido de Gonzalo): uno en el sitio y cuatro que avanzan girando hacia
     * arriba, abajo, izquierda o derecha (tramo marcado «escape»). Con el botón 🏃 Escape quedan sin
     * disparos ni habilidades, con giros a la mitad y el avance ×3: casi todo el tiempo es huida.
     */
    static final String[][] CIRCULAR1 = {
            {"cir_sitio", "Circular · En el sitio",
                    "# Gira sin moverse de su sitio (~2,1 s por vuelta)\n" + CIRCULO + "parar"},
            {"cir_arriba", "Circular · Arriba",
                    "# Gira y avanza hacia arriba (~2,6 s por vuelta)\n" + CIRCULO + "mover arriba 0.5 disparando escape\nparar"},
            {"cir_abajo", "Circular · Abajo",
                    "# Gira y avanza hacia abajo (~2,6 s por vuelta)\n" + CIRCULO + "mover abajo 0.5 disparando escape\nparar"},
            {"cir_izquierda", "Circular · Izquierda",
                    "# Gira y avanza hacia la izquierda (~2,6 s por vuelta)\n" + CIRCULO + "mover izquierda 0.5 disparando escape\nparar"},
            {"cir_derecha", "Circular · Derecha",
                    "# Gira y avanza hacia la derecha (~2,6 s por vuelta)\n" + CIRCULO + "mover derecha 0.5 disparando escape\nparar"},
    };

    /**
     * Aleatorio · Ráfagas (20-oct, pedido de Gonzalo): 6 tramos en dirección al azar (cada uno
     * seguido de su opuesto, para no irse contra una pared), en cada tramo 3 disparos lo más rápido
     * que acepta el juego (un toque cada 0,15 s, el mínimo del compilador es 0,12). Gadget al inicio
     * de la vuelta; hiper y súper a la mitad. ~4,5 s por vuelta; el recorrido cambia en cada vuelta.
     */
    static final String RAFAGA_TRAMO = "disparar 3\nesperar 0.3\n";

    private void seedAleatorio() {
        if (pattern("ale_rafagas") == null) addPattern("ale_rafagas", "Aleatorio · Ráfagas",
                "# Movimiento al azar con ráfagas de 3 disparos; gadget, hiper y súper cada vuelta (~4,5 s)\n"
                        + "disparo toque cada 0.15\n"
                        + "gadget\n"
                        + "mover aleatorio\n" + RAFAGA_TRAMO
                        + "mover opuesto\n" + RAFAGA_TRAMO
                        + "mover aleatorio\n" + RAFAGA_TRAMO
                        + "parar\nhiper\nsúper auto\n"
                        + "mover opuesto\n" + RAFAGA_TRAMO
                        + "mover aleatorio\n" + RAFAGA_TRAMO
                        + "mover opuesto\n" + RAFAGA_TRAMO
                        + "parar");
        if (set("aleatorio") == null) {
            PadSet s = new PadSet();
            s.id = "aleatorio";
            s.brawler = "General";
            s.mode = "Aleatorio";
            s.name = "Aleatorio";
            s.slots.add("ale_rafagas");
            sets.add(s);
            activeSet = s.id;
        }
    }

    private void seedCircular() {
        for (String[] p : CIRCULAR1) if (pattern(p[0]) == null) addPattern(p[0], p[1], p[2]);
        if (set("circular") == null) {
            PadSet s = new PadSet();
            s.id = "circular";
            s.brawler = "General";
            s.mode = "Circular";
            s.name = "Circular";
            for (String[] p : CIRCULAR1) s.slots.add(p[0]);
            sets.add(s);
            activeSet = s.id;
        }
    }

    private void seedFormas() {
        for (String[] p : FORMAS1) if (pattern(p[0]) == null) addPattern(p[0], p[1], p[2]);
        if (set("formas") == null) {
            PadSet s = new PadSet();
            s.id = "formas";
            s.brawler = "General";
            s.mode = "Formas";
            s.name = "Formas";
            for (String[] p : FORMAS1) s.slots.add(p[0]);
            sets.add(s);
        }
    }

    /**
     * kenji2 (19-oct, probado en Terrenos): el gadget de Gonzalo es Hosomaki (cura el daño de los
     * últimos 3 s), no Dashi Dash, y su Kenji no tiene hipercarga. Se agrega «Curación y retirada»
     * y reemplaza a Gadget y a Hiper en los sets que siguen como venían de fábrica.
     */
    private void seedKenji2() {
        if (pattern("k_curacion") == null) addPattern("k_curacion", "Kenji · Curación y retirada",
                "# Hosomaki tras recibir daño, retroceder y un ataque de salida (~1,4 s)\ndisparo toque cada 0.5\n"
                        + "gadget\nmover abajo 0.5\ndisparar 1 toque\nparar");
        String[][] upd = {
                {"kenji_zona", "k_zona,k_combo,k_super,k_gadget,k_hiper", "k_zona,k_combo,k_super,k_curacion,k_entrada"},
                {"kenji_balon", "k_entrada,k_combo,k_hiper,k_super,k_gadget", "k_entrada,k_combo,k_super,k_curacion,k_zona"},
        };
        for (String[] u : upd) {
            PadSet s = set(u[0]);
            if (s == null || !String.join(",", s.slots).equals(u[1])) continue;
            s.slots.clear();
            for (String id : u[2].split(",")) s.slots.add(id);
        }
    }

    /**
     * Kenji (wiki + SeeMeta, oct-2026): el ataque alterna Dash (2,67 tiles, desplaza) y Slash
     * (3,67 tiles, arco de 135°), recarga 1 s; súper = pez a 7,33 tiles que corta en cruz y lo
     * deja invulnerable; hipercarga = el súper atrae a los enemigos cercanos 1 s. Gadgets: Dashi
     * Dash (3 s solo dash) u Hosomaki (cura). Mejores modos: Zona restringida y Balón Brawl.
     * Toque = auto-apuntado: el Slash tolera error por el arco. Combos de guías, no de pros.
     */
    static final String[][] KENJI1 = {
            {"k_combo", "Kenji · Combo corto",
                    "# Dash y Slash al más cercano (~1 s)\ndisparo toque cada 0.5\ndisparar 2 toque"},
            {"k_entrada", "Kenji · Entrada y combo",
                    "# Avanzar y Dash + Slash (~1,4 s)\ndisparo toque cada 0.5\nmover arriba 0.4\ndisparar 2 toque\nparar"},
            {"k_zona", "Kenji · Defender zona",
                    "# Pegar moviéndose de lado dentro de la zona (~1,3 s)\ndisparo toque cada 0.5\n"
                            + "disparar 1 toque\nmover izquierda 0.3\ndisparar 1 toque\nmover derecha 0.3\nparar"},
            {"k_super", "Kenji · Súper y salida",
                    "# Súper (invulnerable mientras corta) y un ataque de salida (~1,4 s)\ndisparo toque cada 0.5\n"
                            + "súper auto\nesperar 0.5\ndisparar 1 toque\nmover abajo 0.4\nparar"},
            {"k_hiper", "Kenji · Hiper y súper",
                    "# Hiper primero: el súper atrae a los cercanos (~1,4 s)\ndisparo toque cada 0.5\n"
                            + "hiper\nsúper auto\nesperar 0.5\ndisparar 1 toque"},
            {"k_gadget", "Kenji · Gadget y persecución",
                    "# Dashi Dash (3 s solo dash): perseguir pegando (~1,4 s)\ndisparo toque cada 0.35\n"
                            + "gadget\nmover arriba 0.3\ndisparar 3 toque\nparar"},
    };

    private void seedKenji() {
        for (String[] p : KENJI1) if (pattern(p[0]) == null) addPattern(p[0], p[1], p[2]);
        String[][] sets = {
                {"kenji_zona", "Zona restringida", "k_zona", "k_combo", "k_super", "k_gadget", "k_hiper"},
                {"kenji_balon", "Balón Brawl", "k_entrada", "k_combo", "k_hiper", "k_super", "k_gadget"},
        };
        for (String[] d : sets) {
            if (set(d[0]) != null) continue;
            PadSet s = new PadSet();
            s.id = d[0];
            s.brawler = "Kenji";
            s.mode = d[1];
            s.name = "Kenji";
            for (int i = 2; i < d.length; i++) s.slots.add(d[i]);
            this.sets.add(s);
        }
    }

    /**
     * v4 (19-oct): la cruceta pasa a ser una columna de botones (toque = bucle, otro toque = parar,
     * más Random) y el tipo de disparo deja de ser del set para ser parte de cada patrón.
     * - Cada set queda como Brawler · Modo, con sus patrones en orden (las 4 flechas en ▲▶▼◀).
     * - A cada patrón sin línea «disparo …» se le antepone la del perfil del primer set que lo usa
     *   (o del perfil global si no está en ninguno), así se sigue disparando igual que antes.
     * - Edgar y Charlie quedan con 5 botones (lo que se usó de verdad al grabar sus partidas).
     */
    private void migrateV4() {
        String[] order = {"up", "right", "down", "left"};
        Map<String, Profile> shotFor = new LinkedHashMap<>();
        for (PadSet s : sets) {
            if (s.slots.isEmpty()) {
                for (String a : order) {
                    Arrow ar = s.arrows.get(a);
                    if (ar != null && ar.patternId != null && pattern(ar.patternId) != null && !s.slots.contains(ar.patternId))
                        s.slots.add(ar.patternId);
                }
            }
            Profile prof = profileFor(s);
            for (String id : s.slots) if (!shotFor.containsKey(id)) shotFor.put(id, prof);
            if (s.brawler.isEmpty()) {
                switch (s.id) {
                    case "edgar": s.brawler = "Edgar"; s.mode = "Supervivencia"; break;
                    case "charlie": s.brawler = "Charlie"; s.mode = "Supervivencia"; break;
                    case "general": s.brawler = "General"; s.mode = "Todos"; break;
                    default: s.brawler = s.name; s.mode = "Todos";
                }
            }
        }
        Profile global = profileFor(null);
        for (PatternDef p : patterns) {
            if (Script.hasShotLine(p.script)) continue;
            Profile prof = shotFor.containsKey(p.id) ? shotFor.get(p.id) : global;
            p.script = insertShotLine(p.script, Script.shotLine(prof));
        }
        PadSet e = set("edgar");
        if (e != null && sameSlots(e, "e_salto", "e_escape", "e_alejarse", "e_doble")) {
            e.slots.clear();
            for (String id : new String[]{"e_salto", "e_doble", "e_alejarse", "e_esquive", "e_escape"})
                if (pattern(id) != null) e.slots.add(id);
        }
        PadSet c = set("charlie");
        if (c != null && sameSlots(c, "c_capullo", "c_esquiva_der", "c_rafaga", "c_esquiva_izq")) {
            c.slots.clear();
            for (String id : new String[]{"c_esquiva_der", "c_esquiva_izq", "c_capullo", "c_rafaga", "c_gadget"})
                if (pattern(id) != null) c.slots.add(id);
        }
    }

    /** ¿El set sigue con los patrones de fábrica (en cualquier orden)? Si el usuario lo cambió, se respeta. */
    private static boolean sameSlots(PadSet s, String... ids) {
        if (s.slots.size() != ids.length) return false;
        for (String id : ids) if (!s.slots.contains(id)) return false;
        return true;
    }

    /** Pone la línea de disparo después de los comentarios iniciales. */
    static String insertShotLine(String script, String line) {
        String[] lines = script.split("\\r?\\n", -1);
        StringBuilder b = new StringBuilder();
        boolean done = false;
        for (String l : lines) {
            String t = l.trim();
            if (!done && !t.isEmpty() && !t.startsWith("#") && !t.startsWith("//")) {
                b.append(line).append('\n');
                done = true;
            }
            b.append(l).append('\n');
        }
        if (!done) b.append(line).append('\n');
        return b.toString().replaceAll("\\n+$", "");
    }

    /**
     * Lote en assets/&lt;archivo&gt;: {"lote": id, "patterns": [{id, name, script}], "sets": [{id, brawler,
     * mode, slots: [ids]}]}. Se aplica una sola vez; solo agrega patrones y sets que falten por id.
     */
    boolean applyAssetSeed(Context ctx, String file) {
        try (java.io.InputStream in = ctx.getAssets().open(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject o = new JSONObject(out.toString("UTF-8"));
            String lote = o.getString("lote");
            if (seeds.contains(lote)) return false;
            JSONArray ps = o.getJSONArray("patterns");
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.getJSONObject(i);
                if (pattern(p.getString("id")) == null) addPattern(p.getString("id"), p.getString("name"), p.getString("script"));
            }
            JSONArray ss = o.getJSONArray("sets");
            for (int i = 0; i < ss.length(); i++) {
                JSONObject s = ss.getJSONObject(i);
                if (set(s.getString("id")) != null) continue;
                PadSet set = new PadSet();
                set.id = s.getString("id");
                set.brawler = s.getString("brawler");
                set.mode = s.getString("mode");
                set.name = set.brawler;
                JSONArray sl = s.getJSONArray("slots");
                for (int k = 0; k < sl.length() && k < MAX_SLOTS; k++) set.slots.add(sl.getString(k));
                sets.add(set);
            }
            seeds.add(lote);
            return true;
        } catch (java.io.FileNotFoundException e) {
            return false;
        } catch (Exception e) {
            android.util.Log.e("PatronTouch", "lote " + file + ": " + e);
            return false;
        }
    }

    /**
     * completos1 (20-oct, pedido de Gonzalo): todo patrón de un set de brawler (no «General»)
     * debe usar disparo, gadget e hiper. Lo que falte se agrega donde no deforma el recorrido.
     */
    void completeBrawlerPatterns() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (PadSet s : sets) if (!"General".equalsIgnoreCase(s.brawler)) ids.addAll(s.slots);
        int n = 0;
        for (String id : ids) {
            PatternDef p = pattern(id);
            if (p == null) continue;
            String full = Script.ensureFull(p.script);
            if (!full.equals(p.script)) {
                p.script = full;
                n++;
            }
        }
        android.util.Log.i("PatronTouch", "completos1: " + n + " de " + ids.size() + " patrones completados");
    }

    /** Patrones de un set, en orden de botón (se saltan los que ya no existen). */
    public List<PatternDef> slotPatterns(PadSet s) {
        List<PatternDef> out = new ArrayList<>();
        if (s != null) for (String id : s.slots) {
            PatternDef p = pattern(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * Charlie, sacado de 10 min de partidas de Gonzalo en BlueStacks (19-oct, video + mando):
     * 83 % de los ataques salen moviéndose, de a uno, cada 1,2–3 s (el yo-yo de 9 tiles va y vuelve
     * en ~1,3 s y es su única munición); el movimiento cambia de dirección cada ~0,34 s; las ráfagas
     * (4–8 ataques cada ~0,4 s) solo pegado al objetivo, donde el yo-yo vuelve rápido. Súper y
     * gadget, poco. Wiki: el capullo dura hasta 5 s y se rompe antes si se le pega, así que tras el
     * súper se reposiciona en vez de pegarle. Todo dura ≤1,5 s (en partida real se toca otra flecha
     * a los ~1,4 s).
     */
    static final String[][] CHARLIE1 = {
            {"c_esquiva_der", "Charlie · Disparo y esquiva →",
                    "# Un yo-yo y zigzag corto a la derecha mientras vuelve (~1,3 s)\n"
                            + "disparar 1 toque\nmover derecha 0.4\nmover abajo-derecha 0.4\nmover derecha 0.4\nparar"},
            {"c_esquiva_izq", "Charlie · Disparo y esquiva ←",
                    "# Un yo-yo y zigzag corto a la izquierda mientras vuelve (~1,3 s)\n"
                            + "disparar 1 toque\nmover izquierda 0.4\nmover abajo-izquierda 0.4\nmover izquierda 0.4\nparar"},
            {"c_capullo", "Charlie · Capullo y retroceder",
                    "# Súper al más cercano (9 tiles) y tomar distancia mientras está atrapado (~1,4 s)\n"
                            + "súper auto\nesperar 0.2\nmover abajo 1\nparar"},
            {"c_rafaga", "Charlie · Ráfaga corta",
                    "# Pegada al objetivo el yo-yo vuelve rápido: 3 ataques seguidos (~1,3 s)\n"
                            + "disparar 3 toque"},
            {"c_gadget", "Charlie · Gadget y disparo",
                    "# Arañas (o Espacio personal) y un yo-yo (~0,7 s)\n"
                            + "gadget\ndisparar 1 toque"},
            {"c_avance", "Charlie · Avance disparando",
                    "# Acercarse en diagonal con un yo-yo al empezar (~1,4 s)\n"
                            + "disparar 1 toque\nmover arriba-derecha 0.5\nmover arriba-izquierda 0.5\nmover arriba 0.4\nparar"},
    };

    private void seedCharlie() {
        if (profile("charlie") == null) addProfile("charlie", "Charlie", "tap", 420, 1000, "move", "auto");
        for (String[] p : CHARLIE1) if (pattern(p[0]) == null) addPattern(p[0], p[1], p[2]);
        if (set("charlie") == null) {
            PadSet s = new PadSet();
            s.id = "charlie";
            s.name = "Charlie";
            s.profileId = "charlie";
            String[][] map = {{"up", "c_capullo"}, {"down", "c_rafaga"}, {"left", "c_esquiva_izq"}, {"right", "c_esquiva_der"}};
            for (String[] m : map) {
                Arrow ar = new Arrow();
                ar.patternId = m[1];
                s.arrows.put(m[0], ar);
            }
            sets.add(s);
        }
    }

    /**
     * edgar2 (18-oct, tras grabar una partida real): de 46 patrones lanzados, 34 se cortaron a los
     * ~1,4 s porque se tocó otra flecha encima. Un salto cortado antes de aterrizar (0,9 s) no pega
     * nada. Todas las versiones duran ~1,5 s para que terminen antes del siguiente toque.
     */
    static final String[][] EDGAR2 = {
            {"e_salto", "Edgar · Salto y golpes",
                    "# Súper sobre el más cercano (hasta 6,67 tiles) y 2 ataques al aterrizar (~1,4 s)\n"
                            + "súper auto\nesperar 0.6\ndisparar 2 toque"},
            {"e_hiper", "Edgar · Hiper y salto",
                    "# Solo si tu Edgar tiene hipercarga. Hiper antes del súper (~1,6 s)\n"
                            + "hiper\nsúper auto\nesperar 0.6\ndisparar 2 toque"},
            {"e_doble", "Edgar · Gadget y golpes",
                    "# Let's Fly al más cercano y 2 ataques al aterrizar (~1,5 s); el súper queda en ▲\n"
                            + "gadget\nesperar 0.8\ndisparar 2 toque"},
            {"e_esquive", "Edgar · Esquive",
                    "# En plena pelea: un ataque, súper auto para esquivar y otro ataque (~1,4 s)\n"
                            + "disparar 1 toque\nsúper auto\nesperar 0.6\ndisparar 1 toque"},
            {"e_alejarse", "Edgar · Pegar y alejarse",
                    "# Un ataque y retroceder pegando (~1,3 s)\n"
                            + "disparar 1 toque\nmover abajo 0.8 disparando\nparar"},
            {"e_rodear", "Edgar · Rodear",
                    "# Una vuelta corta golpeando (~1,6 s)\n" + Data.RODEAR_VUELTA + "parar"},
            {"e_escape", "Edgar · Escape",
                    "# Salto hacia atrás al alcance máximo y un tramo corriendo (~1,5 s)\n"
                            + "súper apuntado abajo\nesperar 0.5\nmover abajo 0.6\nparar"},
    };

    /**
     * Edgar (wiki Brawl Stars, oct-2026): golpe de 2 tiles que tarda 0,4 s, recarga 0,7 s
     * (0,47 s con hipercarga); súper de 6,67 tiles con ~0,86 s en el aire, se carga solo en ~30 s;
     * la hipercarga solo potencia si se activa ANTES del súper; Let's Fly se engancha al brawler
     * más cercano. Mientras está en el aire los toques no sirven: después de cada salto se espera.
     * Ataque y súper con toque se auto-apuntan, que es lo único que una macro ciega puede hacer.
     */
    private static final String RODEAR_VUELTA = "mover izquierda 0.2 disparando\nmover abajo-izquierda 0.2 disparando\n"
            + "mover abajo 0.2 disparando\nmover abajo-derecha 0.2 disparando\n"
            + "mover derecha 0.2 disparando\nmover arriba-derecha 0.2 disparando\n"
            + "mover arriba 0.2 disparando\nmover arriba-izquierda 0.2 disparando\n";

    /** Versión 1 (larga). Se conserva tal cual: edgar2 la usa para saber si el usuario editó algo. */
    static final String[][] EDGAR1 = {
                {"e_salto", "Edgar · Salto y golpes",
                        "# Súper sobre el enemigo más cercano (hasta 6,67 tiles) y golpes al aterrizar\n"
                                + "súper auto\nesperar 0.6\ndisparar 6 toque"},
                {"e_hiper", "Edgar · Hiper y salto",
                        "# Solo si tu Edgar tiene hipercarga. Hiper primero: así el súper activa la recarga rápida (0,47 s)\n"
                                + "hiper\nsúper auto\nesperar 0.6\ndisparar 6 toque\nmover arriba 1 disparando\nparar"},
                {"e_doble", "Edgar · Doble salto",
                        "# Let's Fly al más cercano, golpes, y súper para seguirlo si escapa\n"
                                + "gadget\nesperar 1\ndisparar 2 toque\nsúper auto\nesperar 0.6\ndisparar 4 toque"},
                {"e_esquive", "Edgar · Esquive",
                        "# En plena pelea: súper auto para esquivar golpes y seguir pegando\n"
                                + "disparar 3 toque\nsúper auto\nesperar 0.6\ndisparar 3 toque"},
                {"e_alejarse", "Edgar · Pegar y alejarse",
                        "# Golpear retrocediendo: estira el alcance contra otros cuerpo a cuerpo\n"
                                + "disparar 2 toque\nmover abajo 1.2 disparando\nmover arriba 0.4\ndisparar 2 toque\nparar"},
                {"e_rodear", "Edgar · Rodear",
                        "# Dos vueltas cortas golpeando a un brawler lento (vueltas de 0,2 s por tramo: más largas se alejan)\n"
                                + RODEAR_VUELTA + RODEAR_VUELTA + "parar"},
                {"e_escape", "Edgar · Escape",
                        "# Saltar hacia atrás y correr con el impulso de velocidad (2,5 s)\n"
                                + "súper apuntado abajo\nesperar 0.6\nmover abajo 2.5\nparar"},
        };

    private void seedEdgar() {
        if (profile("edgar") == null) addProfile("edgar", "Edgar", "tap", 400, 1000, "move", "auto");
        for (String[] p : EDGAR1) if (pattern(p[0]) == null) addPattern(p[0], p[1], p[2]);
        if (set("edgar") == null) {
            PadSet s = new PadSet();
            s.id = "edgar";
            s.name = "Edgar";
            s.profileId = "edgar";
            String[][] map = {{"up", "e_salto"}, {"down", "e_escape"}, {"left", "e_alejarse"}, {"right", "e_doble"}};
            for (String[] m : map) {
                Arrow ar = new Arrow();
                ar.patternId = m[1];
                s.arrows.put(m[0], ar);
            }
            sets.add(s);
        }
    }

    // ---------------------------------------------------------------- JSON

    private JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("activeSet", activeSet);
        o.put("globalProfile", globalProfile);
        JSONArray ps = new JSONArray();
        for (PatternDef p : patterns) {
            ps.put(new JSONObject().put("id", p.id).put("name", p.name).put("script", p.script));
        }
        o.put("patterns", ps);
        JSONArray pr = new JSONArray();
        for (Profile p : profiles) {
            pr.put(new JSONObject().put("id", p.id).put("name", p.name).put("attack", p.attack)
                    .put("interval", p.interval).put("hold", p.hold).put("aimDir", p.aimDir)
                    .put("superMode", p.superMode));
        }
        o.put("profiles", pr);
        JSONArray ss = new JSONArray();
        for (PadSet s : sets) {
            JSONObject ar = new JSONObject();
            for (Map.Entry<String, Arrow> e : s.arrows.entrySet()) {
                ar.put(e.getKey(), new JSONObject().put("p", e.getValue().patternId == null ? "" : e.getValue().patternId)
                        .put("r", e.getValue().reps));
            }
            ss.put(new JSONObject().put("id", s.id).put("name", s.name).put("profile", s.profileId).put("arrows", ar)
                    .put("brawler", s.brawler).put("mode", s.mode).put("slots", new JSONArray(s.slots)));
        }
        o.put("sets", ss);
        o.put("seeds", new JSONArray(seeds));
        return o;
    }

    private void fromJson(JSONObject o) throws Exception {
        activeSet = o.optString("activeSet", "");
        globalProfile = o.optString("globalProfile", "normal");
        JSONArray sd = o.optJSONArray("seeds");
        if (sd != null) for (int i = 0; i < sd.length(); i++) seeds.add(sd.getString(i));
        JSONArray ps = o.getJSONArray("patterns");
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.getJSONObject(i);
            addPattern(p.getString("id"), p.getString("name"), p.getString("script"));
        }
        JSONArray pr = o.getJSONArray("profiles");
        for (int i = 0; i < pr.length(); i++) {
            JSONObject p = pr.getJSONObject(i);
            addProfile(p.getString("id"), p.getString("name"), p.optString("attack", "tap"),
                    p.optInt("interval", 550), p.optInt("hold", 1000), p.optString("aimDir", "move"),
                    p.optString("superMode", "auto"));
        }
        JSONArray ss = o.getJSONArray("sets");
        for (int i = 0; i < ss.length(); i++) {
            JSONObject s = ss.getJSONObject(i);
            PadSet set = new PadSet();
            set.id = s.getString("id");
            set.name = s.getString("name");
            set.profileId = s.optString("profile", "");
            set.brawler = s.optString("brawler", "");
            set.mode = s.optString("mode", "");
            JSONArray sl = s.optJSONArray("slots");
            if (sl != null) for (int k = 0; k < sl.length(); k++) set.slots.add(sl.getString(k));
            JSONObject ar = s.optJSONObject("arrows");
            for (String a : ARROWS) {
                Arrow arrow = new Arrow();
                if (ar != null && ar.has(a)) {
                    JSONObject x = ar.getJSONObject(a);
                    arrow.patternId = x.optString("p", "");
                    arrow.reps = Math.max(1, x.optInt("r", 1));
                }
                set.arrows.put(a, arrow);
            }
            sets.add(set);
        }
    }

    static String fmt(int ms) {
        double s = ms / 1000.0;
        return s == Math.floor(s) ? String.valueOf((int) s) : String.valueOf(Math.round(s * 100) / 100.0);
    }
}
