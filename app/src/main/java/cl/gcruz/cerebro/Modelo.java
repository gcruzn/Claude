package cl.gcruz.cerebro;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Modelos de Vosk (Apache-2.0). Se bajan UNA vez de alphacephei.com y quedan en el teléfono;
 * después todo el reconocimiento es sin red.
 * - Español chico (~40 MB): reconoce la palabra clave.
 * - Hablante spk (~13 MB): saca la huella de voz para aceptar solo la voz de Gonzalo.
 */
final class Modelo {
    private Modelo() {}

    static final String ES_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";
    static final String SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip";

    interface Progreso { void avance(String texto); }

    static File espanol(Context ctx, Progreso pr) throws IOException {
        return asegurar(ctx, "modelo", ES_URL, "am", "la voz", pr);
    }

    static File hablante(Context ctx, Progreso pr) throws IOException {
        return asegurar(ctx, "modelo-spk", SPK_URL, "mean.vec", "el reconocedor de tu voz", pr);
    }

    /** Devuelve la carpeta del modelo, bajándolo y descomprimiéndolo si falta. */
    static File asegurar(Context ctx, String carpeta, String url, String marca, String nombre,
                         Progreso pr) throws IOException {
        File base = new File(ctx.getFilesDir(), carpeta);
        File listo = new File(base, "LISTO");
        if (listo.exists()) {
            File dir = buscar(base, marca);
            if (dir != null) return dir;
        }
        borrar(base);
        base.mkdirs();
        File zip = new File(ctx.getFilesDir(), carpeta + ".zip");
        pr.avance("Bajando " + nombre + " (una sola vez)…");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
            byte[] buf = new byte[65536];
            long hecho = 0;
            int n, ultimo = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                hecho += n;
                int pct = total > 0 ? (int) (hecho * 100 / total) : 0;
                if (pct != ultimo && pct % 5 == 0) {
                    ultimo = pct;
                    pr.avance("Bajando " + nombre + "… " + pct + "%");
                }
            }
        } finally {
            c.disconnect();
        }
        pr.avance("Preparando " + nombre + "…");
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            String raiz = base.getCanonicalPath() + File.separator;
            while ((e = zin.getNextEntry()) != null) {
                File f = new File(base, e.getName());
                if (!f.getCanonicalPath().startsWith(raiz)) continue;
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
        zip.delete();
        File dir = buscar(base, marca);
        if (dir == null) throw new IOException("El modelo bajado no tiene el formato esperado.");
        new FileOutputStream(listo).close();
        return dir;
    }

    /** La carpeta del modelo es la que contiene el archivo o carpeta "marca". */
    private static File buscar(File base, String marca) {
        if (new File(base, marca).exists()) return base;
        File[] hijos = base.listFiles();
        if (hijos == null) return null;
        for (File h : hijos) {
            if (h.isDirectory() && new File(h, marca).exists()) return h;
        }
        return null;
    }

    private static void borrar(File f) {
        File[] hijos = f.listFiles();
        if (hijos != null) for (File h : hijos) borrar(h);
        f.delete();
    }
}
