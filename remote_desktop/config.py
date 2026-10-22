"""Configuración y estado persistente del servidor.

config.json (lo editas tú):
    folders   carpetas que se ven en la pestaña Archivos
    projects  carpetas donde Claude puede trabajar
    uploads   carpeta donde quedan los archivos que adjuntas desde el celular

state.json (lo maneja el servidor): celulares vinculados, sesiones de
Claude e historial del chat.
"""

import json
import os
import sys
import threading
from pathlib import Path

APP_NAME = "MiPC"


def config_dir():
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home()))
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / APP_NAME


def _known_folder(guid, fallback):
    """Ruta real de Escritorio/Descargas en Windows (puede estar en OneDrive)."""
    if sys.platform != "win32":
        return fallback
    import ctypes
    from ctypes import wintypes
    import uuid

    class GUID(ctypes.Structure):
        _fields_ = [("data", ctypes.c_ubyte * 16)]

    folder = GUID()
    ctypes.memmove(folder.data, uuid.UUID(guid).bytes_le, 16)
    path = ctypes.c_wchar_p()
    shell32 = ctypes.windll.shell32
    shell32.SHGetKnownFolderPath.argtypes = [
        ctypes.POINTER(GUID), wintypes.DWORD, wintypes.HANDLE,
        ctypes.POINTER(ctypes.c_wchar_p),
    ]
    if shell32.SHGetKnownFolderPath(ctypes.byref(folder), 0, None, ctypes.byref(path)):
        return fallback
    try:
        return Path(path.value)
    finally:
        ctypes.windll.ole32.CoTaskMemFree(path)


def default_config():
    home = Path.home()
    desktop = _known_folder("B4BFCC3A-DB2C-424C-B029-7FE99A87C641", home / "Desktop")
    downloads = _known_folder("374DE290-123F-4565-9164-39C4925E467B", home / "Downloads")
    return {
        "_ayuda": "En projects agrega cada carpeta donde trabaja Claude: un nombre corto "
                  "y su ruta escrita con / (ej. claude -> C:/Users/tu/repos/claude). "
                  "Guarda y ejecuta reiniciar_mi_pc.bat.",
        "folders": {"Escritorio": str(desktop), "Descargas": str(downloads)},
        "projects": {},
        "uploads": str(downloads / APP_NAME),
    }


def load_config(path):
    path = Path(path)
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(default_config(), indent=2, ensure_ascii=False),
                        encoding="utf-8")
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in ("folders", "projects"):
        if not isinstance(data.get(key), dict):
            raise ValueError(f"config.json: '{key}' debe ser un objeto nombre → ruta")
    folders = {name: Path(p).expanduser() for name, p in data["folders"].items()}
    projects = {name: Path(p).expanduser() for name, p in data["projects"].items()}
    uploads = Path(data.get("uploads") or default_config()["uploads"]).expanduser()
    return {"folders": folders, "projects": projects, "uploads": uploads}


class StateStore:
    """Diccionario JSON guardado en disco, seguro entre hilos."""

    def __init__(self, path):
        self._path = Path(path)
        self._lock = threading.Lock()
        try:
            self._data = json.loads(self._path.read_text(encoding="utf-8"))
        except (FileNotFoundError, ValueError):
            self._data = {}

    def get(self, key, default=None):
        with self._lock:
            return json.loads(json.dumps(self._data.get(key, default)))

    def set(self, key, value):
        with self._lock:
            self._data[key] = value
            self._path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self._path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self._data, ensure_ascii=False), encoding="utf-8")
            os.replace(tmp, self._path)
