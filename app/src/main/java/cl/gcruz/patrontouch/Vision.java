package cl.gcruz.patrontouch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Lo que la app ve del juego en un cuadro (22-oct, pedido de Gonzalo: súper solo con enemigo al
 * alcance y disparos según la munición). Trabaja sobre el cuadro de la captura a media resolución
 * (RGBA). Validado primero en Python sobre videos de Terrenos (scratchpad vision/detect.py):
 *  - jugador: la etiqueta verde neón del nombre (H 58–70, S > 220, V > 230); la barra de vida y la
 *    munición van debajo.
 *  - munición: segmentos naranjas bajo la barra de vida; ~34 px por segmento a resolución completa
 *    (17 a media): 101 px con el cargador lleno = 3,0.
 *  - enemigos: barra de vida roja con borde oscuro debajo y el número blanco de vida encima (los
 *    ladrillos rojos del piso no tienen ninguna de las dos). Detecta ~2 de cada 3 barras: se pierden
 *    las diminutas (poca vida) y las tapadas por humo; por eso se recuerdan unos cuadros.
 */
final class Vision {

    static final class Frame {
        long at;
        float px = -1, py = -1;          // jugador (centro de la etiqueta), coordenadas de pantalla completa
        float ammo = -1;                 // segmentos llenos, 0–3 (−1 = no se vio)
        final List<float[]> enemies = new ArrayList<>();   // {x, y} en pantalla completa
    }

    private Vision() { }

    /**
     * Analiza un cuadro RGBA de w×h (media resolución, `scale` = 0,5) y devuelve coordenadas de
     * pantalla completa. `skipBottom` excluye la franja de botones (gadget verde, etc.).
     */
    static Frame analyze(ByteBuffer buf, int w, int h, int rowStride, int pixStride, float scale) {
        Frame f = new Frame();
        f.at = android.os.SystemClock.uptimeMillis();
        int yMax = (int) (h * 0.80f);
        // --- jugador: la franja verde neón MÁS ALTA es la etiqueta del nombre (el círculo verde bajo
        // los pies también es verde neón y, mezclado, corría la posición ~75 px hacia abajo).
        int[] rows = new int[h];
        for (int y = 20; y < yMax; y++) {
            int row = y * rowStride, c = 0;
            for (int x = 0; x < w; x += 2) {
                int o = row + x * pixStride;
                if (neon(buf.get(o) & 0xff, buf.get(o + 1) & 0xff, buf.get(o + 2) & 0xff)) c++;
            }
            rows[y] = c;
        }
        int top = -1;
        for (int y = 20; y < yMax; y++) if (rows[y] >= 5) { top = y; break; }
        if (top >= 0) {
            int bot = top;
            while (bot + 1 < yMax && rows[bot + 1] >= 2 && bot - top < 14) bot++;
            long n = 0, sx = 0, sy = 0;
            for (int y = top; y <= bot; y++) {
                int row = y * rowStride;
                for (int x = 0; x < w; x += 2) {
                    int o = row + x * pixStride;
                    if (neon(buf.get(o) & 0xff, buf.get(o + 1) & 0xff, buf.get(o + 2) & 0xff)) {
                        n++;
                        sx += x;
                        sy += y;
                    }
                }
            }
            if (n > 15) {
                f.px = sx / (float) n / scale;
                f.py = sy / (float) n / scale;
            }
        }
        // --- munición: columnas con naranja en la franja bajo la etiqueta
        if (f.px >= 0) {
            int cx = (int) (f.px * scale), cy = (int) (f.py * scale);
            int x0 = Math.max(0, cx - 55), x1 = Math.min(w - 1, cx + 55);
            int y0 = Math.max(0, cy + 12), y1 = Math.min(h - 1, cy + 45);
            int cols = 0;
            for (int x = x0; x <= x1; x++) {
                int c = 0;
                for (int y = y0; y <= y1; y++) {
                    int o = y * rowStride + x * pixStride;
                    int r = buf.get(o) & 0xff, g = buf.get(o + 1) & 0xff, b = buf.get(o + 2) & 0xff;
                    if (r > 200 && g > 90 && g < 190 && b < 110 && r - b > 120) c++;   // naranja
                }
                if (c >= 2) cols++;
            }
            f.ammo = Math.min(3f, cols / 17f);
        }
        // --- enemigos: tramos rojos horizontales agrupados en barras
        List<int[]> bars = new ArrayList<>();   // {x0, x1, y0, y1}
        for (int y = 20; y < yMax; y++) {
            int row = y * rowStride, run = -1;
            for (int x = 0; x <= w; x++) {
                boolean red = false;
                if (x < w) {
                    int o = row + x * pixStride;
                    int r = buf.get(o) & 0xff, g = buf.get(o + 1) & 0xff, b = buf.get(o + 2) & 0xff;
                    red = r > 170 && g < 70 && b < 80 && r - g > 130;
                }
                if (red && run < 0) run = x;
                else if (!red && run >= 0) {
                    int len = x - run;
                    if (len >= 4 && len <= 90) addRun(bars, run, x - 1, y);
                    run = -1;
                }
            }
        }
        for (int[] bar : bars) {
            int bw = bar[1] - bar[0] + 1, bh = bar[3] - bar[2] + 1;
            if (bh < 2 || bh > 10 || bw < 2 * bh) continue;
            if (!darkBelow(buf, w, h, rowStride, pixStride, bar) || !digitsAbove(buf, w, rowStride, pixStride, bar)) continue;
            float ex = (bar[0] + bar[1]) / 2f / scale, ey = (bar[2] + bar[3]) / 2f / scale;
            boolean dup = false;
            for (float[] e : f.enemies) if (Math.abs(e[0] - ex) < 70 && Math.abs(e[1] - ey) < 30) dup = true;
            if (!dup) f.enemies.add(new float[]{ex, ey});
        }
        return f;
    }

