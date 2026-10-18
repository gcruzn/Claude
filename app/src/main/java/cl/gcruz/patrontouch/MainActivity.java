package cl.gcruz.patrontouch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Pantalla principal: activar el servicio, configurar repeticiones y administrar patrones. */
public class MainActivity extends Activity {

    private static final int CORAL = Color.parseColor("#E25E6B");
    private static final int AZUL = Color.parseColor("#406AB4");
    private static final int VERDE = Color.parseColor("#73C96A");
    private static final int GRAFITO = Color.parseColor("#454A51");
    private static final int GRIS = Color.parseColor("#969798");

    private PatternStore store;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new PatternStore(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#F6F7F9"));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        root.removeAllViews();
        TouchService svc = TouchService.get();

        root.addView(text("Patrón Touch", 24, GRAFITO, true));
        root.addView(text("Graba toques con varios dedos a la vez y repítelos en loop.", 14, GRIS, false));
        space(14);

        // --- Estado del servicio
        LinearLayout card = card();
        if (svc == null) {
            card.addView(text("● Servicio desactivado", 16, CORAL, true));
            card.addView(text("1. Toca el botón y activa \"Patrón Touch\" en Accesibilidad.\n"
                    + "2. Si Android dice \"Configuración restringida\": ve a Ajustes › Apps › Patrón Touch › "
                    + "menú ⋮ › \"Permitir configuración restringida\" y vuelve a intentar.", 13, GRAFITO, false));
            card.addView(button("Abrir Accesibilidad", AZUL, v ->
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        } else {
            card.addView(text("● Servicio activo", 16, VERDE, true));
            card.addView(text("Usa el panel flotante encima del juego: ⏺ Grabar, ▶ Repetir, ■ Parar. "
                    + "Arrástralo desde ⠿ a una esquina donde no estorbe. Bajar volumen también detiene.",
                    13, GRAFITO, false));
            card.addView(button(svc.isPanelVisible() ? "Ocultar panel flotante" : "Mostrar panel flotante",
                    GRAFITO, v -> {
                        if (svc.isPanelVisible()) svc.hidePanel(); else svc.showPanel();
                        render();
                    }));
        }
        root.addView(card);
        space(12);

        // --- Configuración
        LinearLayout cfg = card();
        cfg.addView(text("Repetición", 16, GRAFITO, true));
        EditText reps = number(String.valueOf(store.getRepeats()));
        EditText pause = number(String.valueOf(store.getPauseMs()));
        cfg.addView(row("Veces (0 = infinito)", reps));
        cfg.addView(row("Pausa entre vueltas (ms)", pause));
        cfg.addView(button("Guardar", AZUL, v -> {
            store.setRepeats(parse(reps.getText().toString()));
            store.setPauseMs(parse(pause.getText().toString()));
            if (TouchService.get() != null) TouchService.get().refresh();
            render();
        }));
        root.addView(cfg);
        space(12);

        // --- Patrones
        LinearLayout list = card();
        list.addView(text("Patrones guardados", 16, GRAFITO, true));
        LinearLayout imp = new LinearLayout(this);
        imp.setOrientation(LinearLayout.HORIZONTAL);
        Button paste = button("Pegar patrón", VERDE, v -> importFromClipboard());
        Button file = button("Importar archivo", VERDE, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(46), 1f);
        half.topMargin = dp(10);
        half.rightMargin = dp(4);
        paste.setLayoutParams(half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        half2.topMargin = dp(10);
        half2.leftMargin = dp(4);
        file.setLayoutParams(half2);
        imp.addView(paste);
        imp.addView(file);
        list.addView(imp);
        List<String> ids = store.ids();
        String active = store.getActive();
        if (ids.isEmpty()) {
            list.addView(text("Aún no hay patrones. Abre el juego y toca ⏺ Grabar en el panel.", 13, GRIS, false));
        }
        for (String id : ids) {
            Pattern pt = store.load(id);
            if (pt == null) continue;
            boolean isActive = id.equals(active);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(8), 0, dp(8));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text((isActive ? "★ " : "") + pt.name, 15, isActive ? AZUL : GRAFITO, true));
            info.addView(text(String.format("%.1f s · %d toques · hasta %d dedos a la vez · %s",
                    pt.duration / 1000f, pt.touches.size(), pt.maxSimultaneous(),
                    pt.screenW > pt.screenH ? "horizontal" : "vertical"), 12, GRIS, false));
            r.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!isActive) r.addView(small("Usar", AZUL, v -> {
                store.setActive(id);
                if (TouchService.get() != null) TouchService.get().refresh();
                render();
            }));
            r.addView(small("Renombrar", GRAFITO, v -> rename(id, pt)));
            r.addView(small("Borrar", CORAL, v -> new AlertDialog.Builder(this)
                    .setMessage("¿Borrar \"" + pt.name + "\"?")
                    .setPositiveButton("Borrar", (d, w) -> {
                        store.delete(id);
                        if (TouchService.get() != null) TouchService.get().refresh();
                        render();
                    })
                    .setNegativeButton("Cancelar", null).show()));
            list.addView(r);
        }
        root.addView(list);
        space(12);

        root.addView(text("Tips: graba y reproduce en la misma orientación (horizontal para el juego). "
                + "Durante la grabación los toques NO llegan al juego: haz el patrón \"en seco\" sobre la capa azul. "
                + "Si tocas la pantalla mientras se reproduce, se detiene.", 12, GRIS, false));
    }

