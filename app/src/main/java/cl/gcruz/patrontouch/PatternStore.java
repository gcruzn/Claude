package cl.gcruz.patrontouch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Guarda los patrones como JSON en el almacenamiento interno de la app, más la configuración. */
public class PatternStore {

    private final File dir;
    private final SharedPreferences prefs;

    public PatternStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "patterns");
        if (!dir.exists()) dir.mkdirs();
        prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    /** Ids ordenados del más nuevo al más antiguo. */
    public List<String> ids() {
        String[] names = dir.list((d, n) -> n.endsWith(".json"));
        List<String> out = new ArrayList<>();
        if (names == null) return out;
        Arrays.sort(names);
        for (int i = names.length - 1; i >= 0; i--) out.add(names[i].replace(".json", ""));
        return out;
    }

    public Pattern load(String id) {
        if (id == null) return null;
        File f = new File(dir, id + ".json");
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            return Pattern.fromJson(new JSONObject(new String(buf, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /** Guarda un patrón nuevo, lo deja activo y devuelve su id. */
    public String saveNew(Pattern p) {
        String id = String.valueOf(System.currentTimeMillis());
        if (p.name == null || p.name.isEmpty()) p.name = "Patrón " + (ids().size() + 1);
        save(id, p);
        setActive(id);
        return id;
    }

    public void save(String id, Pattern p) {
        try (FileOutputStream out = new FileOutputStream(new File(dir, id + ".json"))) {
            out.write(p.toJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public void delete(String id) {
        new File(dir, id + ".json").delete();
        if (id.equals(getActive())) {
            List<String> rest = ids();
            setActive(rest.isEmpty() ? null : rest.get(0));
        }
    }

    public String getActive() {
        String id = prefs.getString("active", null);
        if (id != null && !new File(dir, id + ".json").exists()) return null;
        return id;
    }

    public void setActive(String id) { prefs.edit().putString("active", id).apply(); }

    /** 0 = infinito. */
    public int getRepeats() { return prefs.getInt("repeats", 0); }

    public void setRepeats(int r) { prefs.edit().putInt("repeats", Math.max(0, r)).apply(); }

    /** Pausa entre repeticiones, en ms. */
    public int getPauseMs() { return prefs.getInt("pause", 0); }

    public void setPauseMs(int ms) { prefs.edit().putInt("pause", Math.max(0, ms)).apply(); }
}
