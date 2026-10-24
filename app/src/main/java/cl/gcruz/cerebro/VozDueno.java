package cl.gcruz.cerebro;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Huellas de voz (x-vectors de Vosk, 128 números) de las veces que Gonzalo dijo la palabra clave.
 * El archivo lleva la palabra clave en el nombre: al cambiarla, las huellas de la anterior no
 * sirven (otra frase, otra huella) y se parte sin voz enseñada.
 * Se AGREGAN muestras sin borrar las anteriores (su voz cambia entre días) y se compara contra
 * la más parecida, no contra el promedio.
 */
final class VozDueno {
    private final File archivo;
    private final List<double[]> muestras = new ArrayList<>();

    VozDueno(Context c) {
        archivo = new File(c.getFilesDir(), "voz-" + CerebroService.KEYWORD + ".json");
        cargar();
    }

    private synchronized void cargar() {
        muestras.clear();
        try {
            if (!archivo.exists()) return;
            byte[] b = new byte[(int) archivo.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(archivo)) { in.read(b); }
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONArray v = a.getJSONArray(i);
                double[] d = new double[v.length()];
                for (int k = 0; k < d.length; k++) d[k] = v.getDouble(k);
                muestras.add(d);
            }
        } catch (Exception ignored) { }
    }

    synchronized int cantidad() { return muestras.size(); }

    /** Relee el archivo (Ajustes puede haber borrado o agregado muestras) y devuelve cuántas hay. */
    synchronized int recargar() { cargar(); return muestras.size(); }

    synchronized void agregar(List<double[]> nuevas) throws IOException {
        muestras.addAll(nuevas);
        guardar();
    }

    synchronized void borrar() {
        muestras.clear();
        archivo.delete();
    }

    private void guardar() throws IOException {
        try {
            JSONArray a = new JSONArray();
            for (double[] d : muestras) {
                JSONArray v = new JSONArray();
                for (double x : d) v.put(x);
                a.put(v);
            }
            try (FileOutputStream o = new FileOutputStream(archivo)) {
                o.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
    }

    /** Mayor similitud coseno contra las muestras guardadas (-1 si no hay). */
    synchronized double maxSimilitud(double[] v) {
        double mejor = -1;
        for (double[] m : muestras) mejor = Math.max(mejor, coseno(m, v));
        return mejor;
    }

    static double coseno(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < n; i++) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i]; }
        return aa == 0 || bb == 0 ? 0 : ab / (Math.sqrt(aa) * Math.sqrt(bb));
    }
}
