package cl.gcruz.patrontouch;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lenguaje de instrucciones de los patrones. Una instrucción por línea, en orden:
 *
 *   mover arriba [segundos]   el joystick queda apretado en esa dirección (y avanza el tiempo si das segundos)
 *   parar                     suelta el joystick
 *   disparar [N] [toque|mantener|apuntado] [dirección]   usa el perfil si no se indica
 *   súper [auto|apuntado] [dirección]
 *   gadget
 *   hiper
 *   esperar segundos
 *
 * Direcciones: arriba, abajo, izquierda, derecha y diagonales (arriba-izquierda, etc.) o ↑ ↓ ← → ↖ ↗ ↙ ↘.
 * "arriba" es hacia el enemigo. El movimiento sigue mientras se ejecutan las demás instrucciones.
 * Sin dependencias de Android, para poder probarlo aparte.
 */
public class Script {

    public static class Step {
        /** move | stop | attack | super | gadget | hyper | wait | config (línea «disparo …»: count = ms entre disparos, secs = mantener) */
        public String kind;
        public String dir;      // move, o dirección de apuntado (attack/super)
        public double secs;     // move (opcional), wait
        public int count = 1;   // attack
        public String mode;     // attack: tap|hold|aim ; super: auto|aim  (null = según perfil)
        public boolean fire;    // move: dispara durante el movimiento
        public int line;
    }

    public static class Result {
        public final List<Step> steps = new ArrayList<>();
        public String error;
        public int errorLine;

        public boolean ok() { return error == null; }
    }

    // ---------------------------------------------------------------- direcciones

    public static final String[] DIRS = {"up", "down", "left", "right", "upleft", "upright", "downleft", "downright"};
    private static final String[] DIR_ES = {"arriba", "abajo", "izquierda", "derecha",
            "arriba-izquierda", "arriba-derecha", "abajo-izquierda", "abajo-derecha"};
    private static final String[] DIR_ICON = {"↑", "↓", "←", "→", "↖", "↗", "↙", "↘"};

    public static String dirLabel(String dir) {
        if ("move".equals(dir)) return "hacia donde te mueves";
        for (int i = 0; i < DIRS.length; i++) if (DIRS[i].equals(dir)) return DIR_ES[i];
        return dir == null ? "" : dir;
    }

    public static String dirIcon(String dir) {
        for (int i = 0; i < DIRS.length; i++) if (DIRS[i].equals(dir)) return DIR_ICON[i];
        return "";
    }

    public static double degrees(String dir) {
        switch (dir) {
            case "right": return 0;
            case "upright": return 45;
            case "up": return 90;
            case "upleft": return 135;
            case "left": return 180;
            case "downleft": return 225;
            case "down": return 270;
            case "downright": return 315;
        }
        return 90;
    }

    // ---------------------------------------------------------------- texto → pasos

