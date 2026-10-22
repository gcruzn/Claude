"""Servidor "Mi PC": corre en el PC de la casa y lo usa la app del celular.

Uso:
    python -m remote_desktop.server --password "una-clave-larga"

Por defecto solo escucha dentro del PC; Tailscale lo publica con HTTPS:
    tailscale serve --bg 8765
y en el celular se abre https://NOMBRE-DEL-PC.TU-RED.ts.net
"""

import argparse
import asyncio
import json
import logging
import os
import secrets
import socket
import ssl
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import quote

from aiohttp import WSMsgType, web

from remote_desktop.auth import Auth
from remote_desktop.files import FileAccessError, unique_path, safe_filename
from remote_desktop.protocol import ProtocolError, parse_event

STATIC_DIR = Path(__file__).parent / "static"
MAX_PROMPT = 20000
log = logging.getLogger("remote_desktop")

AUTH = web.AppKey("auth", Auth)
CAPTURE = web.AppKey("capture", object)
INPUT = web.AppKey("input", object)
DESKTOP = web.AppKey("desktop", object)
FILES = web.AppKey("files", object)
HUB = web.AppKey("hub", object)
UPLOADS = web.AppKey("uploads", Path)
CAPTURE_POOL = web.AppKey("capture_pool", ThreadPoolExecutor)
INPUT_POOL = web.AppKey("input_pool", ThreadPoolExecutor)
ACTIVE = web.AppKey("active", dict)


def client_id(request):
    remote = request.remote or "desconocido"
    # Detrás de "tailscale serve" todo llega desde 127.0.0.1: la IP real
    # del celular viene en X-Forwarded-For.
    forwarded = request.headers.get("X-Forwarded-For")
    if forwarded and remote in ("127.0.0.1", "::1"):
        return forwarded.split(",")[0].strip()
    return remote


def request_token(request):
    header = request.headers.get("Authorization", "")
    if header.startswith("Bearer "):
        return header[7:]
    return request.query.get("token")


def run_sync(request, pool_key, func, *args):
    loop = asyncio.get_running_loop()
    return loop.run_in_executor(request.app[pool_key], func, *args)


async def json_body(request):
    try:
        body = await request.json()
    except (ValueError, UnicodeDecodeError):
        raise web.HTTPBadRequest(text="JSON inválido")
    if not isinstance(body, dict):
        raise web.HTTPBadRequest(text="se esperaba un objeto")
    return body


# --- Páginas y vinculación ------------------------------------------------

async def index(request):
    response = web.FileResponse(STATIC_DIR / "index.html")
    response.headers["Cache-Control"] = "no-store"
    return response


async def service_worker(request):
    # Servido desde la raíz para que controle toda la app (instalable en Android).
    response = web.FileResponse(STATIC_DIR / "sw.js")
    response.headers["Cache-Control"] = "no-cache"
    return response


async def pair(request):
    auth = request.app[AUTH]
    client = client_id(request)
    if auth.is_locked(client):
        return web.json_response(
            {"error": "Demasiados intentos. Espera unos minutos."}, status=429)
    body = await json_body(request)
    token = auth.login(client, body.get("password"), body.get("device") or "celular")
    if token is None:
        log.warning("Vinculación fallida desde %s", client)
        return web.json_response({"error": "Contraseña incorrecta"}, status=401)
    log.info("Celular vinculado desde %s", client)
    return web.json_response({"token": token})


async def unpair(request):
    request.app[AUTH].logout(request_token(request))
    return web.json_response({"ok": True})


async def status(request):
    app = request.app
    return web.json_response({
        "pc": socket.gethostname(),
        "folders": list(app[FILES].roots),
        "projects": app[HUB].project_names(),
        "windows": app[DESKTOP].supports_windows,
    })


# --- Archivos -------------------------------------------------------------

