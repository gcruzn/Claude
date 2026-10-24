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
import android.media.MediaPlayer;
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
import org.vosk.SpeakerModel;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Oído y boca del cerebro. Servicio en primer plano que:
 * 1. escucha "jarvis" con Vosk (en el dispositivo, gramática restringida);
 * 2. dice "dime", captura la orden con SpeechRecognizer hasta el silencio;
 * 3. la manda al PC (Net.ask), dice "voy" mientras espera y lee la respuesta.
 * Si la respuesta termina en "?" vuelve a escuchar de inmediato (sin palabra clave) para el "sí".
 */
public class CerebroService extends Service {
    static final String ACTION_ASK = "cl.gcruz.cerebro.ASK";
    static final String ACTION_STOP = "cl.gcruz.cerebro.STOP";
    static final String ACTION_ENSENAR = "cl.gcruz.cerebro.ENSENAR";
    static final String ACTION_ENSENAR_FIN = "cl.gcruz.cerebro.ENSENAR_FIN";
    static final String ACTION_REFRESH = "cl.gcruz.cerebro.REFRESH";
    private static final String CHANNEL = "cerebro";
    static final String KEYWORD = "jarvis";

    /** La pantalla se suscribe para mostrar estado y textos. */
    interface Ui { void cambio(); }

