package cl.gcruz.patrontouch;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
 * Servicio de accesibilidad: panel flotante con selector de brawler y modo, botones que
 * ejecutan cada patrón una vez, espejo, calibración de controles, burbuja minimizada y
 * atajos de volumen (+ repite el último patrón, − detiene).
 */
public class TouchService extends AccessibilityService implements Player.Listener {

    private static TouchService instance;

    public static TouchService get() { return instance; }

    private static final int CORAL = Color.parseColor("#E25E6B");
    private static final int AZUL = Color.parseColor("#406AB4");
    private static final int VERDE = Color.parseColor("#73C96A");
    private static final int GRAFITO = Color.parseColor("#454A51");
    private static final int GRIS = Color.parseColor("#969798");
    private static final String CUSTOM_ID = "_custom";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;
    private PatternStore store;
    private Player player;
    private Library lib;
    private Calibration cal;

    private WindowManager.LayoutParams panelParams;
    private LinearLayout panel;
    private TextView bubble;
    private TextView statusTv, brawlerChip, modeChip, mirrorChip;
    private GridLayout grid;
    private View pickerWindow;

    private Runnable lastRun;
    private String lastName;
    private TextView runningBtn;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        store = new PatternStore(this);
        player = new Player(this, this);
        lib = Library.load(this);
        Point s = screenSize();
        cal = new Calibration(this, s.x, s.y);
        showPanel();
        checkForUpdates(false);
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
        removeCalibration();
        closePicker();
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
        boolean panelOn = panel != null || bubble != null;
        if (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP && panelOn && lastRun != null
                && prefs.getBoolean("volShortcut", true)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) lastRun.run();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- estado público (MainActivity)

    public boolean isPanelVisible() { return panel != null || bubble != null; }

    public void refresh() {
        if (panel != null) rebuildPatterns();
    }

    public void reloadLibrary(Library l) {
        lib = l;
        refresh();
    }

    public Library library() { return lib; }

    public Calibration calibration() { return cal; }

    // ---------------------------------------------------------------- panel

    @SuppressLint("ClickableViewAccessibility")
    public void showPanel() {
        if (wm == null || panel != null) return;
        removeBubble();

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(round(Color.argb(240, 255, 255, 255), 18, Color.argb(70, 0, 0, 0)));
        panel.setElevation(dp(4));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView handle = chip("⠿", Color.TRANSPARENT, GRAFITO);
        brawlerChip = chip("", AZUL, Color.WHITE);
        modeChip = chip("", Color.parseColor("#EEF1F6"), GRAFITO);
        mirrorChip = chip("⇄", Color.parseColor("#EEF1F6"), GRAFITO);
        TextView calChip = chip("◎", Color.parseColor("#EEF1F6"), GRAFITO);
        TextView minChip = chip("–", Color.parseColor("#EEF1F6"), GRAFITO);
        TextView closeChip = chip("✕", Color.parseColor("#FBE7E9"), CORAL);

        header.addView(handle);
        header.addView(brawlerChip);
        header.addView(modeChip);
        header.addView(mirrorChip);
        header.addView(calChip);
        header.addView(minChip);
        header.addView(closeChip);

        statusTv = new TextView(this);
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusTv.setTextColor(GRIS);
        statusTv.setPadding(dp(8), dp(2), dp(8), dp(2));
        statusTv.setMaxWidth(dp(420));

        grid = new GridLayout(this);
        grid.setColumnCount(3);

        panel.addView(header);
        panel.addView(statusTv);
        panel.addView(grid);

        brawlerChip.setOnClickListener(v -> pickBrawler());
        modeChip.setOnClickListener(v -> pickMode());
        mirrorChip.setOnClickListener(v -> {
            prefs.edit().putBoolean("mirror", !prefs.getBoolean("mirror", false)).apply();
            updateHeader();
            toast(prefs.getBoolean("mirror", false) ? "Espejo activado: izquierda ⇄ derecha" : "Espejo desactivado");
        });
        calChip.setOnClickListener(v -> startCalibration());
        minChip.setOnClickListener(v -> minimize());
        closeChip.setOnClickListener(v -> {
            player.stop(null);
            hidePanel();
            toast("Panel cerrado. Ábrelo de nuevo desde la app Patrón Touch.");
        });
        handle.setOnTouchListener(dragListener(null));

        if (panelParams == null) {
            panelParams = overlayParams(false);
            panelParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
            panelParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            panelParams.gravity = Gravity.TOP | Gravity.START;
            panelParams.x = dp(12);
            panelParams.y = dp(60);
        }
        updateHeader();
        rebuildPatterns();
        wm.addView(panel, panelParams);
    }

