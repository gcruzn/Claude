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
        if (n >= PRESSED_PX) knobSeenAt = SystemClock.uptimeMillis();
        if (n < PRESSED_PX) {
            if (pressed) android.util.Log.i(TouchService.TAG, "perilla suelta (dirección guardada: " + Script.steerDir + ")");
            pressed = false;
            baseX = -1;
            return;
        }
        float kx = (float) sx / n, ky = (float) sy / n;
        if (!pressed) {
            pressed = true;
            baseX = kx;
            baseY = ky;
            return;
        }
        float dx = kx - baseX, dy = baseY - ky;   // pantalla: y hacia abajo
        if (dx * dx + dy * dy < MOVE_PX * MOVE_PX) return;
        double ang = (Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
        String[] dirs = {"right", "upright", "up", "upleft", "left", "downleft", "down", "downright"};
        String d = dirs[(int) (((ang + 22.5) % 360) / 45)];
        if (injecting) {
            // 22-oct: con el patrón corriendo la perilla puede ser la nuestra o la del lápiz de
            // Gonzalo (con el lápiz cerca Android bloquea lo inyectado). La nuestra siempre se apoya
            // en el centro calibrado y apunta, DESDE ESE CENTRO, al rumbo del patrón; medirla desde
            // donde apareció hacía saltar la dirección a su opuesta en cada re-apoyo (v5.8.0). Se toma
            // como del lápiz solo si desde el centro apunta a más de 45° del rumbo, está bien afuera
            // del centro y se sostiene ADOPT_FRAMES cuadros seguidos.
            float jx = kx - cx, jy = cy - ky;
            if (jx * jx + jy * jy < (2 * MOVE_PX) * (2 * MOVE_PX)) { foreign = 0; return; }
            String fromCenter = dirOf(jx, jy);
            if (Script.steerDir != null && octants(fromCenter, Script.steerDir) < 2) { foreign = 0; return; }
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
