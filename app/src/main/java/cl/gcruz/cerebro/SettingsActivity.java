package cl.gcruz.cerebro;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Dirección del PC, contraseña y exención de batería. Nada de esto vive en el código. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private EditText url, clave;
    private TextView bateria;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView etiqueta(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(20);
        v.setTextColor(0xFF454A51);
        v.setPadding(0, dp(14), 0, dp(4));
        return v;
    }

    private Button boton(String t, int fondo) {
        Button x = new Button(this);
        x.setText(t);
        x.setTextSize(22);
        x.setAllCaps(false);
        x.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fondo);
        g.setCornerRadius(dp(18));
        x.setBackground(g);
        return x;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(24));
        sv.addView(col);

        TextView titulo = etiqueta("Ajustes");
        titulo.setTextSize(30);
        titulo.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(titulo);

        col.addView(etiqueta("Dirección del PC"));
        url = new EditText(this);
        url.setTextSize(20);
        url.setHint("https://mi-pc.mi-red.ts.net");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.url());
        col.addView(url);

        col.addView(etiqueta("Contraseña de Mi PC"));
        clave = new EditText(this);
        clave.setTextSize(20);
        clave.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        clave.setText(prefs.password());
        col.addView(clave);

        Button guardar = boton("Guardar y probar", 0xFF406AB4);
        guardar.setOnClickListener(v -> guardarYProbar());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(72));
        lp.setMargins(0, dp(18), 0, 0);
        col.addView(guardar, lp);

        bateria = etiqueta("");
        col.addView(bateria);
        Button bat = boton("Permitir en segundo plano", 0xFF73C96A);
        bat.setOnClickListener(v -> pedirBateria());
        col.addView(bat, new LinearLayout.LayoutParams(-1, dp(72)));

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PowerManager pm = getSystemService(PowerManager.class);
        boolean libre = pm.isIgnoringBatteryOptimizations(getPackageName());
        bateria.setText(libre ? "Batería: sin restricciones (bien)"
                : "Batería: Samsung puede apagar el servicio. Permítelo aquí.");
    }

    private void guardarYProbar() {
        prefs.save(url.getText().toString(), clave.getText().toString());
        Toast.makeText(this, "Probando conexión…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                Net.pair(this);
                msg = "Conectado al PC";
            } catch (Exception e) {
                msg = e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void pedirBateria() {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
