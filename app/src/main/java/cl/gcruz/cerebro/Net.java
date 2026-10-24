package cl.gcruz.cerebro;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/** Habla con el servidor Mi PC: /api/pair (contraseña -> token) y /api/cerebro. */
final class Net {
    private Net() {}

    static final class Result {
        final int status;
        final String body;
        Result(int s, String b) { status = s; body = b; }
    }

    static Result post(String url, String token, JSONObject json, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) { o.write(data); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Result(code, in == null ? "" : read(in));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** Vincula el teléfono con la contraseña y guarda el token. Lanza IOException con un mensaje legible. */
    static void pair(Context ctx) throws IOException {
        Prefs p = new Prefs(ctx);
        if (p.url().isEmpty()) throw new IOException("Falta la dirección del PC en Ajustes.");
        try {
            JSONObject j = new JSONObject();
            j.put("password", p.password());
            j.put("device", "Oye cerebro " + Build.MODEL);
            Result r = post(p.url() + "/api/pair", null, j, 20000);
            if (r.status == 401) throw new IOException("La contraseña no es correcta.");
            if (r.status == 429) throw new IOException("Demasiados intentos. Espera unos minutos.");
            if (r.status != 200) throw new IOException("El PC respondió " + r.status + ".");
            p.setToken(new JSONObject(r.body).getString("token"));
        } catch (JSONException e) {
            throw new IOException("Respuesta rara del PC.");
        } catch (SocketTimeoutException | UnknownHostException | ConnectException e) {
            throw new IOException("No llego al PC. Revisa que Tailscale esté conectado.");
        }
    }

    /** Manda el pedido y devuelve el texto para leer en voz alta. nueva = empezar conversación nueva. */
    static String ask(Context ctx, String text, boolean nueva) throws IOException {
        Prefs p = new Prefs(ctx);
        try {
            if (p.token().isEmpty()) pair(ctx);
            JSONObject j = new JSONObject();
            j.put("texto", text);
            j.put("nueva", nueva);
            Result r = post(p.url() + "/api/cerebro", p.token(), j, 130000);
            if (r.status == 401) {
                p.clearToken();
                pair(ctx);
                r = post(p.url() + "/api/cerebro", p.token(), j, 130000);
            }
            if (r.status == 200 || r.status == 429) {
                return new JSONObject(r.body).optString("respuesta", "Sin respuesta.");
            }
            throw new IOException("El PC respondió " + r.status + ".");
        } catch (JSONException e) {
            throw new IOException("Respuesta rara del PC.");
        } catch (SocketTimeoutException e) {
            throw new IOException("El PC tardó demasiado en responder.");
        } catch (UnknownHostException | ConnectException e) {
            throw new IOException("No llego al PC. Revisa que Tailscale esté conectado.");
        }
    }
}