    private static boolean neon(int r, int g, int b) {
        return g > 225 && r < 120 && b < 120 && g - r > 140;   // verde neón de la etiqueta del jugador
    }

    /** Une un tramo rojo a la barra de la fila de arriba que se le superpone, o abre una nueva. */
    private static void addRun(List<int[]> bars, int x0, int x1, int y) {
        for (int[] b : bars) {
            if (b[3] >= y - 1 && x0 <= b[1] && x1 >= b[0]) {
                b[0] = Math.min(b[0], x0);
                b[1] = Math.max(b[1], x1);
                b[3] = y;
                return;
            }
        }
        bars.add(new int[]{x0, x1, y, y});
    }

    private static boolean darkBelow(ByteBuffer buf, int w, int h, int rs, int ps, int[] bar) {
        int y0 = bar[3] + 1, y1 = Math.min(h - 1, bar[3] + 2);
        if (y0 >= h) return false;
        long sum = 0, cnt = 0;
        for (int x = bar[0]; x <= bar[1]; x++) {
            int min = 255;
            for (int y = y0; y <= y1; y++) {
                int o = y * rs + x * ps;
                int v = Math.max(buf.get(o) & 0xff, Math.max(buf.get(o + 1) & 0xff, buf.get(o + 2) & 0xff));
                min = Math.min(min, v);
            }
            sum += min;
            cnt++;
        }
        return cnt > 0 && sum / cnt < 110;
    }

    private static boolean digitsAbove(ByteBuffer buf, int w, int rs, int ps, int[] bar) {
        int y0 = Math.max(0, bar[2] - 18), y1 = bar[2] - 1;
        int x0 = Math.max(0, bar[0] - 20), x1 = Math.min(w - 1, bar[1] + 20);
        int white = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int o = y * rs + x * ps;
                int r = buf.get(o) & 0xff, g = buf.get(o + 1) & 0xff, b = buf.get(o + 2) & 0xff;
                if (r > 225 && g > 225 && b > 225) white++;
            }
        }
        return white > 10;
    }
}
