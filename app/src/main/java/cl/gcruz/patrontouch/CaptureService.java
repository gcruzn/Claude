package cl.gcruz.patrontouch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 21-oct, opción 1 de Gonzalo: leer la dirección del joystick MIRANDO LA PANTALLA, sin ADB ni
 * puentes. Con el permiso de captura de pantalla se mira solo el entorno del joystick calibrado.
 * Cuando Gonzalo toca, el juego dibuja la perilla como un disco azul sólido (medido en video:
 * 6.000–12.700 px azules a resolución completa con la perilla apretada, 100–960 sin tocar). La
 * perilla aparece donde apoya el dedo: ese primer cuadro es la base, y la dirección es hacia
 * donde se desplaza la perilla desde ahí. Se guarda en Script.steerDir («mover ultima/dirigido»).
 *
 * Mientras el patrón corre la perilla que se ve es la del joystick inyectado (el juego ignora el
 * dedo real), así que entonces no se lee nada: la dirección es la última que movió Gonzalo con el
 * patrón apagado.
 */
public class CaptureService extends Service {

    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    private static final String CHANNEL = "captura";
    private static final float SCALE = 0.5f;          // se captura a media resolución
    private static final long FRAME_MS = 33;          // ~30 cuadros por segundo
    private static final int PRESSED_PX = 700;        // a media resolución (≈ 2.800 a resolución completa)
    private static final float MOVE_PX = 16;          // desplazamiento mínimo (media res.) para tomar dirección

    private static volatile boolean running;

    public static boolean isRunning() { return running; }

    /** Último instante (uptime) en que se vio la perilla apretada, sea la del patrón o la de Gonzalo. */
    private static volatile long knobSeenAt;

    /** Último análisis del cuadro completo (jugador, munición, enemigos), cada VISION_MS. */
    static volatile Vision.Frame vision;
    static volatile long enemiesSeenAt;
    private static final long VISION_MS = 100;
    private long lastVision, lastVisionLog;

