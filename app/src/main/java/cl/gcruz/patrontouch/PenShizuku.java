package cl.gcruz.patrontouch;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;

/**
 * Lectura del S Pen con Shizuku (22-oct, opción 2 de Gonzalo). Shizuku corre en el mismo celular y
 * da los permisos de ADB sin PC; con ellos se lee «getevent -l» del lápiz (sec_e-pen), igual que el
 * puente por ADB de v4.7 pero sin computador. La pantalla no sirvió para esto: la perilla del
 * joystick «parpadeaba» para el detector y el rumbo salía para cualquier lado (v5.8–v6.0).
 *
 * Shizuku hay que iniciarlo una vez tras cada reinicio del celular (desde la propia app Shizuku,
 * con la Depuración inalámbrica); Patrón Touch pide su permiso una sola vez.
 */
final class PenShizuku {

    static final int REQ = 7;

    enum State { NOT_RUNNING, NO_PERMISSION, READY }

    private static volatile boolean active;
    private static volatile Process proc;

    private PenShizuku() { }

    /** ¿Se están leyendo los eventos del lápiz ahora mismo? */
    static boolean active() { return active; }

    static State state() {
        try {
            if (!Shizuku.pingBinder()) return State.NOT_RUNNING;
            if (Shizuku.isPreV11()) return State.NO_PERMISSION;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? State.READY : State.NO_PERMISSION;
        } catch (Throwable t) {
            return State.NOT_RUNNING;
        }
    }

    static void requestPermission() {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(REQ);
        } catch (Throwable t) {
            android.util.Log.w(TouchService.TAG, "Shizuku: " + t);
        }
    }

    /**
     * Mantiene corriendo «getevent -l» del lápiz y entrega cada línea a `onLine` (en un hilo propio).
     * Si Shizuku no está listo o el proceso termina, reintenta cada 3 s.
     */
    static void start(Consumer<String> onLine) {
        Thread th = new Thread(() -> {
            while (true) {
                if (state() == State.READY) {
                    try {
                        proc = newProcess(new String[]{"sh", "-c",
                                "exec getevent -l $(getevent -pl | grep -B1 '\"sec_e-pen\"' | head -1 | awk '{print $4}')"});
                        active = true;
                        android.util.Log.i(TouchService.TAG, "lápiz por Shizuku: leyendo");
                        BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                        String line;
                        while ((line = in.readLine()) != null) onLine.accept(line);
                    } catch (Throwable t) {
                        android.util.Log.w(TouchService.TAG, "lápiz por Shizuku: " + t);
                    }
                    active = false;
                    android.util.Log.i(TouchService.TAG, "lápiz por Shizuku: detenido");
                }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "lapiz-shizuku");
        th.setDaemon(true);
        th.start();
    }

    static void stop() {
        Process p = proc;
        if (p != null) p.destroy();
    }

    /** Shizuku.newProcess es privado desde la API 12 (recomiendan UserService); basta para un getevent. */
    private static Process newProcess(String[] cmd) throws Exception {
        java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, null, null);
    }
}
