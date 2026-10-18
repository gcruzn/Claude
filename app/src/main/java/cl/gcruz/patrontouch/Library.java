package cl.gcruz.patrontouch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Biblioteca de patrones por brawler y modo de juego, escrita como acciones de alto nivel.
 * Se carga desde el archivo descargado (si existe) o desde el que viene en la app, y se
 * actualiza desde el repositorio sin reinstalar.
 *
 * Formato de un patrón:
 *   moves: [{dir|deg, ms, power?}]  — joystick, secuencial desde t=0; dir "none" = soltar.
 *   acts:  [{t, btn, aim?|deg?, hold?}] — btn: attack|super|gadget|hyper. Sin aim = tap
 *          (auto-apuntado); con aim = arrastrar desde el botón en esa dirección y soltar.
 */
public class Library {

    public static final String REMOTE_URL =
            "https://raw.githubusercontent.com/gcruzn/Claude/patron-touch/patrones/biblioteca.json";

    public static class Mode {
        public String id, name;
    }

    public static class LibPattern {
        public String name, desc;
        public List<String> modes = new ArrayList<>();
        public JSONArray moves, acts;

        public boolean appliesTo(String modeId) {
            return modes.isEmpty() || modes.contains("all") || modes.contains(modeId);
        }
    }

    public static class Brawler {
        public String id, name, role, tip;
        public List<LibPattern> patterns = new ArrayList<>();
    }

    public int version;
    public String updated = "";
    public final List<Mode> modes = new ArrayList<>();
    public final List<Brawler> brawlers = new ArrayList<>();

    // ---------------------------------------------------------------- carga

