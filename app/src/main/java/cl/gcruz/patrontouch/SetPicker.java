package cl.gcruz.patrontouch;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Elegir set con dos listas desplegables: Brawler y Modo (filtrado por el brawler). Escala a
 * cientos de sets sin alargar la pantalla; se usa en el menú de la botonera y en la app.
 */
public class SetPicker {

    public interface Listener {
        void onPick(Data.PadSet set);
    }

    private static final int GRAFITO = Color.parseColor("#454A51");
    private static final int AZUL = Color.parseColor("#406AB4");

    /** Arma las dos filas; avisa al listener con el set elegido (también al mostrarse, con el actual). */
    public static LinearLayout build(Context c, Data data, Data.PadSet current, Listener listener) {
        Map<String, List<Data.PadSet>> byBrawler = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Data.PadSet s : data.sets) {
            String b = brawlerOf(s);
            if (!byBrawler.containsKey(b)) byBrawler.put(b, new ArrayList<>());
            byBrawler.get(b).add(s);
        }
        List<String> brawlers = new ArrayList<>(byBrawler.keySet());

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        Spinner brawlerSp = spinner(c);
        Spinner modeSp = spinner(c);
        box.addView(row(c, "Brawler", brawlerSp));
        box.addView(row(c, "Modo", modeSp));

        final List<Data.PadSet>[] modes = new List[]{new ArrayList<>()};
        brawlerSp.setAdapter(adapter(c, brawlers));
        brawlerSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                modes[0] = byBrawler.get(brawlers.get(pos));
                List<String> labels = new ArrayList<>();
                int sel = 0;
                for (int i = 0; i < modes[0].size(); i++) {
                    Data.PadSet s = modes[0].get(i);
                    labels.add((s.mode.isEmpty() ? s.name : s.mode) + "  ·  " + data.slotPatterns(s).size() + " patrones");
                    if (current != null && s.id.equals(current.id)) sel = i;
                }
                modeSp.setAdapter(adapter(c, labels));
                modeSp.setSelection(sel);
                listener.onPick(modes[0].get(sel));
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) { }
        });
        modeSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < modes[0].size()) listener.onPick(modes[0].get(pos));
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) { }
        });
        int start = current == null ? 0 : Math.max(0, brawlers.indexOf(brawlerOf(current)));
        brawlerSp.setSelection(start);
        return box;
    }

    /** Una fila «etiqueta + lista desplegable» suelta (p. ej. el filtro de la tarjeta Patrones). */
    public static LinearLayout dropdown(Context c, String label, List<String> items, int sel,
                                        java.util.function.IntConsumer onPick) {
        Spinner sp = spinner(c);
        sp.setAdapter(adapter(c, items));
        sp.setSelection(Math.max(0, Math.min(sel, items.size() - 1)));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { onPick.accept(pos); }

            @Override
            public void onNothingSelected(AdapterView<?> p) { }
        });
        return row(c, label, sp);
    }

    static String brawlerOf(Data.PadSet s) {
        return s.brawler.isEmpty() ? s.name : s.brawler;
    }

    private static Spinner spinner(Context c) {
        Spinner sp = new Spinner(c, Spinner.MODE_DROPDOWN);
        GradientDrawable pop = new GradientDrawable();
        pop.setColor(Color.WHITE);
        pop.setCornerRadius(dp(c, 10));
        sp.setPopupBackgroundDrawable(pop);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(236, 240, 247));
        bg.setCornerRadius(dp(c, 10));
        sp.setBackground(bg);
        return sp;
    }

    private static LinearLayout row(Context c, String label, Spinner sp) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(c, 4), 0, dp(c, 4));
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(GRAFITO);
        r.addView(t, new LinearLayout.LayoutParams(dp(c, 70), LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(sp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    /** Textos con color explícito: el tema del servicio de accesibilidad no garantiza contraste. */
    private static ArrayAdapter<String> adapter(Context c, List<String> items) {
        return new ArrayAdapter<String>(c, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                return style(super.getView(pos, convert, parent), false);
            }

            @Override
            public View getDropDownView(int pos, View convert, ViewGroup parent) {
                return style(super.getView(pos, convert, parent), true);
            }

            private View style(View v, boolean drop) {
                TextView t = (TextView) v;
                t.setTextColor(drop ? GRAFITO : AZUL);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                t.setMinHeight(0);
                t.setPadding(dp(c, 12), dp(c, drop ? 11 : 9), dp(c, 12), dp(c, drop ? 11 : 9));
                if (drop) t.setBackgroundColor(Color.WHITE);
                return t;
            }
        };
    }

    private static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
