"""Dobles de prueba: PC, pantalla y Claude simulados."""

import asyncio

from claude_agent_sdk import (AssistantMessage, ResultMessage, TextBlock,
                              ToolPermissionContext, ToolResultBlock, ToolUseBlock,
                              UserMessage)


class FakeCapture:
    monitor = {"left": 0, "top": 0, "width": 1920, "height": 1080}

    def __init__(self):
        self.region = self.monitor
        self.grabs = 0

    def set_region(self, rect=None):
        self.region = dict(rect) if rect else self.monitor

    def grab(self, quality, force=False):
        self.grabs += 1
        return b"\xff\xd8frame" if force or self.grabs % 2 else None


class FakeInput:
    def __init__(self):
        self.events = []

    def handle(self, event):
        self.events.append(event)


class FakeDesktop:
    supports_windows = True

    def __init__(self):
        self.calls = []

    def list_windows(self):
        return [{"id": 42, "title": "presupuesto.xlsx - Excel", "app": "Excel"}]

    def fit_window(self, hwnd, phone_w, phone_h, monitor):
        self.calls.append(("fit", hwnd, phone_w, phone_h))
        return {"left": 700, "top": 100, "width": 450, "height": 800}

    def restore_window(self, hwnd):
        self.calls.append(("restore", hwnd))

    def close_window(self, hwnd):
        self.calls.append(("close", hwnd))

    def list_apps(self):
        return [{"id": 0, "name": "Excel"}]

    def launch_app(self, app_id):
        if app_id != 0:
            raise ValueError("programa no encontrado")
        self.calls.append(("launch", app_id))

    def run_action(self, action):
        if action not in ("lock", "sleep", "volume_up", "volume_down", "mute"):
            raise ValueError("acción desconocida")
        self.calls.append(("action", action))


class FakeClaudeClient:
    """Imita ClaudeSDKClient: lee un archivo, pide permiso para pytest y responde."""

    instances = []

    def __init__(self, options):
        self.options = options
        self.prompts = []
        self.connected = False
        FakeClaudeClient.instances.append(self)

    async def connect(self):
        self.connected = True

    async def query(self, text):
        self.prompts.append(text)

    async def receive_response(self):
        yield AssistantMessage(content=[
            TextBlock("Reviso los tests."),
            ToolUseBlock("t1", "Read", {"file_path": "C:/repo/app.py"}),
        ], model="fake")
        yield UserMessage(content=[ToolResultBlock("t1", "def add(a, b): ...", False)])
        yield AssistantMessage(content=[ToolUseBlock(
            "t2", "Edit", {"file_path": "C:/repo/app.py",
                           "old_string": "return a + b",
                           "new_string": "if a is None:\n    raise ValueError\nreturn a + b"})],
            model="fake")
        decision = await self.options.can_use_tool(
            "Bash", {"command": "pytest tests/"}, ToolPermissionContext())
        allowed = decision.behavior == "allow"
        yield AssistantMessage(content=[TextBlock(
            "Los tests pasan." if allowed else "No corrí los tests.")], model="fake")
        yield ResultMessage(subtype="success", duration_ms=10, duration_api_ms=5,
                            is_error=False, num_turns=2, session_id="sess-123")

    async def interrupt(self):
        pass

    async def disconnect(self):
        self.connected = False


async def wait_for(predicate, timeout=2.0):
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while not predicate():
        if loop.time() > end:
            raise AssertionError("tiempo de espera agotado")
        await asyncio.sleep(0.01)
