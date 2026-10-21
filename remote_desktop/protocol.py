"""Validación de los mensajes de entrada que manda el celular.

El cliente envía JSON con coordenadas normalizadas (0..1) para que no
dependa de la resolución del PC. Todo mensaje se valida aquí antes de
llegar al controlador de mouse/teclado.
"""

BUTTONS = {"left", "right", "middle"}

SPECIAL_KEYS = {
    "enter", "backspace", "tab", "esc", "space", "delete",
    "up", "down", "left", "right", "home", "end",
    "page_up", "page_down", "insert",
    "ctrl", "alt", "shift", "cmd",
    "f1", "f2", "f3", "f4", "f5", "f6",
    "f7", "f8", "f9", "f10", "f11", "f12",
}

MODIFIERS = {"ctrl", "alt", "shift", "cmd"}

MAX_TEXT = 1000
MAX_SCROLL = 50


class ProtocolError(ValueError):
    """Mensaje inválido del cliente."""


def _norm(value):
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ProtocolError("coordenada inválida")
    return min(max(float(value), 0.0), 1.0)


def _button(msg):
    button = msg.get("b", "left")
    if button not in BUTTONS:
        raise ProtocolError(f"botón inválido: {button!r}")
    return button


def _key(name):
    if not isinstance(name, str):
        raise ProtocolError("tecla inválida")
    name = name.lower()
    if name in SPECIAL_KEYS or len(name) == 1:
        return name
    raise ProtocolError(f"tecla inválida: {name!r}")


def _int(value, limit):
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ProtocolError("número inválido")
    return max(-limit, min(limit, int(value)))


def parse_event(msg):
    """Valida un mensaje del cliente y devuelve un dict normalizado."""
    if not isinstance(msg, dict):
        raise ProtocolError("el mensaje debe ser un objeto")
    kind = msg.get("t")

    if kind in ("ack", "refresh"):
        return {"t": kind}
    if kind == "move":
        return {"t": "move", "x": _norm(msg.get("x")), "y": _norm(msg.get("y"))}
    if kind in ("down", "up"):
        return {"t": kind, "b": _button(msg)}
    if kind == "click":
        event = {"t": "click", "b": _button(msg)}
        count = msg.get("n", 1)
        if count not in (1, 2):
            raise ProtocolError("n debe ser 1 o 2")
        event["n"] = count
        return event
    if kind == "scroll":
        return {
            "t": "scroll",
            "dx": _int(msg.get("dx", 0), MAX_SCROLL),
            "dy": _int(msg.get("dy", 0), MAX_SCROLL),
        }
    if kind == "text":
        text = msg.get("s")
        if not isinstance(text, str) or not text or len(text) > MAX_TEXT:
            raise ProtocolError("texto inválido")
        return {"t": "text", "s": text}
    if kind == "key":
        return {"t": "key", "k": _key(msg.get("k"))}
    if kind == "combo":
        keys = msg.get("k")
        if not isinstance(keys, list) or not 2 <= len(keys) <= 4:
            raise ProtocolError("combo inválido")
        keys = [_key(k) for k in keys]
        if not all(k in MODIFIERS for k in keys[:-1]):
            raise ProtocolError("combo: solo modificadores antes de la tecla")
        return {"t": "combo", "k": keys}
    if kind == "settings":
        quality = _int(msg.get("quality", 60), 95)
        fps = _int(msg.get("fps", 10), 30)
        return {
            "t": "settings",
            "quality": max(10, quality),
            "fps": max(1, fps),
        }
    raise ProtocolError(f"tipo desconocido: {kind!r}")


def to_screen(x, y, monitor):
    """Convierte coordenadas normalizadas a píxeles del monitor."""
    px = monitor["left"] + round(x * (monitor["width"] - 1))
    py = monitor["top"] + round(y * (monitor["height"] - 1))
    return px, py
