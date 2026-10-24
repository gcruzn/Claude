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
    String ultimoOido() { return sp.getString("oido", ""); }
    String ultimaRespuesta() { return sp.getString("respuesta", ""); }
    void guardarUltimo(String oido, String respuesta) {
        sp.edit().putString("oido", oido).putString("respuesta", respuesta).apply();
    }

    void setListen(boolean on) { sp.edit().putBoolean("listen", on).apply(); }
}
