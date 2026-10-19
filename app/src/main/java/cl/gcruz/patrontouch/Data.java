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
        if (d.applySeeds()) d.save();
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
        return changed;
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