    public void hidePanel() {
        if (panel != null && wm != null) try { wm.removeView(panel); } catch (Exception ignored) { }
        panel = null;
        runningBtn = null;
        removeBubble();
    }

    private void minimize() {
        if (panel != null) try { wm.removeView(panel); } catch (Exception ignored) { }
        panel = null;
        runningBtn = null;
        bubble = chip("PT", AZUL, Color.WHITE);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        bubble.setMinWidth(dp(44));
        bubble.setMinHeight(dp(44));
        ((GradientDrawable) bubble.getBackground()).setCornerRadius(dp(22));
        bubble.setOnTouchListener(dragListener(() -> showPanel()));
        wm.addView(bubble, panelParams);
    }

    private void removeBubble() {
        if (bubble != null && wm != null) try { wm.removeView(bubble); } catch (Exception ignored) { }
        bubble = null;
    }

    /** Arrastra la ventana del panel/burbuja; si fue un toque corto sin moverse, ejecuta onTap. */
    private View.OnTouchListener dragListener(Runnable onTap) {
        return new View.OnTouchListener() {
            float sx, sy;
            int px, py;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        px = panelParams.x;
                        py = panelParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        panelParams.x = px + (int) dx;
                        panelParams.y = py + (int) dy;
                        View w = panel != null ? panel : bubble;
                        if (w != null) wm.updateViewLayout(w, panelParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && onTap != null) onTap.run();
                        return true;
                }
                return false;
            }
        };
    }

    private void updateHeader() {
        if (panel == null) return;
        String bid = prefs.getString("brawler", null);
        if (CUSTOM_ID.equals(bid)) {
            brawlerChip.setText("Mis patrones ▾");
        } else {
            Library.Brawler b = lib.brawler(bid);
            brawlerChip.setText((b == null ? "Brawler" : b.name) + " ▾");
        }
        Library.Mode m = lib.mode(prefs.getString("mode", null));
        modeChip.setText((m == null ? "Modo" : m.name) + " ▾");
        modeChip.setVisibility(CUSTOM_ID.equals(bid) ? View.GONE : View.VISIBLE);
        boolean mir = prefs.getBoolean("mirror", false);
        ((GradientDrawable) mirrorChip.getBackground()).setColor(mir ? VERDE : Color.parseColor("#EEF1F6"));
        mirrorChip.setTextColor(mir ? Color.WHITE : GRAFITO);
    }

    /** Botones de patrones para el brawler y modo elegidos. */
    private void rebuildPatterns() {
        if (grid == null) return;
        grid.removeAllViews();
        runningBtn = null;
        String bid = prefs.getString("brawler", null);

        if (CUSTOM_ID.equals(bid)) {
            List<String> ids = store.ids();
            for (String id : ids) {
                Pattern p = store.load(id);
                if (p == null) continue;
                addPatternButton(p.name, "Patrón propio · " + String.format("%.1f s", p.duration / 1000f),
                        () -> p);
            }
            setStatus(ids.isEmpty() ? "No tienes patrones propios. Pégalos desde la app." : "Tus patrones importados");
            return;
        }

        Library.Brawler b = lib.brawler(bid);
        Library.Mode m = lib.mode(prefs.getString("mode", null));
        if (b == null) {
            setStatus("Biblioteca vacía: actualízala desde la app");
            return;
        }
        String modeId = m == null ? "all" : m.id;
        int n = 0;
        for (Library.LibPattern lp : b.patterns) {
            if (!lp.appliesTo(modeId)) continue;
            addPatternButton(lp.name, lp.desc, () -> Library.compile(lp, cal, prefs.getBoolean("mirror", false)));
            n++;
        }
        String tip = b.tip == null || b.tip.isEmpty() ? "" : " · " + b.tip;
        setStatus(cal.calibrated ? n + " patrones" + tip
                : "⚠ Toca ◎ para calibrar tus controles (con el juego abierto)");
    }

    private interface PatternSupplier {
        Pattern get();
    }

    private void addPatternButton(String name, String desc, PatternSupplier supplier) {
        TextView btn = chip(name, Color.parseColor("#F6F7F9"), GRAFITO);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ((GradientDrawable) btn.getBackground()).setStroke(dp(1), Color.parseColor("#DADDE2"));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        btn.setLayoutParams(lp);
        Runnable run = () -> {
            if (player.isPlaying()) {
                player.stop(null);
                return;
            }
            Pattern p = supplier.get();
            if (p == null || p.touches.isEmpty()) {
                toast("Este patrón no tiene acciones con tus controles calibrados");
                return;
            }
            lastRun = this.lastRunFor(name, supplier);
            lastName = name;
            runningBtn = btn;
            player.start(p, 1, 0);
        };
        btn.setOnClickListener(v -> run.run());
        btn.setOnLongClickListener(v -> {
            toast(name + ": " + (desc == null || desc.isEmpty() ? "sin descripción" : desc));
            return true;
        });
        grid.addView(btn);
    }

    private Runnable lastRunFor(String name, PatternSupplier supplier) {
        return () -> {
            if (player.isPlaying()) player.stop(null);
            Pattern p = supplier.get();
            if (p != null && !p.touches.isEmpty()) {
                lastName = name;
                player.start(p, 1, 0);
            }
        };
    }

    private void setStatus(String s) {
        if (statusTv != null) statusTv.setText(s);
    }

    // ---------------------------------------------------------------- selectores

    private void pickBrawler() {
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Library.Brawler b : lib.brawlers) {
            labels.add(b.name + (b.role.isEmpty() ? "" : "  ·  " + b.role));
            ids.add(b.id);
        }
        labels.add("★ Mis patrones");
        ids.add(CUSTOM_ID);
        showPicker("Elige brawler", labels, i -> {
            prefs.edit().putString("brawler", ids.get(i)).apply();
            updateHeader();
            rebuildPatterns();
        });
    }

    private void pickMode() {
        List<String> labels = new ArrayList<>();
        for (Library.Mode m : lib.modes) labels.add(m.name);
        showPicker("Elige modo de juego", labels, i -> {
            prefs.edit().putString("mode", lib.modes.get(i).id).apply();
            updateHeader();
            rebuildPatterns();
        });
    }

    private interface Pick {
        void on(int index);
    }

    private void showPicker(String title, List<String> items, Pick pick) {
        closePicker();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(round(Color.WHITE, 16, Color.argb(60, 0, 0, 0)));
        box.setElevation(dp(8));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(GRAFITO);
        t.setPadding(dp(8), dp(4), dp(8), dp(8));
        box.addView(t);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            TextView row = new TextView(this);
            row.setText(items.get(i));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setTextColor(GRAFITO);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setOnClickListener(v -> {
                closePicker();
                pick.on(idx);
            });
            list.addView(row);
        }
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(dp(300), Math.min(dp(260), dp(44) * items.size())));
        TextView cancel = chip("Cancelar", Color.parseColor("#EEF1F6"), GRAFITO);
        cancel.setOnClickListener(v -> closePicker());
        box.addView(cancel);

        WindowManager.LayoutParams lp = overlayParams(false);
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        pickerWindow = box;
        wm.addView(box, lp);
    }

    private void closePicker() {
        if (pickerWindow != null && wm != null) try { wm.removeView(pickerWindow); } catch (Exception ignored) { }
        pickerWindow = null;
    }

    // ---------------------------------------------------------------- reproducción

    @Override
    public float[] releasePoint() {
        View w = panel != null ? panel : bubble;
        if (w != null) {
            int[] loc = new int[2];
            View target = panel != null && panel.getChildCount() > 0
                    ? ((LinearLayout) panel.getChildAt(0)).getChildAt(0) : w;
            target.getLocationOnScreen(loc);
            return new float[]{loc[0] + target.getWidth() / 2f, loc[1] + target.getHeight() / 2f};
        }
        return new float[]{screenSize().x / 2f, 1f};
    }

    @Override
    public void onPlayerState(boolean playing, String status) {
        if (playing) showGhost(); else hideGhost();
        if (runningBtn != null) {
            ((GradientDrawable) runningBtn.getBackground()).setColor(
                    playing ? GRAFITO : Color.parseColor("#F6F7F9"));
            runningBtn.setTextColor(playing ? Color.WHITE : GRAFITO);
        }
        if (playing) {
            setStatus("▶ " + (lastName == null ? "" : lastName) + "  ·  toca de nuevo o volumen − para parar");
        } else {
            if (runningBtn != null) runningBtn = null;
            if (panel != null) rebuildPatterns();
            if (status != null && !status.startsWith("Listo")) toast(status);
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
        closePicker();
        if (calibView != null) return;
        Point sz = screenSize();
        if (sz.x < sz.y) {
            toast("Abre el juego en horizontal y calibra desde ahí");
            return;
        }
        if (panel != null) try { wm.removeView(panel); } catch (Exception ignored) { }
        panel = null;

        calibStep = 0;
        calibPos = new float[Calibration.KEYS.length][];
        calibView = new CalibView(this);
        WindowManager.LayoutParams lp = overlayParams(true);
        wm.addView(calibView, lp);

        calibBar = new LinearLayout(this);
        calibBar.setOrientation(LinearLayout.HORIZONTAL);
        calibBar.setGravity(Gravity.CENTER_VERTICAL);
        calibBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        calibBar.setBackground(round(Color.argb(245, 255, 255, 255), 16, 0));
        calibText = new TextView(this);
        calibText.setTextColor(GRAFITO);
        calibText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        calibText.setPadding(dp(6), 0, dp(10), 0);
        calibSkip = chip("Omitir", Color.parseColor("#EEF1F6"), GRAFITO);
        TextView cancel = chip("Cancelar", Color.parseColor("#FBE7E9"), CORAL);
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

    // ---------------------------------------------------------------- biblioteca

    /** Descarga la biblioteca en segundo plano; si manual, avisa el resultado. */
    public void checkForUpdates(boolean manual) {
        new Thread(() -> {
            try {
                Library l = Library.download(this);
                main.post(() -> {
                    boolean newer = l.version > lib.version;
                    lib = l;
                    refresh();
                    updateHeader();
                    if (manual || newer) toast("Biblioteca actualizada (v" + l.version + ")");
                });
            } catch (Exception e) {
                if (manual) main.post(() -> toast("No pude actualizar: revisa tu conexión"));
            }
        }).start();
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

    // ---------------------------------------------------------------- utilidades

    /** Ventana de accesibilidad; fullScreen = toda la pantalla incluidos bordes/recorte. */
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

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private TextView chip(String text, int bgColor, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(11), dp(7), dp(11), dp(7));
        tv.setBackground(round(bgColor, 16, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        tv.setLayoutParams(lp);
        return tv;
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
