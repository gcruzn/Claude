"""Acceso a archivos limitado a las carpetas permitidas en config.json."""

import re
from pathlib import Path

HIDDEN = {"desktop.ini", "thumbs.db"}
FORBIDDEN_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
RESERVED = {"con", "prn", "aux", "nul"} | {f"com{i}" for i in range(1, 10)} | {
    f"lpt{i}" for i in range(1, 10)}


class FileAccessError(Exception):
    """Ruta fuera de las carpetas permitidas o inexistente."""


def safe_filename(name):
    name = FORBIDDEN_CHARS.sub("_", Path(str(name)).name).strip(" .")
    if not name or name.split(".")[0].lower() in RESERVED:
        name = "archivo_" + name
    return name[:200]


def unique_path(directory, name):
    target = directory / name
    stem, suffix = target.stem, target.suffix
    n = 1
    while target.exists():
        target = directory / f"{stem} ({n}){suffix}"
        n += 1
    return target


class FileStore:
    def __init__(self, roots):
        self.roots = {name: Path(p) for name, p in roots.items()}

    def resolve(self, root, rel=""):
        base = self.roots.get(root)
        if base is None:
            raise FileAccessError(f"carpeta no permitida: {root!r}")
        base = base.resolve()
        rel = str(rel or "").replace("\\", "/").strip("/")
        target = (base / rel).resolve()
        if target != base and base not in target.parents:
            raise FileAccessError("ruta fuera de la carpeta permitida")
        if not target.exists():
            raise FileAccessError("no existe")
        return target

    def list(self, root, rel=""):
        directory = self.resolve(root, rel)
        if not directory.is_dir():
            raise FileAccessError("no es una carpeta")
        entries = []
        for item in directory.iterdir():
            if item.name.startswith(".") or item.name.lower() in HIDDEN:
                continue
            try:
                stat = item.stat()
                is_dir = item.is_dir()
            except OSError:
                continue
            entries.append({
                "name": item.name,
                "dir": is_dir,
                "size": None if is_dir else stat.st_size,
                "mtime": int(stat.st_mtime),
            })
        entries.sort(key=lambda e: (not e["dir"], -e["mtime"]))
        return entries

    def file(self, root, rel):
        target = self.resolve(root, rel)
        if not target.is_file():
            raise FileAccessError("no es un archivo")
        return target

    def upload_target(self, root, rel_dir, filename):
        directory = self.resolve(root, rel_dir)
        if not directory.is_dir():
            raise FileAccessError("no es una carpeta")
        return unique_path(directory, safe_filename(filename))