async def files_list(request):
    store = request.app[FILES]
    root, rel = request.query.get("root"), request.query.get("path", "")
    try:
        entries = store.list(root, rel)
        directory = store.resolve(root, rel)
    except FileAccessError as exc:
        raise web.HTTPNotFound(text=str(exc))
    return web.json_response({"entries": entries, "dir": str(directory), "sep": os.sep})


async def files_raw(request):
    try:
        target = request.app[FILES].file(request.query.get("root"),
                                         request.query.get("path", ""))
    except FileAccessError as exc:
        raise web.HTTPNotFound(text=str(exc))
    response = web.FileResponse(target)
    mode = "attachment" if request.query.get("download") else "inline"
    ascii_name = safe_filename(target.name.encode("ascii", "ignore").decode()) or "archivo"
    response.headers["Content-Disposition"] = (
        f"{mode}; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(target.name)}")
    response.headers["Cache-Control"] = "private, max-age=60"
    return response


async def save_multipart(request, target_for):
    reader = await request.multipart()
    saved = []
    async for part in reader:
        if part.name != "file" or not part.filename:
            continue
        target = target_for(part.filename)
        with open(target, "wb") as out:
            while chunk := await part.read_chunk(256 * 1024):
                out.write(chunk)
        saved.append(target)
    if not saved:
        raise web.HTTPBadRequest(text="no llegó ningún archivo")
    return saved


async def files_upload(request):
    store = request.app[FILES]
    root, rel = request.query.get("root"), request.query.get("path", "")
    try:
        store.resolve(root, rel)
        saved = await save_multipart(
            request, lambda name: store.upload_target(root, rel, name))
    except FileAccessError as exc:
        raise web.HTTPNotFound(text=str(exc))
    return web.json_response({"saved": [p.name for p in saved]})


async def attach(request):
    """Archivo adjunto en el chat: queda en la carpeta de subidas."""
    folder = request.app[UPLOADS]
    folder.mkdir(parents=True, exist_ok=True)
    saved = await save_multipart(
        request, lambda name: unique_path(folder, safe_filename(name)))
    return web.json_response({"paths": [str(p) for p in saved]})


# --- Ventanas, programas y acciones ---------------------------------------

async def windows_list(request):
    windows = await run_sync(request, INPUT_POOL, request.app[DESKTOP].list_windows)
    return web.json_response({"windows": windows})


async def windows_close(request):
    body = await json_body(request)
    await run_sync(request, INPUT_POOL, request.app[DESKTOP].close_window,
                   int(body.get("id", 0)))
    return web.json_response({"ok": True})


async def apps_list(request):
    apps = await run_sync(request, INPUT_POOL, request.app[DESKTOP].list_apps)
    return web.json_response({"apps": apps})


async def apps_launch(request):
    body = await json_body(request)
    try:
        await run_sync(request, INPUT_POOL, request.app[DESKTOP].launch_app,
                       int(body.get("id", -1)))
    except (ValueError, NotImplementedError, OSError) as exc:
        return web.json_response({"error": str(exc)}, status=400)
    return web.json_response({"ok": True})


async def action(request):
    body = await json_body(request)
    try:
        await run_sync(request, INPUT_POOL, request.app[DESKTOP].run_action,
                       str(body.get("action")))
    except (ValueError, NotImplementedError) as exc:
        return web.json_response({"error": str(exc)}, status=400)
    return web.json_response({"ok": True})


# --- Chat con Claude ------------------------------------------------------

