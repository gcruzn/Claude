package cl.gcruz.cerebro;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/** Pantalla principal: tema claro, letra grande y un botón enorme para hablar. */
public class MainActivity extends Activity {
    private static final int PERMISOS = 7;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView estado, oido, respuesta, tareas;
    private Switch escucha;
    private boolean armando;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (CerebroService.respuesta.isEmpty() && CerebroService.oido.isEmpty()) {
            CerebroService.oido = prefs.ultimoOido();
            CerebroService.respuesta = prefs.ultimaRespuesta();
        }
        construir();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView texto(String t, int sp, int color, boolean negrita) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (negrita) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private Button boton(String t, int fondo, int sp) {
        Button x = new Button(this);
        x.setText(t);
        x.setTextSize(sp);
        x.setAllCaps(false);
        x.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fondo);
        g.setCornerRadius(dp(18));
        x.setBackground(g);
        return x;
    }

    private void construir() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(24));
        sv.addView(col);

        col.addView(texto("Jarvis", 30, 0xFF454A51, true));
        estado = texto("", 22, 0xFF406AB4, true);
        col.addView(estado);

        Button hablar = boton("HABLAR", 0xFF406AB4, 40);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(220));
        lp.setMargins(0, dp(12), 0, dp(16));
        hablar.setLayoutParams(lp);
        hablar.setOnClickListener(v -> hablar());
        col.addView(hablar);

        col.addView(texto("Lo que entendí (en vivo)", 18, 0xFF969798, false));
        oido = texto("", 26, 0xFF454A51, false);
        col.addView(oido);
        col.addView(texto("Respuesta", 18, 0xFF969798, false));
        respuesta = texto("", 30, 0xFF454A51, true);
        col.addView(respuesta);

        col.addView(texto("Tareas", 18, 0xFF969798, false));
        tareas = texto("", 22, 0xFF454A51, false);
        col.addView(tareas);

        escucha = new Switch(this);
        escucha.setText("Escuchar «Jarvis»");
        escucha.setTextSize(22);
        escucha.setTextColor(0xFF454A51);
        escucha.setPadding(0, dp(18), 0, dp(18));
        escucha.setChecked(prefs.listen());
        escucha.setOnCheckedChangeListener((v, on) -> {
            if (armando) return;
            prefs.setListen(on);
            if (on) arrancar(); else stopService(new Intent(this, CerebroService.class));
        });
        col.addView(escucha);

        Button nueva = boton("Nueva conversación", 0xFF73C96A, 22);
        nueva.setOnClickListener(v -> {
            CerebroService.nueva = true;
            CerebroService.oido = "";
            CerebroService.respuesta = "";
            refrescar();
            Toast.makeText(this, "Empiezo una conversación nueva", Toast.LENGTH_SHORT).show();
        });
        col.addView(nueva, new LinearLayout.LayoutParams(-1, dp(72)));

        Button ajustes = boton("Ajustes", 0xFF969798, 22);
        ajustes.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-1, dp(72));
        l2.setMargins(0, dp(12), 0, 0);
        col.addView(ajustes, l2);

        setContentView(sv);
    }

    private void refrescar() {
        h.post(() -> {
            estado.setText(CerebroService.estado);
            oido.setText(CerebroService.oido);
            respuesta.setText(CerebroService.respuesta);
            tareas.setText(CerebroService.tareasTexto.isEmpty() ? "Sin tareas" : CerebroService.tareasTexto);
            armando = true;
            escucha.setChecked(prefs.listen());
            armando = false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        CerebroService.ui = this::refrescar;
        refrescar();
        if (CerebroService.vivo) startService(new Intent(this, CerebroService.class).setAction(CerebroService.ACTION_REFRESH));
        if (permisosFaltan().isEmpty() && prefs.listen() && !CerebroService.vivo
                && !prefs.url().isEmpty()) {
            arrancar();
        }
    }

    @Override
    protected void onPause() {
        CerebroService.ui = null;
        super.onPause();
    }

    private ArrayList<String> permisosFaltan() {
        ArrayList<String> f = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            f.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            f.add(Manifest.permission.POST_NOTIFICATIONS);
        return f;
    }

    private boolean listoParaUsar() {
        if (prefs.url().isEmpty()) {
            Toast.makeText(this, "Primero escribe la dirección del PC en Ajustes", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return false;
        }
        ArrayList<String> f = permisosFaltan();
        if (!f.isEmpty()) {
            requestPermissions(f.toArray(new String[0]), PERMISOS);
            return false;
        }
        return true;
    }

    private void arrancar() {
        if (!listoParaUsar()) return;
        startForegroundService(new Intent(this, CerebroService.class));
    }

    private void hablar() {
        if (!listoParaUsar()) return;
        startForegroundService(new Intent(this, CerebroService.class).setAction(CerebroService.ACTION_ASK));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code != PERMISOS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (prefs.listen()) arrancar();
        } else {
            Toast.makeText(this, "Sin permiso de micrófono no puedo escucharte", Toast.LENGTH_LONG).show();
        }
    }
}
