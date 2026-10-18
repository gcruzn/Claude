package cl.gcruz.patrontouch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Posición de los controles del juego en pantalla (horizontal), guardada al calibrar.
 * Los patrones de la biblioteca se escriben como acciones ("súper", "mover arriba") y se
 * traducen a coordenadas con esta calibración.
 */
public class Calibration {

    public static final String[] KEYS = {"joy", "attack", "super", "gadget", "hyper"};
    public static final String[] LABELS = {"el centro del JOYSTICK", "el botón de ATAQUE",
            "el botón de SÚPER", "el botón de GADGET", "el botón de HIPERCARGA"};

    /** Coordenadas [x,y] por control; null si no se calibró (gadget/hipercarga se pueden omitir). */
    public float[][] pos = new float[KEYS.length][];
    public int screenW, screenH;
    public boolean calibrated;

    private final SharedPreferences prefs;

    public Calibration(Context ctx, int curW, int curH) {
        prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
        load(curW, curH);
    }

    public float[] get(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return pos[i];
        return null;
    }

    /** Radio de arrastre del joystick y de los disparos apuntados, proporcional a la pantalla. */
    public float joyRadius() { return 170f * scale(); }

    public float aimRadius() { return 150f * scale(); }

    private float scale() { return Math.min(screenW, screenH) / 1080f; }

    private void load(int curW, int curH) {
        int w = Math.max(curW, curH), h = Math.min(curW, curH);
        String s = prefs.getString("calibration", null);
        if (s != null) {
            try {
                JSONObject o = new JSONObject(s);
                int sw = o.getInt("w"), sh = o.getInt("h");
                float sx = (float) w / sw, sy = (float) h / sh;
                for (int i = 0; i < KEYS.length; i++) {
                    if (o.has(KEYS[i]) && !o.isNull(KEYS[i])) {
                        org.json.JSONArray a = o.getJSONArray(KEYS[i]);
                        pos[i] = new float[]{(float) a.getDouble(0) * sx, (float) a.getDouble(1) * sy};
                    }
                }
                screenW = w;
                screenH = h;
                calibrated = true;
                return;
            } catch (Exception ignored) {
            }
        }
        // Valores por defecto (medidos en un video de 2340×1080), escalados a esta pantalla.
        float sx = w / 2340f, sy = h / 1080f;
        pos[0] = new float[]{555 * sx, 800 * sy};
        pos[1] = new float[]{1664 * sx, 794 * sy};
        pos[2] = new float[]{1508 * sx, 956 * sy};
        pos[3] = null;
        pos[4] = null;
        screenW = w;
        screenH = h;
        calibrated = false;
    }

    public void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("w", screenW);
            o.put("h", screenH);
            for (int i = 0; i < KEYS.length; i++) {
                if (pos[i] == null) o.put(KEYS[i], JSONObject.NULL);
                else o.put(KEYS[i], new org.json.JSONArray().put(pos[i][0]).put(pos[i][1]));
            }
            prefs.edit().putString("calibration", o.toString()).apply();
            calibrated = true;
        } catch (Exception ignored) {
        }
    }
}
