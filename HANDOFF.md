# Handoff — Patrón Touch (app Android de patrones multi-touch)

> Contexto para continuar este proyecto en Claude Code. Estado al **18-oct-2026**, versión **3.1.3**
> (ver «Sesión del 18-oct: Edgar y pruebas por ADB» al final).
> El usuario es Gonzalo Cruz: habla español de Chile, deja los términos técnicos en inglés y prefiere
> respuestas concisas, decisiones tomadas sin menús de opciones e interfaces de tema claro.

## Qué es

App Android propia (sin root) que inyecta toques multi-touch con un **AccessibilityService**
(`dispatchGesture`) para ejecutar patrones en **Brawl Stars**: mover el joystick y, al mismo tiempo,
disparar, súper, gadget e hipercarga. La usa en un **Samsung** en horizontal (pantalla 2340×1080).

- **Repositorio:** `gcruzn/Claude`, rama **`patron-touch`** (no tocar `main`). El repo es **público**.
- **APK listo para instalar:** `apk/PatronTouch.apk` en la rama. Lo commitea el CI.
  Descarga directa: `https://github.com/gcruzn/Claude/raw/patron-touch/apk/PatronTouch.apk`
- **Package:** `cl.gcruz.patrontouch`, minSdk 26, target/compile 34, Java 17, **sin AndroidX** (solo framework).

## Cómo se compila (importante)

El entorno de trabajo no tenía acceso a Google Maven ni al Android SDK, así que **compila GitHub Actions**
(`.github/workflows/build.yml`):

1. Hacer push a `patron-touch`.
2. El CI ejecuta `gradle assembleRelease` (Gradle 8.7, AGP 8.5.2), firma con la clave debug y **commitea
   `apk/PatronTouch.apk` con `[skip ci]`**.
3. Después de cada build exitoso hay que hacer `git pull` para traer ese commit antes del siguiente push.
4. **Subir `versionCode`/`versionName` en `app/build.gradle` en cada entrega.**

- **Ver errores de compilación:** `gh run view <id> -R gcruzn/claude --log-failed | grep error:`
- **Si el entorno tiene Android SDK,** `./gradlew assembleRelease` local sirve igual (no hay wrapper
  commiteado: usar `gradle` 8.7).

## Arquitectura (`app/src/main/java/cl/gcruz/patrontouch/`)

| Archivo | Rol |
|---|---|
| `TouchService.java` | AccessibilityService. **Cruceta** flotante (▲▼◀▶, centro, ✕), menú central, candado, calibración en el juego, overlay de "dedos virtuales", teclas de volumen. |
| `Player.java` | Reproduce un `Pattern` con `dispatchGesture`: agrupa toques en bloques que no se solapan y hace **un solo gesto por bloque** (no encadena con `continueStroke`, que se cortaba). Remuestrea los trazos con un micro-zigzag para respetar los tiempos (Android recorre los strokes a velocidad constante). Al detener inyecta un toque mínimo para **soltar dedos**. |
| `Pattern.java` | Modelo de bajo nivel: toques (dedos) con puntos `(t, x, y)` en píxeles de pantalla. |
| `Script.java` | **Lenguaje de instrucciones**: parser texto → pasos, formateador pasos → texto y compilador pasos → `Pattern` según el perfil y la calibración. Sin dependencias de Android (se puede testear con stubs). |
| `Data.java` | Datos del usuario en SharedPreferences (`config` / clave `data`, JSON): **patrones** (texto), **perfiles de disparo**, **sets** (perfil + patrón y repeticiones por flecha), set activo y perfil global. Siembra valores por defecto. |
| `Calibration.java` | Posición de joystick, ataque, súper, gadget e hipercarga (se calibra tocando cada control en el juego; gadget e hiper son opcionales). Escala por resolución. |
| `MainActivity.java` | App: servicio, set activo (perfil y patrón ×N por flecha), lista de patrones, perfiles (editor en diálogo), ajustes de la cruceta (candado, opacidad, tamaño, vibración, volumen+) y calibración. |
| `PatternEditorActivity.java` | Editor dual: **Pasos** (botones) ⇄ **Texto**, con validación por línea, **▶ Probar** (abre el juego y ejecuta tras 3-2-1) y **Copiar texto**. |
| `Ui.java` | Helpers de UI y paleta (coral `#E25E6B`, azul `#406AB4`, verde `#73C96A`, grafito `#454A51`, gris `#969798`). |
| `Library.java`, `PatternStore.java` | **Legado** (v2.x: biblioteca por brawler/modo y patrones grabados en JSON). Hoy no se usan desde la UI; se pueden borrar. `assets/biblioteca.json`, `patrones/*.json` y `tools/gen_biblioteca.py` también son legado. |

