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

/** Pantalla principal: servicio, set activo (brawler · modo y sus patrones), patrones y botonera. */
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
        root.addView(ui.text("Botonera con patrones por brawler y modo de batalla.", 14, GRIS, false));
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
            card.addView(ui.text("Botones 1–N: toque = ese patrón en bucle · otro toque = parar · 🎲 = al azar.\n"
                    + "Asa ≡: toque = cambiar set / candado · mantener 3 s = candado · arrastrar = mover.\n"
                    + "Volumen − detiene · volumen + repite.", 13, GRAFITO, false));
            card.addView(ui.button(svc.isPanelVisible() ? "Ocultar botonera" : "Mostrar botonera", GRAFITO, v -> {
                if (svc.isPanelVisible()) svc.hidePanel(); else svc.showPanel();
                render();
            }));
        }
        root.addView(card);
        root.addView(ui.space(12));

        // --- Lápiz por Shizuku (22-oct)
        LinearLayout pen = ui.card();
        PenShizuku.State ps = PenShizuku.state();
        boolean reading = PenShizuku.active();
        pen.addView(ui.text(reading ? "● Lápiz conectado (Shizuku)"
                : ps == PenShizuku.State.NOT_RUNNING ? "○ Shizuku no está corriendo"
                : ps == PenShizuku.State.NO_PERMISSION ? "○ Falta el permiso de Shizuku" : "○ Conectando el lápiz…",
                16, reading ? VERDE : GRIS, true));
        pen.addView(ui.text("Con Shizuku la app sabe cuándo acercas, apoyas, arrastras y alejas el S Pen: pausa al "
                + "acercarlo y retoma al alejarlo, hacia donde lo arrastraste. Shizuku se inicia una vez tras cada "
                + "reinicio del celular, desde la app Shizuku (Depuración inalámbrica).", 13, GRAFITO, false));
        if (ps == PenShizuku.State.NO_PERMISSION) {
            pen.addView(ui.button("Dar permiso a Patrón Touch", AZUL, v -> {
                PenShizuku.requestPermission();
                sv.postDelayed(this::render, 1500);
            }));
        } else if (ps == PenShizuku.State.NOT_RUNNING) {
            pen.addView(ui.button("Abrir Shizuku", AZUL, v -> {
                Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (i != null) startActivity(i);
                else startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")));
            }));
        }
        root.addView(pen);
        root.addView(ui.space(12));

        // --- Lectura del joystick por captura de pantalla (21-oct)
        LinearLayout cap = ui.card();
        boolean on = CaptureService.isRunning();
        cap.addView(ui.text(on ? "● Mirando tu joystick" : "○ Sin mirar el joystick", 16, on ? VERDE : GRIS, true));
        cap.addView(ui.text("Con la captura de pantalla la app ve hacia dónde mueves el joystick cuando el patrón "
                + "está apagado, y «última dirección» / «dirigido» siguen esa dirección. Android pide «Iniciar "
                + "grabación» cada vez.", 13, GRAFITO, false));
        cap.addView(ui.button(on ? "Dejar de mirar" : "Mirar mi joystick", on ? GRAFITO : AZUL, v -> {
            if (CaptureService.isRunning()) stopService(new Intent(this, CaptureService.class));
            else startActivity(new Intent(this, CaptureActivity.class));
            sv.postDelayed(this::render, 400);
        }));
        root.addView(cap);
        root.addView(ui.space(12));

        renderSet();
        root.addView(ui.space(12));
        renderPatterns();
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
        c.addView(ui.text("Un set = un brawler en un modo de batalla, con sus patrones en el orden de los botones. "
                + "Cada patrón trae su propio tipo de disparo.", 12, GRIS, false));
        c.addView(SetPicker.build(this, data, set, s -> {
            if (s != null && !s.id.equals(data.activeSet)) {
                data.activeSet = s.id;
                commit();
            }
        }));
        if (set == null) {
            root.addView(c);
            return;
        }
        c.addView(ui.selector("Brawler", set.brawler, v -> ui.ask("Brawler", set.brawler, false, s -> {
            if (!s.isEmpty()) set.brawler = s;
            commit();
        })));
        c.addView(ui.selector("Modo de batalla", set.mode.isEmpty() ? "—" : set.mode, v -> pickMode(set)));

        List<Data.PatternDef> pats = data.slotPatterns(set);
        for (int i = 0; i < pats.size(); i++) {
            final int idx = i;
            Data.PatternDef def = pats.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, ui.dp(6), 0, 0);
            TextView num = ui.text(String.valueOf(i + 1), 18, AZUL, true);
            num.setPadding(ui.dp(4), 0, ui.dp(10), 0);
            r.addView(num);
            TextView sel = ui.selector("Patrón", def.name, v -> pickSlotPattern(set, idx));
            sel.setText(def.name + "  ▾");
            r.addView(sel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView up = ui.link("↑", GRAFITO, v -> {
                moveSlot(set, def.id, -1);
                commit();
            });
            TextView down = ui.link("↓", GRAFITO, v -> {
                moveSlot(set, def.id, 1);
                commit();
            });
            TextView del = ui.link("✕", CORAL, v -> {
                set.slots.remove(def.id);
                commit();
            });
            up.setTextSize(18);
            down.setTextSize(18);
            r.addView(up);
            r.addView(down);
            r.addView(del);
            c.addView(r);
        }
        if (pats.size() < Data.MAX_SLOTS) {
            c.addView(ui.link("＋ Agregar patrón al set", AZUL, v -> pickSlotPattern(set, -1)));
        }
        c.addView(ui.text("En la botonera: toque = ese patrón en bucle hasta volver a tocarlo. "
                + "🎲 Random = un patrón del set al azar en cada repetición.", 12, GRIS, false));
        c.addView(ui.row(
                ui.button("Nuevo set", AZUL, v -> newSet(null)),
                ui.button("Duplicar", SUAVE, v -> newSet(set))));
        if (data.sets.size() > 1) {
            c.addView(ui.link("Borrar este set", CORAL, v -> ui.confirm("¿Borrar el set \"" + set.title() + "\"?", "Borrar", () -> {
                data.sets.remove(set);
                data.activeSet = data.sets.get(0).id;
                commit();
            })));
        }
        root.addView(c);
    }

    private static final String[] MODES = {"Supervivencia", "Supervivencia dúo", "Atrapagemas", "Balón Brawl",
            "Atraco", "Zona restringida", "Noqueo", "Caza estelar", "Asedio", "Todos"};

    private void pickMode(Data.PadSet set) {
        String[] items = new String[MODES.length + 1];
        System.arraycopy(MODES, 0, items, 0, MODES.length);
        items[MODES.length] = "Otro…";
        ui.choose("Modo de batalla", items, i -> {
            if (i < MODES.length) {
                set.mode = MODES[i];
                commit();
            } else {
                ui.ask("Modo de batalla", set.mode, false, s -> {
                    set.mode = s;
                    commit();
                });
            }
        });
    }

    /** Dos pasos (con 30+ sets la lista plana obligaba a scrollear mucho): brawler y después modo. */
    private void pickSet() {
        java.util.Map<String, List<Data.PadSet>> byBrawler = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Data.PadSet s : data.sets) {
            String b = s.brawler.isEmpty() ? s.name : s.brawler;
            if (!byBrawler.containsKey(b)) byBrawler.put(b, new ArrayList<>());
            byBrawler.get(b).add(s);
        }
        List<String> names = new ArrayList<>(byBrawler.keySet());
        String[] items = new String[names.size()];
        for (int i = 0; i < items.length; i++) {
            List<Data.PadSet> ss = byBrawler.get(names.get(i));
            StringBuilder modes = new StringBuilder();
            for (Data.PadSet s : ss) modes.append(modes.length() > 0 ? " · " : "").append(s.mode.isEmpty() ? s.name : s.mode);
            items[i] = names.get(i) + "   (" + modes + ")";
        }
        ui.choose("Elige brawler", items, i -> {
            List<Data.PadSet> ss = byBrawler.get(names.get(i));
            if (ss.size() == 1) {
                data.activeSet = ss.get(0).id;
                commit();
                return;
            }
            String[] modes = new String[ss.size()];
            for (int k = 0; k < modes.length; k++)
                modes[k] = (ss.get(k).mode.isEmpty() ? ss.get(k).name : ss.get(k).mode) + "  ·  " + data.slotPatterns(ss.get(k)).size() + " patrones";
            ui.choose(names.get(i) + " · modo", modes, k -> {
                data.activeSet = ss.get(k).id;
                commit();
            });
        });
    }

    /** idx = -1 agrega al final; si no, reemplaza el patrón de ese botón. */
    private void pickSlotPattern(Data.PadSet set, int idx) {
        String[] items = new String[data.patterns.size()];
        for (int i = 0; i < items.length; i++) items[i] = data.patterns.get(i).name;
        ui.choose(idx < 0 ? "Agregar patrón" : "Patrón del botón " + (idx + 1), items, i -> {
            String id = data.patterns.get(i).id;
            List<Data.PatternDef> pats = data.slotPatterns(set);
            set.slots.clear();
            for (Data.PatternDef p : pats) set.slots.add(p.id);
            set.slots.remove(id);
            if (idx < 0 || idx >= set.slots.size() + 1) set.slots.add(id);
            else set.slots.add(Math.min(idx, set.slots.size()), id);
            while (set.slots.size() > Data.MAX_SLOTS) set.slots.remove(set.slots.size() - 1);
            commit();
        });
    }

    private static void moveSlot(Data.PadSet set, String id, int d) {
        int i = set.slots.indexOf(id), j = i + d;
        if (i < 0 || j < 0 || j >= set.slots.size()) return;
        set.slots.set(i, set.slots.get(j));
        set.slots.set(j, id);
    }

    private void newSet(Data.PadSet from) {
        ui.ask(from == null ? "Brawler del nuevo set (ej. Kenji)" : "Brawler de la copia", from == null ? "" : from.brawler, false, name -> {
            Data.PadSet s = new Data.PadSet();
            s.id = Data.newId("s");
            s.brawler = name.isEmpty() ? "Brawler " + (data.sets.size() + 1) : name;
            s.mode = from != null ? from.mode : "Todos";
            s.name = s.brawler;
            if (from != null) s.slots.addAll(from.slots);
            data.sets.add(s);
            data.activeSet = s.id;
            commit();
            pickMode(s);
        });
    }

    // ---------------------------------------------------------------- patrones

    /** Grupo de un patrón: lo que va antes de «·» en su nombre («Rico · Ráfaga» → Rico). */
    private static String groupOf(Data.PatternDef p) {
        int dot = p.name.indexOf('·');
        return dot > 0 ? p.name.substring(0, dot).trim() : "Otros";
    }

    /** Grupo elegido en el filtro (se recuerda mientras la app está abierta). */
    private String patternGroup;

    private void renderPatterns() {
        LinearLayout c = ui.card();
        c.addView(ui.text("Patrones", 16, GRAFITO, true));
        c.addView(ui.text("Se crean con instrucciones: pasos con botones o texto libre.", 12, GRIS, false));
        java.util.TreeMap<String, Integer> groups = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Data.PatternDef p : data.patterns) groups.merge(groupOf(p), 1, Integer::sum);
        List<String> names = new ArrayList<>(groups.keySet());
        if (patternGroup == null || !groups.containsKey(patternGroup)) {
            // Parte en el grupo del primer patrón del set activo (en «General · Circular» es «Circular»).
            List<Data.PatternDef> act = data.slotPatterns(data.active());
            String b = act.isEmpty() ? null : groupOf(act.get(0));
            patternGroup = b != null && groups.containsKey(b) ? b : names.isEmpty() ? "Otros" : names.get(0);
        }
        List<String> labels = new ArrayList<>();
        for (String n : names) labels.add(n + "  ·  " + groups.get(n));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        c.addView(SetPicker.dropdown(this, "Brawler", labels, names.indexOf(patternGroup), i -> {
            patternGroup = names.get(i);
            fillPatterns(list);
        }));
        c.addView(list);
        fillPatterns(list);
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

    /** Solo los patrones del grupo elegido. */
    private void fillPatterns(LinearLayout c) {
        c.removeAllViews();
        for (Data.PatternDef p : data.patterns) {
            if (!groupOf(p).equalsIgnoreCase(patternGroup)) continue;
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
    }

    private void edit(String id, String script) {
        Intent i = new Intent(this, PatternEditorActivity.class);
        if (id != null) i.putExtra("id", id);
        if (script != null) i.putExtra("script", script);
        startActivity(i);
    }

    // ---------------------------------------------------------------- cruceta y calibración

    private void renderPad(TouchService svc) {
        LinearLayout c = ui.card();
        c.addView(ui.text("Botonera", 16, GRAFITO, true));
        Switch lock = new Switch(this);
        lock.setText("🔒 Bloquear posición y ✕");
        lock.setTextColor(GRAFITO);
        lock.setChecked(prefs.getBoolean("padLocked", false));
        lock.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("padLocked", on).apply();
            if (TouchService.get() != null) TouchService.get().rebuildPanel();
        });
        c.addView(lock);
        c.addView(ui.text("También se bloquea o desbloquea manteniendo el asa ≡ 3 s.", 12, GRIS, false));
        c.addView(slider("Opacidad", "padAlpha", 45, 15, 90, "%"));
        c.addView(slider("Tamaño", "padSize", 58, 40, 90, " dp"));
        Switch vib = new Switch(this);
        vib.setText("Vibrar al activar el candado");
        vib.setTextColor(GRAFITO);
        vib.setChecked(prefs.getBoolean("vibrate", true));
        vib.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("vibrate", on).apply());
        c.addView(vib);
        Switch esc = new Switch(this);
        esc.setText("Mostrar botón 🏃 Escape");
        esc.setTextColor(GRAFITO);
        esc.setChecked(prefs.getBoolean("showEscape", true));
        esc.setOnCheckedChangeListener((b, on) -> {
            SharedPreferences.Editor e = prefs.edit().putBoolean("showEscape", on);
            if (!on) e.putBoolean("escapeMode", false); // oculto = apagado: nada queda sin disparos
            e.apply();
            if (TouchService.get() != null) TouchService.get().rebuildPanel();
        });
        c.addView(esc);
        Switch rnd = new Switch(this);
        rnd.setText("Mostrar botón 🎲 Random");
        rnd.setTextColor(GRAFITO);
        rnd.setChecked(prefs.getBoolean("showRandom", true));
        rnd.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("showRandom", on).apply();
            if (TouchService.get() != null) TouchService.get().rebuildPanel();
        });
        c.addView(rnd);
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