async def claude_ws(request):
    app = request.app
    if not app[AUTH].check(request.query.get("token")):
        raise web.HTTPUnauthorized(text="token inválido")
    hub = app[HUB]
    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=256 * 1024)
    await ws.prepare(request)
    await ws.send_json({"t": "hello", "projects": hub.project_names(),
                        "busy": {n: s.busy for n, s in hub.sessions.items()}})
    hub.listeners.add(ws.send_json)
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                data = json.loads(msg.data)
                session = hub.get(data.get("project"))
                kind = data.get("t")
                if kind == "open":
                    await ws.send_json({"t": "history", "project": session.name,
                                        "events": session.events, "busy": session.busy})
                elif kind == "prompt":
                    text = data.get("text")
                    if not isinstance(text, str) or not text.strip() or len(text) > MAX_PROMPT:
                        raise ValueError("mensaje inválido")
                    await session.prompt(text.strip())
                elif kind == "perm":
                    session.resolve(str(data.get("id")), data.get("allow") is True)
                elif kind == "stop":
                    await session.interrupt()
                elif kind == "reset":
                    await session.reset()
                    await ws.send_json({"t": "history", "project": session.name,
                                        "events": [], "busy": False})
                else:
                    raise ValueError("tipo desconocido")
            except (ValueError, KeyError, RuntimeError, AttributeError) as exc:
                await ws.send_json({"t": "error", "msg": str(exc)})
    finally:
        hub.listeners.discard(ws.send_json)
    return ws


# --- Pantalla / ventana ---------------------------------------------------

async def stream_frames(ws, app, state):
    loop = asyncio.get_running_loop()
    capture = app[CAPTURE]
    force = True
    while not ws.closed:
        started = loop.time()
        frame = await loop.run_in_executor(
            app[CAPTURE_POOL], capture.grab, state["quality"], force)
        if frame is not None:
            state["acked"].clear()
            await ws.send_bytes(frame)
            force = False
            # Espera a que el celular dibuje el cuadro: así la red lenta
            # no acumula cuadros atrasados.
            try:
                await asyncio.wait_for(state["acked"].wait(), timeout=5)
            except asyncio.TimeoutError:
                force = True
        if state.pop("refresh", False):
            force = True
        delay = 1 / state["fps"] - (loop.time() - started)
        if delay > 0:
            await asyncio.sleep(delay)


def _int_query(request, name, default):
    try:
        return int(request.query.get(name, default))
    except (TypeError, ValueError):
        return default


async def screen_ws(request):
    app = request.app
    if not app[AUTH].check(request.query.get("token")):
        raise web.HTTPUnauthorized(text="token inválido")

    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=64 * 1024)
    await ws.prepare(request)

    # Una sola vista a la vez: la nueva desconecta a la anterior.
    previous = app[ACTIVE].get("ws")
    if previous is not None and not previous.closed:
        await previous.close(code=4000, message=b"otra vista se abrio")
    app[ACTIVE]["ws"] = ws

    loop = asyncio.get_running_loop()
    capture, desktop = app[CAPTURE], app[DESKTOP]
    hwnd = _int_query(request, "window", 0)
    if hwnd and desktop.supports_windows:
        try:
            rect = await loop.run_in_executor(
                app[INPUT_POOL], desktop.fit_window, hwnd,
                _int_query(request, "vw", 390), _int_query(request, "vh", 700),
                capture.monitor)
        except (ValueError, OSError) as exc:
            await ws.send_json({"t": "error", "msg": str(exc)})
            await ws.close()
            return ws
        await asyncio.sleep(0.25)  # que la ventana alcance a redibujarse
        await loop.run_in_executor(app[CAPTURE_POOL], capture.set_region, rect)
    else:
        hwnd = 0
        await loop.run_in_executor(app[CAPTURE_POOL], capture.set_region, None)

    region = capture.region
    await ws.send_json({"t": "hello", "w": region["width"], "h": region["height"]})

    state = {"quality": 60, "fps": 10, "acked": asyncio.Event()}
    streamer = asyncio.create_task(stream_frames(ws, app, state))
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                event = parse_event(json.loads(msg.data))
            except (ProtocolError, ValueError) as exc:
                await ws.send_json({"t": "error", "msg": str(exc)})
                continue
            kind = event["t"]
            if kind == "ack":
                state["acked"].set()
            elif kind == "refresh":
                state["refresh"] = True
            elif kind == "settings":
                state["quality"] = event["quality"]
                state["fps"] = event["fps"]
            else:
                await loop.run_in_executor(app[INPUT_POOL], app[INPUT].handle, event)
    finally:
        streamer.cancel()
        try:
            await streamer
        except (asyncio.CancelledError, ConnectionError):
            pass
        if app[ACTIVE].get("ws") is ws:
            app[ACTIVE].pop("ws")
            await loop.run_in_executor(app[CAPTURE_POOL], capture.set_region, None)
        if hwnd:
            await loop.run_in_executor(app[INPUT_POOL], desktop.restore_window, hwnd)
    return ws


