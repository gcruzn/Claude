package cl.gcruz.patrontouch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de accesibilidad con la botonera flotante (columna vertical).
 *
 * Botones 1..N = patrones del set activo (Brawler · Modo): toque = ese patrón en bucle, otro toque = parar.
 * 🎲 Random = en cada repetición un patrón del set al azar. Asa ≡: toque = menú (sets, candado, calibrar),
 * mantener 3 s = candado, arrastrar = mover. ✕: cerrar (desactivada con candado) · mantener ✕ = calibrar.
 * Volumen −: detiene · Volumen +: repite la última acción.
 */
public class TouchService extends AccessibilityService implements Player.Listener {

    private static TouchService instance;

    public static TouchService get() { return instance; }

    private static final int CORAL = Color.parseColor("#E25E6B");
    private static final int AZUL = Color.parseColor("#406AB4");
    private static final int GRAFITO = Color.parseColor("#454A51");

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;
    private Player player;
    private Calibration cal;
    private Data data;

    private WindowManager.LayoutParams panelParams;
    private LinearLayout panel;
    private View menuWindow;
    private Runnable lastRun;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        player = new Player(this, this);
        data = Data.load(this);
        Point s = screenSize();
        cal = new Calibration(this, s.x, s.y);
        showPanel();
        registerDebug();
        observePen();
        startPenBridge();
        startFingerBridge();
    }

    // ---------------------------------------------------------------- S Pen

    /**
     * Observa el S Pen SIN quitarle los eventos al juego (Android 15+: setObservedMotionEventSources).
     * Con el lápiz abajo el juego ignora los toques inyectados, así que el patrón se pausa al apoyarlo
     * y se reanuda al instante al levantarlo; mientras se arrastra se guarda la dirección (para
     * «mover dirigido»). Si el sistema no permite observar, NO se activa: setMotionEventSources solo
     * consumiría los eventos del lápiz y le quitaría el control a Gonzalo.
     */
    private boolean penObserved;
    private float penX, penY;
    private Runnable penResume;

    /**
     * DESACTIVADO (20-oct): en el S26 (Android 16), pedir las fuentes del lápiz le quitó el S Pen al
     * juego aunque se pidieran como «observadas», y onMotionEvent no recibió nada. Se dejan las
     * fuentes en 0 (como antes) hasta encontrar una vía que no interfiera.
     */
    private void observePen() {
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            info.getClass().getMethod("setMotionEventSources", int.class).invoke(info, 0);
            setServiceInfo(info);
        } catch (Throwable ignored) {
        }
        penObserved = false;
        android.util.Log.i(TAG, "S Pen observado: false (desactivado)");
    }

    /**
     * Puente del S Pen por ADB (20-oct): un proceso lanzado una vez con ADB publica en
     * 127.0.0.1:7787 la salida de «getevent -l» del lápiz (solo lee el dispositivo de entrada; el
     * juego recibe el lápiz igual). La app se conecta y traduce: BTN_TOUCH DOWN = lápiz apoyado,
     * UP = levantado, ABS_X/ABS_Y = posición en coordenadas del panel (vertical). Se reintenta cada
     * 3 s, así funciona aunque el proceso se lance después.
     */
    static final int PEN_PORT = 7787;
    private volatile boolean penBridgeRun;
    private int penDownX = -1, penDownY = -1, penCurX = -1, penCurY = -1;
    private boolean penTouching;

    private void startPenBridge() {
        if (penBridgeRun) return;
        penBridgeRun = true;
        Thread th = new Thread(() -> {
            while (penBridgeRun) {
                try (java.net.Socket so = new java.net.Socket()) {
                    so.connect(new java.net.InetSocketAddress("127.0.0.1", PEN_PORT), 1500);
                    android.util.Log.i(TAG, "puente S Pen conectado");
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(so.getInputStream()));
                    String line;
                    while (penBridgeRun && (line = in.readLine()) != null) penLine(line);
                } catch (Exception ignored) {
                }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "puente-spen");
        th.setDaemon(true);
        th.start();
    }

    private void penLine(String line) {
        String[] w = line.trim().split("\\s+");
        if (w.length < 3) return;
        String code = w[w.length - 2], val = w[w.length - 1];
        if (code.equals("ABS_X") || code.equals("ABS_Y")) {
            int v;
            try { v = Integer.parseInt(val, 16); } catch (NumberFormatException e) { return; }
            if (code.equals("ABS_X")) penCurX = v; else penCurY = v;
            if (penTouching && penDownX < 0 && penCurX >= 0 && penCurY >= 0) {
                penDownX = penCurX;
                penDownY = penCurY;
            }
            if (penTouching && penDownX >= 0) updatePenDir();
        } else if (code.equals("BTN_TOUCH")) {
            boolean down = val.equals("DOWN");
            if (down == penTouching) return;
            penTouching = down;
            if (down) {
                penDownX = penCurX;
                penDownY = penCurY;
                main.post(this::penDown);
            } else {
                penDownX = -1;
                penUpAt = android.os.SystemClock.uptimeMillis();
                android.util.Log.i(TAG, "lápiz levantado (sigue cerca de la pantalla)");
                // Mientras el lápiz flota cerca, Android rechaza los toques (rechazo de palma):
                // se reanuda al salir del rango; si no llega ese aviso, a los 1,5 s igual.
                main.postDelayed(penHoverTimeout, 1500);
            }
        } else if (code.equals("BTN_TOOL_PEN") || code.equals("BTN_DIGI")) { // en el S26 se llama BTN_DIGI
            android.util.Log.i(TAG, "lápiz " + (val.equals("DOWN") ? "entra en rango" : "sale de rango"));
            if (val.equals("UP")) {
                android.util.Log.i(TAG, "lápiz fuera de rango");
                // Si el lápiz «se levantó» hace muy poco puede ser un rebote: se confirma 150 ms después.
                long wait = Math.max(0, 150 - (android.os.SystemClock.uptimeMillis() - penUpAt));
                main.postDelayed(() -> {
                    main.removeCallbacks(penHoverTimeout);
                    if (!penTouching) penUp();
                }, wait);
            }
        }
    }

    // ---------------------------------------------------------------- dedo (pantalla táctil)

    /**
     * Puente del dedo (20-oct): igual que el del lápiz, pero leyendo la pantalla táctil
     * (/dev/input/event7, multitáctil 0–4095) por 127.0.0.1:7788. Solo ve dedos reales: los toques
     * de la app no pasan por el sensor. Con un dedo en la pantalla el patrón se pausa; el dedo que
     * empezó sobre el joystick da la dirección; al levantar el último dedo se reanuda (60 ms, sin
     * la espera del lápiz porque el dedo no flota).
     */
    static final int FINGER_PORT = 7788;
    private final java.util.Map<Integer, float[]> fingers = new java.util.HashMap<>(); // slot → {x0,y0,x,y,esJoystick}
    private int fSlot = 0;
    private boolean fingerDown;

    private void startFingerBridge() {
        Thread th = new Thread(() -> {
            while (penBridgeRun) {
                try (java.net.Socket so = new java.net.Socket()) {
                    so.connect(new java.net.InetSocketAddress("127.0.0.1", FINGER_PORT), 1500);
                    android.util.Log.i(TAG, "puente dedo conectado");
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(so.getInputStream()));
                    String line;
                    while (penBridgeRun && (line = in.readLine()) != null) fingerLine(line);
                } catch (Exception ignored) {
                }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "puente-dedo");
        th.setDaemon(true);
        th.start();
    }

    /** Panel vertical 0–4095 → píxeles de pantalla según la rotación. */
    private float[] panelToScreen(float nx, float ny) {
        Point s = screenSize();
        int pw = Math.min(s.x, s.y), ph = Math.max(s.x, s.y);
        float px = nx / 4095f * pw, py = ny / 4095f * ph;
        switch (wm.getDefaultDisplay().getRotation()) {
            case android.view.Surface.ROTATION_90: return new float[]{py, pw - px};
            case android.view.Surface.ROTATION_270: return new float[]{ph - py, px};
            case android.view.Surface.ROTATION_180: return new float[]{pw - px, ph - py};
            default: return new float[]{px, py};
        }
    }

    private void fingerLine(String line) {
        String[] w = line.trim().split("\\s+");
        if (w.length < 3) return;
        String code = w[w.length - 2], val = w[w.length - 1];
        switch (code) {
            case "ABS_MT_SLOT":
                try { fSlot = Integer.parseInt(val, 16); } catch (NumberFormatException ignored) { }
                break;
            case "ABS_MT_TRACKING_ID":
                if (val.equals("ffffffff")) fingers.remove(fSlot);
                else fingers.put(fSlot, new float[]{-1, -1, -1, -1, 0});
                break;
            case "ABS_MT_POSITION_X":
            case "ABS_MT_POSITION_Y": {
                float[] f = fingers.get(fSlot);
                if (f == null) break;
                float v;
                try { v = Integer.parseInt(val, 16); } catch (NumberFormatException e) { break; }
                if (code.endsWith("X")) f[2] = v; else f[3] = v;
                if (f[0] < 0 && f[2] >= 0 && f[3] >= 0) {
                    f[0] = f[2];
                    f[1] = f[3];
                    float[] p = panelToScreen(f[0], f[1]), joy = cal.get("joy");
                    if (joy != null && Math.hypot(p[0] - joy[0], p[1] - joy[1]) < cal.joyRadius() * 2.2f) f[4] = 1;
                    android.util.Log.i(TAG, "dedo abajo en " + Math.round(p[0]) + "," + Math.round(p[1])
                            + (f[4] == 1 ? " (joystick)" : ""));
                }
                if (f[4] == 1 && f[0] >= 0) fingerDir(f);
                break;
            }
            case "SYN_REPORT": {
                boolean any = !fingers.isEmpty();
                if (any == fingerDown) break;
                fingerDown = any;
                if (any) {
                    main.removeCallbacks(fingerResume);
                    main.post(this::penDown);
                } else {
                    android.util.Log.i(TAG, "dedo levantado");
                    // 80 ms: con 0 ms el toque inyectado llegaba antes de que el juego procesara el
                    // UP real, Android lo descartaba y se perdía el gesto entero (20-oct, v4.9.0).
                    main.postDelayed(fingerResume, resumeDelayMs);
                }
                break;
            }
        }
    }

    /** Espera entre levantar el dedo y reanudar; ajustable por ADB («tune») para medir variantes. */
    static volatile int resumeDelayMs = 80;

    private final Runnable fingerResume = () -> { if (!fingerDown && !penTouching) penUp(); };

    private void fingerDir(float[] f) {
        float[] a = panelToScreen(f[0], f[1]), b = panelToScreen(f[2], f[3]);
        float sx = b[0] - a[0], sy = b[1] - a[1];
        if (sx * sx + sy * sy < dp(14) * dp(14)) return;
        double ang = (Math.toDegrees(Math.atan2(-sy, sx)) + 360) % 360;
        String[] dirs = {"right", "upright", "up", "upleft", "left", "downleft", "down", "downright"};
        String d = dirs[(int) (((ang + 22.5) % 360) / 45)];
        if (!d.equals(Script.steerDir)) android.util.Log.i(TAG, "dedo hacia " + d);
        Script.steerDir = d;
    }

    /** Vector desde donde apoyó el lápiz, pasado de panel vertical a pantalla según la rotación. */
    private void updatePenDir() {
        float rx = penCurX - penDownX, ry = penCurY - penDownY; // panel: x a lo corto, y a lo largo, y hacia abajo
        if (rx * rx + ry * ry < 250f * 250f) return;
        float sx, sy;
        switch (wm.getDefaultDisplay().getRotation()) {
            case android.view.Surface.ROTATION_90: sx = ry; sy = -rx; break;
            case android.view.Surface.ROTATION_270: sx = -ry; sy = rx; break;
            case android.view.Surface.ROTATION_180: sx = -rx; sy = -ry; break;
            default: sx = rx; sy = ry;
        }
        double ang = (Math.toDegrees(Math.atan2(-sy, sx)) + 360) % 360;
        String[] dirs = {"right", "upright", "up", "upleft", "left", "downleft", "down", "downright"};
        String d = dirs[(int) (((ang + 22.5) % 360) / 45)];
        if (!d.equals(Script.steerDir)) android.util.Log.i(TAG, "lápiz hacia " + d);
        Script.steerDir = d;
    }

    /**
     * Rebotes (20-oct): con poca presión el S Pen reporta «levantado» y «apoyado» en ráfagas de
     * 10–200 ms mientras se arrastra; si el patrón se reanudaba en ese hueco, el toque inyectado del
     * joystick quedaba a medias y el juego lo dejaba pegado. Solo se reanuda si el lápiz sigue arriba.
     */
    private volatile long penUpAt;

    private final Runnable penHoverTimeout = () -> { if (!penTouching) penUp(); };

    /** Botón del patrón que el lápiz dejó en pausa (para que tocarlo lo detenga). */
    private int penPausedIdx = -1;

    private void penDown() {
        main.removeCallbacks(penHoverTimeout);
        if (player.isPlaying() && lastRun != null) {
            penPausedIdx = activeIdx;
            penResume = lastRun;
            // 20-oct: el dedo o el lápiz NO cortan el gesto inyectado en curso (en el S26 conviven),
            // y ese gesto seguía apretando el joystick: el juego atendía a ese dedo virtual y no al
            // de Gonzalo. Se corta ya, con el toque mínimo de soltar en la zona invisible del panel.
            player.stop(null);
            android.util.Log.i(TAG, "lápiz/dedo abajo: patrón en pausa y gesto cortado");
        }
    }

    private void penUp() {
        if (penTouching) return;
        if (penResume != null && !player.isPlaying()) {
            android.util.Log.i(TAG, "lápiz arriba: reanuda hacia " + Script.steerDir);
            // 20-oct (Gonzalo): la 1.ª vuelta tras soltar partía desde donde quedó su joystick y no
            // avanzaba; la 2.ª, que viene tras un toque soltado, sí partía del centro. Se imita ese
            // paso: un toque corto en el centro del joystick y, al terminar, la vuelta del patrón.
            float[] joy = cal.get("joy");
            final Runnable resume = penResume;
            if (joy != null) {
                Path path = new Path();
                path.moveTo(joy[0], joy[1]);
                // v4.9.4 lo lanzaba 40 ms y arrancaba la vuelta a los 60 ms: la vuelta cancelaba el toque
                // y la 1.ª seguía sin avanzar. Ahora se espera a que el toque termine de verdad.
                final Runnable go = () -> { if (!penTouching && !fingerDown && !player.isPlaying()) resume.run(); };
                dispatchGesture(new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, 120)).build(),
                        new GestureResultCallback() {
                            @Override
                            public void onCompleted(GestureDescription g) {
                                android.util.Log.i(TAG, "toque de joystick previo: completo");
                                main.postDelayed(go, 50);
                            }

                            @Override
                            public void onCancelled(GestureDescription g) {
                                android.util.Log.w(TAG, "toque de joystick previo: cancelado");
                                main.postDelayed(go, 50);
                            }
                        }, main);
            } else {
                resume.run();
            }
        }
    }

    @SuppressWarnings("unused")
    private void observePenExperimental() {
        // AccessibilityServiceInfo.setMotionEventSources (API 34) + setObservedMotionEventSources
        // (API 35), por reflexión porque se compila con SDK 34. Las dos o ninguna.
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            Class<?> c = info.getClass();
            java.lang.reflect.Method sources = c.getMethod("setMotionEventSources", int.class);
            java.lang.reflect.Method observed = c.getMethod("setObservedMotionEventSources", int.class);
            sources.invoke(info, android.view.InputDevice.SOURCE_STYLUS);
            observed.invoke(info, android.view.InputDevice.SOURCE_STYLUS);
            setServiceInfo(info);
            penObserved = true;
        } catch (Throwable t) {
            penObserved = false;
            android.util.Log.w(TAG, "S Pen no observable: " + t);
        }
        android.util.Log.i(TAG, "S Pen observado: " + penObserved);
    }

    @Override
    public void onMotionEvent(MotionEvent e) {
        if (!penObserved || e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                penX = e.getRawX();
                penY = e.getRawY();
                if (player.isPlaying() && lastRun != null) {
                    penResume = lastRun;
                    player.stopByUserTouch();
                    android.util.Log.i(TAG, "lápiz abajo: patrón en pausa");
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - penX, dy = penY - e.getRawY();
                if (dx * dx + dy * dy > dp(18) * dp(18)) {
                    double ang = (Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
                    String[] dirs = {"right", "upright", "up", "upleft", "left", "downleft", "down", "downright"};
                    Script.steerDir = dirs[(int) (((ang + 22.5) % 360) / 45)];
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (penResume != null && !player.isPlaying()) {
                    android.util.Log.i(TAG, "lápiz arriba: reanuda hacia " + Script.steerDir);
                    main.post(penResume);
                }
                break;
        }
    }

    // ---------------------------------------------------------------- pruebas por ADB

    /**
     * Comandos de prueba desde el PC, sin tocar la pantalla (un toque real cancelaría el gesto):
     *   adb shell am broadcast -a cl.gcruz.patrontouch.DEBUG --es cmd run --es id e_salto [--es profile edgar] [--ei reps 1]
     *   ... --es cmd run --es script "súper auto\nesperar 0.6"     (patrón suelto, \n separa líneas)
     *   ... --es cmd stop | dump | set --es id charlie | calib --es joy 555,800 --es attack 1664,794 ...
     * La salida va a logcat con la etiqueta PatronTouch. Solo el shell puede enviarlos: el receptor
     * exige android.permission.DUMP, que ninguna app instalada tiene.
     */
    static final String DEBUG_ACTION = "cl.gcruz.patrontouch.DEBUG";
    static final String TAG = "PatronTouch";
    private android.content.BroadcastReceiver debugReceiver;

    private void registerDebug() {
        debugReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                try {
                    debug(i);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "debug: " + e);
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(DEBUG_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(debugReceiver, f, android.Manifest.permission.DUMP, main, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(debugReceiver, f, android.Manifest.permission.DUMP, main);
        }
    }

    private void debug(android.content.Intent i) {
        String cmd = i.getStringExtra("cmd");
        if (cmd == null) cmd = "dump";
        switch (cmd) {
            case "stop":
                penResume = null;
                player.stop("Detenido por ADB");
                break;
            case "tune":
                resumeDelayMs = i.getIntExtra("resume", resumeDelayMs);
                Script.joyHoldMs = i.getIntExtra("hold", Script.joyHoldMs);
                Script.joyRampMs = i.getIntExtra("ramp", Script.joyRampMs);
                android.util.Log.i(TAG, "tune resume=" + resumeDelayMs + " hold=" + Script.joyHoldMs + " ramp=" + Script.joyRampMs);
                break;
            case "escape":
                if (i.getBooleanExtra("on", !escapeOn()) != escapeOn()) toggleEscape();
                android.util.Log.i(TAG, "escape=" + escapeOn());
                break;
            case "set": {
                String id = i.getStringExtra("id");
                if (id != null && data.set(id) != null) {
                    data.activeSet = id;
                    data.save();
                    rebuildPanel();
                    toast("Set: " + data.set(id).title());
                }
                android.util.Log.i(TAG, "set activo=" + data.activeSet);
                break;
            }
            case "calib": {
                for (int k = 0; k < Calibration.KEYS.length; k++) {
                    String v = i.getStringExtra(Calibration.KEYS[k]);
                    if (v == null) continue;
                    if (v.equals("none")) {
                        cal.pos[k] = null;
                    } else {
                        String[] xy = v.split(",");
                        cal.pos[k] = new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1])};
                    }
                }
                Point s = screenSize();
                cal.screenW = Math.max(s.x, s.y);
                cal.screenH = Math.min(s.x, s.y);
                cal.save();
                logCalibration();
                break;
            }
            case "run": {
                String id = i.getStringExtra("id");
                String script = i.getStringExtra("script");
                String name = "ADB";
                if (id != null) {
                    Data.PatternDef def = data.pattern(id);
                    if (def == null) {
                        android.util.Log.w(TAG, "run: no existe el patrón " + id);
                        return;
                    }
                    script = def.script;
                    name = def.name;
                }
                if (script == null) return;
                script = script.replace("\\n", "\n");
                int reps = i.getIntExtra("reps", 1);
                logCompiled(name, script, Script.defaultProfile());
                final String n = name, sc = script;
                final int rp = Math.max(0, reps), md = reps == 0 ? MODE_LOOP : MODE_ONCE;
                lastRun = () -> runScript(n, sc, rp, -1, md); // Escape reinicia este, no el último botón
                lastRun.run();
                break;
            }
            default: {
                logCalibration();
                android.util.Log.i(TAG, "set activo=" + data.activeSet + " perfil global=" + data.globalProfile
                        + " lotes=" + data.seeds);
                for (Data.Profile p : data.profiles) android.util.Log.i(TAG, "perfil " + p.id + ": " + p.summary());
                for (Data.PadSet s : data.sets) {
                    StringBuilder b = new StringBuilder("set " + s.id + " (" + s.title() + "):");
                    for (int k = 0; k < s.slots.size(); k++) b.append(' ').append(k + 1).append('=').append(s.slots.get(k));
                    android.util.Log.i(TAG, b.toString());
                }
                for (Data.PatternDef p : data.patterns) {
                    Script.Result r = Script.parse(p.script);
                    android.util.Log.i(TAG, "patrón " + p.id + " «" + p.name + "»: "
                            + (r.ok() ? r.steps.size() + " pasos OK" : "ERROR línea " + r.errorLine + ": " + r.error));
                }
            }
        }
    }

    private void logCalibration() {
        StringBuilder b = new StringBuilder("calibración " + cal.screenW + "x" + cal.screenH
                + (cal.calibrated ? "" : " (por defecto)") + ":");
        for (int k = 0; k < Calibration.KEYS.length; k++) {
            float[] p = cal.pos[k];
            b.append(' ').append(Calibration.KEYS[k]).append('=')
                    .append(p == null ? "—" : Math.round(p[0]) + "," + Math.round(p[1]));
        }
        android.util.Log.i(TAG, b.toString());
    }

    /** Escribe en logcat cada toque que va a inyectar el patrón (inicio, fin, de dónde a dónde). */
    private void logCompiled(String name, String script, Data.Profile prof) {
        Script.Result r = Script.parse(script);
        if (!r.ok()) {
            android.util.Log.w(TAG, name + ": ERROR línea " + r.errorLine + ": " + r.error);
            return;
        }
        Pattern p = Script.compile(name, r.steps, prof, cal);
        android.util.Log.i(TAG, name + " (perfil " + prof.id + "): " + p.touches.size() + " toques, " + p.duration + " ms");
        for (Pattern.Touch t : p.touches) {
            Pattern.Point a = t.pts.get(0), z = t.pts.get(t.pts.size() - 1);
            android.util.Log.i(TAG, "  " + a.t + "–" + z.t + " ms  " + Math.round(a.x) + "," + Math.round(a.y)
                    + " → " + Math.round(z.x) + "," + Math.round(z.y));
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() {
        if (player != null) player.stop("Interrumpido");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        penBridgeRun = false;
        if (debugReceiver != null) try { unregisterReceiver(debugReceiver); } catch (Exception ignored) { }
        debugReceiver = null;
        if (player != null) player.stop(null);
        hideGhost();
        removeCalibration();
        closeMenu();
        hidePanel();
        instance = null;
    }

    // ---------------------------------------------------------------- teclas de volumen

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (player == null) return false;
        if (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN && player.isPlaying()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) player.stop("Detenido con volumen −");
            return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP && panel != null && lastRun != null
                && prefs.getBoolean("volShortcut", true)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) lastRun.run();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- API para la app

    public boolean isPanelVisible() { return panel != null; }

    public Calibration calibration() { return cal; }

    /** La app guardó cambios: recargar datos y redibujar la cruceta. */
    public void reloadData() {
        player.stop(null);
        data = Data.load(this);
        rebuildPanel();
    }

    public boolean isLocked() { return prefs.getBoolean("padLocked", false); }

    public void setLocked(boolean locked) {
        prefs.edit().putBoolean("padLocked", locked).apply();
        rebuildPanel();
    }

    /** Calibración pedida desde la app: espera a que abras el juego en horizontal. */
    public void calibrateSoon() {
        toast("Abre Brawl Stars: la calibración empieza en unos segundos");
        whenLandscape(5000, this::startCalibration);
    }

    /** Botón Probar del editor: espera el juego en horizontal, cuenta 3-2-1 y ejecuta una vez. */
    public void testSoon(String name, String script) {
        toast("Abre el juego: el patrón se ejecuta en unos segundos");
        whenLandscape(3000, () -> {
            toast("3…");
            main.postDelayed(() -> toast("2…"), 1000);
            main.postDelayed(() -> toast("1…"), 2000);
            main.postDelayed(() -> runScript(name, script, 1, -1, MODE_ONCE), 3000);
        });
    }

    private void whenLandscape(long firstDelay, Runnable r) {
        final int[] tries = {0};
        Runnable check = new Runnable() {
            @Override
            public void run() {
                Point s = screenSize();
                if (s.x > s.y) r.run();
                else if (++tries[0] < 25) main.postDelayed(this, 1000);
            }
        };
        main.postDelayed(check, firstDelay);
    }

    // ---------------------------------------------------------------- botonera

    private static final int MODE_ONCE = 1, MODE_LOOP = 2;
    private static final long LOCK_PRESS_MS = 3000;
    /** Índice del botón Random (los de patrón son 0..N-1). */
    private static final int RANDOM = 100;

    private final List<TextView> slotButtons = new ArrayList<>();
    private TextView randomButton, escapeButton;

    /**
     * 🏃 Escape: interruptor, no patrón. Encendido, todos los patrones se compilan sin disparos,
     * súper, gadget ni hiper, y con el movimiento de huida estirado (ver Script.ESCAPE_*). Si hay
     * un patrón corriendo, se reinicia al tiro con el modo nuevo.
     */
    private boolean escapeOn() {
        return prefs.getBoolean("showEscape", true) && prefs.getBoolean("escapeMode", false);
    }

    /**
     * Cambia el modo. wasPlaying: había un patrón corriendo (ya detenido por el toque real); se
     * suelta el joystick y se reinicia con el modo nuevo, 120 ms después de levantar el dedo.
     */
    private void toggleEscape(boolean wasPlaying) {
        boolean on = !escapeOn();
        prefs.edit().putBoolean("escapeMode", on).apply();
        paintEscape();
        vibrate(on ? 40 : 20);
        android.util.Log.i(TAG, "escape " + (on ? "ON" : "OFF") + (wasPlaying ? " · reinicia " : ""));
        main.postDelayed(() -> {
            player.releaseNow();
            if (wasPlaying && lastRun != null) main.postDelayed(lastRun, 60);
        }, 120);
    }

    private void toggleEscape() {
        boolean was = player.isPlaying();
        if (was) player.stop(null);
        toggleEscape(was);
    }

    /** Escape con el dedo: igual que los botones de patrón, detiene al apoyar sin inyectar nada. */
    private View.OnTouchListener escapeTouch() {
        final boolean[] was = {false};
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Android puede cortar el gesto un instante antes de entregarnos el toque.
                    was[0] = player.isPlaying() || SystemClock.uptimeMillis() - interruptedAt < 400;
                    if (player.isPlaying()) player.stopByUserTouch();
                    return true;
                case MotionEvent.ACTION_UP:
                    toggleEscape(was[0]);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (was[0]) main.postDelayed(player::releaseNow, 120);
                    return true;
            }
            return false;
        };
    }

    private void paintEscape() {
        if (escapeButton == null) return;
        boolean on = escapeOn();
        escapeButton.setText(on ? "🏃\nEscape ON" : "🏃\nEscape");
        int a = Math.max(on ? 200 : 0, alpha());
        ((GradientDrawable) escapeButton.getBackground()).setColor(on
                ? Color.argb(a, 115, 201, 106) : idleColor());
    }
    private View releaseTarget;
    private int activeIdx = -1, activeMode = 0;
    private boolean touchStopOnly;
    private final java.util.Random rnd = new java.util.Random();

    private int alpha() { return Math.round(255 * prefs.getInt("padAlpha", 45) / 100f); }

    private int idleColor() { return Color.argb(alpha(), 64, 106, 180); }

    private int activeColor(int mode) {
        int a = Math.max(150, alpha());
        if (mode == MODE_LOOP) return Color.argb(a, 226, 94, 107);   // coral = en bucle
        return Color.argb(a, 69, 74, 81);                            // grafito = una vez (Probar)
    }

    /** «Edgar · Salto y golpes» → «Salto y g…»: lo que cabe en un botón. */
    private static String shortName(String name) {
        String n = name == null ? "" : name;
        int dot = n.indexOf('·');
        if (dot >= 0) n = n.substring(dot + 1).trim();
        if (n.length() > 9) {
            // Si termina en flecha, se conserva: «Disparo y esquiva →» → «Disparo →».
            char last = n.charAt(n.length() - 1);
            if ("→←↑↓↗↖↘↙".indexOf(last) >= 0) {
                String first = n.split(" ")[0];
                return (first.length() > 7 ? first.substring(0, 7) : first) + " " + last;
            }
            return n.substring(0, 8) + "…";
        }
        return n;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void showPanel() {
        if (wm == null || panel != null) return;
        int size = dp(prefs.getInt("padSize", 58));
        boolean locked = isLocked();
        slotButtons.clear();
        randomButton = null;
        escapeButton = null;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        // Asa: menú / candado / arrastrar.
        TextView handle = new TextView(this);
        handle.setText(locked ? "🔒" : "≡");
        handle.setGravity(Gravity.CENTER);
        handle.setTextColor(Color.argb(Math.min(255, alpha() + 90), 69, 74, 81));
        handle.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * (locked ? 0.3f : 0.45f));
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(Color.argb(Math.max(30, alpha() / 2), 255, 255, 255));
        hbg.setCornerRadius(size * 0.2f);
        handle.setBackground(hbg);
        handle.setOnTouchListener(handleTouch());
        panel.addView(handle, column(size, (int) (size * 0.5f)));

        List<Data.PatternDef> pats = data.slotPatterns(data.active());
        for (int i = 0; i < pats.size() && i < Data.MAX_SLOTS; i++) {
            TextView b = slotButton((i + 1) + "\n" + shortName(pats.get(i).name), size);
            b.setOnTouchListener(slotTouch(i));
            slotButtons.add(b);
            panel.addView(b, column(size, (int) (size * 0.8f)));
        }
        if (!pats.isEmpty() && prefs.getBoolean("showEscape", true)) {
            escapeButton = slotButton("", size);
            paintEscape();
            escapeButton.setOnTouchListener(escapeTouch());
            panel.addView(escapeButton, column(size, (int) (size * 0.8f)));
        }
        if (pats.size() >= 2 && prefs.getBoolean("showRandom", true)) {
            randomButton = slotButton("🎲\nRandom", size);
            randomButton.setOnTouchListener(slotTouch(RANDOM));
            panel.addView(randomButton, column(size, (int) (size * 0.8f)));
        } else if (pats.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Set sin\npatrones");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(Color.WHITE);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.16f);
            panel.addView(empty, column(size, (int) (size * 0.6f)));
        }

        // ✕ abajo; con candado queda desactivada. Mantenerla = calibrar.
        TextView close = new TextView(this);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.26f);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setShape(GradientDrawable.OVAL);
        cbg.setColor(locked ? Color.argb(Math.max(25, alpha() / 3), 150, 151, 152)
                : Color.argb(alpha(), 226, 94, 107));
        close.setBackground(cbg);
        if (locked) {
            close.setAlpha(0.35f);
            close.setOnClickListener(v -> toast("Bloqueada · mantén el asa 3 s o desbloquéala en la app"));
        } else {
            close.setOnClickListener(v -> {
                player.stop(null);
                hidePanel();
                toast("Botonera cerrada. Ábrela de nuevo desde la app Patrón Touch.");
            });
            close.setOnLongClickListener(v -> {
                startCalibration();
                return true;
            });
        }
        panel.addView(close, column((int) (size * 0.55f), (int) (size * 0.55f)));

        // Zona invisible al pie: absorbe el toque mínimo que se inyecta al detener un patrón
        // para soltar los dedos virtuales, sin activar ningún botón.
        View sink = new View(this);
        sink.setOnTouchListener((v, e) -> true);
        panel.addView(sink, column(size, dp(16)));
        releaseTarget = sink;

        if (panelParams == null) {
            panelParams = overlayParams(false);
            panelParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
            panelParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            panelParams.gravity = Gravity.TOP | Gravity.START;
            panelParams.x = prefs.getInt("padX", dp(12));
            panelParams.y = prefs.getInt("padY", dp(8));
        }
        wm.addView(panel, panelParams);
        if (!cal.calibrated) toast("Calibra tus controles: mantén presionada la ✕ con el juego abierto");
    }

    private TextView slotButton(String text, int size) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.17f);
        b.setLineSpacing(0, 0.9f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(idleColor());
        bg.setCornerRadius(size * 0.2f);
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams column(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        int m = dp(2);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    public void hidePanel() {
        closeMenu();
        if (panel != null && wm != null) try { wm.removeView(panel); } catch (Exception ignored) { }
        panel = null;
    }

    /** Vuelve a dibujar la botonera (cambios de tamaño, opacidad, candado o set). */
    public void rebuildPanel() {
        if (panel == null) return;
        player.stop(null);
        try { wm.removeView(panel); } catch (Exception ignored) { }
        panel = null;
        showPanel();
    }

    /** Asa: arrastrar (sin candado) · toque = menú · mantener 3 s = candado. */
    private View.OnTouchListener handleTouch() {
        return new View.OnTouchListener() {
            float sx, sy;
            int px, py;
            boolean moved, lockFired;
            final Runnable lockToggle = () -> {
                lockFired = true;
                vibrate(60);
                boolean nowLocked = !isLocked();
                toast(nowLocked ? "🔒 Botonera bloqueada" : "🔓 Botonera desbloqueada");
                setLocked(nowLocked);
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (player.isPlaying()) player.stopByUserTouch();
                        sx = e.getRawX();
                        sy = e.getRawY();
                        px = panelParams.x;
                        py = panelParams.y;
                        moved = false;
                        lockFired = false;
                        main.postDelayed(lockToggle, LOCK_PRESS_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) {
                            moved = true;
                            main.removeCallbacks(lockToggle);
                        }
                        if (moved && !isLocked() && panel != null) {
                            panelParams.x = px + (int) dx;
                            panelParams.y = py + (int) dy;
                            wm.updateViewLayout(panel, panelParams);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(lockToggle);
                        if (moved && !isLocked()) {
                            prefs.edit().putInt("padX", panelParams.x).putInt("padY", panelParams.y).apply();
                        } else if (!moved && !lockFired) {
                            showMenu();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(lockToggle);
                        return true;
                }
                return false;
            }
        };
    }

    /**
     * Toque = ese patrón en bucle hasta volver a tocarlo. Tocar otro botón cambia de patrón.
     * El dedo real ya corta el gesto inyectado (Android), así que al bajar solo se detiene sin inyectar.
     */
    private View.OnTouchListener slotTouch(int i) {
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Con el S Pen, el puente avisa el apoyo antes que este toque y deja el patrón en
                    // pausa: igual cuenta como «estaba corriendo» para que el toque lo DETENGA.
                    touchStopOnly = (player.isPlaying() && activeIdx == i)
                            || (penResume != null && penPausedIdx == i);
                    if (player.isPlaying()) player.stopByUserTouch();
                    ((GradientDrawable) v.getBackground()).setColor(activeColor(MODE_LOOP));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (touchStopOnly) {
                        penResume = null; // detenido a propósito: el lápiz ya no lo reanuda
                        paintIdle();
                        main.postDelayed(player::releaseNow, 120); // soltar el joystick ya sin el dedo real
                    } else {
                        startSlot(i);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    paintIdle();
                    return true;
            }
            return false;
        };
    }

    private void startSlot(int i) {
        lastRun = () -> startSlot(i);
        penResume = null;
        List<Data.PatternDef> pats = data.slotPatterns(data.active());
        if (i == RANDOM) {
            startRandom(pats);
            return;
        }
        if (i >= pats.size()) {
            paintIdle();
            toast("Ese botón no tiene patrón (configúralo en la app)");
            return;
        }
        Data.PatternDef def = pats.get(i);
        runScript(def.name, def.script, 0, i, MODE_LOOP);
    }

    /** Random: cada repetición es un patrón del set al azar, sin repetir el anterior. */
    private void startRandom(List<Data.PatternDef> pats) {
        List<Pattern> compiled = new ArrayList<>();
        for (Data.PatternDef d : pats) {
            Pattern p = compileDef(d.name, d.script, false);
            if (p != null) compiled.add(p);
        }
        if (compiled.size() < 2) {
            paintIdle();
            toast("Random necesita al menos 2 patrones válidos en el set");
            return;
        }
        final int[] last = {rnd.nextInt(compiled.size())};
        java.util.function.Supplier<Pattern> next = () -> {
            int k = rnd.nextInt(compiled.size() - 1);
            if (k >= last[0]) k++;
            last[0] = k;
            return compiled.get(k);
        };
        if (player.isPlaying()) player.stop(null);
        activeIdx = RANDOM;
        activeMode = MODE_LOOP;
        android.util.Log.i(TAG, "random en «" + data.active().title() + "» entre " + compiled.size() + " patrones");
        player.start(compiled.get(last[0]), 0, 120, next);
    }

    /** Compila un patrón con su propio tipo de disparo; null (y aviso) si tiene errores o no toca nada. */
    private Pattern compileDef(String name, String script, boolean warn) {
        Script.Result r = Script.parse(script);
        if (!r.ok()) {
            if (warn) toast(name + ": error en línea " + r.errorLine + " · " + r.error);
            return null;
        }
        Pattern p = Script.compile(name, r.steps, Script.defaultProfile(), cal, escapeOn());
        if (p.touches.isEmpty() || p.duration <= 0) {
            if (warn) toast(name + ": no hay toques con tus controles calibrados");
            return null;
        }
        return p;
    }

    private void runScript(String name, String script, int reps, int idx, int mode) {
        if (player.isPlaying()) player.stop(null);
        Pattern p = compileDef(name, script, true);
        if (p == null) {
            paintIdle();
            return;
        }
        activeIdx = idx;
        activeMode = mode;
        android.util.Log.i(TAG, "patrón «" + name + "» botón=" + (idx == RANDOM ? "random" : idx >= 0 ? String.valueOf(idx + 1) : "—")
                + " modo=" + (mode == MODE_LOOP ? "bucle" : "una vez") + " reps=" + (reps == 0 ? "∞" : reps));
        // «mover aleatorio»: cada vuelta se vuelve a compilar para que el recorrido cambie.
        java.util.function.Supplier<Pattern> next = Script.isRandom(Script.parse(script).steps)
                ? () -> compileDef(name, script, false) : null;
        player.start(p, reps, mode == MODE_LOOP ? 120 : 0, next);
    }

    private TextView buttonFor(int idx) {
        if (idx == RANDOM) return randomButton;
        return idx >= 0 && idx < slotButtons.size() ? slotButtons.get(idx) : null;
    }

    private void paintIdle() {
        for (TextView b : slotButtons) ((GradientDrawable) b.getBackground()).setColor(idleColor());
        if (randomButton != null) ((GradientDrawable) randomButton.getBackground()).setColor(idleColor());
    }

    // ---------------------------------------------------------------- menú del centro

    /**
     * Menú del asa: el set se elige con dos listas desplegables (Brawler y Modo) y «Usar este set»;
     * escala a cientos de sets sin alargar el menú. Candado, calibrar y cerrar en una fila al pie.
     */
    private void showMenu() {
        closeMenu();
        Data.PadSet active = data.active();
        Point scr = screenSize();
        int width = Math.min(dp(420), (int) (scr.x * 0.9f));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(250, 255, 255, 255));
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        box.setElevation(dp(8));
        TextView t = new TextView(this);
        t.setText("Set activo · " + (active == null ? "—" : active.title()));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(GRAFITO);
        t.setPadding(dp(2), 0, dp(2), dp(6));
        box.addView(t);

        final Data.PadSet[] chosen = {active};
        box.addView(SetPicker.build(this, data, active, s -> chosen[0] = s),
                new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView use = new TextView(this);
        use.setText("Usar este set");
        use.setGravity(Gravity.CENTER);
        use.setTextColor(Color.WHITE);
        use.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        use.setTypeface(Typeface.DEFAULT_BOLD);
        use.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable ub = new GradientDrawable();
        ub.setColor(AZUL);
        ub.setCornerRadius(dp(12));
        use.setBackground(ub);
        use.setOnClickListener(v -> {
            closeMenu();
            Data.PadSet s = chosen[0];
            if (s == null) return;
            data.activeSet = s.id;
            data.save();
            toast("Set: " + s.title());
            rebuildPanel();
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.setMargins(0, dp(8), 0, 0);
        box.addView(use, ulp);

        // Pie: candado · calibrar · cerrar, en una fila.
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(6), 0, 0);
        boolean locked = isLocked();
        String[] labels = {locked ? "🔓 Desbloquear" : "🔒 Bloquear", "◎ Calibrar", "Cerrar"};
        Runnable[] acts = {() -> setLocked(!locked), this::startCalibration, null};
        for (int i = 0; i < labels.length; i++) {
            final Runnable a = acts[i];
            TextView f = new TextView(this);
            f.setText(labels[i]);
            f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            f.setTextColor(a == null ? CORAL : AZUL);
            f.setGravity(Gravity.CENTER);
            f.setPadding(dp(8), dp(8), dp(8), dp(4));
            f.setOnClickListener(v -> {
                closeMenu();
                if (a != null) a.run();
            });
            foot.addView(f, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(foot, new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams lp = overlayParams(false);
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        menuWindow = box;
        wm.addView(box, lp);
    }

    private void closeMenu() {
        if (menuWindow != null && wm != null) try { wm.removeView(menuWindow); } catch (Exception ignored) { }
        menuWindow = null;
    }

    // ---------------------------------------------------------------- reproducción

    @Override
    public float[] releasePoint() {
        if (panel != null && releaseTarget != null) {
            int[] loc = new int[2];
            releaseTarget.getLocationOnScreen(loc);
            return new float[]{loc[0] + releaseTarget.getWidth() / 2f, loc[1] + releaseTarget.getHeight() / 2f};
        }
        return new float[]{screenSize().x / 2f, 1f};
    }

    /** Cuándo un toque real cortó el último gesto (para que Escape sepa que había un patrón corriendo). */
    private long interruptedAt;

    @Override
    public void onPlayerState(boolean playing, String status) {
        android.util.Log.i(TAG, (playing ? "▶ " : "■ ") + status);
        if (!playing && status != null && status.startsWith("Interrumpido")) interruptedAt = SystemClock.uptimeMillis();
        if (playing) showGhost(); else hideGhost();
        paintIdle();
        TextView b = buttonFor(activeIdx);
        if (playing && b != null) ((GradientDrawable) b.getBackground()).setColor(activeColor(activeMode));
        if (!playing) {
            activeIdx = -1;
            if (status != null && !status.startsWith("Listo") && !status.equals("Detenido")) toast(status);
        }
    }

    private void vibrate(long ms) {
        if (!prefs.getBoolean("vibrate", true)) return;
        try {
            android.os.Vibrator vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib != null) vib.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- calibración

    private CalibView calibView;
    private LinearLayout calibBar;
    private TextView calibText, calibSkip;
    private int calibStep;
    private float[][] calibPos;

    private void startCalibration() {
        player.stop(null);
        closeMenu();
        if (calibView != null) return;
        Point sz = screenSize();
        if (sz.x < sz.y) {
            toast("Abre el juego en horizontal y calibra desde ahí");
            return;
        }
        boolean hadPanel = panel != null;
        hidePanel();
        prefs.edit().putBoolean("panelAfterCalib", hadPanel).apply();

        calibStep = 0;
        calibPos = new float[Calibration.KEYS.length][];
        calibView = new CalibView(this);
        wm.addView(calibView, overlayParams(true));

        calibBar = new LinearLayout(this);
        calibBar.setOrientation(LinearLayout.HORIZONTAL);
        calibBar.setGravity(Gravity.CENTER_VERTICAL);
        calibBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(245, 255, 255, 255));
        bg.setCornerRadius(dp(16));
        calibBar.setBackground(bg);
        calibText = new TextView(this);
        calibText.setTextColor(GRAFITO);
        calibText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        calibText.setPadding(dp(6), 0, dp(10), 0);
        calibSkip = barButton("Omitir", Color.parseColor("#EEF1F6"), GRAFITO);
        TextView cancel = barButton("Cancelar", Color.parseColor("#FBE7E9"), CORAL);
        calibSkip.setOnClickListener(v -> calibAdvance(null));
        cancel.setOnClickListener(v -> {
            removeCalibration();
            showPanel();
        });
        calibBar.addView(calibText);
        calibBar.addView(calibSkip);
        calibBar.addView(cancel);
        WindowManager.LayoutParams blp = overlayParams(false);
        blp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        blp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        blp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        blp.y = dp(10);
        wm.addView(calibBar, blp);
        updateCalibText();
    }

    private TextView barButton(String text, int bg, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(11), dp(7), dp(11), dp(7));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(16));
        tv.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void updateCalibText() {
        int i = calibStep;
        calibText.setText((i + 1) + "/" + Calibration.KEYS.length + "  Toca " + Calibration.LABELS[i]
                + (i >= 3 ? "\n(si no lo tienes o no aparece ahora, toca Omitir)" : ""));
        calibSkip.setVisibility(i >= 3 ? View.VISIBLE : View.GONE);
    }

    private void calibAdvance(float[] p) {
        calibPos[calibStep] = p;
        calibView.invalidate();
        calibStep++;
        if (calibStep < Calibration.KEYS.length) {
            updateCalibText();
            return;
        }
        Point s = screenSize();
        cal.screenW = Math.max(s.x, s.y);
        cal.screenH = Math.min(s.x, s.y);
        cal.pos = calibPos;
        cal.save();
        removeCalibration();
        showPanel();
        toast("Controles calibrados ✓");
    }

    private void removeCalibration() {
        if (wm == null) return;
        if (calibView != null) try { wm.removeView(calibView); } catch (Exception ignored) { }
        if (calibBar != null) try { wm.removeView(calibBar); } catch (Exception ignored) { }
        calibView = null;
        calibBar = null;
    }

    private class CalibView extends View {
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];

        CalibView(Context c) {
            super(c);
            setBackgroundColor(Color.argb(60, 64, 106, 180));
            dot.setColor(Color.argb(200, 226, 94, 107));
            label.setColor(Color.WHITE);
            label.setTextSize(dp(12));
            label.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                getLocationOnScreen(loc);
                calibAdvance(new float[]{e.getX() + loc[0], e.getY() + loc[1]});
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            getLocationOnScreen(loc);
            String[] names = {"Joystick", "Ataque", "Súper", "Gadget", "Hiper"};
            for (int i = 0; i < calibPos.length; i++) {
                if (calibPos[i] == null) continue;
                float x = calibPos[i][0] - loc[0], y = calibPos[i][1] - loc[1];
                c.drawCircle(x, y, dp(14), dot);
                c.drawText(names[i], x + dp(16), y + dp(4), label);
            }
        }
    }

    // ---------------------------------------------------------------- dedos virtuales

    private GhostView ghostView;

    private void showGhost() {
        if (ghostView != null || wm == null) return;
        ghostView = new GhostView(this, player);
        WindowManager.LayoutParams lp = overlayParams(true);
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.addView(ghostView, lp); } catch (Exception e) { ghostView = null; }
    }

    private void hideGhost() {
        if (ghostView != null && wm != null) try { wm.removeView(ghostView); } catch (Exception ignored) { }
        ghostView = null;
    }

    private static class GhostView extends View {
        private final Player player;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];

        GhostView(Context c, Player player) {
            super(c);
            this.player = player;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(5f);
            ring.setColor(Color.argb(200, 226, 94, 107));
            fill.setColor(Color.argb(70, 226, 94, 107));
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            Pattern p = player.pattern();
            if (!player.isPlaying() || p == null) return;
            getLocationOnScreen(loc);
            long t = player.elapsedInRep();
            for (Pattern.Touch touch : p.touches) {
                if (t >= touch.down() && t <= touch.up()) {
                    float[] xy = touch.at(t);
                    c.drawCircle(xy[0] - loc[0], xy[1] - loc[1], 30f, fill);
                    c.drawCircle(xy[0] - loc[0], xy[1] - loc[1], 30f, ring);
                }
            }
            postInvalidateOnAnimation();
        }
    }

    // ---------------------------------------------------------------- utilidades

    private WindowManager.LayoutParams overlayParams(boolean fullScreen) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (fullScreen ? WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS : 0),
                PixelFormat.TRANSLUCENT);
        if (fullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    @SuppressWarnings("deprecation")
    private Point screenSize() {
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
