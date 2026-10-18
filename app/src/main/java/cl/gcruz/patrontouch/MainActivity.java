package cl.gcruz.patrontouch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** Pantalla principal: activar el servicio, biblioteca, calibración, ajustes y patrones propios. */
public class MainActivity extends Activity {

    private static final int CORAL = Color.parseColor("#E25E6B");
    private static final int AZUL = Color.parseColor("#406AB4");
    private static final int VERDE = Color.parseColor("#73C96A");
    private static final int GRAFITO = Color.parseColor("#454A51");
    private static final int GRIS = Color.parseColor("#969798");
    private static final int REQ_IMPORT = 42;

    private PatternStore store;
    private SharedPreferences prefs;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new PatternStore(this);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
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
        root.addView(text("Cuatro patrones completos (↑ ↓ ← →) para cualquier brawler.", 14, GRIS, false));
        space(14);

        // --- Servicio
        LinearLayout card = card();
        if (svc == null) {
            card.addView(text("● Servicio desactivado", 16, CORAL, true));
            card.addView(text("Activa \"Patrón Touch\" en Accesibilidad. Si Android dice \"Configuración restringida\": "
                    + "Ajustes › Apps › Patrón Touch › ⋮ › Permitir ajustes restringidos.", 13, GRAFITO, false));
            card.addView(button("Abrir Accesibilidad", AZUL, v ->
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        } else {
            card.addView(text("● Servicio activo", 16, VERDE, true));
            card.addView(text("En el juego toca ↑ ↓ ← →: se mueve hacia allá disparando y usa gadget, hipercarga y súper. "
                    + "Se ejecuta una vez. ◎ calibra · – minimiza · ✕ cierra · ⠿ arrastra.", 13, GRAFITO, false));
            card.addView(button(svc.isPanelVisible() ? "Ocultar panel" : "Mostrar panel", GRAFITO, v -> {
                if (svc.isPanelVisible()) svc.hidePanel(); else svc.showPanel();
                render();
            }));
        }
        root.addView(card);
        space(12);

        // --- Calibración
        LinearLayout calCard = card();
        calCard.addView(text("Controles", 16, GRAFITO, true));
        boolean calibrated = svc != null && svc.calibration().calibrated;
        calCard.addView(text(calibrated
                ? "✓ Calibrados. Si cambias la disposición de tus controles, vuelve a calibrar."
                : "Sin calibrar. Abre Brawl Stars en una partida o en el entrenamiento, toca ◎ en el panel "
                + "y luego toca cada botón cuando la app lo pida (joystick, ataque, súper, gadget e hipercarga).",
                13, calibrated ? VERDE : GRAFITO, false));
        root.addView(calCard);
        space(12);

        // --- Ajustes
        LinearLayout set = card();
        set.addView(text("Atajos", 16, GRAFITO, true));
        Switch vol = new Switch(this);
        vol.setText("Volumen + repite el último patrón (solo con el panel abierto)");
        vol.setTextColor(GRAFITO);
        vol.setChecked(prefs.getBoolean("volShortcut", true));
        vol.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("volShortcut", on).apply());
        set.addView(vol);
        set.addView(text("Volumen − siempre detiene un patrón en curso.", 12, GRIS, false));
        root.addView(set);
        space(12);

        root.addView(text("Tip: mantén presionada una flecha para ver qué hace. Volumen − detiene.", 12, GRIS, false));
    }

    // ---------------------------------------------------------------- importar

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

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
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

    private void space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        root.addView(v);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