# --- Aplicación -----------------------------------------------------------

PUBLIC_API = {"/api/pair"}


@web.middleware
async def guard(request, handler):
    if request.path.startswith("/api/") and request.path not in PUBLIC_API:
        if not request.app[AUTH].check(request_token(request)):
            return web.json_response({"error": "no autorizado"}, status=401)
    response = await handler(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    return response


def make_app(auth, capture, input_controller, desktop, files, hub, uploads):
    app = web.Application(middlewares=[guard], client_max_size=4 * 1024 ** 3)
    app[AUTH] = auth
    app[CAPTURE] = capture
    app[INPUT] = input_controller
    app[DESKTOP] = desktop
    app[FILES] = files
    app[HUB] = hub
    app[UPLOADS] = Path(uploads)
    # Un hilo por tarea: mss exige usarse siempre desde el mismo hilo y
    # un hilo dedicado de entrada conserva el orden de clics y teclas.
    app[CAPTURE_POOL] = ThreadPoolExecutor(max_workers=1)
    app[INPUT_POOL] = ThreadPoolExecutor(max_workers=1)
    app[ACTIVE] = {}

    async def shutdown(app):
        ws = app[ACTIVE].get("ws")
        if ws is not None:
            await ws.close()
        await app[HUB].close()
        app[CAPTURE_POOL].shutdown(wait=False)
        app[INPUT_POOL].shutdown(wait=False)

    app.on_shutdown.append(shutdown)
    r = app.router
    r.add_get("/", index)
    r.add_get("/sw.js", service_worker)
    r.add_post("/api/pair", pair)
    r.add_post("/api/unpair", unpair)
    r.add_get("/api/status", status)
    r.add_get("/api/files", files_list)
    r.add_get("/api/files/raw", files_raw)
    r.add_post("/api/files/upload", files_upload)
    r.add_post("/api/attach", attach)
    r.add_get("/api/windows", windows_list)
    r.add_post("/api/windows/close", windows_close)
    r.add_get("/api/apps", apps_list)
    r.add_post("/api/apps/launch", apps_launch)
    r.add_post("/api/action", action)
    r.add_get("/ws/claude", claude_ws)
    r.add_get("/ws/screen", screen_ws)
    r.add_static("/static", STATIC_DIR)
    return app


def local_ips():
    ips = set()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            ips.add(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    ips.discard("127.0.0.1")
    return sorted(ips) or ["127.0.0.1"]


def enable_windows_dpi_awareness():
    """Sin esto, Windows con escalado (125%, 150%) desalinea el mouse."""
    if sys.platform != "win32":
        return
    import ctypes

    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except (AttributeError, OSError):
        ctypes.windll.user32.SetProcessDPIAware()


def parse_args(argv=None):
    from remote_desktop.config import config_dir

    parser = argparse.ArgumentParser(description="Servidor Mi PC")
    parser.add_argument("--host", default="127.0.0.1",
                        help="127.0.0.1 = solo vía tailscale serve; 0.0.0.0 = toda la red")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--password", default=os.environ.get("RD_PASSWORD"),
        help="Fija la contraseña para vincular celulares (se guarda como hash). "
        "Si nunca se fijó, se genera una y se muestra.")
    parser.add_argument("--config", default=str(config_dir() / "config.json"))
    parser.add_argument("--monitor", type=int, default=1, help="1 = principal")
    parser.add_argument("--max-width", type=int, default=1280,
                        help="Ancho máximo de la imagen enviada")
    parser.add_argument("--allow-sleep", action="store_true",
                        help="Dejar que Windows suspenda el PC")
    parser.add_argument("--set-password", action="store_true",
                        help="Pedir una contraseña nueva, guardarla y salir")
    parser.add_argument("--revoke-devices", action="store_true",
                        help="Desvincular todos los celulares y salir")
    parser.add_argument("--cert", help="Certificado TLS para HTTPS")
    parser.add_argument("--key", help="Llave privada TLS para HTTPS")
    return parser.parse_args(argv)


def setup_logging(folder):
    from logging.handlers import RotatingFileHandler

    folder.mkdir(parents=True, exist_ok=True)
    handlers = [RotatingFileHandler(folder / "mi_pc.log", maxBytes=1_000_000,
                                    backupCount=2, encoding="utf-8")]
    if sys.stderr is not None:  # con pythonw no hay consola
        handlers.append(logging.StreamHandler())
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s",
                        handlers=handlers)