    private static String norm(String s) {
        s = s.toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replace("↑", " arriba ").replace("↓", " abajo ").replace("←", " izquierda ").replace("→", " derecha ")
                .replace("▲", " arriba ").replace("▼", " abajo ").replace("◀", " izquierda ").replace("▶", " derecha ")
                .replace("↖", " arriba izquierda ").replace("↗", " arriba derecha ")
                .replace("↙", " abajo izquierda ").replace("↘", " abajo derecha ");
        s = s.replaceAll("(\\d),(\\d)", "$1.$2");
        s = s.replaceAll("[,;:\\-_/]", " ");
        s = s.replaceAll("(\\d)\\s*(s|seg|segs|segundos|segundo)\\b", "$1");
        s = s.replaceAll("[x×](\\d)", "$1").replaceAll("(\\d)\\s*(veces|vez)\\b", "$1");
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String wordDir(String w) {
        switch (w) {
            case "arriba": case "adelante": case "frente": case "up": return "up";
            case "abajo": case "atras": case "down": return "down";
            case "izquierda": case "izq": case "left": return "left";
            case "derecha": case "der": case "right": return "right";
        }
        return null;
    }

    private static String combine(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        boolean vert = a.equals("up") || a.equals("down");
        boolean horz = b.equals("left") || b.equals("right");
        if (vert && horz) return a + b;
        if (!vert && (b.equals("up") || b.equals("down"))) return b + a;
        return null;
    }

    private static Double number(String w) {
        try { return Double.parseDouble(w); } catch (Exception e) { return null; }
    }

    /**
     * «disparo toque cada 0.42» · «disparo mantener 1.2» · «disparo apuntado cada 0.55 arriba»
     * (o «… apuntado movimiento»): fija cómo se dispara desde esa línea en adelante. Es parte del
     * patrón; antes de la primera línea rige {@link #defaultProfile()}.
     */
    private static String parseShotConfig(String[] w, Step s) {
        s.kind = "config";
        s.mode = null;
        for (int i = 1; i < w.length; i++) {
            String x = w[i];
            Double n = number(x);
            if (n != null) {
                if ("hold".equals(s.mode)) s.secs = n; else s.count = (int) Math.round(n * 1000);
                continue;
            }
            String d = wordDir(x);
            if (d != null) {
                s.dir = s.dir == null || "move".equals(s.dir) ? d : combine(s.dir, d);
                if (s.dir == null) return "Dirección inválida";
                continue;
            }
            switch (x) {
                case "toque": case "tap": s.mode = "tap"; break;
                case "mantener": case "mantenido": case "hold": s.mode = "hold"; break;
                case "apuntado": case "apuntar": case "aim": s.mode = "aim"; break;
                case "movimiento": case "moviendo": s.dir = "move"; break;
                case "cada": case "durante": case "hacia": case "a": case "al": case "de": case "s": break;
                default: return "No entiendo \"" + x + "\" en disparo";
            }
        }
        if (s.mode == null) return "Falta el tipo: toque, mantener o apuntado";
        return null;
    }

    /** Disparo por defecto de un patrón que no dice nada: toque cada 0,5 s, súper auto. */
    public static Data.Profile defaultProfile() {
        Data.Profile p = new Data.Profile();
        p.id = "patron";
        p.name = "Patrón";
        p.attack = "tap";
        p.interval = 500;
        p.hold = 1000;
        p.aimDir = "move";
        p.superMode = "auto";
        return p;
    }

    /** Línea «disparo …» equivalente a un perfil (para migrar sets antiguos). */
    public static String shotLine(Data.Profile p) {
        switch (p.attack) {
            case "hold": return "disparo mantener " + num(p.hold / 1000.0);
            case "aim": return "disparo apuntado cada " + num(p.interval / 1000.0) + " "
                    + ("move".equals(p.aimDir) ? "movimiento" : dirLabel(p.aimDir));
            default: return "disparo toque cada " + num(p.interval / 1000.0);
        }
    }

    public static boolean hasShotLine(String script) {
        Result r = parse(script);
        if (!r.ok()) return script != null && norm(script).contains("disparo ");
        for (Step s : r.steps) if ("config".equals(s.kind)) return true;
        return false;
    }

    public static Result parse(String text) {
        Result r = new Result();
        String[] lines = text == null ? new String[0] : text.split("\\r?\\n");
        for (int li = 0; li < lines.length; li++) {
            String raw = lines[li].trim();
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) continue;
            String[] w = norm(raw).split(" ");
            if (w.length == 0 || w[0].isEmpty()) continue;
            Step s = new Step();
            s.line = li + 1;
            String cmd = w[0];
            if (cmd.equals("disparo") || cmd.equals("tiro")) {
                String err = parseShotConfig(w, s);
                if (err != null) {
                    r.error = err;
                    r.errorLine = s.line;
                    return r;
                }
                r.steps.add(s);
                continue;
            }
            String dir = null, mode = null;
            Double num = null;
            boolean badDir = false, firing = false;
            for (int i = 1; i < w.length; i++) {
                String d = wordDir(w[i]);
                Double n = number(w[i]);
                if (d != null) {
                    String c = dir == null ? d : combine(dir, d);
                    if (c == null) badDir = true; else dir = c;
                } else if (n != null) {
                    num = n;
                } else switch (w[i]) {
                    case "toque": case "tap": mode = "tap"; break;
                    case "mantener": case "mantenido": case "hold": mode = "hold"; break;
                    case "apuntado": case "apuntar": case "aim": mode = "aim"; break;
                    case "auto": case "automatico": mode = "auto"; break;
                    case "hacia": case "a": case "al": case "la": case "el": case "y": case "por": case "durante":
                    case "en": case "de": break;
                    case "disparando": case "atacando": firing = true; break;
                    default:
                        r.error = "No entiendo \"" + w[i] + "\"";
                        r.errorLine = s.line;
                        return r;
                }
            }
            if (badDir) {
                r.error = "Dirección inválida";
                r.errorLine = s.line;
                return r;
            }
            switch (cmd) {
                case "mover": case "mueve": case "moverse": case "muevete": case "caminar": case "camina": case "ir": case "move":
                    if (dir == null) {
                        r.error = "Falta la dirección (arriba, abajo, izquierda, derecha…)";
                        r.errorLine = s.line;
                        return r;
                    }
                    s.kind = "move";
                    s.dir = dir;
                    s.secs = num == null ? 0 : Math.max(0, Math.min(30, num));
                    s.fire = firing;
                    if (firing && s.secs == 0) s.secs = 1.5;
                    break;
                case "parar": case "para": case "soltar": case "detener": case "quieto": case "stop":
                    s.kind = "stop";
                    break;
                case "disparar": case "dispara": case "disparo": case "atacar": case "ataca": case "ataque": case "attack":
                    s.kind = "attack";
                    s.count = num == null ? 1 : (int) Math.max(1, Math.min(30, Math.round(num)));
                    s.mode = "auto".equals(mode) ? "tap" : mode;
                    s.dir = dir;
                    if (dir != null && s.mode == null) s.mode = "aim";
                    break;
                case "super": case "superataque": case "especial":
                    s.kind = "super";
                    s.mode = "aim".equals(mode) ? "aim" : "auto".equals(mode) || "tap".equals(mode) ? "auto" : null;
                    s.dir = dir;
                    if (dir != null) s.mode = "aim";
                    break;
                case "gadget": case "artefacto":
                    s.kind = "gadget";
                    break;
                case "hiper": case "hipercarga": case "hyper": case "hypercharge":
                    s.kind = "hyper";
                    break;
                case "esperar": case "espera": case "pausa": case "wait":
                    s.kind = "wait";
                    s.secs = num == null ? 0.5 : Math.max(0, Math.min(30, num));
                    break;
                default:
                    r.error = "Instrucción desconocida \"" + cmd + "\"";
                    r.errorLine = s.line;
                    return r;
            }
            r.steps.add(s);
        }
        if (r.steps.isEmpty()) {
            r.error = "El patrón está vacío";
            r.errorLine = 1;
        }
        return r;
    }