    static volatile Ui ui;
    static volatile boolean vivo;
    static volatile String estado = "Apagado";
    static volatile String oido = "";
    static volatile String respuesta = "";
    static volatile String tareasTexto = "";
    /** La próxima orden abre conversación nueva (botón "Nueva conversación"). */
    static volatile boolean nueva = true;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextToSpeech tts;
    private boolean ttsListo;
    private Model model;
    private Recognizer recognizer;
    private SpeakerModel spk;
    private VozDueno duenoVoz;
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
        duenoVoz = new VozDueno(this);
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
            Voces.aplicar(tts, prefs);
            ttsListo = true;
            h.post(() -> { if (pendienteAsk) { pendienteAsk = false; empezarDialogo(); } });
        });
        new Thread(this::cargarModelo, "cerebro-modelo").start();
        sondear(1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ponerPrimerPlano();
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(a)) sondear(0);
        if (ACTION_ENSENAR.equals(a)) h.post(this::iniciarEnsenanza);
        if (ACTION_ENSENAR_FIN.equals(a)) h.post(() -> terminarEnsenanza(true));
        if (ACTION_ASK.equals(a)) {
            if (ttsListo) empezarDialogo(); else pendienteAsk = true;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        vivo = false;
        gen++;
        capIdioma = null;
        h.removeCallbacksAndMessages(null);
        pararKeyword();
        destruirSr();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (reproductor != null) { try { reproductor.release(); } catch (Exception ignored) { } }
        if (recognizer != null) recognizer.close();
        if (model != null) model.close();
        if (wake != null && wake.isHeld()) wake.release();
        poner("Apagado");
        super.onDestroy();
    }

    // --- Primer plano -----------------------------------------------------

    private void ponerPrimerPlano() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW));
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent parar = PendingIntent.getService(this, 1,
                new Intent(this, CerebroService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis")
                .setContentText("Escuchando. Di «Jarvis».")
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

    // Gramática: la clave más señuelos (palabras parecidas y muy comunes) para que el habla ajena
    // caiga en ellas y no en «jarvis».
    private static final String[] SENUELOS = {
        "jardín", "jarra", "jarabe", "jarro", "jaime", "javier", "jabón", "jamás", "carlos",
        "marcos", "lápiz", "parís", "martes", "tarde", "bárbara",
        "verde", "el", "la", "los", "las", "que", "sí", "no", "un", "una", "de", "y", "en", "es",
        "por", "con", "para", "mi", "me", "te", "lo", "ya", "si", "muy", "más", "pero", "como",
        "aquí", "bien", "hola", "bueno", "vamos", "mira", "oye", "oiga", "hoy", "ojo",
        "pero", "dime", "eso", "está", "tiene", "hay", "todo", "nada", "algo", "ver", "oír"
    };

    private static String gramatica() {
        StringBuilder sb = new StringBuilder("[\"" + KEYWORD + "\"");
        for (String w : SENUELOS) sb.append(", \"").append(w).append("\"");
        return sb.append(", \"[unk]\"]").toString();
    }

    private void cargarModelo() {
        try {
            File dir = Modelo.espanol(this, t -> h.post(() -> { if (!ocupado) poner(t); }));
            Model m = new Model(dir.getAbsolutePath());
            SpeakerModel sm = null;
            try {
                File sd = Modelo.hablante(this, t -> h.post(() -> { if (!ocupado) poner(t); }));
                sm = new SpeakerModel(sd.getAbsolutePath());
            } catch (Throwable e) {
                // Sin modelo de hablante la palabra clave funciona igual, sin filtro de voz.
                new Registro(this).anotar("sin modelo de hablante: " + e.getMessage());
            }
            Recognizer r = new Recognizer(m, 16000f, gramatica());
            r.setWords(true);
            if (sm != null) r.setSpeakerModel(sm);
            final SpeakerModel fsm = sm;
            h.post(() -> {
                if (!vivo) { r.close(); m.close(); return; }
                model = m;
                spk = fsm;
                recognizer = r;
                if (!ocupado) reanudar();
            });
        } catch (Throwable e) {
            h.post(() -> poner("No pude preparar la voz: " + e.getMessage() + ". El botón sigue funcionando."));
        }
    }

    private void reanudar() {
        if ((!prefs.listen() && !modoEnsenar) || recognizer == null) {
            poner(prefs.listen() ? "Preparando la voz…" : "Listo. Toca el botón para hablar.");
            return;
        }
        pararKeyword();
        keyword = new Escucha(recognizer);
        keyword.start();
        poner("Escuchando: di «Jarvis»");
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
                    h.post(() -> { if (!ocupado) poner("Escuchando: di «Jarvis»"); });
                    while (!fin) {
                        int n = ar.read(buf, 0, buf.length);
                        if (n < 0) throw new IllegalStateException("lectura falló (" + n + ")");
                        if (n == 0) continue;
                        if (rec.acceptWaveForm(buf, n)) resultado(rec.getResult());
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

    /**
     * Resultado final del reconocedor (en el hilo de escucha). Acepta la palabra clave solo si
     * es exactamente «jarvis», con confianza alta en cada palabra y, si Gonzalo enseñó su
     * voz, con huella parecida a alguna de sus muestras. Todo queda en el registro.
     */
    private void resultado(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String text = o.optString("text", "").trim();
            if (!text.contains(KEYWORD)) return;   // ruido: ni se registra
            org.json.JSONArray ws = o.optJSONArray("result");
            double minConf = 1;
            StringBuilder cs = new StringBuilder();
            if (ws != null) {
                for (int i = 0; i < ws.length(); i++) {
                    JSONObject w = ws.getJSONObject(i);
                    double c = w.optDouble("conf", 0);
                    minConf = Math.min(minConf, c);
                    cs.append(w.optString("word")).append(String.format(Locale.US, " %.2f ", c));
                }
            }
            double[] huella = null;
            org.json.JSONArray sp = o.optJSONArray("spk");
            if (sp != null) {
                huella = new double[sp.length()];
                for (int i = 0; i < huella.length; i++) huella[i] = sp.getDouble(i);
            }
            if (modoEnsenar) { muestra(text, minConf, huella); return; }
            double umbral = prefs.umbralConfianza();
            String motivo = null;
            String sim = "voz -";
            if (!text.equals(KEYWORD)) motivo = "texto";
            else if (minConf < umbral) motivo = String.format(Locale.US, "confianza %.2f<%.2f", minConf, umbral);
            else if (duenoVoz.recargar() > 0) {
                // «Jarvis» es corto: Vosk a veces no alcanza a sacar la huella. Sin huella no hay con
                // qué comparar, así que se acepta (antes se rechazaba y bloqueaba 3 de cada 5).
                if (huella == null) sim = "voz sin huella";
                else {
                    double s = duenoVoz.maxSimilitud(huella);
                    sim = String.format(Locale.US, "voz %.2f", s);
                    if (s < prefs.umbralVoz() / 100.0) motivo = String.format(Locale.US, "voz %.2f<%.2f", s, prefs.umbralVoz() / 100.0);
                }
            }
            new Registro(this).anotar("«" + text + "» | " + cs.toString().trim() + " | " + sim + " | "
                    + (motivo == null ? "ACEPTADA" : "rechazada (" + motivo + ")"));
            if (motivo == null) h.post(this::empezarDialogo);
        } catch (Exception ignored) { }
    }

    // --- Enseñar la voz -----------------------------------------------------

    static final int MUESTRAS = 6;
    static volatile boolean modoEnsenar;
    static volatile String ensenarMsg = "";
    static volatile int ensenarHechas;
    private final java.util.List<double[]> nuevasMuestras = new ArrayList<>();

    private void iniciarEnsenanza() {
        if (spk == null) {
            ensenarMsg = recognizer == null ? "Todavía estoy bajando la voz. Espera un momento y vuelve a entrar."
                    : "No tengo el modelo de reconocimiento de voz. Revisa internet y reinicia la app.";
            avisarUi();
            return;
        }
        if (ocupado) { ensenarMsg = "Estoy ocupado con un pedido. Intenta en un momento."; avisarUi(); return; }
        nuevasMuestras.clear();
        ensenarHechas = 0;
        ensenarMsg = "Di «Jarvis»";
        modoEnsenar = true;
        reanudar();
        avisarUi();
    }

    private void terminarEnsenanza(boolean guardar) {
        modoEnsenar = false;
        if (guardar && !nuevasMuestras.isEmpty()) {
            try {
                duenoVoz.agregar(new ArrayList<>(nuevasMuestras));
                ensenarMsg = "Listo. Guardé " + nuevasMuestras.size() + " muestras. Ya tengo " + duenoVoz.cantidad() + " en total.";
            } catch (Exception e) {
                ensenarMsg = "No pude guardar las muestras: " + e.getMessage();
            }
        } else if (!guardar) {
            ensenarMsg = nuevasMuestras.isEmpty() ? "Cancelado." : "Cancelado, no guardé nada.";
        }
        nuevasMuestras.clear();
        avisarUi();
        h.post(() -> { if (!ocupado) reanudar(); });
    }

    /** Una repetición de «jarvis» durante la enseñanza (hilo de escucha). */
    private void muestra(String text, double minConf, double[] huella) {
        if (!text.equals(KEYWORD) || huella == null || minConf < 0.8) {
            h.post(() -> { ensenarMsg = "No te entendí bien. Di «Jarvis» otra vez"; avisarUi(); });
            return;
        }
        new Registro(this).anotar("muestra de voz " + (nuevasMuestras.size() + 1) + " | conf " + String.format(Locale.US, "%.2f", minConf));
        h.post(() -> {
            nuevasMuestras.add(huella);
            ensenarHechas = nuevasMuestras.size();
            beep();
            if (ensenarHechas >= MUESTRAS) terminarEnsenanza(true);
            else { ensenarMsg = "Muy bien. Otra vez: «Jarvis»"; avisarUi(); }
        });
    }

    private void avisarUi() {
        Ui u = ui;
        if (u != null) u.cambio();
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

    private boolean vozRemota;   // el servidor ofrece voz personalizada (apagada por defecto)
    private MediaPlayer reproductor;

    private void decir(String texto, String id, boolean encolar) {
        decir(texto, id, encolar, null);
    }

    /** Habla con la voz personalizada del servidor si existe; si no o si falla, con TextToSpeech. */
    private void decir(String texto, String id, boolean encolar, String audioUrl) {
        boolean remota = audioUrl != null || (vozRemota && !id.equals("dime") && !id.equals("voy"));
        if (!remota) { hablarTts(texto, id, encolar); return; }
        final String url = audioUrl;
        new Thread(() -> {
            File f = Net.audio(this, url != null ? url : texto, url != null);
            h.post(() -> { if (f == null || !reproducir(f, id)) hablarTts(texto, id, encolar); });
        }, "cerebro-voz").start();
    }

    private boolean reproducir(File f, String id) {
        try {
            if (reproductor != null) { try { reproductor.release(); } catch (Exception ignored) { } }
            final MediaPlayer mp = new MediaPlayer();
            reproductor = mp;
            mp.setDataSource(f.getPath());
            mp.setOnCompletionListener(m -> { m.release(); f.delete(); h.post(() -> terminoHabla(id)); });
            mp.setOnErrorListener((m, w, e) -> { m.release(); f.delete(); h.post(() -> terminoHabla(id)); return true; });
            mp.prepare();
            mp.start();
            return true;
        } catch (Exception e) {
            f.delete();
            return false;
        }
    }

    private void hablarTts(String texto, String id, boolean encolar) {
        if (!ttsListo) { h.post(() -> terminoHabla(id)); return; }
        Voces.aplicar(tts, prefs);
        tts.speak(texto, encolar ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, null, id);
    }

    private void empezarDialogo() {
        if (ocupado) return;
        ocupado = true;
        reintentoUS = false;
        capRepetido = false;
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
                    // Espera una respuesta (sí/no, cuál tarea): escucha directo, sin palabra clave.
                    beep();
                    capturar("es-CL");
                } else {
                    terminar();
                }
                break;
            case "repite":
                capturar("es-CL");
                break;
            case "aviso":
                terminar();
                break;
            default:
                break;
        }
    }

    // --- Captura continua ---------------------------------------------------
    // El reconocedor de Google corta a los 1-2 s de silencio sin importar los extras, así que se
    // relanza y se concatena el texto hasta que pasen X segundos sin habla (Ajustes, 3 a 12 s).

    private static final Pattern LISTO = Pattern.compile("(?i)[\\s,.;:!¡¿?]*\\b(listo|eso es todo)[\\s.!]*$");

    private String capIdioma;
    private final StringBuilder capAcum = new StringBuilder();
    private String capParcial = "";
    private long capUltimoHabla;
    private long capInicio;
    private boolean capFinalizar;

    private boolean sesionActiva;     // nunca dos sesiones a la vez
    private int capReintentos;        // reintentos por errores del servicio de voz
    private boolean capRepetido;      // ya pedí «¿me repites?» en esta orden

    private void capturar(String idioma) {
        capIdioma = idioma;
        capAcum.setLength(0);
        capParcial = "";
        capFinalizar = false;
        capReintentos = 0;
        capInicio = capUltimoHabla = System.currentTimeMillis();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            capIdioma = null;
            hablarYTerminar("Este teléfono no tiene reconocimiento de voz.");
            return;
        }
        h.removeCallbacks(vigilante);
        h.postDelayed(vigilante, 400);
        // Un SpeechRecognizer por captura, nuevo y con un respiro tras soltar el anterior.
        soltarSr();
        h.postDelayed(() -> { if (capIdioma != null) { crearSr(); iniciarSesion(); } }, 300);
    }

    private final Runnable vigilante = new Runnable() {
        @Override public void run() {
            if (!ocupado || capIdioma == null) return;
            long ahora = System.currentTimeMillis();
            long silencio = prefs.silencio() * 1000L;
            if (!capFinalizar && (ahora - capUltimoHabla >= silencio || ahora - capInicio > 90000)) {
                capFinalizar = true;
                if (sr != null && sesionActiva) { try { sr.stopListening(); } catch (Exception ignored) { } }
                // por si no llegan resultados
                h.postDelayed(() -> { if (capFinalizar && capIdioma != null) cerrarCaptura(); }, 2500);
            }
            h.postDelayed(this, 400);
        }
    };

    private String textoActual() {
        String t = capAcum.toString();
        if (!capParcial.isEmpty()) t = (t.isEmpty() ? "" : t + " ") + capParcial;
        return t.trim();
    }

    private void crearSr() {
        if (sr != null) return;
        final SpeechRecognizer mi = SpeechRecognizer.createSpeechRecognizer(this);
        sr = mi;
        mi.setRecognitionListener(new android.speech.RecognitionListener() {
            private boolean vigente() { return sr == mi && capIdioma != null; }
            @Override public void onReadyForSpeech(Bundle b) { if (vigente()) poner("Habla ahora…"); }
            @Override public void onBeginningOfSpeech() { if (vigente()) capUltimoHabla = System.currentTimeMillis(); }
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int t, Bundle b) {}
            @Override public void onPartialResults(Bundle b) {
                if (!vigente()) return;
                String t = primero(b);
                if (t != null && !t.isEmpty()) {
                    capParcial = t;
                    capUltimoHabla = System.currentTimeMillis();
                    oido = textoActual();
                    poner("Oyendo…");
                    if (LISTO.matcher(oido).find() && !capFinalizar && sesionActiva) {
                        capFinalizar = true;
                        try { mi.stopListening(); } catch (Exception ignored) { }
                    }
                }
            }
            @Override public void onResults(Bundle b) {
                if (!vigente()) return;
                sesionActiva = false;
                String t = primero(b);
                if (t != null && !t.trim().isEmpty()) {
                    if (capAcum.length() > 0) capAcum.append(" ");
                    capAcum.append(t.trim());
                    capUltimoHabla = System.currentTimeMillis();
                }
                capParcial = "";
                oido = textoActual();
                poner("Oyendo…");
                terminoSesion();
            }
            @Override public void onError(int e) {
                if (!vigente()) return;
                sesionActiva = false;
                new Registro(CerebroService.this, "errores-escucha.log").anotar("error " + e + " " + nombreError(e)
                        + " | captura " + (System.currentTimeMillis() - capInicio) / 1000 + " s | reintento " + capReintentos);
                if ((e == 12 || e == 13) && !reintentoUS) {   // idioma no disponible
                    reintentoUS = true;
                    capIdioma = "es-US";
                    reiniciarSr();
                } else if (e == 7 || e == 6) {
                    capParcial = "";            // sin voz: fin normal de sesión
                    terminoSesion();
                } else if (e == 9) {
                    cerrarConMensaje("Me falta el permiso del micrófono.");
                } else if (capReintentos < 2) {
                    capReintentos++;            // 11, 8, 5 y otros: recrear y reintentar
                    reiniciarSr();
                } else if (!capRepetido) {
                    capRepetido = true;
                    capIdioma = null;
                    h.removeCallbacks(vigilante);
                    soltarSr();
                    poner("No te escuché bien");
                    decir("No te escuché bien, ¿me repites?", "repite", false);
                } else {
                    cerrarConMensaje("No pude escucharte. Error " + e + ".");
                }
            }
        });
    }

    private static String nombreError(int e) {
        switch (e) {
            case 1: return "red (timeout)";
            case 2: return "red";
            case 3: return "audio";
            case 4: return "servidor";
            case 5: return "cliente";
            case 6: return "sin voz (timeout)";
            case 7: return "sin coincidencia";
            case 8: return "reconocedor ocupado";
            case 9: return "sin permisos";
            case 10: return "demasiadas peticiones";
            case 11: return "servidor desconectado";
            case 12: return "idioma no soportado";
            case 13: return "idioma no disponible";
            default: return "otro";
        }
    }

    /** Destruye el reconocedor y, tras un respiro, crea uno nuevo y reintenta. Nunca en el mismo tick. */
    private void reiniciarSr() {
        soltarSr();
        h.postDelayed(() -> { if (capIdioma != null) { crearSr(); iniciarSesion(); } }, 600);
    }

    /** Arranca una sesión del reconocedor, solo si no hay otra en marcha. */
    private void iniciarSesion() {
        if (sesionActiva || capIdioma == null || sr == null) return;
        sesionActiva = true;
        // Los extras de silencio van como INT (con long Android los ignora).
        int silencioMs = prefs.silencio() * 1000;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, capIdioma)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silencioMs)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silencioMs);
        try {
            sr.startListening(i);
        } catch (Exception e) {
            sesionActiva = false;
            new Registro(this, "errores-escucha.log").anotar("startListening falló: " + e);
            if (capReintentos < 2) { capReintentos++; reiniciarSr(); }
            else cerrarConMensaje("No pude escucharte.");
        }
    }

    /** Terminó una sesión del reconocedor: ¿enviar o seguir escuchando? */
    private void terminoSesion() {
        if (capIdioma == null) return;
        String t = textoActual();
        boolean listo = LISTO.matcher(t).find();
        long silencio = prefs.silencio() * 1000L;
        boolean paso = System.currentTimeMillis() - capUltimoHabla >= silencio;
        if (listo || capFinalizar || paso) {
            cerrarCaptura();
        } else {
            // Respaldo: la sesión cerró antes del silencio configurado. Se relanza SOLO después
            // de este onResults/onError y con una pausa; iniciarSesion() impide sesiones dobles.
            h.postDelayed(() -> { if (capIdioma != null && !capFinalizar) iniciarSesion(); }, 300);
        }
    }

    private void cerrarCaptura() {
        if (capIdioma == null) return;
        String t = LISTO.matcher(textoActual()).replaceFirst("").trim();
        capIdioma = null;
        capFinalizar = false;
        h.removeCallbacks(vigilante);
        soltarSr();
        if (t.isEmpty()) { oido = ""; respuesta = ""; poner("Cerrado, no oí nada"); terminar(); }
        else if (esCancelacion(t)) { poner("Cerrado, no envié nada"); new Registro(this).anotar("orden cancelada: " + t); terminar(); }
        else enviar(t);
    }

    private static boolean esCancelacion(String t) {
        String n = java.text.Normalizer.normalize(t.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return n.equals("nada") || n.equals("cancela") || n.equals("cancelar") || n.equals("olvidalo")
                || n.contains("no era a ti") || n.contains("no era para ti") || n.contains("no te hablaba")
                || n.equals("nada nada") || n.equals("no nada");
    }

    private void cerrarConMensaje(String msg) {
        capIdioma = null;
        h.removeCallbacks(vigilante);
        soltarSr();
        hablarYTerminar(msg);
    }

    private static String primero(Bundle b) {
        ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private void destruirSr() { soltarSr(); }

    /** Suelta el reconocedor actual; el destroy va en un tick posterior, nunca junto a un create. */
    private void soltarSr() {
        final SpeechRecognizer x = sr;
        sr = null;
        sesionActiva = false;
        if (x != null) {
            h.postDelayed(() -> {
                try { x.cancel(); } catch (Exception ignored) { }
                try { x.destroy(); } catch (Exception ignored) { }
            }, 50);
        }
    }

    // --- Envío, tareas y avisos ---------------------------------------------

    private void enviar(String texto) {
        oido = texto;
        respuesta = "";
        poner("Pensando…");
        decir("voy", "voy", false);
        final boolean n = nueva;
        nueva = false;
        new Thread(() -> {
            try {
                final Net.Respuesta r = Net.ask(this, texto, n);
                prefs.guardarUltimo(texto, r.texto);
                h.post(() -> {
                    respuesta = r.texto;
                    poner("Respuesta");
                    if (r.tarea != null) seguir(r.tarea);
                    vozRemota = r.vozRemota;
                    decir(r.texto, "respuesta", true, r.audioUrl);
                });
            } catch (Throwable e) {
                if (n) nueva = true;
                final String msg = e.getMessage() == null ? "Falló la conexión con el PC." : e.getMessage();
                prefs.guardarUltimo(texto, msg);
                h.post(() -> { respuesta = msg; poner("Sin conexión"); decir(msg, "aviso", true); });
            }
        }, "cerebro-red").start();
    }

    private void hablarYTerminar(String msg) {
        respuesta = msg;
        poner(msg);
        decir(msg, "aviso", false);
    }

    private void terminar() {
        ocupado = false;
        capRepetido = false;
        if (!anuncios.isEmpty()) { h.postDelayed(this::siguienteAnuncio, 400); return; }
        h.postDelayed(() -> { if (!ocupado) reanudar(); }, 500);
    }

    // Tareas que sigo: id -> ya la vi activa. Se consulta el PC cada ~3 s mientras haya alguna
    // o la pantalla esté abierta.
    private final java.util.Map<String, Boolean> seguidas = new java.util.LinkedHashMap<>();
    private final java.util.ArrayDeque<String> anuncios = new java.util.ArrayDeque<>();
    private boolean sondeando;

    private void seguir(String id) {
        if (!seguidas.containsKey(id)) seguidas.put(id, false);
        sondear(0);
    }

    private void sondear(long demora) {
        h.removeCallbacks(sondeo);
        h.postDelayed(sondeo, demora);
    }

    private final Runnable sondeo = new Runnable() {
        @Override public void run() {
            if (!vivo) return;
            if (sondeando) { h.postDelayed(this, 3000); return; }
            if (seguidas.isEmpty() && ui == null) return;
            sondeando = true;
            new Thread(() -> {
                try {
                    final java.util.List<Net.Tarea> ts = Net.tareas(CerebroService.this);
                    h.post(() -> { sondeando = false; procesar(ts); });
                } catch (Throwable e) {
                    h.post(() -> sondeando = false);
                }
                h.post(() -> { if (vivo && (!seguidas.isEmpty() || ui != null)) h.postDelayed(sondeo, 3000); });
            }, "cerebro-sondeo").start();
        }
    };

    private void procesar(java.util.List<Net.Tarea> ts) {
        StringBuilder sb = new StringBuilder();
        int desde = Math.max(0, ts.size() - 6);
        for (int k = 0; k < ts.size(); k++) {
            Net.Tarea t = ts.get(k);
            if (k >= desde) {
                sb.append("Tarea ").append(t.id).append(" · ").append(t.estado).append(" — ")
                        .append(t.texto.length() > 60 ? t.texto.substring(0, 60) + "…" : t.texto).append("\n");
            }
            if (!seguidas.containsKey(t.id)) continue;
            boolean activa = t.estado.equals("en cola") || t.estado.equals("trabajando");
            if (activa) { seguidas.put(t.id, true); continue; }
            boolean vista = seguidas.remove(t.id);
            if (!vista || t.estado.equals("cancelada")) continue;   // nada nuevo que avisar
            String voz;
            if (t.estado.equals("lista")) voz = "Tarea " + t.id + " lista: " + t.respuesta;
            else if (t.estado.equals("falló")) voz = "Tarea " + t.id + " falló: " + t.respuesta;
            else voz = "Tarea " + t.id + ": " + t.respuesta;   // espera tu respuesta (termina en ?)
            anuncios.add(voz);
        }
        tareasTexto = sb.toString().trim();
        Ui u = ui;
        if (u != null) u.cambio();
        if (!ocupado && !anuncios.isEmpty()) siguienteAnuncio();
    }

    private void siguienteAnuncio() {
        if (ocupado || anuncios.isEmpty()) return;
        String voz = anuncios.poll();
        ocupado = true;
        pararKeyword();
        beep();
        respuesta = voz;
        poner("Aviso de tarea");
        decir(voz, "respuesta", false);
    }
}