### Lenguaje de instrucciones (una por línea; acepta tildes, flechas y sinónimos)

```
mover arriba [segundos] [disparando]   # el joystick queda apretado; "arriba" = hacia el enemigo
parar                                  # suelta el joystick
disparar [N] [toque|mantener|apuntado] [dirección]
súper [auto|apuntado] [dirección]
gadget
hiper
esperar segundos
# comentario
```

- **Direcciones:** arriba, abajo, izquierda, derecha y sus diagonales (`arriba-izquierda`…), o `↑ ↓ ← → ↖ ↗ ↙ ↘`.
- **Movimiento continuo:** el movimiento sigue mientras corren las demás instrucciones, hasta un `parar` u otro `mover`.
- **Tiempo:** avanza con `esperar`, con los `disparar` (según el intervalo del perfil) y con `mover … N`.
- **Controles sin calibrar:** si gadget o hiper no están calibrados, esos toques se omiten pero el tiempo corre igual.

### Perfiles de disparo (`Data.Profile`)

| Campo | Valores |
|---|---|
| `attack` | `tap` · `hold` · `aim` |
| `interval` | ms entre disparos |
| `hold` | ms que se mantiene apretado |
| `aimDir` | `move` (hacia donde te mueves) o una dirección |
| `superMode` | `auto` (toque) · `move` · una dirección |

**Por defecto:** Rápido 0,3 s · Normal 0,55 s · Lento 0,8 s · Mantener 1,2 s · Apuntado · Lanzador al frente.

### Interacción de la cruceta

- **Flecha:**
  - toque = patrón del set con sus **N repeticiones**;
  - **doble toque** = bucle infinito (coral);
  - **mantener ~0,45 s (vibra) y soltar** = solo moverse (verde).
- **Una a la vez:** tocar la misma flecha detiene; tocar otra cancela la actual y arranca la nueva.
- **Centro:**
  - toque = menú (cambiar de set, candado, calibrar);
  - **mantener 3 s** = candado;
  - arrastrar = mover la cruceta (si no está bloqueada).
- **Persistencia:** la posición se guarda en `padX`/`padY`.
- **✕:** cierra la cruceta; manteniéndola, calibra. **Con candado queda desactivada.**
- **Volumen −** detiene; **volumen +** repite la última acción.

## Limitaciones de Android (no son bugs)

- **Toques reales:** cualquier toque real en pantalla **cancela** los gestos inyectados. Por eso
  "mantener" funciona como mantener y soltar, y el toque del dedo sobre la cruceta usa
  `stopByUserTouch()`, que detiene sin inyectar nada.
- **Grabar mientras se juega:** no se puede sin root (la capa de grabación intercepta los toques).
  La v1 grababa "en seco"; se descartó y hoy los patrones se crean con instrucciones.
- **Patrones ciegos:** saben dónde están los botones, no dónde está el enemigo. Rinden bien con brawlers
  de distancia y auto-apuntado, y mal con cuerpo a cuerpo (Edgar).

## Bugs resueltos (para no repetirlos)

1. **Joystick pegado:** encadenar gestos con `continueStroke` se cortaba y dejaba el dedo virtual abajo.
   Se resolvió con un gesto por bloque y soltando los dedos al detener.