    // ---------------------------------------------------------------- pasos → texto

    public static String format(Step s) {
        switch (s.kind) {
            case "move": return "mover " + dirLabel(s.dir) + (s.secs > 0 ? " " + num(s.secs) : "") + (s.fire ? " disparando" : "");
            case "stop": return "parar";
            case "attack": {
                StringBuilder b = new StringBuilder("disparar");
                if (s.count > 1) b.append(' ').append(s.count);
                if ("tap".equals(s.mode)) b.append(" toque");
                if ("hold".equals(s.mode)) b.append(" mantener");
                if ("aim".equals(s.mode)) b.append(" apuntado");
                if (s.dir != null) b.append(' ').append(dirLabel(s.dir));
                return b.toString();
            }
            case "super": {
                StringBuilder b = new StringBuilder("súper");
                if ("auto".equals(s.mode)) b.append(" auto");
                if ("aim".equals(s.mode)) b.append(" apuntado");
                if (s.dir != null) b.append(' ').append(dirLabel(s.dir));
                return b.toString();
            }
            case "gadget": return "gadget";
            case "hyper": return "hiper";
            case "wait": return "esperar " + num(s.secs);
            case "config": return configText(s);
        }
        return "";
    }

    public static String format(List<Step> steps) {
        StringBuilder b = new StringBuilder();
        for (Step s : steps) {
            if (b.length() > 0) b.append('\n');
            b.append(format(s));
        }
        return b.toString();
    }

