"""Ejecuta en el PC los eventos de mouse y teclado ya validados."""

from remote_desktop.protocol import to_screen


class InputController:
    def __init__(self, region):
        """region: función que devuelve el rectángulo que se está mostrando."""
        from pynput import keyboard, mouse

        self._region = region
        self._mouse = mouse.Controller()
        self._keyboard = keyboard.Controller()
        self._buttons = {
            "left": mouse.Button.left,
            "right": mouse.Button.right,
            "middle": mouse.Button.middle,
        }
        self._Key = keyboard.Key

    def _resolve(self, name):
        if len(name) == 1:
            return name
        return getattr(self._Key, name)

    def handle(self, event):
        kind = event["t"]
        if kind == "move":
            self._mouse.position = to_screen(event["x"], event["y"], self._region())
        elif kind == "down":
            self._mouse.press(self._buttons[event["b"]])
        elif kind == "up":
            self._mouse.release(self._buttons[event["b"]])
        elif kind == "click":
            self._mouse.click(self._buttons[event["b"]], event["n"])
        elif kind == "scroll":
            self._mouse.scroll(event["dx"], event["dy"])
        elif kind == "text":
            self._keyboard.type(event["s"])
        elif kind == "key":
            self._keyboard.tap(self._resolve(event["k"]))
        elif kind == "combo":
            keys = [self._resolve(k) for k in event["k"]]
            for key in keys:
                self._keyboard.press(key)
            for key in reversed(keys):
                self._keyboard.release(key)
