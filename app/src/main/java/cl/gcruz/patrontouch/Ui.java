package cl.gcruz.patrontouch;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Utilidades de interfaz compartidas por las pantallas de la app (tema claro, paleta propia). */
public class Ui {

    public static final int CORAL = Color.parseColor("#E25E6B");
    public static final int AZUL = Color.parseColor("#406AB4");
    public static final int VERDE = Color.parseColor("#73C96A");
    public static final int GRAFITO = Color.parseColor("#454A51");
    public static final int GRIS = Color.parseColor("#969798");
    public static final int FONDO = Color.parseColor("#F6F7F9");
    public static final int SUAVE = Color.parseColor("#EEF1F6");

    public interface Pick {
        void on(int index);
    }

    public interface Text {
        void on(String value);
    }

    private final Activity a;

    public Ui(Activity a) { this.a = a; }

    public int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    public LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
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

    public TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    public Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(color == SUAVE ? GRAFITO : Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** Fila de botones que se reparten el ancho. */
    public LinearLayout row(Button... buttons) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.topMargin = dp(8);
            if (i > 0) lp.leftMargin = dp(6);
            buttons[i].setLayoutParams(lp);
            r.addView(buttons[i]);
        }
        return r;
    }

    public TextView link(String s, int color, View.OnClickListener l) {
        TextView t = text(s, 13, color, true);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    /** Selector tipo "Etiqueta: valor ▾". */
    public TextView selector(String label, String value, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(label + ":  " + value + "  ▾");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(GRAFITO);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setColor(SUAVE);
        d.setCornerRadius(dp(10));
        t.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    public View space(int h) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    public void choose(String title, String[] items, Pick pick) {
        new AlertDialog.Builder(a).setTitle(title).setItems(items, (d, w) -> pick.on(w))
                .setNegativeButton("Cancelar", null).show();
    }

    public void ask(String title, String initial, boolean number, Text done) {
        EditText et = new EditText(a);
        et.setText(initial);
        if (number) et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(a);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a).setTitle(title).setView(box)
                .setPositiveButton("Aceptar", (d, w) -> done.on(et.getText().toString().trim()))
                .setNegativeButton("Cancelar", null).show();
    }

    public void confirm(String msg, String yes, Runnable r) {
        new AlertDialog.Builder(a).setMessage(msg).setPositiveButton(yes, (d, w) -> r.run())
                .setNegativeButton("Cancelar", null).show();
    }

    /** Lista de direcciones para selectores: opcionalmente con "hacia donde me muevo". */
    public static String[] dirChoices(boolean withMove, boolean withAuto) {
        java.util.List<String> l = new java.util.ArrayList<>();
        if (withAuto) l.add("Auto (toque, apunta solo)");
        if (withMove) l.add("Hacia donde me muevo");
        for (String d : Script.DIRS) l.add(Script.dirIcon(d) + "  " + Script.dirLabel(d));
        return l.toArray(new String[0]);
    }

    public static String dirFromChoice(int idx, boolean withMove, boolean withAuto) {
        if (withAuto) {
            if (idx == 0) return "auto";
            idx--;
        }
        if (withMove) {
            if (idx == 0) return "move";
            idx--;
        }
        return Script.DIRS[Math.max(0, Math.min(Script.DIRS.length - 1, idx))];
    }

    public static double parseNum(String s, double def) {
        try { return Double.parseDouble(s.replace(',', '.').trim()); } catch (Exception e) { return def; }
    }

    public static EditText field(Activity a, String value, boolean number) {
        EditText et = new EditText(a);
        et.setText(value);
        if (number) et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setGravity(Gravity.CENTER);
        et.setEms(4);
        return et;
    }
}
