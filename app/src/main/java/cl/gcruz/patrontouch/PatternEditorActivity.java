package cl.gcruz.patrontouch;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static cl.gcruz.patrontouch.Ui.*;

/**
 * Editor de un patrón con dos modos que editan lo mismo:
 *  - Pasos: lista de instrucciones que se agregan, editan y reordenan con botones.
 *  - Texto: instrucciones escritas, una por línea.
 */
public class PatternEditorActivity extends Activity {

    private Ui ui;
    private Data data;
    private Data.PatternDef def;
    private boolean isNew;
    private boolean textMode;
    private List<Script.Step> steps = new ArrayList<>();

    private EditText nameEt, textEt;
    private LinearLayout stepsBox, stepsPanel, textPanel;
    private TextView errorTv, modeSteps, modeText;

    private static final String HELP =
            "Una instrucción por línea:\n"
                    + "mover arriba [segundos] [disparando]\n"
                    + "parar\n"
                    + "disparar [N] [toque | mantener | apuntado] [dirección]\n"
                    + "súper [auto | apuntado] [dirección]\n"
                    + "gadget · hiper · esperar segundos\n"
                    + "Direcciones: arriba, abajo, izquierda, derecha, arriba-izquierda… (arriba = hacia el enemigo).\n"
                    + "El movimiento sigue mientras se ejecutan las otras instrucciones, hasta \"parar\" u otro \"mover\".";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        data = Data.load(this);
        String id = getIntent().getStringExtra("id");
        def = id == null ? null : data.pattern(id);
        isNew = def == null;
        if (isNew) {
            def = new Data.PatternDef();
            def.id = Data.newId("p");
            def.name = "Nuevo patrón";
            String pasted = getIntent().getStringExtra("script");
            def.script = pasted != null && !pasted.trim().isEmpty() ? pasted.trim() : "mover arriba\ndisparar 2\nparar";
        }
        Script.Result r = Script.parse(def.script);
        if (r.ok()) {
            steps = r.steps;
        } else {
            textMode = true; // el texto pegado tiene errores: abrir en modo texto para corregirlo
        }
        build();
        if (!r.ok()) showError(r);
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(FONDO);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = ui.dp(16);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        root.addView(ui.text(isNew ? "Nuevo patrón" : "Editar patrón", 22, GRAFITO, true));
        nameEt = new EditText(this);
        nameEt.setText(def.name);
        nameEt.setHint("Nombre del patrón");
        nameEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(nameEt);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        modeSteps = tab("Pasos", v -> switchMode(false));
        modeText = tab("Texto", v -> switchMode(true));
        tabs.addView(modeSteps, new LinearLayout.LayoutParams(0, ui.dp(40), 1f));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ui.dp(40), 1f);
        tl.leftMargin = ui.dp(6);
        tabs.addView(modeText, tl);
        root.addView(tabs);

        errorTv = ui.text("", 13, CORAL, true);
        errorTv.setVisibility(View.GONE);
        root.addView(errorTv);

        // --- modo pasos
        stepsPanel = new LinearLayout(this);
        stepsPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = ui.card();
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(stepsBox);
        stepsPanel.addView(ui.space(8));
        stepsPanel.addView(card);
        stepsPanel.addView(ui.text("Agregar paso", 14, GRAFITO, true));
        GridLayout add = new GridLayout(this);
        add.setColumnCount(4);
        String[][] kinds = {{"🕹 Mover", "move"}, {"✋ Parar", "stop"}, {"🎯 Disparar", "attack"}, {"⭐ Súper", "super"},
                {"🧩 Gadget", "gadget"}, {"⚡ Hiper", "hyper"}, {"⏱ Esperar", "wait"}};
        for (String[] k : kinds) {
            TextView t = chip(k[0], v -> addStep(k[1]));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            t.setLayoutParams(lp);
            add.addView(t);
        }
        stepsPanel.addView(add);
        root.addView(stepsPanel);

        // --- modo texto
        textPanel = new LinearLayout(this);
        textPanel.setOrientation(LinearLayout.VERTICAL);
        textEt = new EditText(this);
        textEt.setTypeface(Typeface.MONOSPACE);
        textEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textEt.setMinLines(8);
        textEt.setGravity(Gravity.TOP | Gravity.START);
        textEt.setText(textMode ? def.script : Script.format(steps));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(ui.dp(10));
        bg.setStroke(ui.dp(1), 0xFFE3E5E8);
        textEt.setBackground(bg);
        textEt.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        textPanel.addView(ui.space(8));
        textPanel.addView(textEt);
        textPanel.addView(ui.text(HELP, 12, GRIS, false));
        root.addView(textPanel);

        root.addView(ui.space(8));
        root.addView(ui.row(
                ui.button("Guardar", AZUL, v -> save(true)),
                ui.button("▶ Probar", VERDE, v -> test())));
        root.addView(ui.row(
                ui.button("Copiar texto", SUAVE, v -> copy()),
                ui.button("Cancelar", SUAVE, v -> finish())));
        root.addView(ui.text("Probar usa el perfil del set activo (" + data.profileFor(data.active()).name
                + "): abre el juego y el patrón se ejecuta tras 3-2-1.", 12, GRIS, false));
        setContentView(sv);
        applyMode();
    }

    private TextView tab(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setOnClickListener(l);
        return t;
    }

    private TextView chip(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(GRAFITO);
        t.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(ui.dp(16));
        d.setStroke(ui.dp(1), 0xFFDADDE2);
        t.setBackground(d);
        t.setOnClickListener(l);
        return t;
    }

    private void styleTab(TextView t, boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(on ? AZUL : SUAVE);
        d.setCornerRadius(ui.dp(10));
        t.setBackground(d);
        t.setTextColor(on ? 0xFFFFFFFF : GRAFITO);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void applyMode() {
        styleTab(modeSteps, !textMode);
        styleTab(modeText, textMode);
        stepsPanel.setVisibility(textMode ? View.GONE : View.VISIBLE);
        textPanel.setVisibility(textMode ? View.VISIBLE : View.GONE);
        if (!textMode) renderSteps();
    }

    private void switchMode(boolean toText) {
        if (toText == textMode) return;
        if (toText) {
            textEt.setText(Script.format(steps));
            hideError();
        } else {
            Script.Result r = Script.parse(textEt.getText().toString());
            if (!r.ok()) {
                showError(r);
                return;
            }
            steps = r.steps;
            hideError();
        }
        textMode = toText;
        applyMode();
    }

    private void showError(Script.Result r) {
        errorTv.setText("⚠ Línea " + r.errorLine + ": " + r.error);
        errorTv.setVisibility(View.VISIBLE);
    }

    private void hideError() { errorTv.setVisibility(View.GONE); }

    // ---------------------------------------------------------------- lista de pasos

    private void renderSteps() {
        stepsBox.removeAllViews();
        if (steps.isEmpty()) {
            stepsBox.addView(ui.text("Sin pasos. Agrega el primero con los botones de abajo.", 13, GRIS, false));
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            final int idx = i;
            Script.Step s = steps.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, ui.dp(4), 0, ui.dp(4));
            TextView num = ui.text((i + 1) + ".", 13, GRIS, false);
            num.setMinWidth(ui.dp(26));
            r.addView(num);
            TextView label = ui.text(Script.pretty(s), 15, GRAFITO, false);
            label.setOnClickListener(v -> editStep(idx));
            r.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(ui.link("↑", GRAFITO, v -> move(idx, -1)));
            r.addView(ui.link("↓", GRAFITO, v -> move(idx, 1)));
            r.addView(ui.link("⧉", GRAFITO, v -> {
                steps.add(idx + 1, copyOf(s));
                renderSteps();
            }));
            r.addView(ui.link("✕", CORAL, v -> {
                steps.remove(idx);
                renderSteps();
            }));
            stepsBox.addView(r);
        }
    }

    private void move(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= steps.size()) return;
        Script.Step a = steps.get(i);
        steps.set(i, steps.get(j));
        steps.set(j, a);
        renderSteps();
    }

    private static Script.Step copyOf(Script.Step s) {
        Script.Step c = new Script.Step();
        c.kind = s.kind;
        c.dir = s.dir;
        c.secs = s.secs;
        c.count = s.count;
        c.mode = s.mode;
        c.fire = s.fire;
        return c;
    }

    private void addStep(String kind) {
        Script.Step s = new Script.Step();
        s.kind = kind;
        switch (kind) {
            case "stop": case "gadget": case "hyper":
                steps.add(s);
                renderSteps();
                return;
            case "wait":
                s.secs = 0.5;
                break;
            case "move":
                s.dir = "up";
                break;
        }
        stepDialog(s, true, -1);
    }

    private void editStep(int i) {
        Script.Step s = steps.get(i);
        if ("stop".equals(s.kind) || "gadget".equals(s.kind) || "hyper".equals(s.kind)) return;
        stepDialog(copyOf(s), false, i);
    }

    /** Diálogo de parámetros de un paso (mover, disparar, súper, esperar). */
    private void stepDialog(Script.Step s, boolean isNew, int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        String title;
        Runnable apply;

        switch (s.kind) {
            case "move": {
                title = "Mover";
                box.addView(ui.text("Dirección", 14, GRAFITO, true));
                final String[] dir = {s.dir == null ? "up" : s.dir};
                GridLayout g = dirGrid(dir);
                box.addView(g);
                EditText secs = Ui.field(this, s.secs > 0 ? Script.num(s.secs) : "", true);
                secs.setHint("—");
                box.addView(labeled("Segundos (vacío = hasta el siguiente paso)", secs));
                CheckBox fire = new CheckBox(this);
                fire.setText("Disparar mientras se mueve (según perfil)");
                fire.setChecked(s.fire);
                box.addView(fire);
                apply = () -> {
                    s.dir = dir[0];
                    s.secs = Math.max(0, Math.min(30, Ui.parseNum(secs.getText().toString(), 0)));
                    s.fire = fire.isChecked();
                    if (s.fire && s.secs == 0) s.secs = 1.5;
                };
                break;
            }
            case "attack": {
                title = "Disparar";
                EditText count = Ui.field(this, String.valueOf(Math.max(1, s.count)), true);
                box.addView(labeled("Cantidad de disparos", count));
                final String[] mode = {s.mode};
                final String[] dir = {s.dir};
                TextView modeSel = ui.selector("Forma", modeLabel(mode[0]), null);
                TextView dirSel = ui.selector("Hacia", dir[0] == null ? "según perfil" : Script.dirLabel(dir[0]), null);
                modeSel.setOnClickListener(v -> ui.choose("Forma de disparar",
                        new String[]{"Según el perfil", "Toque (auto-apuntado)", "Mantener apretado", "Apuntado (arrastrar)"}, i -> {
                            mode[0] = i == 0 ? null : i == 1 ? "tap" : i == 2 ? "hold" : "aim";
                            modeSel.setText("Forma:  " + modeLabel(mode[0]) + "  ▾");
                            dirSel.setVisibility("aim".equals(mode[0]) ? View.VISIBLE : View.GONE);
                        }));
                dirSel.setOnClickListener(v -> ui.choose("Hacia dónde apuntar", withProfile(Ui.dirChoices(false, false)), i -> {
                    dir[0] = i == 0 ? null : Script.DIRS[i - 1];
                    dirSel.setText("Hacia:  " + (dir[0] == null ? "según perfil" : Script.dirLabel(dir[0])) + "  ▾");
                }));
                dirSel.setVisibility("aim".equals(mode[0]) ? View.VISIBLE : View.GONE);
                box.addView(modeSel);
                box.addView(dirSel);
                apply = () -> {
                    s.count = (int) Math.max(1, Math.min(30, Math.round(Ui.parseNum(count.getText().toString(), 1))));
                    s.mode = mode[0];
                    s.dir = "aim".equals(mode[0]) ? dir[0] : null;
                };
                break;
            }
            case "super": {
                title = "Súper";
                final String[] mode = {s.mode};
                final String[] dir = {s.dir};
                TextView modeSel = ui.selector("Forma", superLabel(mode[0]), null);
                TextView dirSel = ui.selector("Hacia", dir[0] == null ? "según perfil / movimiento" : Script.dirLabel(dir[0]), null);
                modeSel.setOnClickListener(v -> ui.choose("Súper",
                        new String[]{"Según el perfil", "Auto (toque)", "Apuntado (arrastrar)"}, i -> {
                            mode[0] = i == 0 ? null : i == 1 ? "auto" : "aim";
                            modeSel.setText("Forma:  " + superLabel(mode[0]) + "  ▾");
                            dirSel.setVisibility("aim".equals(mode[0]) ? View.VISIBLE : View.GONE);
                        }));
                dirSel.setOnClickListener(v -> ui.choose("Hacia dónde apuntar", withProfile(Ui.dirChoices(false, false)), i -> {
                    dir[0] = i == 0 ? null : Script.DIRS[i - 1];
                    dirSel.setText("Hacia:  " + (dir[0] == null ? "según perfil / movimiento" : Script.dirLabel(dir[0])) + "  ▾");
                }));
                dirSel.setVisibility("aim".equals(mode[0]) ? View.VISIBLE : View.GONE);
                box.addView(modeSel);
                box.addView(dirSel);
                apply = () -> {
                    s.mode = mode[0];
                    s.dir = "aim".equals(mode[0]) ? dir[0] : null;
                };
                break;
            }
            default: {
                title = "Esperar";
                EditText secs = Ui.field(this, Script.num(s.secs > 0 ? s.secs : 0.5), true);
                box.addView(labeled("Segundos", secs));
                apply = () -> s.secs = Math.max(0, Math.min(30, Ui.parseNum(secs.getText().toString(), 0.5)));
            }
        }

        new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton(isNew ? "Agregar" : "Guardar", (d, w) -> {
                    apply.run();
                    if (isNew) steps.add(s); else steps.set(index, s);
                    renderSteps();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private static String[] withProfile(String[] dirs) {
        String[] out = new String[dirs.length + 1];
        out[0] = "Según el perfil / hacia donde me muevo";
        System.arraycopy(dirs, 0, out, 1, dirs.length);
        return out;
    }

    private static String modeLabel(String m) {
        if (m == null) return "según perfil";
        return m.equals("tap") ? "toque" : m.equals("hold") ? "mantener" : "apuntado";
    }

    private static String superLabel(String m) {
        if (m == null) return "según perfil";
        return m.equals("auto") ? "auto" : "apuntado";
    }

    /** Cuadrícula 3×3 de flechas para elegir dirección. */
    private GridLayout dirGrid(String[] dir) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        String[] order = {"upleft", "up", "upright", "left", null, "right", "downleft", "down", "downright"};
        List<TextView> cells = new ArrayList<>();
        for (String d : order) {
            TextView t = new TextView(this);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(20);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = ui.dp(54);
            lp.height = ui.dp(46);
            lp.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            t.setLayoutParams(lp);
            if (d != null) {
                t.setText(Script.dirIcon(d));
                t.setTag(d);
                t.setOnClickListener(v -> {
                    dir[0] = d;
                    paintGrid(cells, d);
                });
                cells.add(t);
            }
            g.addView(t);
        }
        paintGrid(cells, dir[0]);
        return g;
    }

    private void paintGrid(List<TextView> cells, String sel) {
        for (TextView t : cells) {
            boolean on = t.getTag().equals(sel);
            GradientDrawable d = new GradientDrawable();
            d.setColor(on ? AZUL : SUAVE);
            d.setCornerRadius(ui.dp(10));
            t.setBackground(d);
            t.setTextColor(on ? 0xFFFFFFFF : GRAFITO);
        }
    }

    private LinearLayout labeled(String label, View field) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(ui.text(label, 14, GRAFITO, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(field);
        return r;
    }

    // ---------------------------------------------------------------- guardar / probar / copiar

    /** Texto actual del patrón (validado); null si tiene errores. */
    private String currentScript() {
        if (textMode) {
            String txt = textEt.getText().toString().trim();
            Script.Result r = Script.parse(txt);
            if (!r.ok()) {
                showError(r);
                return null;
            }
            hideError();
            return txt;
        }
        if (steps.isEmpty()) {
            errorTv.setText("⚠ El patrón no tiene pasos");
            errorTv.setVisibility(View.VISIBLE);
            return null;
        }
        hideError();
        return Script.format(steps);
    }

    private boolean save(boolean close) {
        String script = currentScript();
        if (script == null) return false;
        Data fresh = Data.load(this);
        Data.PatternDef target = fresh.pattern(def.id);
        if (target == null) {
            target = new Data.PatternDef();
            target.id = def.id;
            fresh.patterns.add(target);
        }
        String n = nameEt.getText().toString().trim();
        target.name = n.isEmpty() ? "Patrón" : n;
        target.script = script;
        fresh.save();
        isNew = false;
        if (TouchService.get() != null) TouchService.get().reloadData();
        if (close) {
            Toast.makeText(this, "Guardado: " + target.name, Toast.LENGTH_SHORT).show();
            finish();
        }
        return true;
    }

    private void test() {
        String script = currentScript();
        if (script == null) return;
        TouchService svc = TouchService.get();
        if (svc == null) {
            Toast.makeText(this, "Primero activa el servicio en Accesibilidad", Toast.LENGTH_SHORT).show();
            return;
        }
        save(false);
        String name = nameEt.getText().toString().trim();
        svc.testSoon(name.isEmpty() ? "Prueba" : name, script, data.profileFor(Data.load(this).active()).id);
        moveTaskToBack(true);
    }

    private void copy() {
        String script = currentScript();
        if (script == null) return;
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("patrón", script));
        Toast.makeText(this, "Texto copiado: pégalo en el chat para ajustarlo", Toast.LENGTH_SHORT).show();
    }
}