    // ---------------------------------------------------------------- importar

    private static final int REQ_IMPORT = 42;

    private void importFromClipboard() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            toast("El portapapeles está vacío");
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        importText(cs == null ? "" : cs.toString());
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            importText(bos.toString("UTF-8"));
        } catch (Exception e) {
            toast("No pude leer el archivo");
        }
    }

    /** Acepta el JSON de un patrón (aunque venga con texto alrededor) y lo adapta a esta pantalla. */
    private void importText(String text) {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) {
            toast("No encontré un patrón en el texto");
            return;
        }
        Pattern p;
        try {
            p = Pattern.fromJson(new org.json.JSONObject(text.substring(a, b + 1)));
        } catch (Exception e) {
            toast("El patrón no es válido");
            return;
        }
        if (p.touches.isEmpty()) {
            toast("El patrón no tiene toques");
            return;
        }
        // Escalar si el patrón viene de una pantalla de otra resolución.
        android.graphics.Point size = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        int curLong = Math.max(size.x, size.y), curShort = Math.min(size.x, size.y);
        if (p.screenW > 0 && p.screenH > 0) {
            boolean landscape = p.screenW > p.screenH;
            int newW = landscape ? curLong : curShort;
            int newH = landscape ? curShort : curLong;
            float sx = (float) newW / p.screenW, sy = (float) newH / p.screenH;
            if (Math.abs(sx - 1f) > 0.001f || Math.abs(sy - 1f) > 0.001f) {
                for (Pattern.Touch t : p.touches) {
                    java.util.List<Pattern.Point> scaled = new java.util.ArrayList<>();
                    for (Pattern.Point pt : t.pts) scaled.add(new Pattern.Point(pt.t, pt.x * sx, pt.y * sy));
                    t.pts.clear();
                    t.pts.addAll(scaled);
                }
            }
            p.screenW = newW;
            p.screenH = newH;
        }
        p.normalize();
        store.saveNew(p);
        if (TouchService.get() != null) TouchService.get().refresh();
        toast("Importado: " + p.name);
        render();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void rename(String id, Pattern pt) {
        EditText et = new EditText(this);
        et.setText(pt.name);
        new AlertDialog.Builder(this).setTitle("Nombre del patrón").setView(et)
                .setPositiveButton("Guardar", (d, w) -> {
                    pt.name = et.getText().toString().trim();
                    if (pt.name.isEmpty()) pt.name = "Patrón";
                    store.save(id, pt);
                    if (TouchService.get() != null) TouchService.get().refresh();
                    render();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    // ---------------------------------------------------------------- helpers de UI

    private int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.WHITE);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), Color.parseColor("#E3E5E8"));
        c.setBackground(d);
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView small(String s, int color, View.OnClickListener l) {
        TextView t = text(s, 13, color, true);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    private EditText number(String v) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(v);
        e.setEms(5);
        return e;
    }

    private LinearLayout row(String label, View field) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(text(label, 14, GRAFITO, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(field);
        return r;
    }

    private void space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        root.addView(v);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
