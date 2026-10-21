import asyncio

from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer

from remote_desktop.auth import Auth
from remote_desktop.server import make_app

PASSWORD = "clave-segura-123"


class FakeCapture:
    monitor = {"left": 0, "top": 0, "width": 1920, "height": 1080}

    def __init__(self):
        self.grabs = 0

    def grab(self, quality, force=False):
        self.grabs += 1
        return b"\xff\xd8frame" if force or self.grabs % 2 else None


class FakeInput:
    def __init__(self):
        self.events = []

    def handle(self, event):
        self.events.append(event)


def run(scenario):
    async def main():
        capture, controller = FakeCapture(), FakeInput()
        app = make_app(Auth(PASSWORD), capture, controller)
        async with TestClient(TestServer(app)) as client:
            await scenario(client, controller)
    asyncio.run(main())


async def login(client):
    res = await client.post("/api/login", json={"password": PASSWORD})
    assert res.status == 200
    return (await res.json())["token"]


def test_index_served():
    async def scenario(client, _):
        res = await client.get("/")
        assert res.status == 200
        assert "Mi Escritorio Remoto" in await res.text()
        assert res.headers["X-Frame-Options"] == "DENY"
    run(scenario)


def test_login_rejects_wrong_password_and_locks_out():
    async def scenario(client, _):
        for _ in range(5):
            res = await client.post("/api/login", json={"password": "mala"})
            assert res.status == 401
        res = await client.post("/api/login", json={"password": PASSWORD})
        assert res.status == 429
    run(scenario)


def test_websocket_requires_token():
    async def scenario(client, _):
        res = await client.get("/ws?token=falso")
        assert res.status == 401
    run(scenario)


def test_websocket_streams_frames_and_forwards_input():
    async def scenario(client, controller):
        token = await login(client)
        ws = await client.ws_connect(f"/ws?token={token}")
        hello = await ws.receive_json()
        assert hello == {"t": "hello", "w": 1920, "h": 1080}

        frame = await ws.receive(timeout=2)
        assert frame.type == WSMsgType.BINARY
        assert frame.data.startswith(b"\xff\xd8")
        await ws.send_json({"t": "ack"})

        await ws.send_json({"t": "move", "x": 0.5, "y": 0.5})
        await ws.send_json({"t": "click", "b": "right"})
        await ws.send_json({"t": "key", "k": "no-existe"})
        while True:
            msg = await ws.receive(timeout=2)
            if msg.type == WSMsgType.TEXT:
                assert msg.json()["t"] == "error"
                break
            await ws.send_json({"t": "ack"})
        assert controller.events == [
            {"t": "move", "x": 0.5, "y": 0.5},
            {"t": "click", "b": "right", "n": 1},
        ]
        await ws.close()
    run(scenario)


def test_new_session_replaces_old_one():
    async def scenario(client, _):
        token = await login(client)
        first = await client.ws_connect(f"/ws?token={token}")
        await first.receive_json()
        second = await client.ws_connect(f"/ws?token={token}")
        await second.receive_json()
        while True:
            msg = await first.receive(timeout=2)
            if msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSED):
                break
        assert first.close_code == 4000
        await second.close()
    run(scenario)


def test_logout_invalidates_token():
    async def scenario(client, _):
        token = await login(client)
        await client.post("/api/logout", json={"token": token})
        res = await client.get(f"/ws?token={token}")
        assert res.status == 401
    run(scenario)
