package cl.gcruz.cerebro;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Dirección del PC, contraseña, tiempos de escucha, voz y exención de batería. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private EditText url, clave;
    private TextView bateria, silencioTxt, velTxt, tonoTxt;
    private SeekBar silencio, vel, tono;
    private Spinner voces, sensib;
    private TextView vozInfo, umbralTxt, activaciones, errores;
    private SeekBar umbralVoz;
    private TextToSpeech tts;
    private boolean ttsListo;
    private final List<Voice> lista = new ArrayList<>();

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

    private interface Cambio { void al(int valor); }

    private SeekBar barra(int max, int valor, Cambio c) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(valor);
        s.setPadding(dp(8), dp(8), dp(8), dp(8));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean u) { c.al(p); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { guardarVoz(); }
        });
        return s;
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

        silencioTxt = etiqueta("");
        col.addView(silencioTxt);
        silencio = barra(9, prefs.silencio() - 3, p -> {
            prefs.setSilencio(p + 3);
            silencioTxt.setText("Espero " + (p + 3) + " segundos de silencio antes de enviar tu orden");
        });
        silencioTxt.setText("Espero " + prefs.silencio() + " segundos de silencio antes de enviar tu orden");
        col.addView(silencio);

        col.addView(etiqueta("Sensibilidad de «Jarvis»"));
        sensib = new Spinner(this);
        String[] niveles = {"Alta (se activa más fácil)", "Media", "Baja (más exigente, menos falsas)"};
        sensib.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, niveles) {
            @Override public View getView(int pos, View v, android.view.ViewGroup g) {
                TextView t = (TextView) super.getView(pos, v, g);
                t.setTextSize(18);
                return t;
            }
        });
        sensib.setSelection(prefs.sensibilidad());
        sensib.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { prefs.setSensibilidad(pos); }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        col.addView(sensib);

        vozInfo = etiqueta("");
        col.addView(vozInfo);
        Button ensenar = boton("Enseñarle mi voz", 0xFF406AB4);
        ensenar.setOnClickListener(v -> startActivity(new Intent(this, EnsenarActivity.class)));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-1, dp(72));
        lp3.setMargins(0, dp(6), 0, 0);
        col.addView(ensenar, lp3);
        Button borrarVoz = boton("Borrar mis muestras de voz", 0xFF969798);
        borrarVoz.setOnClickListener(v -> { new VozDueno(this).borrar(); onResume(); });
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(-1, dp(60));
        lp4.setMargins(0, dp(8), 0, 0);
        col.addView(borrarVoz, lp4);

        umbralTxt = etiqueta("");
        col.addView(umbralTxt);
        umbralVoz = barra(65, prefs.umbralVoz() - 30, p -> {
            prefs.setUmbralVoz(p + 30);
            umbralTxt.setText("Parecido mínimo con tu voz: " + (p + 30) + "%");
        });
        umbralTxt.setText("Parecido mínimo con tu voz: " + prefs.umbralVoz() + "%");
        col.addView(umbralVoz);

        col.addView(etiqueta("Voz de lectura"));
        voces = new Spinner(this);
        col.addView(voces);
        velTxt = etiqueta("");
        col.addView(velTxt);
        vel = barra(100, prefs.velocidad() - 50, p -> velTxt.setText("Velocidad: " + (p + 50) + "%"));
        velTxt.setText("Velocidad: " + prefs.velocidad() + "%");
        col.addView(vel);
        tonoTxt = etiqueta("");
        col.addView(tonoTxt);
        tono = barra(100, prefs.tono() - 50, p -> tonoTxt.setText("Tono: " + (p + 50) + "%"));
        tonoTxt.setText("Tono: " + prefs.tono() + "%");
        col.addView(tono);
        Button probar = boton("Probar la voz", 0xFF406AB4);
        probar.setOnClickListener(v -> probar());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(72));
        lp2.setMargins(0, dp(10), 0, 0);
        col.addView(probar, lp2);

        col.addView(etiqueta("Últimas activaciones"));
        activaciones = new TextView(this);
        activaciones.setTextSize(15);
        activaciones.setTextColor(0xFF454A51);
        activaciones.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(activaciones);

        col.addView(etiqueta("Últimos errores de escucha"));
        errores = new TextView(this);
        errores.setTextSize(15);
        errores.setTextColor(0xFF454A51);
        errores.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(errores);

        bateria = etiqueta("");
        col.addView(bateria);
        Button bat = boton("Permitir en segundo plano", 0xFF73C96A);
        bat.setOnClickListener(v -> pedirBateria());
        col.addView(bat, new LinearLayout.LayoutParams(-1, dp(72)));

        setContentView(sv);

        tts = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) return;
            ttsListo = true;
            runOnUiThread(this::llenarVoces);
        });
    }

    private void llenarVoces() {
        lista.clear();
        lista.addAll(Voces.espanolas(tts));
        List<String> nombres = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < lista.size(); i++) {
            nombres.add(Voces.etiqueta(lista.get(i)));
            if (lista.get(i).getName().equals(prefs.voz())) sel = i;
        }
        if (nombres.isEmpty()) nombres.add("No hay voces en español instaladas");
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, nombres) {
            @Override public View getView(int pos, View v, android.view.ViewGroup g) {
                TextView t = (TextView) super.getView(pos, v, g);
                t.setTextSize(18);
                return t;
            }
            @Override public View getDropDownView(int pos, View v, android.view.ViewGroup g) {
                TextView t = (TextView) super.getDropDownView(pos, v, g);
                t.setTextSize(18);
                t.setPadding(dp(12), dp(14), dp(12), dp(14));
                return t;
            }
        };
        voces.setAdapter(ad);
        voces.setSelection(sel);
        voces.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { guardarVoz(); }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
    }

    private void guardarVoz() {
        prefs.setVelocidadTono(vel.getProgress() + 50, tono.getProgress() + 50);
        int pos = voces.getSelectedItemPosition();
        if (pos >= 0 && pos < lista.size()) prefs.setVoz(lista.get(pos).getName());
    }

    private void probar() {
        if (!ttsListo) return;
        guardarVoz();
        Voces.aplicar(tts, prefs);
        tts.speak("Hola Gonzalo. Soy tu cerebro. Ya, lo reviso y te aviso apenas termine.",
                TextToSpeech.QUEUE_FLUSH, null, "prueba");
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        int n = new VozDueno(this).cantidad();
        vozInfo.setText(n == 0 ? "Sin voz enseñada: «Jarvis» responde a cualquier voz."
                : "Tengo " + n + " muestras de tu voz. Solo respondo a una voz parecida.");
        activaciones.setText(new Registro(this).ultimas(15));
        errores.setText(new Registro(this, "errores-escucha.log").ultimas(10));
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
