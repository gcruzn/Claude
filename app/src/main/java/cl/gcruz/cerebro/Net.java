package cl.gcruz.cerebro;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Habla con el servidor Mi PC: /api/pair, /api/cerebro, /api/cerebro/tareas y /api/cerebro/voz. */
final class Net {
    private Net() {}

    static final class Result {
        final int status;
        final String body;
        Result(int s, String b) { status = s; body = b; }
    }

    /** Respuesta inmediata del servidor a un pedido. */
    static final class Respuesta {
        String texto;
        String tarea;        // id de tarea, o null
        String audioUrl;     // si el servidor tiene voz personalizada, audio de esta respuesta
        boolean vozRemota;   // el servidor ofrece /api/cerebro/voz para cualquier texto
    }

    static final class Tarea {
        String id, texto, estado, respuesta;
    }

    static Result request(String method, String url, String token, JSONObject json, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(timeoutMs);
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream o = c.getOutputStream()) { o.write(data); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Result(code, in == null ? "" : read(in));
        } finally {
            c.disconnect();
        }
    }

    static Result post(String url, String token, JSONObject json, int timeoutMs) throws IOException {
        return request("POST", url, token, json, timeoutMs);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static IOException legible(Exception e) {
        if (e instanceof SocketTimeoutException) return new IOException("El PC tardó demasiado en responder.");
        if (e instanceof UnknownHostException || e instanceof ConnectException)
            return new IOException("No llego al PC. Revisa que Tailscale esté conectado.");
        if (e instanceof JSONException) return new IOException("Respuesta rara del PC.");
        return e instanceof IOException ? (IOException) e : new IOException(String.valueOf(e.getMessage()));
    }

    /** Vincula el teléfono con la contraseña y guarda el token. Lanza IOException con un mensaje legible. */
    static void pair(Context ctx) throws IOException {
        Prefs p = new Prefs(ctx);
        if (p.url().isEmpty()) throw new IOException("Falta la dirección del PC en Ajustes.");
        try {
            JSONObject j = new JSONObject();
            j.put("password", p.password());
            j.put("device", "Jarvis " + Build.MODEL);
            Result r = post(p.url() + "/api/pair", null, j, 20000);
            if (r.status == 401) throw new IOException("La contraseña no es correcta.");
            if (r.status == 429) throw new IOException("Demasiados intentos. Espera unos minutos.");
            if (r.status != 200) throw new IOException("El PC respondió " + r.status + ".");
            p.setToken(new JSONObject(r.body).getString("token"));
        } catch (JSONException | SocketTimeoutException | UnknownHostException | ConnectException e) {
            throw legible(e);
        }
    }

    /** Llamada autenticada que se re-vincula sola si el token venció. */
    private static Result autenticada(Context ctx, String method, String path, JSONObject json, int timeout) throws IOException {
        Prefs p = new Prefs(ctx);
        if (p.token().isEmpty()) pair(ctx);
        Result r = request(method, p.url() + path, p.token(), json, timeout);
        if (r.status == 401) {
            p.clearToken();
            pair(ctx);
            r = request(method, p.url() + path, p.token(), json, timeout);
        }
        return r;
    }

    /** Manda el pedido. El servidor responde al instante. nueva = no enrutar a tareas en curso. */
    static Respuesta ask(Context ctx, String text, boolean nueva) throws IOException {
        try {
            JSONObject j = new JSONObject();
            j.put("texto", text);
            j.put("nueva", nueva);
            Result r = autenticada(ctx, "POST", "/api/cerebro", j, 60000);
            if (r.status != 200 && r.status != 429) throw new IOException("El PC respondió " + r.status + ".");
            JSONObject o = new JSONObject(r.body);
            Respuesta x = new Respuesta();
            x.texto = o.optString("respuesta", "Sin respuesta.");
            x.tarea = o.isNull("tarea") ? null : o.optString("tarea", null);
            x.audioUrl = o.isNull("audio_url") ? null : o.optString("audio_url", null);
            x.vozRemota = o.optBoolean("voz", false);
            return x;
        } catch (JSONException | SocketTimeoutException | UnknownHostException | ConnectException e) {
            throw legible(e);
        }
    }

    static List<Tarea> tareas(Context ctx) throws IOException {
        try {
            Result r = autenticada(ctx, "GET", "/api/cerebro/tareas", null, 15000);
            if (r.status != 200) throw new IOException("El PC respondió " + r.status + ".");
            JSONArray a = new JSONObject(r.body).getJSONArray("tareas");
            List<Tarea> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Tarea t = new Tarea();
                t.id = o.getString("id");
                t.texto = o.optString("texto", "");
                t.estado = o.optString("estado", "");
                t.respuesta = o.optString("respuesta", "");
                out.add(t);
            }
            return out;
        } catch (JSONException | SocketTimeoutException | UnknownHostException | ConnectException e) {
            throw legible(e);
        }
    }

    /**
     * Voz personalizada (apagada por defecto): baja el audio de un texto o de una audio_url del
     * servidor a un archivo de caché. Devuelve null si el servidor no la ofrece o falla; el que
     * llama cae entonces a TextToSpeech.
     */
    static File audio(Context ctx, String urlOTexto, boolean esUrl) {
        try {
            Prefs p = new Prefs(ctx);
            String url = esUrl ? (urlOTexto.startsWith("http") ? urlOTexto : p.url() + urlOTexto)
                    : p.url() + "/api/cerebro/voz?texto=" + URLEncoder.encode(urlOTexto, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Authorization", "Bearer " + p.token());
            try {
                if (c.getResponseCode() != 200) return null;
                File f = File.createTempFile("voz", ".audio", ctx.getCacheDir());
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                return f;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