2. **Menú del centro que se abría solo (v3.0):** el toque de liberación caía en el centro de la cruceta.
   Ahora va a una **vista "sink" invisible** en la celda (2,0) del grid, que lo absorbe.
3. **Servicio "activo" que no inyectaba nada:** Samsung lo suspendió en segundo plano.
   Se solucionó reiniciando el teléfono y poniendo Batería en "Sin restricciones".

## Instalación en el teléfono (ya hecha; por si se repite)

- **Play Protect bloquea la instalación:** desactivar momentáneamente "Analizar apps con Play Protect".
- **"Configuración restringida" en Accesibilidad:** Ajustes › Apps › Patrón Touch › ⋮ › Permitir ajustes restringidos.
- **Para que Samsung no suspenda el servicio:** Batería › Sin restricciones y sacar la app de "Apps en reposo".
- **Conflictos:** el usuario tenía **otros auto clickers activos en Accesibilidad**, que pueden cortar los gestos.

## Estado y pendientes

- **v3.0.1 instalada y funcionando:** cruceta, sets, perfiles, editor y candado.
- **Edgar:** el patrón "Asalto total" (hiper → súper auto → golpes → gadget → súper → golpes) **no le funcionó**,
  y el usuario no dijo en qué falló. Se propuso "Edgar · Pegado" (`gadget, hiper, súper auto, esperar 0.6, disparar 8 toque`),
  basado en un video de un Edgar de Prestigio 3. Falta su feedback.
- **Siguiente paso sugerido:** que grabe una partida propia con *Mostrar toques* (Opciones de desarrollador) y la suba.
  Analizar los cuadros con ffmpeg y OpenCV, detectar los círculos de toque y convertirlos en instrucciones.
  Ya se hizo una vez: joystick ≈ (555, 800), ataque ≈ (1664, 794), súper ≈ (1508, 956) en 2340×1080.
- **Ideas conversadas no implementadas:**
  - diagonales en la cruceta;
  - importar o exportar sets completos como texto;
  - eliminar el código legado (`Library`, `PatternStore`, la biblioteca y `tools/`).
- **Riesgo explicado al usuario:** Supercell prohíbe macros y hay riesgo de ban. Se le sugirió probar en una
  cuenta secundaria o en el entrenamiento. **No implementar evasión de detección** (por ejemplo, aleatorizar
  tiempos para parecer humano).

## Probar el parser sin Android

`Script.java` no depende de Android. Se puede compilar con stubs mínimos de `Pattern`, `Calibration` y
`Data.Profile` y ejecutar con `javac`/`java` para validar textos de patrones y ver los toques resultantes
(tiempos y coordenadas).

## Sesión del 18-oct: Edgar y pruebas por ADB (v3.1.0 → v3.1.1)

**Conexión al celular:** `C:\Program Files\ASUS\GlideX\adb.exe` (no hay adb en el PATH). Teléfono SM-S948B.

**Comandos de prueba** (receptor en `TouchService`, protegido con `android.permission.DUMP`: solo el shell):

```
adb shell am broadcast -a cl.gcruz.patrontouch.DEBUG --es cmd dump                 # calibración, sets, valida patrones
adb shell am broadcast -a cl.gcruz.patrontouch.DEBUG --es cmd run --es id e_salto --es profile edgar
adb shell am broadcast -a cl.gcruz.patrontouch.DEBUG --es cmd run --es script "'súper auto\nesperar 0.6'"
adb shell am broadcast -a cl.gcruz.patrontouch.DEBUG --es cmd calib --es joy 571,801 --es attack 1966,676 --es super 1664,796 --es gadget 1854,954 --es hyper none
adb logcat -s PatronTouch:*
```

Flujo de prueba: Edgar › Probar › **Terrenos de práctica** (muñecos quietos), `screenrecord` + `run`, y hoja
de contacto de cuadros con OpenCV (`cv2` está en el Python del PC; no hay ffmpeg).

