package cl.gcruz.patrontouch;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de accesibilidad: muestra un panel flotante (Grabar / Reproducir / Parar),
 * un overlay transparente para grabar el patrón multi-touch, y reproduce con Player.
 * Bajar volumen detiene la reproducción en cualquier momento.
 */
public class TouchService extends AccessibilityService implements Player.Listener {

    private static TouchService instance;

    public static TouchService get() { return instance; }

    private WindowManager wm;
    private PatternStore store;
    private Player player;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private TextView playBtn, statusTv;

    private RecordView recordView;
    private TextView finishBtn;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        store = new PatternStore(this);
        player = new Player(this, this);
        showPanel();
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
        if (player != null) player.stop(null);
        hideGhost();
        removeRecordOverlay();
        hidePanel();
        instance = null;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN && player != null && player.isPlaying()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) player.stop("Detenido con volumen −");
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- panel flotante

    public boolean isPanelVisible() { return panel != null; }

    @SuppressLint("ClickableViewAccessibility")
    public void showPanel() {
        if (panel != null || wm == null) return;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 255, 255, 255));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Color.argb(80, 0, 0, 0));
        panel.setBackground(bg);
        panel.setElevation(dp(4));

        TextView handle = chip("⠿", Color.TRANSPARENT, Color.DKGRAY);
        TextView recBtn = chip("⏺ Grabar", Color.parseColor("#E25E6B"), Color.WHITE);
        playBtn = chip("▶ Repetir", Color.parseColor("#406AB4"), Color.WHITE);
        statusTv = new TextView(this);
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusTv.setTextColor(Color.parseColor("#454A51"));
        statusTv.setPadding(dp(8), 0, dp(6), 0);
        statusTv.setMaxWidth(dp(150));
        updateStatusIdle();

        panel.addView(handle);
        panel.addView(recBtn);
        panel.addView(playBtn);
        panel.addView(statusTv);

        recBtn.setOnClickListener(v -> startRecording());
        playBtn.setOnClickListener(v -> togglePlay());

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = dp(12);
        panelParams.y = dp(80);

        // Arrastrar el panel desde el ícono ⠿
        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int px, py;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        px = panelParams.x;
                        py = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelParams.x = px + (int) (e.getRawX() - sx);
                        panelParams.y = py + (int) (e.getRawY() - sy);
                        if (panel != null) wm.updateViewLayout(panel, panelParams);
                        return true;
                }
                return false;
            }
        });

        wm.addView(panel, panelParams);
    }

    public void hidePanel() {
        if (panel != null && wm != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
        }
        panel = null;
    }

    private TextView chip(String text, int bgColor, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(18));
        tv.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void updateStatusIdle() {
        if (statusTv == null) return;
        Pattern p = store.load(store.getActive());
        if (p == null) {
            statusTv.setText("Sin patrón");
        } else {
            int r = store.getRepeats();
            statusTv.setText(p.name + " · " + String.format("%.1fs", p.duration / 1000f)
                    + " · " + (r == 0 ? "∞" : r + "x"));
        }
    }

    public void refresh() {
        if (player == null || !player.isPlaying()) updateStatusIdle();
    }

    // ---------------------------------------------------------------- reproducción

    private void togglePlay() {
        if (player.isPlaying()) {
            player.stop(null);
            return;
        }
        Pattern p = store.load(store.getActive());
        if (p == null) {
            toast("Primero graba un patrón");
            return;
        }
        Point size = screenSize();
        boolean nowLandscape = size.x > size.y;
        boolean recLandscape = p.screenW > p.screenH;
        if (p.screenW > 0 && nowLandscape != recLandscape) {
            toast("Ojo: el patrón se grabó en " + (recLandscape ? "horizontal" : "vertical"));
        }
        player.start(p, store.getRepeats(), store.getPauseMs());
    }

    @Override
    public void onPlayerState(boolean playing, String status) {
        if (playing) showGhost(); else hideGhost();
        if (playBtn != null) {
            playBtn.setText(playing ? "■ Parar" : "▶ Repetir");
            ((GradientDrawable) playBtn.getBackground()).setColor(
                    Color.parseColor(playing ? "#454A51" : "#406AB4"));
        }
        if (statusTv != null) {
            if (playing) statusTv.setText(status);
            else {
                updateStatusIdle();
                if (status != null) toast(status);
            }
        }
    }

    // ---------------------------------------------------------------- grabación

    private void startRecording() {
        if (player.isPlaying()) player.stop(null);
        if (recordView != null) return;
        hidePanel();

        recordView = new RecordView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(recordView, lp);

        // Botón "Terminar" en su propia ventana, encima del overlay, para que sus toques no se graben.
        finishBtn = chip("■ Terminar grabación", Color.parseColor("#454A51"), Color.WHITE);
        finishBtn.setOnClickListener(v -> finishRecording());
        WindowManager.LayoutParams flp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        flp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        flp.y = dp(16);
        wm.addView(finishBtn, flp);

        toast("Grabando: haz el patrón con los dedos que quieras");
    }

    private void finishRecording() {
        if (recordView == null) return;
        Pattern p = recordView.buildPattern();
        removeRecordOverlay();
        showPanel();
        if (p.touches.isEmpty()) {
            toast("No se grabó ningún toque");
            return;
        }
        Point size = screenSize();
        p.screenW = size.x;
        p.screenH = size.y;
        store.saveNew(p);
        updateStatusIdle();
        toast("Guardado: " + p.name + " · vista previa…");
        // Vista previa: se reproduce una vez sola para que veas cómo se mueve el personaje.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> player.start(p, 1, 0), 600);
    }

    // ---------------------------------------------------------------- dedos virtuales

    private GhostView ghostView;

    /** Capa NO táctil que dibuja dónde está cada dedo virtual mientras se reproduce. */
    private void showGhost() {
        if (ghostView != null || wm == null) return;
        ghostView = new GhostView(this, player);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try { wm.addView(ghostView, lp); } catch (Exception e) { ghostView = null; }
    }

    private void hideGhost() {
        if (ghostView != null && wm != null) {
            try { wm.removeView(ghostView); } catch (Exception ignored) { }
        }
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
            ring.setColor(Color.argb(230, 226, 94, 107));
            fill.setColor(Color.argb(90, 226, 94, 107));
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
                    c.drawCircle(xy[0] - loc[0], xy[1] - loc[1], 34f, fill);
                    c.drawCircle(xy[0] - loc[0], xy[1] - loc[1], 34f, ring);
                }
            }
            postInvalidateOnAnimation();
        }
    }

    private void removeRecordOverlay() {
        if (wm == null) return;
        if (recordView != null) try { wm.removeView(recordView); } catch (Exception ignored) { }
        if (finishBtn != null) try { wm.removeView(finishBtn); } catch (Exception ignored) { }
        recordView = null;
        finishBtn = null;
    }

    /** Overlay a pantalla completa que captura cada dedo con su trayectoria y tiempos. */
    private static class RecordView extends View {
        private final SparseArray<Pattern.Touch> active = new SparseArray<>();
        private final List<Pattern.Touch> done = new ArrayList<>();
        private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];
        private long lastEventTime;

        RecordView(Context c) {
            super(c);
            setBackgroundColor(Color.argb(40, 64, 106, 180));
            trail.setStyle(Paint.Style.STROKE);
            trail.setStrokeWidth(6f);
            trail.setColor(Color.argb(170, 226, 94, 107));
            dot.setColor(Color.argb(200, 115, 201, 106));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            getLocationOnScreen(loc);
            lastEventTime = e.getEventTime();
            int action = e.getActionMasked();
            int idx = e.getActionIndex();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    Pattern.Touch t = new Pattern.Touch();
                    t.pts.add(pt(e.getEventTime(), e, idx));
                    active.put(e.getPointerId(idx), t);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    // Incluye las muestras históricas para no perder precisión en movimientos rápidos.
                    for (int h = 0; h < e.getHistorySize(); h++) {
                        for (int i = 0; i < e.getPointerCount(); i++) {
                            Pattern.Touch t = active.get(e.getPointerId(i));
                            if (t != null) t.pts.add(new Pattern.Point(e.getHistoricalEventTime(h),
                                    e.getHistoricalX(i, h) + loc[0], e.getHistoricalY(i, h) + loc[1]));
                        }
                    }
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        Pattern.Touch t = active.get(e.getPointerId(i));
                        if (t != null) t.pts.add(pt(e.getEventTime(), e, i));
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int id = e.getPointerId(idx);
                    Pattern.Touch t = active.get(id);
                    if (t != null) {
                        t.pts.add(pt(e.getEventTime(), e, idx));
                        done.add(t);
                        active.remove(id);
                    }
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    closeActive(e.getEventTime());
                    break;
            }
            invalidate();
            return true;
        }

        private Pattern.Point pt(long time, MotionEvent e, int i) {
            return new Pattern.Point(time, e.getX(i) + loc[0], e.getY(i) + loc[1]);
        }

        private void closeActive(long time) {
            for (int i = 0; i < active.size(); i++) {
                Pattern.Touch t = active.valueAt(i);
                Pattern.Point last = t.pts.get(t.pts.size() - 1);
                t.pts.add(new Pattern.Point(Math.max(time, last.t), last.x, last.y));
                done.add(t);
            }
            active.clear();
        }

        Pattern buildPattern() {
            closeActive(lastEventTime);
            Pattern p = new Pattern();
            p.touches.addAll(done);
            p.normalize();
            return p;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            getLocationOnScreen(loc);
            drawTouches(c, done);
            List<Pattern.Touch> act = new ArrayList<>();
            for (int i = 0; i < active.size(); i++) act.add(active.valueAt(i));
            drawTouches(c, act);
            for (Pattern.Touch t : act) {
                Pattern.Point p = t.pts.get(t.pts.size() - 1);
                c.drawCircle(p.x - loc[0], p.y - loc[1], 28f, dot);
            }
        }

        private void drawTouches(Canvas c, List<Pattern.Touch> list) {
            for (Pattern.Touch t : list) {
                Pattern.Point prev = null;
                for (Pattern.Point p : t.pts) {
                    if (prev != null) {
                        c.drawLine(prev.x - loc[0], prev.y - loc[1], p.x - loc[0], p.y - loc[1], trail);
                    }
                    prev = p;
                }
                if (t.pts.size() == 1) {
                    Pattern.Point p = t.pts.get(0);
                    c.drawCircle(p.x - loc[0], p.y - loc[1], 10f, trail);
                }
            }
        }
    }

    // ---------------------------------------------------------------- utilidades

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
