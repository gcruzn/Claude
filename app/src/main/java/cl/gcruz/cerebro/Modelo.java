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
 * Modelo chico de Vosk en español (Apache-2.0, ~40 MB). Se baja UNA vez de alphacephei.com y
 * queda en el teléfono; después todo el reconocimiento de la palabra clave es sin red.
 */
final class Modelo {
    private Modelo() {}

    static final String URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";

    interface Progreso { void avance(String texto); }

    /** Devuelve la carpeta del modelo, bajándolo y descomprimiéndolo si falta. */
    static File asegurar(Context ctx, Progreso pr) throws IOException {
        File base = new File(ctx.getFilesDir(), "modelo");
        File listo = new File(base, "LISTO");
        if (listo.exists()) {
            File dir = carpeta(base);
            if (dir != null) return dir;
        }
        borrar(base);
        base.mkdirs();
        File zip = new File(ctx.getFilesDir(), "modelo.zip");
        pr.avance("Bajando la voz (una sola vez)…");
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
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
                    pr.avance("Bajando la voz… " + pct + "%");
                }
            }
        } finally {
            c.disconnect();
        }
        pr.avance("Preparando la voz…");
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
        File dir = carpeta(base);
        if (dir == null) throw new IOException("El modelo bajado no tiene el formato esperado.");
        new FileOutputStream(listo).close();
        return dir;
    }

    /** La carpeta del modelo es la que contiene "am". */
    private static File carpeta(File base) {
        if (new File(base, "am").isDirectory()) return base;
        File[] hijos = base.listFiles();
        if (hijos == null) return null;
        for (File h : hijos) {
            if (h.isDirectory() && new File(h, "am").isDirectory()) return h;
        }
        return null;
    }

    private static void borrar(File f) {
        File[] hijos = f.listFiles();
        if (hijos != null) for (File h : hijos) borrar(h);
        f.delete();
    }
}
