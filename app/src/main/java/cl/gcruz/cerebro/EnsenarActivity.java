package cl.gcruz.cerebro;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Enseñarle mi voz»: pide decir «oye cerebro» 6 veces, avanza sola (sin tocar entre
 * repeticiones) y AGREGA las muestras a las que ya había. Las toma el servicio con el micrófono
 * que ya tiene abierto para la palabra clave.
 */
public class EnsenarActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView grande, cuenta, aviso;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.WHITE);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView t = new TextView(this);
        t.setText("Enseñarle mi voz");
        t.setTextSize(28);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF454A51);
        col.addView(t);

        grande = new TextView(this);
        grande.setTextSize(40);
        grande.setTypeface(Typeface.DEFAULT_BOLD);
        grande.setTextColor(0xFF406AB4);
        grande.setGravity(Gravity.CENTER);
        grande.setPadding(0, dp(40), 0, dp(20));
        col.addView(grande);

        cuenta = new TextView(this);
        cuenta.setTextSize(64);
        cuenta.setTextColor(0xFF454A51);
        cuenta.setGravity(Gravity.CENTER);
        col.addView(cuenta);

        aviso = new TextView(this);
        aviso.setTextSize(20);
        aviso.setTextColor(0xFF969798);
        aviso.setGravity(Gravity.CENTER);
        aviso.setPadding(0, dp(30), 0, dp(20));
        aviso.setText("Dilo con tu voz de siempre, una vez tras otra. Se agrega a lo que ya enseñaste.");
        col.addView(aviso);

        Button fin = new Button(this);
        fin.setText("Terminar");
        fin.setTextSize(24);
        fin.setAllCaps(false);
        fin.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF969798);
        g.setCornerRadius(dp(18));
        fin.setBackground(g);
        fin.setOnClickListener(v -> finish());
        col.addView(fin, new LinearLayout.LayoutParams(-1, dp(80)));
        setContentView(col);
    }

    private void refrescar() {
        h.post(() -> {
            grande.setText(CerebroService.ensenarMsg);
            cuenta.setText(CerebroService.ensenarHechas + " de " + CerebroService.MUESTRAS);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            grande.setText("Primero dame permiso de micrófono desde la pantalla principal.");
            return;
        }
        CerebroService.ui = this::refrescar;
        CerebroService.ensenarMsg = "Preparando…";
        CerebroService.ensenarHechas = 0;
        refrescar();
        startForegroundService(new Intent(this, CerebroService.class).setAction(CerebroService.ACTION_ENSENAR));
    }

    @Override
    protected void onPause() {
        CerebroService.ui = null;
        if (CerebroService.modoEnsenar) {
            startService(new Intent(this, CerebroService.class).setAction(CerebroService.ACTION_ENSENAR_FIN));
        }
        super.onPause();
    }
}