    /**
     * ¿El juego está mostrando la perilla? Con el patrón corriendo es la del joystick inyectado: si
     * no se ve, Android lo está bloqueando (S Pen cerca) y el reproductor pasa a tramos cortos para
     * retomar apenas se aleje el lápiz (22-oct).
     */
    public static boolean knobVisible(long withinMs) {
        return running && SystemClock.uptimeMillis() - knobSeenAt <= withinMs;
    }

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private int w, h;
    private long lastFrame;
    private float baseX = -1, baseY = -1;
    private boolean pressed;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent == null || projection != null) return START_NOT_STICKY;
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class) : intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            android.util.Log.w(TouchService.TAG, "captura: " + e);
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        thread = new HandlerThread("captura-joystick");
        thread.start();
        Handler bg = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, bg);
        // El juego va en horizontal: la pantalla virtual se arma apaisada, a media resolución.
        Point real = new Point();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(real);
        w = Math.round(Math.max(real.x, real.y) * SCALE);
        h = Math.round(Math.min(real.x, real.y) * SCALE);
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onFrame, bg);
        display = projection.createVirtualDisplay("patron-touch-joystick", w, h,
                getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, bg);
        running = true;
        android.util.Log.i(TouchService.TAG, "captura del joystick iniciada " + w + "x" + h);
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Lectura del joystick", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = (Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Patrón Touch mira tu joystick")
                .setContentText("Guarda hacia dónde te mueves para «última dirección»")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < FRAME_MS) return;
            lastFrame = now;
            analyze(img);
            if (now - lastVision >= VISION_MS) {
                lastVision = now;
                Image.Plane pl = img.getPlanes()[0];
                Vision.Frame vf = Vision.analyze(pl.getBuffer(), w, h, pl.getRowStride(), pl.getPixelStride(), SCALE);
                TouchService ts = TouchService.get();
                float[] sb = ts != null ? ts.calib().get("super") : null;
                if (sb != null) vf.superYellow = Vision.superYellow(pl.getBuffer(), w, h, pl.getRowStride(), pl.getPixelStride(), SCALE, sb[0], sb[1]);
                vision = vf;
                if (!vf.enemies.isEmpty()) enemiesSeenAt = now;
                if (now - lastVisionLog >= 1000) {
                    lastVisionLog = now;
                    StringBuilder b = new StringBuilder("visión: jugador ");
                    b.append(vf.px < 0 ? "—" : Math.round(vf.px) + "," + Math.round(vf.py));
                    b.append(" · balas ").append(vf.ammo < 0 ? "—" : String.format(java.util.Locale.ROOT, "%.1f", vf.ammo));
                    b.append(" · súper ").append(vf.superYellow < 0 ? "—" : vf.superYellow > 0.2f ? "cargado" : "no");
                    b.append(" · enemigos ").append(vf.enemies.size());
                    for (float[] e : vf.enemies) {
                        if (vf.px >= 0) b.append(" d=").append(Math.round(Math.hypot(e[0] - vf.px, e[1] - vf.py)));
                    }
                    b.append(" (").append(SystemClock.uptimeMillis() - now).append(" ms)");
                    android.util.Log.i(TouchService.TAG, b.toString());
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TouchService.TAG, "captura: " + e);
        } finally {
            if (img != null) img.close();
        }
    }

    private void analyze(Image img) {
        TouchService svc = TouchService.get();
        if (svc == null) return;
        boolean injecting = svc.isInjecting();   // entonces la perilla que se ve es la nuestra
        // Con el lápiz por Shizuku, su dirección y su cercanía vienen de sus eventos: con el patrón
        // corriendo la pantalla no se usa (era poco confiable, v5.8–v6.0).
        // 22-oct (v6.3.1): tampoco con el patrón detenido; en la sesión con Auto la pantalla («perilla
        // hacia right») y el lápiz («lápiz hacia downleft») se pisaban la dirección 20 veces por segundo.
        if (PenShizuku.active()) return;   // con Shizuku, Player.jointSlice tampoco usa la perilla
        float[] joy = svc.joyPoint();
        if (joy == null) return;
        Rect panel = svc.panelRect();
        float cx = joy[0] * SCALE, cy = joy[1] * SCALE, rad = svc.joyRadiusPx() * 1.8f * SCALE;
        int x0 = Math.max(0, (int) (cx - rad)), x1 = Math.min(w - 1, (int) (cx + rad));
        int y0 = Math.max(0, (int) (cy - rad)), y1 = Math.min(h - 1, (int) (cy + rad));
        Image.Plane pl = img.getPlanes()[0];
        ByteBuffer buf = pl.getBuffer();
        int ps = pl.getPixelStride(), rs = pl.getRowStride();
        long n = 0, sx = 0, sy = 0;
        for (int y = y0; y <= y1; y++) {
            int row = y * rs;
            for (int x = x0; x <= x1; x++) {
                if (panel != null && panel.contains((int) (x / SCALE), (int) (y / SCALE))) continue;
                int o = row + x * ps;
                int rr = buf.get(o) & 0xff, gg = buf.get(o + 1) & 0xff, bb = buf.get(o + 2) & 0xff;
                if (bb > 140 && bb - rr > 90 && bb - gg > 50) {
                    n++;
                    sx += x;
                    sy += y;
                }
            }
        }
        long now = SystemClock.uptimeMillis();
        if (n >= PRESSED_PX) knobSeenAt = now;
        // Con el patrón corriendo, la perilla ausente más de BLOCK_MS = Android bloquea lo inyectado
        // (lápiz cerca). Solo en ese estado una perilla que aparece es la del lápiz de Gonzalo.
        if (!injecting) blocked = false;
        else if (n < PRESSED_PX && now - knobSeenAt > BLOCK_MS && !blocked) {
            blocked = true;
            svc.patternBlocked();
        }
        if (n < PRESSED_PX) {
            if (pressed) android.util.Log.i(TouchService.TAG, "perilla suelta (dirección guardada: " + Script.steerDir + ")");
            pressed = false;
            baseX = -1;
            return;
        }
        float kx = (float) sx / n, ky = (float) sy / n;
        // Joystick flotante: la base es donde la perilla aparece o SALTA (un apoyo nuevo: el lápiz de
        // Gonzalo o un re-apoyo del patrón), no un centro fijo (22-oct, Gonzalo: con la base fija una
        // perilla empujada a la derecha se leía a la izquierda).
        boolean jumped = pressed && Math.hypot(kx - lastKx, ky - lastKy) > JUMP_PX;
        lastKx = kx;
        lastKy = ky;
        if (!pressed || jumped) {
            pressed = true;
            baseX = kx;
            baseY = ky;
            foreign = 0;
            return;
        }
        float dx = kx - baseX, dy = baseY - ky;   // pantalla: y hacia abajo
        if (dx * dx + dy * dy < MOVE_PX * MOVE_PX) return;
        double ang = (Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
        String[] dirs = {"right", "upright", "up", "upleft", "left", "downleft", "down", "downright"};
        String d = dirs[(int) (((ang + 22.5) % 360) / 45)];
        if (injecting) {
            // 22-oct (v5.9.3): adivinar cuadro a cuadro de quién era la perilla cambiaba el rumbo solo,
            // con el lápiz lejos (Gonzalo). Ahora solo se lee en estado «bloqueado»: ahí el patrón no
            // llega al juego y la perilla visible es la del lápiz. Al retomar, la perilla del patrón
            // apunta al rumbo ya adoptado y no cambia nada.
            if (d.equals(Script.steerDir)) { blocked = false; foreign = 0; return; }   // el patrón retomó
            if (!blocked) return;
            if (!d.equals(foreignDir)) { foreignDir = d; foreign = 0; }
            if (++foreign < ADOPT_FRAMES) return;
            foreign = 0;
            android.util.Log.i(TouchService.TAG, "lápiz hacia " + d + " (el patrón iba " + Script.steerDir + ")");
            Script.steerDir = d;
            svc.steerChanged();
            return;
        }
        if (!d.equals(Script.steerDir)) android.util.Log.i(TouchService.TAG, "perilla hacia " + d);
        Script.steerDir = d;
    }

    private static final int ADOPT_FRAMES = 3;
    private static final float JUMP_PX = 35;          // salto entre cuadros (media res.) = apoyo nuevo
    private float lastKx, lastKy;
    private static final long BLOCK_MS = 200;
    private boolean blocked;
    private int foreign;
    private String foreignDir;

    private static String dirOf(float dx, float dy) {
        double ang = (Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
        String[] dirs = {"right", "upright", "up", "upleft", "left", "downleft", "down", "downright"};
        return dirs[(int) (((ang + 22.5) % 360) / 45)];
    }

    /** Distancia en octantes (0–4) entre dos direcciones. */
    private static int octants(String a, String b) {
        java.util.List<String> r = java.util.Arrays.asList("right", "upright", "up", "upleft", "left", "downleft", "down", "downright");
        int i = r.indexOf(a), j = r.indexOf(b);
        if (i < 0 || j < 0) return 4;
        int k = Math.abs(i - j) % 8;
        return Math.min(k, 8 - k);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();
        android.util.Log.i(TouchService.TAG, "captura del joystick detenida");
        super.onDestroy();
    }
}
