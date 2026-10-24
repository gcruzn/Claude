package cl.gcruz.cerebro;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes en el teléfono. La URL y la contraseña NUNCA van en el código (repo público). */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("cerebro", Context.MODE_PRIVATE);
    }

    String url() {
        String u = sp.getString("url", "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        return u;
    }

    String password() { return sp.getString("password", ""); }
    String token() { return sp.getString("token", ""); }
    boolean listen() { return sp.getBoolean("listen", true); }

    void save(String url, String password) {
        sp.edit().putString("url", url.trim()).putString("password", password)
                .remove("token").apply();
    }

    void setToken(String t) { sp.edit().putString("token", t).apply(); }
    void clearToken() { sp.edit().remove("token").apply(); }
    void setListen(boolean on) { sp.edit().putBoolean("listen", on).apply(); }

    String ultimoOido() { return sp.getString("oido", ""); }
    String ultimaRespuesta() { return sp.getString("respuesta", ""); }
    void guardarUltimo(String oido, String respuesta) {
        sp.edit().putString("oido", oido).putString("respuesta", respuesta).apply();
    }

    /** Segundos de silencio total para dar por terminada la orden (3 a 12). */
    int silencio() { return Math.max(3, Math.min(12, sp.getInt("silencio", 6))); }
    void setSilencio(int s) { sp.edit().putInt("silencio", s).apply(); }

    /** Voz de TextToSpeech elegida (nombre), o "" para la mejor disponible. */
    String voz() { return sp.getString("voz", ""); }
    void setVoz(String v) { sp.edit().putString("voz", v).apply(); }
    /** Velocidad y tono en porcentaje (50 a 150). */
    int velocidad() { return sp.getInt("velocidad", 100); }
    int tono() { return sp.getInt("tono", 100); }
    void setVelocidadTono(int v, int t) { sp.edit().putInt("velocidad", v).putInt("tono", t).apply(); }
}
