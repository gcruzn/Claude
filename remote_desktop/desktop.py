"""Ventanas, programas y acciones del PC (Windows).

En otros sistemas la lista de ventanas viene vacía y la app usa la
pantalla completa; así el resto se puede probar fuera de Windows.
"""

import os
import sys
from pathlib import Path, PureWindowsPath

FRIENDLY_APPS = {
    "chrome": "Chrome", "msedge": "Edge", "firefox": "Firefox",
    "excel": "Excel", "winword": "Word", "powerpnt": "PowerPoint",
    "outlook": "Outlook", "olk": "Outlook", "onenote": "OneNote",
    "teams": "Teams", "ms-teams": "Teams", "code": "VS Code",
    "explorer": "Explorador", "windowsterminal": "Terminal",
    "powershell": "PowerShell", "cmd": "Símbolo del sistema",
    "notepad": "Bloc de notas", "acrord32": "Acrobat", "acrobat": "Acrobat",
    "spotify": "Spotify", "whatsapp": "WhatsApp",
}

ACTIONS = ("lock", "sleep", "volume_up", "volume_down", "mute")


def friendly_app(exe_path):
    stem = PureWindowsPath(exe_path or "").stem.lower()
    return FRIENDLY_APPS.get(stem, stem.capitalize() or "Programa")


def phone_window_size(phone_w, phone_h, monitor_w, monitor_h):
    """Tamaño en píxeles del PC para que una ventana se lea bien en el celular.

    El ancho se acerca al ancho del celular (un poco más, porque muchas
    apps de escritorio no se achican menos de ~420 px) y el alto sigue la
    proporción del celular, sin pasarse del monitor.
    """
    aspect = max(0.3, min(phone_w / max(phone_h, 1), 3.0))
    width = min(max(round(phone_w * 1.15), 420), monitor_w)
    height = round(width / aspect)
    if height > monitor_h - 60:
        height = monitor_h - 60
        width = min(round(height * aspect), monitor_w)
    return width, height


def start_menu_apps():
    roots = [
        Path(os.environ.get("PROGRAMDATA", "")) / "Microsoft/Windows/Start Menu/Programs",
        Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs",
    ]
    apps = {}
    for root in roots:
        if not root.is_dir():
            continue
        for link in root.rglob("*.lnk"):
            name = link.stem
            lower = name.lower()
            if "uninstall" in lower or "desinstal" in lower:
                continue
            apps.setdefault(lower, {"name": name, "path": str(link)})
    return sorted(apps.values(), key=lambda a: a["name"].lower())


class GenericDesktop:
    supports_windows = False

    def __init__(self):
        self._apps = None

    def list_windows(self):
        return []

    def fit_window(self, hwnd, phone_w, phone_h, monitor):
        return None

    def restore_window(self, hwnd):
        pass

    def close_window(self, hwnd):
        pass

    def list_apps(self):
        if self._apps is None:
            self._apps = start_menu_apps()
        return [{"id": i, "name": a["name"]} for i, a in enumerate(self._apps)]

    def launch_app(self, app_id):
        apps = self._apps if self._apps is not None else start_menu_apps()
        if not 0 <= app_id < len(apps):
            raise ValueError("programa no encontrado")
        if not hasattr(os, "startfile"):
            raise NotImplementedError("solo disponible en Windows")
        os.startfile(apps[app_id]["path"])

    def run_action(self, action, keyboard=None):
        if action not in ACTIONS:
            raise ValueError("acción desconocida")
        if action.startswith("volume") or action == "mute":
            from pynput.keyboard import Controller, Key

            key = {"volume_up": Key.media_volume_up,
                   "volume_down": Key.media_volume_down,
                   "mute": Key.media_volume_mute}[action]
            kb = keyboard or Controller()
            for _ in range(1 if action == "mute" else 3):
                kb.tap(key)
            return
        raise NotImplementedError("solo disponible en Windows")

    def keep_awake(self):
        pass


