"""Captura de pantalla a JPEG usando mss + Pillow."""

import io

from PIL import Image


def encode_jpeg(rgb_bytes, size, max_width, quality):
    """Convierte píxeles RGB crudos en JPEG, reduciendo al ancho máximo."""
    image = Image.frombytes("RGB", size, rgb_bytes)
    if image.width > max_width:
        height = round(image.height * max_width / image.width)
        image = image.resize((max_width, height), Image.BILINEAR)
    out = io.BytesIO()
    image.save(out, "JPEG", quality=quality, optimize=False)
    return out.getvalue()


class ScreenCapture:
    """Captura un monitor. Usar siempre desde el mismo hilo (requisito de mss)."""

    def __init__(self, monitor_index=1, max_width=1280):
        import mss

        self._sct = mss.mss()
        monitors = self._sct.monitors
        if not 1 <= monitor_index < len(monitors):
            raise ValueError(
                f"monitor {monitor_index} no existe (hay {len(monitors) - 1})"
            )
        self.monitor = dict(monitors[monitor_index])
        self.max_width = max_width
        self._last_raw = None

    def grab(self, quality, force=False):
        """Devuelve un JPEG, o None si la pantalla no cambió."""
        shot = self._sct.grab(self.monitor)
        raw = shot.rgb
        if not force and raw == self._last_raw:
            return None
        self._last_raw = raw
        return encode_jpeg(raw, shot.size, self.max_width, quality)

    def close(self):
        self._sct.close()
