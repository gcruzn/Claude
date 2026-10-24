package cl.gcruz.cerebro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Oído y boca del cerebro. Servicio en primer plano que:
 * 1. escucha "oye cerebro" con Vosk (en el dispositivo, gramática restringida);
 * 2. dice "dime", captura la orden con SpeechRecognizer hasta el silencio;
 * 3. la manda al PC (Net.ask), dice "voy" mientras espera y lee la respuesta.
 * Si la respuesta termina en "?" vuelve a escuchar de inmediato (sin palabra clave) para el "sí".
 */
public class CerebroService extends Service {
    static final String ACTION_ASK = "cl.gcruz.cerebro.ASK";
    static final String ACTION_STOP = "cl.gcruz.cerebro.STOP";
    static final String ACTION_RELISTEN = "cl.gcruz.cerebro.RELISTEN";
    private static final String CHANNEL = "cerebro";
    private static final String KEYWORD = "oye cerebro";

    /** La pantalla se suscribe para mostrar estado y textos. */
    interface Ui { void cambio(); }

    static volatile Ui ui;
    static volatile boolean vivo;
    static volatile String estado = "Apagado";
    static volatile String oido = "";
    static volatile String respuesta = "";
    /** La próxima orden abre conversación nueva (botón "Nueva conversación"). */
    static volatile boolean nueva = true;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextToSpeech tts;
    private boolean ttsListo;
    private Model model;
    private Recognizer recognizer;
    private Escucha keyword;
    private SpeechRecognizer sr;
    private PowerManager.WakeLock wake;
    private boolean ocupado;
    private boolean reintentoUS;
    private boolean pendienteAsk;
    private int gen;

