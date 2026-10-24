package cl.gcruz.cerebro;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Elección de la voz de TextToSpeech: las de red/neuronales de Google suenan mejor. */
final class Voces {
    private Voces() {}

    static boolean neuronal(Voice v) {
        return v.isNetworkConnectionRequired() || v.getName().toLowerCase(Locale.ROOT).contains("network");
    }

    static int puntaje(Voice v) {
        String n = v.getName().toLowerCase(Locale.ROOT);
        int p = 0;
        if (neuronal(v)) p += 10;
        if (n.contains("es-cl")) p += 3;
        else if (n.contains("es-us")) p += 2;
        else p += 1;
        if (n.contains("google") || n.contains("-x-")) p += 1;   // motor de Google
        if (v.getQuality() >= Voice.QUALITY_HIGH) p += 1;
        return p;
    }

    /** Voces en español instaladas, mejores primero. */
    static List<Voice> espanolas(TextToSpeech tts) {
        List<Voice> out = new ArrayList<>();
        try {
            if (tts != null && tts.getVoices() != null) {
                for (Voice v : tts.getVoices()) {
                    if ("es".equals(v.getLocale().getLanguage())
                            && !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                        out.add(v);
                    }
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(out, (a, b) -> puntaje(b) - puntaje(a));
        return out;
    }

    static String etiqueta(Voice v) {
        return v.getLocale().toLanguageTag() + " · " + v.getName()
                + (neuronal(v) ? "  (red, más natural)" : "  (en el teléfono)");
    }

    /** Aplica voz, velocidad y tono de los ajustes. Sin voz elegida usa la mejor disponible. */
    static void aplicar(TextToSpeech tts, Prefs p) {
        try {
            List<Voice> vs = espanolas(tts);
            Voice elegida = null;
            for (Voice v : vs) if (v.getName().equals(p.voz())) elegida = v;
            if (elegida == null && !vs.isEmpty()) elegida = vs.get(0);
            if (elegida != null) tts.setVoice(elegida);
            tts.setSpeechRate(p.velocidad() / 100f);
            tts.setPitch(p.tono() / 100f);
        } catch (Exception ignored) { }
    }
}
