# Jarvis (rama `cerebro`; antes «Oye cerebro»)
App Android: oído y boca del cerebro. Palabra clave "jarvis" (desde 0.4.0; antes "oye cerebro") con Vosk (on-device), orden con
SpeechRecognizer, POST a /api/cerebro del servidor Mi PC (por Tailscale). URL y contraseña se
configuran en la app; nunca en el repo. El modelo de Vosk se baja una vez al primer arranque.
Compila GitHub Actions (igual que Patrón Touch) y commitea apk/Cerebro.apk.
