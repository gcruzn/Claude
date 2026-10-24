package cl.gcruz.cerebro;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Bitácora de activaciones y rechazos de la palabra clave, para calibrar con datos reales. */
final class Registro {
    private static final int MAX = 300, QUEDAN = 200;
    private static final Object LOCK = new Object();
    private final File archivo;

    Registro(Context c) { archivo = new File(c.getFilesDir(), "activaciones.log"); }

    void anotar(String linea) {
        String hora = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date());
        synchronized (LOCK) {
            try {
                List<String> l = todas();
                l.add(hora + " | " + linea);
                if (l.size() > MAX) l = new ArrayList<>(l.subList(l.size() - QUEDAN, l.size()));
                StringBuilder sb = new StringBuilder();
                for (String s : l) sb.append(s).append("\n");
                try (FileOutputStream o = new FileOutputStream(archivo)) {
                    o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) { }
        }
    }

    private List<String> todas() {
        List<String> l = new ArrayList<>();
        try {
            if (!archivo.exists()) return l;
            byte[] b = new byte[(int) archivo.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(archivo)) { in.read(b); }
            for (String s : new String(b, StandardCharsets.UTF_8).split("\n")) if (!s.isEmpty()) l.add(s);
        } catch (Exception ignored) { }
        return l;
    }

    /** Las últimas n líneas, la más nueva primero. */
    String ultimas(int n) {
        synchronized (LOCK) {
            List<String> l = todas();
            StringBuilder sb = new StringBuilder();
            for (int i = l.size() - 1; i >= 0 && i >= l.size() - n; i--) sb.append(l.get(i)).append("\n\n");
            return sb.length() == 0 ? "Aún no hay activaciones." : sb.toString().trim();
        }
    }
}
