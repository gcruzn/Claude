"""Servidor de escritorio remoto: corre en el PC y lo controla el celular.

Uso:
    python -m remote_desktop.server --password "una-clave-larga"

Luego abre http://IP-DEL-PC:8765 en el navegador del celular.
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

from aiohttp import WSMsgType, web

from remote_desktop.auth import Auth
from remote_desktop.protocol import ProtocolError, parse_event

STATIC_DIR = Path(__file__).parent / "static"
log = logging.getLogger("remote_desktop")

AUTH = web.AppKey("auth", Auth)
CAPTURE = web.AppKey("capture", object)
INPUT = web.AppKey("input", object)
CAPTURE_POOL = web.AppKey("capture_pool", ThreadPoolExecutor)
INPUT_POOL = web.AppKey("input_pool", ThreadPoolExecutor)
ACTIVE = web.AppKey("active", dict)


def client_id(request):
    return request.remote or "desconocido"


async def index(request):
    response = web.FileResponse(STATIC_DIR / "index.html")
    response.headers["Cache-Control"] = "no-store"
    return response


async def login(request):
    auth = request.app[AUTH]
    client = client_id(request)
    if auth.is_locked(client):
        return web.json_response(
            {"error": "Demasiados intentos. Espera unos minutos."}, status=429
        )
    try:
        body = await request.json()
    except (ValueError, UnicodeDecodeError):
        return web.json_response({"error": "JSON inválido"}, status=400)
    token = auth.login(client, body.get("password") if isinstance(body, dict) else None)
    if token is None:
        log.warning("Login fallido desde %s", client)
        return web.json_response({"error": "Contraseña incorrecta"}, status=401)
    log.info("Login correcto desde %s", client)
    return web.json_response({"token": token})


async def logout(request):
    try:
        body = await request.json()
    except (ValueError, UnicodeDecodeError):
        body = {}
    if isinstance(body, dict) and isinstance(body.get("token"), str):
        request.app[AUTH].logout(body["token"])
    return web.json_response({"ok": True})


async def stream_frames(ws, app, state):
    loop = asyncio.get_running_loop()
    capture = app[CAPTURE]
    force = True
    while not ws.closed:
        started = loop.time()
        frame = await loop.run_in_executor(
            app[CAPTURE_POOL], capture.grab, state["quality"], force
        )
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


async def websocket(request):
    app = request.app
    if not app[AUTH].check(request.query.get("token")):
        raise web.HTTPUnauthorized(text="token inválido")

    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=64 * 1024)
    await ws.prepare(request)

    # Una sola sesión a la vez: la nueva desconecta a la anterior.
    previous = app[ACTIVE].get("ws")
    if previous is not None and not previous.closed:
        await previous.close(code=4000, message=b"otra sesion se conecto")
    app[ACTIVE]["ws"] = ws

    monitor = app[CAPTURE].monitor
    await ws.send_json({"t": "hello", "w": monitor["width"], "h": monitor["height"]})
    log.info("Sesión iniciada desde %s", client_id(request))

    state = {"quality": 60, "fps": 10, "acked": asyncio.Event()}
    streamer = asyncio.create_task(stream_frames(ws, app, state))
    loop = asyncio.get_running_loop()
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
        log.info("Sesión terminada")
    return ws


@web.middleware
async def security_headers(request, handler):
    response = await handler(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    return response


def make_app(auth, capture, input_controller):
    app = web.Application(middlewares=[security_headers])
    app[AUTH] = auth
    app[CAPTURE] = capture
    app[INPUT] = input_controller
    # Un hilo por tarea: mss exige usarse siempre desde el mismo hilo y
    # un hilo dedicado de entrada conserva el orden de clics y teclas.
    app[CAPTURE_POOL] = ThreadPoolExecutor(max_workers=1)
    app[INPUT_POOL] = ThreadPoolExecutor(max_workers=1)
    app[ACTIVE] = {}

    async def shutdown(app):
        ws = app[ACTIVE].get("ws")
        if ws is not None:
            await ws.close()
        app[CAPTURE_POOL].shutdown(wait=False)
        app[INPUT_POOL].shutdown(wait=False)

    app.on_shutdown.append(shutdown)
    app.router.add_get("/", index)
    app.router.add_post("/api/login", login)
    app.router.add_post("/api/logout", logout)
    app.router.add_get("/ws", websocket)
    app.router.add_static("/static", STATIC_DIR)
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
    parser = argparse.ArgumentParser(description="Servidor de escritorio remoto")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--password",
        default=os.environ.get("RD_PASSWORD"),
        help="Contraseña de acceso (o variable RD_PASSWORD). "
        "Si falta, se genera una al azar.",
    )
    parser.add_argument("--monitor", type=int, default=1, help="1 = principal")
    parser.add_argument(
        "--max-width", type=int, default=1280,
        help="Ancho máximo de la imagen enviada (menos = más fluido)",
    )
    parser.add_argument("--cert", help="Certificado TLS para HTTPS")
    parser.add_argument("--key", help="Llave privada TLS para HTTPS")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    enable_windows_dpi_awareness()

    from remote_desktop.capture import ScreenCapture
    from remote_desktop.input_control import InputController

    password = args.password or secrets.token_urlsafe(9)
    auth = Auth(password)
    capture = ScreenCapture(args.monitor, args.max_width)
    controller = InputController(capture.monitor)

    ssl_context = None
    if args.cert and args.key:
        ssl_context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        ssl_context.load_cert_chain(args.cert, args.key)
    scheme = "https" if ssl_context else "http"

    print("\n=== Escritorio remoto listo ===")
    for ip in local_ips():
        print(f"  Abre en el celular: {scheme}://{ip}:{args.port}")
    if not args.password:
        print(f"  Contraseña generada: {password}")
    print("  Ctrl+C para detener\n")

    web.run_app(
        make_app(auth, capture, controller),
        host=args.host,
        port=args.port,
        ssl_context=ssl_context,
        print=None,
        access_log=None,  # la URL del WebSocket lleva el token
    )


if __name__ == "__main__":
    main()
