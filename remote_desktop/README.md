# Mi Escritorio Remoto

Controla el PC de tu casa desde el navegador del celular. No hay que instalar
nada en el teléfono: el PC corre un pequeño servidor y el celular abre una
página web.

```
 Celular (navegador)  ── imagen JPEG ──  PC (servidor Python)
   toques / teclado   ── WebSocket ──>   mouse y teclado reales
```

## 1. Instalar en el PC

Necesitas Python 3.10 o superior ([python.org](https://www.python.org/downloads/),
marca "Add Python to PATH" en Windows).

```bash
pip install -r remote_desktop/requirements.txt
```

## 2. Iniciar el servidor

```bash
python -m remote_desktop.server --password "una-clave-larga-y-unica"
```

En Windows también puedes editar la contraseña en
`iniciar_escritorio_remoto.bat` y hacerle doble clic.

Verás algo así:

```
=== Escritorio remoto listo ===
  Abre en el celular: http://192.168.1.50:8765
```

La primera vez Windows pregunta si permite el acceso por red: acepta para
**redes privadas**.

## 3. Conectarte desde el celular

**En la misma red WiFi:** abre en el navegador la dirección que mostró el
servidor e ingresa la contraseña.

**Desde fuera de casa (4G, otra WiFi):** la forma recomendada es
[Tailscale](https://tailscale.com) (gratis para uso personal):

1. Instala Tailscale en el PC y en el celular, con la misma cuenta.
2. En el celular abre `http://NOMBRE-DEL-PC:8765` (o la IP `100.x.x.x` que
   muestra Tailscale para el PC).

Así el PC no queda expuesto en internet y el tráfico va cifrado. **No abras
el puerto 8765 en el router**: sin HTTPS la contraseña y la imagen viajarían
sin cifrar.

**Tip:** en el menú del navegador elige "Agregar a pantalla de inicio" y
queda como una app más, a pantalla completa.

## Cómo se usa

| Gesto | Acción en el PC |
|---|---|
| Tocar | Clic izquierdo (dos toques rápidos = doble clic) |
| Mantener presionado | Clic derecho |
| Arrastrar un dedo | Arrastrar (seleccionar, mover ventanas) |
| Pellizcar con dos dedos | Zoom de la imagen (botón **1:1** lo restablece) |
| Botón **🖱️ Clic / ↕️ Rueda** | En modo Rueda, arrastrar = rueda del mouse |
| Botón **⌨️** | Abre el teclado y la barra de teclas especiales |
| **Baja / Media / Alta** | Calidad de imagen (baja = más fluido en 4G) |
| **⏻** | Cerrar sesión |

## Opciones del servidor

| Opción | Para qué |
|---|---|
| `--password` | Contraseña (mín. 8 caracteres). También `RD_PASSWORD`. Si falta, se genera una y se muestra en pantalla |
| `--port 8765` | Puerto |
| `--monitor 2` | Transmitir otro monitor |
| `--max-width 1280` | Resolución enviada; bájala (p. ej. 960) si va lento |
| `--cert` / `--key` | Activar HTTPS con tu propio certificado |

## Seguridad

- Contraseña obligatoria; tras 5 intentos fallidos esa IP queda bloqueada 5 min.
- Sesiones con token que expira a las 12 h; "⏻" lo invalida.
- Una sola sesión a la vez: si alguien más se conecta, la anterior se corta.
- Usa una contraseña larga y única, y prefiere Tailscale para acceso externo.

## Limitaciones

- Sin audio ni transferencia de archivos.
- Ctrl+Alt+Supr y la pantalla de bloqueo/UAC de Windows no se pueden
  controlar (Windows lo impide a programas normales). Desactiva el bloqueo
  automático si necesitas usarlo mucho rato.
- El servidor debe estar corriendo con tu sesión de Windows iniciada.
