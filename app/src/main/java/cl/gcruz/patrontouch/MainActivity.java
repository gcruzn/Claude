package cl.gcruz.patrontouch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static cl.gcruz.patrontouch.Ui.*;

/** Pantalla principal: servicio, set activo (perfil + flechas), patrones, perfiles y cruceta. */
public class MainActivity extends Activity {

    private Ui ui;
    private Data data;
    private SharedPreferences prefs;
    private LinearLayout root;
    private ScrollView sv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        sv = new ScrollView(this);
        sv.setBackgroundColor(FONDO);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = ui.dp(16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        data = Data.load(this);
        render();
    }

    /** Guarda, avisa a la cruceta y redibuja manteniendo el scroll. */
    private void commit() {
        data.save();
        if (TouchService.get() != null) TouchService.get().reloadData();
        int y = sv.getScrollY();
        render();
        sv.post(() -> sv.scrollTo(0, y));
    }

    private void render() {
        root.removeAllViews();
        TouchService svc = TouchService.get();

        root.addView(ui.text("Patrón Touch", 24, GRAFITO, true));
        root.addView(ui.text("Cruceta con patrones creados por instrucciones.", 14, GRIS, false));
        root.addView(ui.space(12));

        // --- Servicio
        LinearLayout card = ui.card();
        if (svc == null) {
            card.addView(ui.text("● Servicio desactivado", 16, CORAL, true));
            card.addView(ui.text("Activa \"Patrón Touch\" en Accesibilidad. Si dice \"Configuración restringida\": "
                    + "Ajustes › Apps › Patrón Touch › ⋮ › Permitir ajustes restringidos.", 13, GRAFITO, false));
            card.addView(ui.button("Abrir Accesibilidad", AZUL, v ->
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        } else {
            card.addView(ui.text("● Servicio activo", 16, VERDE, true));
            card.addView(ui.text("Flechas: toque = patrón · doble toque = bucle · mantener (vibra) y soltar = solo moverse.\n"
                    + "Centro: toque = cambiar set / candado · mantener 3 s = candado.\n"
                    + "Volumen − detiene · volumen + repite.", 13, GRAFITO, false));
            card.addView(ui.button(svc.isPanelVisible() ? "Ocultar cruceta" : "Mostrar cruceta", GRAFITO, v -> {
                if (svc.isPanelVisible()) svc.hidePanel(); else svc.showPanel();
                render();
            }));
        }
        root.addView(card);
        root.addView(ui.space(12));

        renderSet();
        root.addView(ui.space(12));
        renderPatterns();
        root.addView(ui.space(12));
        renderProfiles();
        root.addView(ui.space(12));
        renderPad(svc);
        root.addView(ui.space(12));
        renderCalibration(svc);
        root.addView(ui.space(24));
    }

    // ---------------------------------------------------------------- set activo

    private void renderSet() {
        Data.PadSet set = data.active();
        LinearLayout c = ui.card();
        c.addView(ui.text("Set activo", 16, GRAFITO, true));
        c.addView(ui.text("Un set = un perfil + el patrón de cada flecha. Crea uno por brawler.", 12, GRIS, false));
        c.addView(ui.selector("Set", set == null ? "—" : set.name, v -> pickSet()));
        if (set == null) {
            root.addView(c);
            return;
        }
        String profLabel = set.profileId.isEmpty()
                ? "Usar global (" + data.profileFor(set).name + ")"
                : data.profileFor(set).name;
        c.addView(ui.selector("Perfil del set", profLabel, v -> pickSetProfile(set)));

        for (int i = 0; i < Data.ARROWS.length; i++) {
            String a = Data.ARROWS[i];
            Data.Arrow ar = set.arrows.get(a);
            if (ar == null) {
                ar = new Data.Arrow();
                set.arrows.put(a, ar);
            }
            final Data.Arrow arrow = ar;
            Data.PatternDef def = data.pattern(arrow.patternId);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, ui.dp(6), 0, 0);
            TextView icon = ui.text(Data.ARROW_ICONS[i], 20, AZUL, true);
            icon.setPadding(ui.dp(4), 0, ui.dp(8), 0);
            r.addView(icon);
            TextView sel = ui.selector("Patrón", def == null ? "(ninguno)" : def.name, v -> pickArrowPattern(arrow));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            sel.setLayoutParams(lp);
            sel.setText((def == null ? "(ninguno)" : def.name) + "  ▾");
            r.addView(sel);
            TextView minus = ui.link("−", GRAFITO, v -> {
                arrow.reps = Math.max(1, arrow.reps - 1);
                commit();
            });
            TextView reps = ui.text("×" + arrow.reps, 15, GRAFITO, true);
            TextView plus = ui.link("+", GRAFITO, v -> {
                arrow.reps = Math.min(99, arrow.reps + 1);
                commit();
            });
            minus.setTextSize(20);
            plus.setTextSize(20);
            r.addView(minus);
            r.addView(reps);
            r.addView(plus);
            c.addView(r);
        }
        c.addView(ui.text("×N = repeticiones con un toque. Doble toque = infinito.", 12, GRIS, false));
        c.addView(ui.row(
                ui.button("Nuevo set", AZUL, v -> newSet(null)),
                ui.button("Duplicar", SUAVE, v -> newSet(set)),
                ui.button("Renombrar", SUAVE, v -> ui.ask("Nombre del set", set.name, false, s -> {
                    if (!s.isEmpty()) set.name = s;
                    commit();
                }))));
        if (data.sets.size() > 1) {
            c.addView(ui.link("Borrar este set", CORAL, v -> ui.confirm("¿Borrar el set \"" + set.name + "\"?", "Borrar", () -> {
                data.sets.remove(set);
                data.activeSet = data.sets.get(0).id;
                commit();
            })));
        }
        root.addView(c);
    }

    private void pickSet() {
        String[] items = new String[data.sets.size()];
        for (int i = 0; i < items.length; i++) items[i] = data.sets.get(i).name + "  ·  " + data.profileFor(data.sets.get(i)).name;
        ui.choose("Elige set", items, i -> {
            data.activeSet = data.sets.get(i).id;
            commit();
        });
    }

    private void pickSetProfile(Data.PadSet set) {
        String[] items = new String[data.profiles.size() + 1];
        items[0] = "Usar perfil global (" + data.profileFor(null).name + ")";
        for (int i = 0; i < data.profiles.size(); i++) items[i + 1] = data.profiles.get(i).name + "  ·  " + data.profiles.get(i).summary();
        ui.choose("Perfil del set", items, i -> {
            set.profileId = i == 0 ? "" : data.profiles.get(i - 1).id;
            commit();
        });
    }

    private void pickArrowPattern(Data.Arrow arrow) {
        String[] items = new String[data.patterns.size()];
        for (int i = 0; i < items.length; i++) items[i] = data.patterns.get(i).name;
        ui.choose("Patrón para esta flecha", items, i -> {
            arrow.patternId = data.patterns.get(i).id;
            commit();
        });
    }

    private void newSet(Data.PadSet from) {
        ui.ask(from == null ? "Nombre del nuevo set (ej. Shade)" : "Nombre de la copia", from == null ? "" : from.name + " (copia)", false, name -> {
            Data.PadSet s = new Data.PadSet();
            s.id = Data.newId("s");
            s.name = name.isEmpty() ? "Set " + (data.sets.size() + 1) : name;
            Data.PadSet base = from != null ? from : data.active();
            s.profileId = from != null ? from.profileId : "";
            for (String a : Data.ARROWS) {
                Data.Arrow ar = new Data.Arrow();
                Data.Arrow src = base == null ? null : base.arrows.get(a);
                ar.patternId = src == null ? "p_" + a : src.patternId;
                ar.reps = src == null ? 1 : src.reps;
                s.arrows.put(a, ar);
            }
            data.sets.add(s);
            data.activeSet = s.id;
            commit();
        });
    }

    // ---------------------------------------------------------------- patrones

    private void renderPatterns() {
        LinearLayout c = ui.card();
        c.addView(ui.text("Patrones", 16, GRAFITO, true));
        c.addView(ui.text("Se crean con instrucciones: pasos con botones o texto libre.", 12, GRIS, false));
        for (Data.PatternDef p : data.patterns) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, ui.dp(6), 0, ui.dp(6));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(ui.text(p.name, 15, GRAFITO, true));
            String preview = p.script.replace("\n", " · ");
            if (preview.length() > 60) preview = preview.substring(0, 60) + "…";
            info.addView(ui.text(preview, 12, GRIS, false));
            info.setOnClickListener(v -> edit(p.id, null));
            r.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(ui.link("Editar", AZUL, v -> edit(p.id, null)));
            r.addView(ui.link("Copiar", GRAFITO, v -> {
                Data.PatternDef d = new Data.PatternDef();
                d.id = Data.newId("p");
                d.name = p.name + " (copia)";
                d.script = p.script;
                data.patterns.add(d);
                commit();
            }));
            r.addView(ui.link("Borrar", CORAL, v -> ui.confirm("¿Borrar \"" + p.name + "\"?", "Borrar", () -> {
                data.patterns.remove(p);
                commit();
            })));
            c.addView(r);
        }
        c.addView(ui.row(
                ui.button("＋ Nuevo patrón", AZUL, v -> edit(null, null)),
                ui.button("Pegar texto", SUAVE, v -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    CharSequence cs = cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0
                            ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
                    edit(null, cs == null ? "" : cs.toString());
                })));
        root.addView(c);
    }

    private void edit(String id, String script) {
        Intent i = new Intent(this, PatternEditorActivity.class);
        if (id != null) i.putExtra("id", id);
        if (script != null) i.putExtra("script", script);
        startActivity(i);
    }

    // ---------------------------------------------------------------- perfiles

    private void renderProfiles() {
        LinearLayout c = ui.card();
        c.addView(ui.text("Perfiles de disparo", 16, GRAFITO, true));
        c.addView(ui.text("Definen cómo se ejecuta cada \"disparar\" y \"súper\" (rápido, lento, mantener, apuntado…).", 12, GRIS, false));
        Data.Profile global = data.profileFor(null);
        c.addView(ui.selector("Perfil global", global.name, v -> {
            String[] items = new String[data.profiles.size()];
            for (int i = 0; i < items.length; i++) items[i] = data.profiles.get(i).name;
            ui.choose("Perfil global", items, i -> {
                data.globalProfile = data.profiles.get(i).id;
                commit();
            });
        }));
        for (Data.Profile p : data.profiles) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, ui.dp(6), 0, ui.dp(6));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(ui.text(p.name, 15, GRAFITO, true));
            info.addView(ui.text(p.summary(), 12, GRIS, false));
            r.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(ui.link("Editar", AZUL, v -> editProfile(p, false)));
            if (data.profiles.size() > 1) {
                r.addView(ui.link("Borrar", CORAL, v -> ui.confirm("¿Borrar el perfil \"" + p.name + "\"?", "Borrar", () -> {
                    data.profiles.remove(p);
                    for (Data.PadSet s : data.sets) if (s.profileId.equals(p.id)) s.profileId = "";
                    if (data.globalProfile.equals(p.id)) data.globalProfile = data.profiles.get(0).id;
                    commit();
                })));
            }
            c.addView(r);
        }
        c.addView(ui.button("＋ Nuevo perfil", AZUL, v -> {
            Data.Profile p = new Data.Profile();
            p.id = Data.newId("f");
            p.name = "Mi perfil";
            editProfile(p, true);
        }));
        root.addView(c);
    }

    private void editProfile(Data.Profile p, boolean isNew) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(20);
        box.setPadding(pad, ui.dp(8), pad, 0);

        EditText name = new EditText(this);
        name.setText(p.name);
        name.setHint("Nombre");
        box.addView(name);

        box.addView(ui.text("Disparo", 14, GRAFITO, true));
        RadioGroup rg = new RadioGroup(this);
        String[] modes = {"tap", "hold", "aim"};
        String[] labels = {"Toque (auto-apuntado)", "Mantener apretado", "Apuntado (arrastrar)"};
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setId(1000 + i);
            rg.addView(rb);
            if (modes[i].equals(p.attack)) rb.setChecked(true);
        }
        box.addView(rg);

        EditText interval = Ui.field(this, Data.fmt(p.interval), true);
        EditText hold = Ui.field(this, Data.fmt(p.hold), true);
        box.addView(labeled("Segundos entre disparos", interval));
        box.addView(labeled("Segundos manteniendo (modo mantener)", hold));

        final String[] aim = {p.aimDir};
        final String[] sup = {p.superMode};
        TextView aimSel = ui.selector("Dirección del disparo apuntado", Script.dirLabel(aim[0]), null);
        aimSel.setOnClickListener(v -> ui.choose("Dirección del disparo apuntado", Ui.dirChoices(true, false), i -> {
            aim[0] = Ui.dirFromChoice(i, true, false);
            aimSel.setText("Dirección del disparo apuntado:  " + Script.dirLabel(aim[0]) + "  ▾");
        }));
        box.addView(aimSel);
        TextView supSel = ui.selector("Súper", "auto".equals(sup[0]) ? "auto" : "apuntado " + Script.dirLabel(sup[0]), null);
        supSel.setOnClickListener(v -> ui.choose("Súper", Ui.dirChoices(true, true), i -> {
            sup[0] = Ui.dirFromChoice(i, true, true);
            supSel.setText("Súper:  " + ("auto".equals(sup[0]) ? "auto" : "apuntado " + Script.dirLabel(sup[0])) + "  ▾");
        }));
        box.addView(supSel);

        ScrollView s = new ScrollView(this);
        s.addView(box);
        new AlertDialog.Builder(this).setTitle(isNew ? "Nuevo perfil" : "Editar perfil").setView(s)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = name.getText().toString().trim();
                    p.name = n.isEmpty() ? "Perfil" : n;
                    int checked = rg.getCheckedRadioButtonId() - 1000;
                    p.attack = checked >= 0 && checked < modes.length ? modes[checked] : "tap";
                    p.interval = (int) Math.round(Math.max(0.1, Ui.parseNum(interval.getText().toString(), 0.55)) * 1000);
                    p.hold = (int) Math.round(Math.max(0.1, Ui.parseNum(hold.getText().toString(), 1)) * 1000);
                    p.aimDir = aim[0];
                    p.superMode = sup[0];
                    if (isNew) data.profiles.add(p);
                    commit();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private LinearLayout labeled(String label, android.view.View field) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(ui.text(label, 14, GRAFITO, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(field);
        return r;
    }

    // ---------------------------------------------------------------- cruceta y calibración

    private void renderPad(TouchService svc) {
        LinearLayout c = ui.card();
        c.addView(ui.text("Cruceta", 16, GRAFITO, true));
        Switch lock = new Switch(this);
        lock.setText("🔒 Bloquear posición y ✕");
        lock.setTextColor(GRAFITO);
        lock.setChecked(prefs.getBoolean("padLocked", false));
        lock.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("padLocked", on).apply();
            if (TouchService.get() != null) TouchService.get().rebuildPanel();
        });
        c.addView(lock);
        c.addView(ui.text("También se bloquea o desbloquea manteniendo el centro de la cruceta 3 s.", 12, GRIS, false));
        c.addView(slider("Opacidad", "padAlpha", 45, 15, 90, "%"));
        c.addView(slider("Tamaño", "padSize", 58, 40, 90, " dp"));
        Switch vib = new Switch(this);
        vib.setText("Vibrar al activar bucle, solo-movimiento o candado");
        vib.setTextColor(GRAFITO);
        vib.setChecked(prefs.getBoolean("vibrate", true));
        vib.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("vibrate", on).apply());
        c.addView(vib);
        Switch vol = new Switch(this);
        vol.setText("Volumen + repite la última acción");
        vol.setTextColor(GRAFITO);
        vol.setChecked(prefs.getBoolean("volShortcut", true));
        vol.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("volShortcut", on).apply());
        c.addView(vol);
        root.addView(c);
    }

    private void renderCalibration(TouchService svc) {
        LinearLayout c = ui.card();
        c.addView(ui.text("Controles del juego", 16, GRAFITO, true));
        boolean calibrated = svc != null && svc.calibration().calibrated;
        c.addView(ui.text(calibrated
                ? "✓ Calibrados. Si cambias la disposición de tus controles, vuelve a calibrar."
                : "Sin calibrar: toca el botón, abre Brawl Stars en el entrenamiento y toca cada control cuando se pida.",
                13, calibrated ? VERDE : GRAFITO, false));
        if (svc != null) c.addView(ui.button("Calibrar en el juego", AZUL, v -> {
            TouchService.get().calibrateSoon();
            moveTaskToBack(true);
        }));
        root.addView(c);
    }

    private LinearLayout slider(String label, String key, int def, int min, int max, String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = ui.text(label + ": " + prefs.getInt(key, def) + unit, 14, GRAFITO, false);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(prefs.getInt(key, def) - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                t.setText(label + ": " + (p + min) + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(key, s.getProgress() + min).apply();
                if (TouchService.get() != null) TouchService.get().rebuildPanel();
            }
        });
        box.addView(t);
        box.addView(sb);
        return box;
    }
}