    /** Texto amigable para mostrar un paso en la lista del editor. */
    public static String pretty(Step s) {
        switch (s.kind) {
            case "move": return "🕹 Mover " + dirIcon(s.dir) + " " + dirLabel(s.dir) + (s.secs > 0 ? " · " + num(s.secs) + " s" : "") + (s.fire ? " · disparando" : "");
            case "stop": return "✋ Parar (soltar joystick)";
            case "attack": {
                String m = s.mode == null ? "según perfil" : s.mode.equals("tap") ? "toque" : s.mode.equals("hold") ? "mantener" : "apuntado";
                return "🎯 Disparar ×" + s.count + " · " + m + (s.dir != null ? " " + dirIcon(s.dir) : "");
            }
            case "super": {
                String m = s.mode == null ? "según perfil" : s.mode.equals("auto") ? "auto" : "apuntado";
                return "⭐ Súper · " + m + (s.dir != null ? " " + dirIcon(s.dir) : "");
            }
            case "gadget": return "🧩 Gadget";
            case "hyper": return "⚡ Hipercarga";
            case "wait": return "⏱ Esperar " + num(s.secs) + " s";
            case "config": return "🔧 Tipo de disparo: " + configText(s).substring("disparo ".length());
        }
        return s.kind;
    }

    static String configText(Step s) {
        StringBuilder b = new StringBuilder("disparo ");
        b.append("hold".equals(s.mode) ? "mantener" : "aim".equals(s.mode) ? "apuntado" : "toque");
        if ("hold".equals(s.mode)) {
            if (s.secs > 0) b.append(' ').append(num(s.secs));
        } else if (s.count > 1) {
            b.append(" cada ").append(num(s.count / 1000.0));
        }
        if ("aim".equals(s.mode) && s.dir != null) b.append(' ').append("move".equals(s.dir) ? "movimiento" : dirLabel(s.dir));
        return b.toString();
    }