    static void poner(String e) {
        estado = e;
        Ui u = ui;
        if (u != null) u.cambio();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        vivo = true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cerebro:escucha");
        wake.acquire();
        tts = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) { poner("No hay voz de lectura en el teléfono."); return; }
            Locale[] opciones = {new Locale("es", "CL"), new Locale("es", "US"), new Locale("es", "ES"), new Locale("es")};
            for (Locale l : opciones) {
                int r = tts.setLanguage(l);
                if (r >= TextToSpeech.LANG_AVAILABLE) break;
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { h.post(() -> terminoHabla(id)); }
                @Override public void onError(String id) { h.post(() -> terminoHabla(id)); }
            });
            ttsListo = true;
            h.post(() -> { if (pendienteAsk) { pendienteAsk = false; empezarDialogo(); } });
        });
        new Thread(this::cargarModelo, "cerebro-modelo").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ponerPrimerPlano();
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ASK.equals(a)) {
            if (ttsListo) empezarDialogo(); else pendienteAsk = true;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        vivo = false;
        gen++;
        h.removeCallbacksAndMessages(null);
        pararKeyword();
        destruirSr();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (recognizer != null) recognizer.close();
        if (model != null) model.close();
        if (wake != null && wake.isHeld()) wake.release();
        poner("Apagado");
        super.onDestroy();
    }

    // --- Primer plano -----------------------------------------------------

    private void ponerPrimerPlano() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Oye cerebro", NotificationManager.IMPORTANCE_LOW));
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent parar = PendingIntent.getService(this, 1,
                new Intent(this, CerebroService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Oye cerebro")
                .setContentText("Escuchando. Di «oye cerebro».")
                .setContentIntent(abrir)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Apagar", parar).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
    }

    // --- Palabra clave (Vosk) ---------------------------------------------

    private void cargarModelo() {
        try {
            File dir = Modelo.asegurar(this, t -> h.post(() -> { if (!ocupado) poner(t); }));
            Model m = new Model(dir.getAbsolutePath());
            Recognizer r = new Recognizer(m, 16000f, "[\"" + KEYWORD + "\", \"[unk]\"]");
            h.post(() -> {
                if (!vivo) { r.close(); m.close(); return; }
                model = m;
                recognizer = r;
                if (!ocupado) reanudar();
            });
        } catch (Exception e) {
            h.post(() -> poner("No pude preparar la voz: " + e.getMessage() + ". El botón sigue funcionando."));
        }
    }

    private void reanudar() {
        if (!prefs.listen() || recognizer == null) {
            poner(prefs.listen() ? "Preparando la voz…" : "Listo. Toca el botón para hablar.");
            return;
        }
        pararKeyword();
        keyword = new Escucha(recognizer);
        keyword.start();
        poner("Escuchando: di «oye cerebro»");
    }

    /**
     * Hilo propio con AudioRecord + Vosk Recognizer. Si el micrófono está ocupado o read() da
     * error, libera, espera y reintenta: nunca lanza al hilo (SpeechService de Vosk sí lo hacía).
     */
    private final class Escucha extends Thread {
        private final Recognizer rec;
        private volatile boolean fin;

        Escucha(Recognizer r) { super("cerebro-escucha"); rec = r; }

        void parar() {
            fin = true;
            interrupt();
            try { join(2500); } catch (InterruptedException ignored) { }
        }

        @Override public void run() {
            try { rec.reset(); } catch (Throwable ignored) { }
            short[] buf = new short[4000];
            while (!fin) {
                AudioRecord ar = null;
                try {
                    int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(min, 16000));
                    if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("micrófono no disponible");
                    ar.startRecording();
                    if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("micrófono ocupado");
                    h.post(() -> { if (!ocupado) poner("Escuchando: di «oye cerebro»"); });
                    while (!fin) {
                        int n = ar.read(buf, 0, buf.length);
                        if (n < 0) throw new IllegalStateException("lectura falló (" + n + ")");
                        if (n == 0) continue;
                        if (rec.acceptWaveForm(buf, n)) revisar(rec.getResult(), "text");
                        else revisar(rec.getPartialResult(), "partial");
                    }
                } catch (Throwable t) {
                    if (!fin) h.post(() -> { if (!ocupado) poner("Micrófono ocupado, reintento…"); });
                } finally {
                    if (ar != null) {
                        try { ar.stop(); } catch (Throwable ignored) { }
                        try { ar.release(); } catch (Throwable ignored) { }
                    }
                }
                if (fin) break;
                try { rec.reset(); } catch (Throwable ignored) { }
                try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
            }
        }
    }

    private void revisar(String json, String clave) {
        try {
            String t = new JSONObject(json).optString(clave, "");
            if (t.contains(KEYWORD)) h.post(this::empezarDialogo);
        } catch (Exception ignored) { }
    }

    private void pararKeyword() {
        if (keyword != null) {
            keyword.parar();
            keyword = null;
        }
    }

    // --- Diálogo ------------------------------------------------------------

    private void beep() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            h.postDelayed(tg::release, 400);
        } catch (Exception ignored) { }
    }

    private void decir(String texto, String id, boolean encolar) {
        if (!ttsListo) { h.post(() -> terminoHabla(id)); return; }
        tts.speak(texto, encolar ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, null, id);
    }

    private void empezarDialogo() {
        if (ocupado) return;
        ocupado = true;
        reintentoUS = false;
        pararKeyword();
        beep();
        poner("Te escucho…");
        decir("dime", "dime", false);
    }

    private void terminoHabla(String id) {
        switch (id) {
            case "dime":
                capturar("es-CL");
                break;
            case "respuesta":
                if (respuesta.trim().endsWith("?")) {
                    // Espera un "sí" o un "no": escucha directo, sin palabra clave.
                    beep();
                    capturar("es-CL");
                } else {
                    terminar();
                }
                break;
            case "aviso":
                terminar();
                break;
            default:
                break;
        }
    }

    private void capturar(String idioma) {
        destruirSr();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            hablarYTerminar("Este teléfono no tiene reconocimiento de voz.");
            return;
        }
        final int mia = ++gen;
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { poner("Habla ahora…"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int t, Bundle b) {}
            @Override public void onPartialResults(Bundle b) {
                String t = primero(b);
                if (t != null && !t.isEmpty()) { oido = t; poner("Oyendo…"); }
            }
            @Override public void onResults(Bundle b) {
                if (mia != gen) return;
                String t = primero(b);
                destruirSr();
                if (t == null || t.trim().isEmpty()) hablarYTerminar("No te escuché.");
                else enviar(t.trim());
            }
            @Override public void onError(int e) {
                if (mia != gen) return;
                destruirSr();
                if ((e == 12 || e == 13) && !reintentoUS) {   // idioma no disponible
                    reintentoUS = true;
                    capturar("es-US");
                } else if (e == 7 || e == 6) {
                    hablarYTerminar("No te escuché.");
                } else if (e == 9) {
                    hablarYTerminar("Me falta el permiso del micrófono.");
                } else {
                    hablarYTerminar("No pude escucharte. Error " + e + ".");
                }
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Habla con pausas largas: espera más silencio antes de dar por terminada la orden.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        sr.startListening(i);
        // Si nadie habla, no quedarse colgado.
        h.postDelayed(() -> {
            if (mia == gen && sr != null) { destruirSr(); hablarYTerminar("No te escuché."); }
        }, 25000);
    }

    private static String primero(Bundle b) {
        ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private void destruirSr() {
        gen++;
        if (sr != null) {
            try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { }
            sr = null;
        }
    }

    private void enviar(String texto) {
        oido = texto;
        respuesta = "";
        poner("Pensando…");
        decir("voy", "voy", false);
        final boolean n = nueva;
        nueva = false;
        new Thread(() -> {
            String r;
            try {
                r = Net.ask(this, texto, n);
            } catch (Throwable e) {
                if (n) nueva = true;
                r = e.getMessage() == null ? "Falló la conexión con el PC." : e.getMessage();
                final String msg = r;
                prefs.guardarUltimo(texto, msg);
                h.post(() -> { respuesta = msg; poner("Sin conexión"); decir(msg, "aviso", true); });
                return;
            }
            final String ok = r;
            prefs.guardarUltimo(texto, ok);
            h.post(() -> { respuesta = ok; poner("Respuesta"); decir(ok, "respuesta", true); });
        }, "cerebro-red").start();
    }

    private void hablarYTerminar(String msg) {
        respuesta = msg;
        poner(msg);
        decir(msg, "aviso", false);
    }

    private void terminar() {
        ocupado = false;
        h.postDelayed(() -> { if (!ocupado) reanudar(); }, 500);
    }
}