    public static Library load(Context ctx) {
        File f = new File(ctx.getFilesDir(), "biblioteca.json");
        Library fromFile = null, fromAsset = null;
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                fromFile = parse(readAll(in));
            } catch (Exception ignored) {
            }
        }
        try (InputStream in = ctx.getAssets().open("biblioteca.json")) {
            fromAsset = parse(readAll(in));
        } catch (Exception ignored) {
        }
        if (fromFile != null && (fromAsset == null || fromFile.version >= fromAsset.version)) return fromFile;
        return fromAsset != null ? fromAsset : new Library();
    }

    /** Descarga la biblioteca del repo; devuelve la nueva versión o lanza excepción. Llamar fuera del hilo UI. */
    public static Library download(Context ctx) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(REMOTE_URL + "?t=" + System.currentTimeMillis()).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        try (InputStream in = c.getInputStream()) {
            String text = readAll(in);
            Library lib = parse(text);
            if (lib.brawlers.isEmpty()) throw new Exception("Biblioteca vacía");
            try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), "biblioteca.json"))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return lib;
        } finally {
            c.disconnect();
        }
    }

    static Library parse(String text) throws Exception {
        JSONObject o = new JSONObject(text);
        Library lib = new Library();
        lib.version = o.optInt("version", 0);
        lib.updated = o.optString("updated", "");
        JSONArray ms = o.optJSONArray("modes");
        if (ms != null) for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.getJSONObject(i);
            Mode mode = new Mode();
            mode.id = m.getString("id");
            mode.name = m.getString("name");
            lib.modes.add(mode);
        }
        JSONArray bs = o.getJSONArray("brawlers");
        for (int i = 0; i < bs.length(); i++) {
            JSONObject b = bs.getJSONObject(i);
            Brawler br = new Brawler();
            br.id = b.getString("id");
            br.name = b.getString("name");
            br.role = b.optString("role", "");
            br.tip = b.optString("tip", "");
            JSONArray ps = b.getJSONArray("patterns");
            for (int j = 0; j < ps.length(); j++) {
                JSONObject p = ps.getJSONObject(j);
                LibPattern lp = new LibPattern();
                lp.name = p.getString("name");
                lp.desc = p.optString("desc", "");
                JSONArray md = p.optJSONArray("modes");
                if (md != null) for (int k = 0; k < md.length(); k++) lp.modes.add(md.getString(k));
                lp.moves = p.optJSONArray("moves");
                lp.acts = p.optJSONArray("acts");
                br.patterns.add(lp);
            }
            lib.brawlers.add(br);
        }
        return lib;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toString("UTF-8");
    }

    public Brawler brawler(String id) {
        for (Brawler b : brawlers) if (b.id.equals(id)) return b;
        return brawlers.isEmpty() ? null : brawlers.get(0);
    }

    public Mode mode(String id) {
        for (Mode m : modes) if (m.id.equals(id)) return m;
        return modes.isEmpty() ? null : modes.get(0);
    }

    // ---------------------------------------------------------------- compilación a toques

    /** Ángulo en grados (0 = derecha, 90 = arriba en pantalla) de una dirección o de "deg". */
    static Double angle(JSONObject o, boolean mirror) {
        Double deg = null;
        if (o.has("deg")) deg = o.optDouble("deg");
        else {
            String d = o.optString(o.has("aim") ? "aim" : "dir", "");
            switch (d) {
                case "right": deg = 0.0; break;
                case "upright": deg = 45.0; break;
                case "up": deg = 90.0; break;
                case "upleft": deg = 135.0; break;
                case "left": deg = 180.0; break;
                case "downleft": deg = 225.0; break;
                case "down": deg = 270.0; break;
                case "downright": deg = 315.0; break;
                default: deg = null;
            }
        }
        if (deg != null && mirror) deg = 180.0 - deg;
        return deg;
    }

    static float[] offset(float[] center, double deg, float dist) {
        double r = Math.toRadians(deg);
        return new float[]{(float) (center[0] + Math.cos(r) * dist), (float) (center[1] - Math.sin(r) * dist)};
    }

    /** Traduce un patrón de acciones a toques concretos según la calibración. */
    public static Pattern compile(LibPattern lp, Calibration cal, boolean mirror) {
        Pattern p = new Pattern();
        p.name = lp.name;
        p.screenW = cal.screenW;
        p.screenH = cal.screenH;

        // Joystick: tramos seguidos con dirección = un solo dedo sin levantar.
        float[] joy = cal.get("joy");
        if (lp.moves != null && joy != null) {
            long t = 0;
            Pattern.Touch cur = null;
            float[] last = null;
            for (int i = 0; i < lp.moves.length(); i++) {
                JSONObject m = lp.moves.optJSONObject(i);
                if (m == null) continue;
                long ms = Math.max(1, m.optLong("ms", 300));
                Double deg = angle(m, mirror);
                if (deg == null) {
                    if (cur != null) {
                        cur.pts.add(new Pattern.Point(t, last[0], last[1]));
                        p.touches.add(cur);
                        cur = null;
                    }
                } else {
                    float[] target = offset(joy, deg, (float) (cal.joyRadius() * m.optDouble("power", 1.0)));
                    long ramp = Math.min(80, ms / 3);
                    if (cur == null) {
                        cur = new Pattern.Touch();
                        cur.pts.add(new Pattern.Point(t, joy[0], joy[1]));
                    }
                    cur.pts.add(new Pattern.Point(t + ramp, target[0], target[1]));
                    last = target;
                }
                t += ms;
            }
            if (cur != null) {
                cur.pts.add(new Pattern.Point(t, last[0], last[1]));
                cur.pts.add(new Pattern.Point(t + 60, joy[0], joy[1]));
                p.touches.add(cur);
            }
        }

        // Botones: tap (auto-apuntado) o arrastre apuntado.
        if (lp.acts != null) {
            for (int i = 0; i < lp.acts.length(); i++) {
                JSONObject a = lp.acts.optJSONObject(i);
                if (a == null) continue;
                float[] btn = cal.get(a.optString("btn", "attack"));
                if (btn == null) continue; // control no calibrado (p. ej. sin gadget): se omite
                long t = a.optLong("t", 0);
                Pattern.Touch touch = new Pattern.Touch();
                Double deg = angle(a, mirror);
                touch.pts.add(new Pattern.Point(t, btn[0], btn[1]));
                if (deg == null) {
                    touch.pts.add(new Pattern.Point(t + 70, btn[0], btn[1]));
                } else {
                    long hold = Math.max(150, a.optLong("hold", 300));
                    float[] aim = offset(btn, deg, cal.aimRadius() * (float) a.optDouble("power", 1.0));
                    touch.pts.add(new Pattern.Point(t + 120, aim[0], aim[1]));
                    touch.pts.add(new Pattern.Point(t + hold, aim[0], aim[1]));
                }
                p.touches.add(touch);
            }
        }

        p.normalize();
        return p;
    }
}