class WindowsDesktop(GenericDesktop):
    supports_windows = True

    def __init__(self):
        super().__init__()
        import ctypes
        from ctypes import wintypes

        self._ctypes = ctypes
        self._wt = wintypes
        self.user32 = ctypes.windll.user32
        self.kernel32 = ctypes.windll.kernel32
        self.dwmapi = ctypes.windll.dwmapi
        self._placements = {}

        u = self.user32
        HWND = wintypes.HWND
        for name, args in {
            "IsWindowVisible": [HWND], "GetWindowTextLengthW": [HWND],
            "GetWindowTextW": [HWND, wintypes.LPWSTR, ctypes.c_int],
            "GetWindow": [HWND, wintypes.UINT],
            "GetWindowLongW": [HWND, ctypes.c_int],
            "GetWindowThreadProcessId": [HWND, ctypes.POINTER(wintypes.DWORD)],
            "ShowWindow": [HWND, ctypes.c_int], "SetForegroundWindow": [HWND],
            "SetWindowPos": [HWND, HWND, ctypes.c_int, ctypes.c_int,
                             ctypes.c_int, ctypes.c_int, wintypes.UINT],
            "GetWindowPlacement": [HWND, ctypes.c_void_p],
            "SetWindowPlacement": [HWND, ctypes.c_void_p],
            "PostMessageW": [HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM],
            "IsWindow": [HWND],
        }.items():
            getattr(u, name).argtypes = args
        u.GetWindow.restype = HWND
        self.dwmapi.DwmGetWindowAttribute.argtypes = [
            HWND, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD]

        class WINDOWPLACEMENT(ctypes.Structure):
            _fields_ = [("length", wintypes.UINT), ("flags", wintypes.UINT),
                        ("showCmd", wintypes.UINT), ("ptMinPosition", wintypes.POINT),
                        ("ptMaxPosition", wintypes.POINT),
                        ("rcNormalPosition", wintypes.RECT)]

        self.WINDOWPLACEMENT = WINDOWPLACEMENT

    def _exe(self, hwnd):
        wt = self._wt
        pid = wt.DWORD()
        self.user32.GetWindowThreadProcessId(hwnd, self._ctypes.byref(pid))
        handle = self.kernel32.OpenProcess(0x1000, False, pid.value)
        if not handle:
            return ""
        try:
            size = wt.DWORD(1024)
            buf = self._ctypes.create_unicode_buffer(size.value)
            if self.kernel32.QueryFullProcessImageNameW(
                    handle, 0, buf, self._ctypes.byref(size)):
                return buf.value
            return ""
        finally:
            self.kernel32.CloseHandle(handle)

    def list_windows(self):
        ctypes, wt, u = self._ctypes, self._wt, self.user32
        own_pid = os.getpid()
        found = []

        @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
        def callback(hwnd, _):
            if not u.IsWindowVisible(hwnd) or u.GetWindow(hwnd, 4):  # GW_OWNER
                return True
            if u.GetWindowLongW(hwnd, -20) & 0x80:  # WS_EX_TOOLWINDOW
                return True
            cloaked = wt.DWORD()
            self.dwmapi.DwmGetWindowAttribute(hwnd, 14, ctypes.byref(cloaked), 4)
            if cloaked.value:
                return True
            length = u.GetWindowTextLengthW(hwnd)
            if not length:
                return True
            buf = ctypes.create_unicode_buffer(length + 1)
            u.GetWindowTextW(hwnd, buf, length + 1)
            pid = wt.DWORD()
            u.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            if pid.value == own_pid or buf.value == "Program Manager":
                return True
            found.append({"id": int(hwnd), "title": buf.value,
                          "app": friendly_app(self._exe(hwnd))})
            return True

        u.EnumWindows(callback, 0)
        return found

    def _bring_to_front(self, hwnd):
        # Windows solo deja pasar al frente si "hubo teclado": un Alt suelto basta.
        self.user32.keybd_event(0x12, 0, 0, 0)
        self.user32.keybd_event(0x12, 0, 2, 0)
        self.user32.SetForegroundWindow(hwnd)

    def window_rect(self, hwnd):
        rect = self._wt.RECT()
        self.dwmapi.DwmGetWindowAttribute(  # DWMWA_EXTENDED_FRAME_BOUNDS
            hwnd, 9, self._ctypes.byref(rect), self._ctypes.sizeof(rect))
        return {"left": rect.left, "top": rect.top,
                "width": rect.right - rect.left, "height": rect.bottom - rect.top}

    def fit_window(self, hwnd, phone_w, phone_h, monitor):
        if not self.user32.IsWindow(hwnd):
            raise ValueError("la ventana ya no existe")
        if hwnd not in self._placements:
            placement = self.WINDOWPLACEMENT()
            placement.length = self._ctypes.sizeof(placement)
            self.user32.GetWindowPlacement(hwnd, self._ctypes.byref(placement))
            self._placements[hwnd] = placement
        width, height = phone_window_size(
            phone_w, phone_h, monitor["width"], monitor["height"])
        left = monitor["left"] + (monitor["width"] - width) // 2
        top = monitor["top"] + max(0, (monitor["height"] - height) // 2)
        self.user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        self.user32.SetWindowPos(hwnd, None, left, top, width, height, 0x0004 | 0x0040)
        self._bring_to_front(hwnd)
        return self.window_rect(hwnd)

    def restore_window(self, hwnd):
        placement = self._placements.pop(hwnd, None)
        if placement is not None and self.user32.IsWindow(hwnd):
            self.user32.SetWindowPlacement(hwnd, self._ctypes.byref(placement))

    def close_window(self, hwnd):
        self.restore_window(hwnd)
        self.user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE

    def run_action(self, action, keyboard=None):
        if action == "lock":
            self.user32.LockWorkStation()
        elif action == "sleep":
            self._ctypes.windll.powrprof.SetSuspendState(False, True, False)
        else:
            super().run_action(action, keyboard)

    def keep_awake(self):
        # ES_CONTINUOUS | ES_SYSTEM_REQUIRED: el PC no se suspende mientras
        # el servidor esté abierto (la pantalla sí puede apagarse).
        self.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)


def make_desktop():
    return WindowsDesktop() if sys.platform == "win32" else GenericDesktop()