**Calibración real de Gonzalo** (2340×1080, distinta de los valores por defecto): joystick 571,801 · ataque
1966,676 · súper 1664,796 · gadget 1854,954 · sin hipercarga (su Edgar no la tiene). El botón morado en
1508,950 no es un control de Edgar. **El valor por defecto de «ataque» caía sobre su botón de súper**: con
la calibración por defecto, los patrones de Edgar no podían funcionar.

**Set «Edgar»** (perfil «Edgar»: toque cada 0,4 s; se agrega una sola vez a los datos existentes, lote
`edgar1`): ▲ Salto y golpes · ▼ Escape · ◀ Pegar y alejarse · ▶ Doble salto. También están Esquive, Rodear e
Hiper y salto (este último solo sirve con hipercarga).

Los siete se probaron en Terrenos de práctica revisando la grabación: Salto y golpes, Pegar y alejarse,
Doble salto, Rodear (v2, tramos de 0,2 s), Esquive y Escape (súper apuntado con arrastre ×1,8: salta al
alcance máximo). Hiper y salto no se pudo probar (no hay hipercarga). Límite de fondo: son ciegos; el
auto-apuntado elige al más cercano, y «arriba/abajo» son de la pantalla, no hacia el enemigo.

**Firma (resuelto desde v3.1.2):** el CI firma con la clave fija de los secretos `SIDELOAD_KEYSTORE`
(base64 del .p12) y `SIDELOAD_PASSWORD`; la clave vive en `C:\Users\gonza\.patrontouch\`, fuera del repo
público. Las versiones nuevas se instalan encima con `adb install -r` sin perder datos ni el permiso de
Accesibilidad (probado 3.1.2 → 3.1.3). Si el CI falla, el error de Gradle queda como anotación pública del job.

### v3.2.0: patrones cortos (~1,5 s) tras una partida real

Partida grabada (Supervivencia solo, 2.º de 10, +10 trofeos; murió en el duelo final dentro del gas):
de 46 patrones lanzados, 34 se cortaron a los ~1,4 s porque tocó otra flecha encima; 12 duraron menos
de 1 s. Además el set activo era **General** (las duraciones de ~3,04 s calzan con «Completo»), no Edgar.
Respuesta: lote `edgar2`, que acorta los 7 patrones de Edgar a ~1,4–1,6 s y solo reemplaza los que el
usuario no editó (compara contra `EDGAR1`). ◀▶▲▼ del set Edgar = Pegar y alejarse · Gadget y golpes ·
Salto y golpes · Escape. Probados en Terrenos: Salto (1,37 s) y Gadget (1,47 s) conectan sus golpes.
Desde v3.1.4 el log dice qué patrón corre en cada flecha (`patrón «…» flecha=… modo=…`).

## v4 (19-oct): botonera vertical, sets Brawler · Modo, disparo dentro del patrón

- **Botonera:** columna con asa ≡ (toque = menú de sets, mantener 3 s = candado, arrastrar = mover),
  botones 1..N (máx. 6) con los patrones del set, 🎲 Random y ✕. Toque = ese patrón en bucle; otro toque
  = parar; tocar otro botón cambia. Random = en cada repetición un patrón del set al azar, sin repetir
  el anterior (`Player.start(..., Supplier<Pattern>)`). Se eliminaron doble toque y mantener.
- **Sets:** `brawler`, `mode` y `slots` (ids ordenados). Lote `v4` migra la cruceta: flechas ▲▶▼◀ →
  slots, y antepone a cada patrón la línea de disparo del perfil de su set. Los perfiles ya no se ven.
- **Disparo en el patrón:** `disparo toque cada 0.42` · `disparo mantener 1.2` ·
  `disparo apuntado cada 0.55 arriba|movimiento`. Sin línea rige toque cada 0,5 s y súper auto.
- **Charlie** (lote `charlie1`): sacado de 10 min de partidas de Gonzalo en BlueStacks (grabador de
  ventana + mando por joyGetPosEx; el ADB de BlueStacks no deja screenrecord/getevent). 5 botones.
- **Estado del celular al 19-oct:** Gonzalo recalibró con controles invertidos (joystick a la derecha),
  borró los patrones de Edgar (el set Edgar quedó vacío) y armó patrones propios. Comando ADB nuevo:
  `--es cmd set --es id <set>`.

### Kenji (v4.1.x, 19-oct)

Sets «Kenji · Zona restringida» (Defender zona, Combo corto, Súper y salida, Curación y retirada,
Entrada y combo) y «Kenji · Balón Brawl» (mismos patrones, otro orden). El Kenji de Gonzalo tiene
**Hosomaki** (no Dashi Dash) y **no tiene hipercarga** (lote `kenji2`). Probado en Terrenos: Dash 1500
+ Slash 2000 conectan; el súper corta 2×2600. Botonera: el bucle se repite cada ~0,7 s y Random
alterna sin repetir. Parar tocando el mismo botón: verificado por Gonzalo con el dedo (19-oct). Los `adb input tap`
sobre la botonera mientras corre un gesto se pierden a veces: para probar por ADB usar `--es cmd stop`.

### Formas (v4.2.0, 19-oct)

Set «General · Formas»: Cuadrado, Círculo, Triángulo, Rectángulo horizontal y vertical. Presión
constante (toque cada 0,4 s mientras se mueve) y evasión en figura cerrada; gadget, hiper y súper una
vez por vuelta dentro de un lado acortado para que cierre (simulado: desplazamiento neto ≈ 0). Probado
en Terrenos con Poco (tiene hipercarga), 2 vueltas cada una: vuelven al punto de partida, los ataques
conectan (1520) y se activan gadget, hiper y súper.

### Sets por brawler y modo (v4.3.0, 19-oct)

`assets/brawlers1.json` (lote `brawlers1`): 28 sets de 5 botones para 14 brawlers (Rico, Poco, Shade,
El Primo, Cordelius, Charlie, Starr Nova, Fang, Buzz, Larry & Lawrie, Jessie, Emz, Wendy, Nita), 2 modos
cada uno según SeeMeta (oct-2026). Combos inferidos de la wiki, no de guías de pros. Los JSON crudos de
los agentes están en `tools/combos/`; `tools/armar_lote.py` los normaliza (parar tras cada movimiento
con tiempo + vuelta al origen) y genera el asset. Validado en el celular: 170 patrones sin errores.
Gonzalo decidió no probarlos en Terrenos: los usa directo en partida y reporta.

### Pruebas del 20-oct (v4.4.6, Terrenos con Starr Nova, ADB por Wi-Fi)

- **Escape (v4.4.5):** el toque real en Escape cortaba el gesto y el «soltar» se inyectaba con el dedo
  todavía abajo → joystick pegado. Ahora Escape detiene al apoyar y suelta/reinicia al levantar.
  Probado con tiempos del log alineados al video: Escape = 0 daño en 5 s; al detener queda quieto 6 s.
  Ojo al probar por Wi-Fi: cada `am broadcast` tarda ~1 s; usar `log -t PatronTouch REC_START` y
  `logcat -v time` para alinear fases.
- **Disparo automático con el joystick en manos de Gonzalo: NO sirve en el S26.** Con su dedo en el
  joystick, 20 toques inyectados (cada 0,5 s) no hicieron daño (muñecos a 1500 todo el rato) y Android
  no reportó cancelación: el juego los ignora. Su movimiento sí siguió normal. Alternativa: BlueStacks.
- **Aleatorio (v4.5.0–4.5.1):** `mover aleatorio` / `mover opuesto` (el patrón se recompila en cada
  vuelta). Set «General · Aleatorio», 1 botón «Ráfagas»: 6 tramos al azar, ráfagas de 3 disparos a
  0,15 s, gadget al inicio, hiper + súper a la mitad (~5,4 s por vuelta). Probado en Terrenos: se mueve
  en direcciones distintas, conecta ráfagas y sale el súper.
- **Límite de strokes (v4.5.1):** Android acepta 20 strokes por gesto y lanzar el siguiente cancela el
  que corre; con 23 toques el joystick se perdía a los 4,6 s. `Player.buildChunks` ahora parte los
  toques largos en el corte (re-apoyan en el centro y retoman en 60 ms). El log sigue mostrando
  «cancelado (reemplazado)» justo en el corte, y en el video el brawler sigue moviéndose.
- **ADB por puerto fijo:** `adb tcpip 5555` + `adb connect 192.168.100.119:5555`; no se cae cuando
  Samsung apaga la depuración inalámbrica (~30 min). Se pierde al reiniciar el celular.

### S Pen (v4.6.0 → v4.7.3, 20-oct)

- Observar el lápiz por accesibilidad (setObservedMotionEventSources) le quitó el S Pen al juego en el
  S26: DESACTIVADO (v4.6.1).
- **Puente por ADB:** `nohup setsid nc -L -s 127.0.0.1 -p 7787 getevent -l /dev/input/event12 &`
  (event12 = sec_e-pen). El servicio se conecta al socket: pausa el patrón con el lápiz abajo, guarda
  la dirección del arrastre y reanuda cuando el lápiz SALE DE RANGO (`BTN_DIGI UP` en el S26; tope
  1,5 s). Mientras el lápiz flota cerca, Android rechaza los toques inyectados (palma), por eso no se
  reanuda al levantarlo. Medido: reanuda 0,1–0,15 s después de levantar (antes 1,5 s).
- Lenguaje: `mover dirigido` (última dirección del lápiz ±45° al azar) y `mover ultima` (exacta).
  Set Aleatorio: 1 Corta (ráfaga hacia la última dirección + habilidades, ~1,7 s), 2 Ráfagas, 3 Dirigido.
- El puente y el puerto 5555 se pierden si se cae ADB (Bloqueador automático): relanzar ambos.

### Dedo (v4.7.5 → v4.8.1, 20-oct)

- **Puente del dedo:** `nohup setsid nc -L -s 127.0.0.1 -p 7788 getevent -l /dev/input/event7 &`
  (event7 = sec_touchscreen, multitáctil 0–4095). Solo ve dedos reales. Con un dedo abajo el patrón
  se pausa; el dedo que empezó sobre el joystick (radio 2,2 × joyRadius de la calibración) da la
  dirección; al levantar el último dedo se reanuda en 60 ms. Probado y OK por Gonzalo.
- **Joystick tomado por el dedo virtual (v4.8.1):** en el S26 el dedo/lápiz real NO cancela el gesto
  inyectado en curso; ese gesto seguía apretando el joystick y el juego lo atendía a él. Ahora al
  apoyar dedo o lápiz se llama `player.stop(null)` (inyecta el soltar en la zona invisible y corta).
- **v4.7.5:** tocar el botón del patrón con lápiz/dedo lo detiene (antes el puente pausaba primero y
  el toque se leía como «iniciar»).
- Al reconectar ADB relanzar los DOS puentes (7787 lápiz, 7788 dedo).

### Dedo trabado en Android y reproductor v5 (20-oct)

- **Los números de /dev/input cambian al reiniciar** (tras reiniciar, la pantalla pasó de event7 a
  event8 y el puente del dedo leía el sensor de luz: la app nunca pausaba). Lanzar los puentes por
  NOMBRE:
  `adb shell 'T=$(getevent -pl | grep -B1 "\"sec_touchscreen\"" | head -1 | awk "{print \$4}"); P=$(getevent -pl | grep -B1 "\"sec_e-pen\"" | head -1 | awk "{print \$4}"); nohup setsid nc -L -s 127.0.0.1 -p 7788 getevent -l $T >/dev/null 2>&1 & nohup setsid nc -L -s 127.0.0.1 -p 7787 getevent -l $P >/dev/null 2>&1 &'`
- **Causa de «se demora / no avanza / no dispara» (v4.9.x):** lanzar un gesto con otro en curso
  (Android cancela el anterior) dejaba un dedo inyectado apretado en el InputDispatcher
  (`logcat`: «Invalid DOWN event - pointers already down for device -1»; `dumpsys input`:
  «Verifier on 0 : {DeviceId(-1): {1}}»). Desde ahí Android descarta TODO toque inyectado (también
  `adb shell input`) hasta reiniciar el celular. Chequeo rápido: `dumpsys input | grep Verifier`
  debe dar `{}` con el patrón detenido.
- **v5.0.2:** el reproductor nunca lanza un gesto con otro en curso: tramos de 100 ms (v5.0.8; medido: 80–600 ms avanzan igual), cada uno un
  gesto completo (todo dedo en UP), el siguiente al terminar el anterior; detener espera el tramo en
  curso. El joystick que cruza un borde vuelve a apoyar en el centro y en 40 ms retoma su posición.
  `continueStroke` NO sirve en el S26: Android cancelaba cada continuación (v5.0.0/5.0.1), también
  partiendo del punto final exacto. Verificado sin tocar (sin cancelaciones, Verifier `{}`, mueve y
  dispara) y con el dedo de Gonzalo en «Prueba · Disparo» (9 reanudaciones, 0 inconsistencias).
- Set **Pruebas** (General): 1 «Prueba · Disparo» (solo 3 tiros), 2 «Prueba · Dirección» (solo
  mover hacia la última dirección). Para aislar fallas al soltar el dedo.
- Comando ADB `tune` (resume/hold/ramp) para medir variantes sin recompilar.

### Sin puentes (v5.4.0, 21-oct)

- Pedido de Gonzalo: se ELIMINARON los puentes del dedo y del lápiz (7787/7788) y todo lo que
  dependía de ellos (pausa al tocar, reanudar al soltar, última dirección, girar en el sitio con el
  dedo quieto). El botón corre su patrón en bucle hasta volver a tocarlo. «mover ultima» y
  «mover dirigido» salen al azar (Script.steerDir queda en null).
- El reproductor sigue en un gesto continuo por vuelta, sin lanzar un gesto con otro en curso.
- ADB por Tailscale cuando no están en la misma red: el celular aparece como 100.118.105.84
  (Depuración inalámbrica → `adb connect 100.118.105.84:<puerto>`, luego `tcpip 5555`).
- **v5.5.0 — Lectura del joystick por captura de pantalla** (CaptureActivity + CaptureService,
  MediaProjection, servicio en primer plano tipo mediaProjection). Con el patrón apagado mira el
  entorno del joystick a media resolución ~30 fps: perilla apretada = disco azul sólido (regla
  b>140, b−r>90, b−g>50; medido 6.000–12.700 px a res. completa vs 100–960 sin tocar, la botonera
  se excluye). Base = primer cuadro con perilla; dirección = desplazamiento desde la base →
  Script.steerDir. Con el patrón corriendo no se lee (la perilla visible es la inyectada).

### Lápiz por Shizuku (v6.1.0, 22-oct)

- La captura de pantalla no sirvió para el rumbo del lápiz con el patrón corriendo (la perilla
  «parpadea» para el detector; v5.8–v6.0 cambiaban de rumbo solas). Samsung además cancela siempre
  `continueStroke` (comando ADB `conttest`), así que cada re-apoyo suelta el joystick.
- **PenShizuku** lee `getevent -l` del sec_e-pen a través de Shizuku (dependencia
  dev.rikka.shizuku:api/provider 13.1.5; `newProcess` por reflexión). Lápiz en rango → pausa con corte
  seguro; arrastre → Script.steerDir; fuera de rango → retoma al instante. Con Shizuku activo, un
  gesto por vuelta (sin re-apoyos) y la pantalla no se usa con el patrón corriendo.
- Shizuku se inicia tras cada reinicio desde su propia app (Depuración inalámbrica, sin PC).
- **Validado por Gonzalo (22-oct, v6.1.0): «funciona genial»** con Disparo 0,4 + Joystick y el lápiz.
  Arranque de Shizuku sin pasar por su app (con ADB conectado):
  `adb shell 'P=$(pm path --user 0 moe.shizuku.privileged.api | head -1 | sed "s/package://"); $(dirname $P)/lib/arm64/libshizuku.so'`