    static String num(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    // ---------------------------------------------------------------- pasos → toques

    public static float[] offset(float[] c, String dir, float dist) {
        double r = Math.toRadians(degrees(dir));
        return new float[]{(float) (c[0] + Math.cos(r) * dist), (float) (c[1] - Math.sin(r) * dist)};
    }

    /** Traduce los pasos a toques concretos según el perfil y la calibración. */
    public static Pattern compile(String name, List<Step> steps, Data.Profile base, Calibration cal) {
        Data.Profile prof = copy(base != null ? base : defaultProfile());
        Pattern p = new Pattern();
        p.name = name;
        p.screenW = cal.screenW;
        p.screenH = cal.screenH;
        float[] joy = cal.get("joy");
        long t = 0;
        Pattern.Touch js = null;
        long jsLast = 0;
        float[] jsPos = null;
        String curDir = null;

        for (Step s : steps) {
            switch (s.kind) {
                case "move": {
                    if (joy == null) break;
                    float[] target = offset(joy, s.dir, cal.joyRadius());
                    if (js == null) {
                        js = new Pattern.Touch();
                        js.pts.add(new Pattern.Point(t, joy[0], joy[1]));
                        jsLast = t;
                    } else {
                        jsLast = Math.max(jsLast, t);
                        js.pts.add(new Pattern.Point(jsLast, jsPos[0], jsPos[1]));
                    }
                    jsLast = jsLast + 60;
                    js.pts.add(new Pattern.Point(jsLast, target[0], target[1]));
                    jsPos = target;
                    curDir = s.dir;
                    long end = t + Math.round(s.secs * 1000);
                    if (s.fire) {
                        long tt = t;
                        while (tt < end - 60) tt += attack(p, tt, prof.attack, null, prof, cal, curDir);
                    }
                    t = end;
                    break;
                }
                case "stop": {
                    if (js != null) {
                        jsLast = Math.max(jsLast, t);
                        js.pts.add(new Pattern.Point(jsLast, jsPos[0], jsPos[1]));
                        js.pts.add(new Pattern.Point(jsLast + 60, joy[0], joy[1]));
                        p.touches.add(js);
                        js = null;
                        t = Math.max(t, jsLast + 60);
                    }
                    curDir = null;
                    break;
                }
                case "attack": {
                    String mode = s.mode != null ? s.mode : prof.attack;
                    for (int i = 0; i < s.count; i++) t += attack(p, t, mode, s.dir, prof, cal, curDir);
                    break;
                }
                case "super": {
                    float[] btn = cal.get("super");
                    String mode = s.mode != null ? s.mode : ("auto".equals(prof.superMode) ? "auto" : "aim");
                    if ("aim".equals(mode)) {
                        String d = s.dir != null ? s.dir
                                : !"auto".equals(prof.superMode) && !"move".equals(prof.superMode) ? prof.superMode
                                : curDir != null ? curDir : "up";
                        // El súper apuntado va al alcance máximo: con el arrastre corto del ataque el salto quedaba corto.
                        if (btn != null) p.touches.add(drag(t, btn, offset(btn, d, cal.aimRadius() * 1.8f)));
                        t += 400;
                    } else {
                        if (btn != null) p.touches.add(press(t, btn, 70));
                        t += 300;
                    }
                    break;
                }
                case "gadget": {
                    float[] btn = cal.get("gadget");
                    if (btn != null) p.touches.add(press(t, btn, 70));
                    t += 200;
                    break;
                }
                case "hyper": {
                    float[] btn = cal.get("hyper");
                    if (btn != null) p.touches.add(press(t, btn, 70));
                    t += 250;
                    break;
                }
                case "wait":
                    t += Math.round(s.secs * 1000);
                    break;
                case "config":
                    prof.attack = s.mode;
                    if (s.count > 1) prof.interval = s.count;
                    if (s.secs > 0) prof.hold = (int) Math.round(s.secs * 1000);
                    if (s.dir != null) prof.aimDir = s.dir;
                    break;
            }
        }
        if (js != null) {
            long end = Math.max(t, jsLast + 100);
            js.pts.add(new Pattern.Point(end, jsPos[0], jsPos[1]));
            js.pts.add(new Pattern.Point(end + 60, joy[0], joy[1]));
            p.touches.add(js);
        }
        p.normalize();
        return p;
    }

    private static Data.Profile copy(Data.Profile b) {
        Data.Profile p = new Data.Profile();
        p.id = b.id;
        p.name = b.name;
        p.attack = b.attack;
        p.interval = b.interval;
        p.hold = b.hold;
        p.aimDir = b.aimDir;
        p.superMode = b.superMode;
        return p;
    }

    /** Agrega un disparo en t y devuelve cuánto avanza el tiempo. */
    private static long attack(Pattern p, long t, String mode, String dir, Data.Profile prof, Calibration cal, String curDir) {
        float[] btn = cal.get("attack");
        if ("hold".equals(mode)) {
            long h = Math.max(100, prof.hold);
            if (btn != null) p.touches.add(press(t, btn, h));
            return h + 150;
        } else if ("aim".equals(mode)) {
            String d = dir != null ? dir : "move".equals(prof.aimDir) ? (curDir != null ? curDir : "up") : prof.aimDir;
            if (btn != null) p.touches.add(drag(t, btn, offset(btn, d, cal.aimRadius())));
            return Math.max(350, prof.interval);
        }
        if (btn != null) p.touches.add(press(t, btn, 70));
        return Math.max(120, prof.interval);
    }

    private static Pattern.Touch press(long t, float[] btn, long ms) {
        Pattern.Touch x = new Pattern.Touch();
        x.pts.add(new Pattern.Point(t, btn[0], btn[1]));
        x.pts.add(new Pattern.Point(t + ms, btn[0], btn[1]));
        return x;
    }

    private static Pattern.Touch drag(long t, float[] btn, float[] to) {
        Pattern.Touch x = new Pattern.Touch();
        x.pts.add(new Pattern.Point(t, btn[0], btn[1]));
        x.pts.add(new Pattern.Point(t + 120, to[0], to[1]));
        x.pts.add(new Pattern.Point(t + 300, to[0], to[1]));
        return x;
    }
}