def main(argv=None):
    args = parse_args(argv)
    setup_logging(Path(args.config).parent)
    enable_windows_dpi_awareness()

    from remote_desktop.capture import ScreenCapture
    from remote_desktop.claude_agent import ClaudeHub
    from remote_desktop.config import StateStore, load_config
    from remote_desktop.desktop import make_desktop
    from remote_desktop.files import FileStore
    from remote_desktop.input_control import InputController

    config_path = Path(args.config)
    config = load_config(config_path)
    store = StateStore(config_path.with_name("state.json"))

    if args.set_password:
        import getpass

        while True:
            first = getpass.getpass("Contraseña para vincular el celular (mín. 8): ")
            if len(first) < 8:
                print("Muy corta, intenta de nuevo.")
            elif getpass.getpass("Repítela: ") != first:
                print("No coinciden, intenta de nuevo.")
            else:
                break
        Auth(first, store)
        print("Contraseña guardada.")
        return

    generated = None
    if args.password is None and not store.get("password"):
        generated = args.password = secrets.token_urlsafe(9)
    auth = Auth(args.password, store)
    if args.revoke_devices:
        auth.revoke_all()
        print("Todos los celulares quedaron desvinculados.")
        return

    desktop = make_desktop()
    if not args.allow_sleep:
        desktop.keep_awake()
    capture = ScreenCapture(args.monitor, args.max_width)
    controller = InputController(lambda: capture.region)
    files = FileStore(config["folders"])
    hub = ClaudeHub(config["projects"], store, extra_dirs=config["folders"].values())

    (config_path.parent / "mi_pc.pid").write_text(str(os.getpid()))

    ssl_context = None
    if args.cert and args.key:
        ssl_context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        ssl_context.load_cert_chain(args.cert, args.key)
    scheme = "https" if ssl_context else "http"

    print("\n=== Mi PC listo ===")
    if args.host in ("127.0.0.1", "localhost"):
        print(f"  Escuchando solo en este PC ({scheme}://127.0.0.1:{args.port}).")
        print(f"  Para el celular: tailscale serve --bg {args.port}")
    else:
        for ip in local_ips():
            print(f"  En la red: {scheme}://{ip}:{args.port}")
    if generated:
        print(f"  Contraseña para vincular el celular: {generated}")
    print(f"  Configuración: {config_path}")
    if not config["projects"]:
        print("  Aviso: no hay proyectos de Claude en config.json ('projects').")
    print("  Ctrl+C para detener\n")

    web.run_app(
        make_app(auth, capture, controller, desktop, files, hub, config["uploads"]),
        host=args.host,
        port=args.port,
        ssl_context=ssl_context,
        print=None,
        access_log=None,  # las URLs de WebSocket llevan el token
    )


if __name__ == "__main__":
    main()
