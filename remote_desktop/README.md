# Mi PC

Una sola app en el celular para trabajar con el PC de tu casa desde
cualquier lugar. Se conecta sola al abrirla y tiene tres pestañas:

- **Claude**: chat con Claude Code corriendo en tu PC, con tus repos,
  scripts, CLAUDE.md y skills. Ves los pasos que da, los cambios que hace
  y le das **Permitir / Rechazar** cuando quiere ejecutar algo.
- **Archivos**: Escritorio y Descargas del PC. Ver, descargar, compartir,
  subir desde el celular o pedirle a Claude que revise un archivo.
- **Mi PC**: lista de ventanas abiertas; tocas una y la ves sola, ajustada
  al ancho del celular. Además: abrir programa, bloquear, volumen y
  suspender. La pantalla completa queda como último recurso.

```
 Celular (app Mi PC) ──HTTPS por Tailscale──> PC de casa (servidor Mi PC)
                                                 ├─ Claude Code (Agent SDK)
                                                 ├─ Escritorio y Descargas
                                                 └─ ventanas, mouse y teclado
```

## Instalación (una sola vez)

### En el PC de casa (Windows)

1. Instala [Python](https://www.python.org/downloads/) (marca **Add
   Python to PATH**).
2. Instala [Claude Code](https://claude.com/claude-code), abre una
   terminal, ejecuta `claude` e inicia sesión con tu cuenta.
3. Instala [Tailscale](https://tailscale.com/download) e inicia sesión.
   En el [panel de Tailscale](https://login.tailscale.com/admin/dns) activa
   **MagicDNS** y **HTTPS Certificates**.
4. Descarga esta carpeta y haz doble clic en **`instalar_mi_pc.bat`**:
   - instala lo necesario,
   - te pide la contraseña para vincular el celular,
   - deja Mi PC arrancando solo cada vez que inicias Windows,
   - lo publica en tu red de Tailscale con HTTPS,
   - abre `config.json` para que agregues tus proyectos (ver abajo).

Mientras Mi PC está abierto, Windows no suspende el PC (la pantalla sí
puede apagarse). El PC debe quedar encendido y con tu sesión iniciada.

### En el celular (Android)

1. Instala **Tailscale** desde Play Store e inicia sesión con la misma
   cuenta.
2. En Android: **Ajustes › Redes › VPN › Tailscale › VPN siempre activa**.
   Así no tienes que volver a abrir Tailscale nunca.
3. En Chrome abre `https://NOMBRE-DEL-PC.TU-RED.ts.net` (la dirección
   exacta aparece en la app de Tailscale, en tu PC).
4. Ingresa la contraseña: el celular queda vinculado.
5. Menú de Chrome › **Instalar app**. Desde ahí, Mi PC es un ícono más:
   lo abres y entra solo.

## config.json

Está en `%APPDATA%\MiPC\config.json`:

```json
{
  "folders": {
    "Escritorio": "C:/Users/tu/Desktop",
    "Descargas": "C:/Users/tu/Downloads"
  },
  "projects": {
    "claude": "C:/Users/tu/repos/claude",
    "scripts": "C:/Users/tu/repos/scripts"
  },
  "uploads": "C:/Users/tu/Downloads/MiPC"
}
```

- `folders`: lo único que se ve en la pestaña Archivos.
- `projects`: las carpetas donde puede trabajar Claude (el selector de
  arriba en la pestaña Claude). Claude también puede leer Escritorio y
  Descargas para revisar archivos que le pidas.
- `uploads`: dónde quedan los archivos que adjuntas en el chat.

Después de editarlo, ejecuta **`reiniciar_mi_pc.bat`**.

## Seguridad

- Solo se puede entrar desde tus equipos de Tailscale: el servidor
  escucha únicamente dentro del PC y Tailscale lo publica cifrado.
- Vincular un celular requiere la contraseña; tras 5 intentos fallidos se
  bloquea 5 minutos. En el PC se guardan solo hashes, nunca la contraseña
  ni los tokens.
- Claude pide permiso en el celular antes de ejecutar comandos o editar
  archivos (según tu configuración de permisos de Claude Code).
- Para desvincular todos los celulares:
  `.venv\Scripts\python -m remote_desktop.server --revoke-devices`

Claude usa el login de tu suscripción que ya tienes en el PC. Según las
condiciones de Anthropic eso está pensado para tu uso personal; si otra
persona usara la app, habría que configurar una API key.

## Problemas comunes

| Síntoma | Qué revisar |
|---|---|
| "No se pudo conectar" | PC encendido, Tailscale conectado en ambos, `tailscale serve status` muestra el puerto 8765 |
| Claude responde con error | En el PC ejecuta `claude` y revisa que la sesión siga iniciada |
| No aparecen proyectos | Faltan en `projects` de config.json; reinicia con `reiniciar_mi_pc.bat` |
| Una ventana se ve mal ajustada | Algunas apps no se achican; usa "Pantalla completa" en Mi PC |
| Registro de errores | `%APPDATA%\MiPC\mi_pc.log` |

## Limitaciones

- Ctrl+Alt+Supr, la pantalla de bloqueo y las ventanas de administrador
  (UAC) de Windows no se pueden controlar a distancia.
- Si el PC se suspende o apaga, la app no puede despertarlo.
- Sin audio.

## Para desarrollar

```bash
pip install -r requirements.txt
pytest
flake8
python -m remote_desktop.server --host 0.0.0.0   # probar en la red local
```
