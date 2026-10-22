import asyncio

import aiohttp
from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer

from remote_desktop.auth import Auth
from remote_desktop.claude_agent import ClaudeHub
from remote_desktop.files import FileStore
from remote_desktop.server import make_app
from tests.fakes import (FakeCapture, FakeClaudeClient, FakeDesktop, FakeInput,
                         wait_for)

PASSWORD = "clave-segura-123"


def run(tmp_path, scenario):
    async def main():
        desk = tmp_path / "Escritorio"
        desk.mkdir()
        (desk / "notas.txt").write_text("hola desde el PC", encoding="utf-8")
        ctx = {
            "input": FakeInput(),
            "desktop": FakeDesktop(),
            "capture": FakeCapture(),
            "desk": desk,
            "uploads": tmp_path / "subidas",
        }
        hub = ClaudeHub({"claude": tmp_path}, client_factory=FakeClaudeClient)
        app = make_app(Auth(PASSWORD), ctx["capture"], ctx["input"], ctx["desktop"],
                       FileStore({"Escritorio": desk}), hub, ctx["uploads"])
        async with TestClient(TestServer(app)) as client:
            await scenario(client, ctx)
    asyncio.run(main())


async def pair(client):
    res = await client.post("/api/pair", json={"password": PASSWORD, "device": "Pixel"})
    assert res.status == 200
    return (await res.json())["token"]


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def test_index_and_security_headers(tmp_path):
    async def scenario(client, _):
        res = await client.get("/")
        assert res.status == 200
        assert "Mi PC" in await res.text()
        assert res.headers["X-Frame-Options"] == "DENY"
    run(tmp_path, scenario)


def test_api_requires_pairing(tmp_path):
    async def scenario(client, _):
        for path in ("/api/status", "/api/files?root=Escritorio", "/api/windows"):
            assert (await client.get(path)).status == 401
        assert (await client.get("/ws/claude?token=x")).status == 401
        assert (await client.get("/ws/screen?token=x")).status == 401
    run(tmp_path, scenario)


def test_pairing_lockout(tmp_path):
    async def scenario(client, _):
        for _ in range(5):
            res = await client.post("/api/pair", json={"password": "mala"})
            assert res.status == 401
        res = await client.post("/api/pair", json={"password": PASSWORD})
        assert res.status == 429
    run(tmp_path, scenario)


def test_status_and_unpair(tmp_path):
    async def scenario(client, _):
        token = await pair(client)
        res = await client.get("/api/status", headers=auth(token))
        body = await res.json()
        assert body["folders"] == ["Escritorio"] and body["projects"] == ["claude"]
        await client.post("/api/unpair", headers=auth(token))
        assert (await client.get("/api/status", headers=auth(token))).status == 401
    run(tmp_path, scenario)


def test_files_list_download_and_upload(tmp_path):
    async def scenario(client, ctx):
        token = await pair(client)
        res = await client.get("/api/files?root=Escritorio", headers=auth(token))
        assert [e["name"] for e in (await res.json())["entries"]] == ["notas.txt"]

        res = await client.get(
            f"/api/files/raw?root=Escritorio&path=notas.txt&download=1&token={token}")
        assert await res.text() == "hola desde el PC"
        assert res.headers["Content-Disposition"].startswith("attachment;")

        res = await client.get("/api/files/raw?root=Escritorio&path=../x", headers=auth(token))
        assert res.status == 404

        form = aiohttp.FormData()
        form.add_field("file", b"foto", filename="foto.jpg")
        res = await client.post("/api/files/upload?root=Escritorio&path=",
                                data=form, headers=auth(token))
        assert (await res.json())["saved"] == ["foto.jpg"]
        assert (ctx["desk"] / "foto.jpg").read_bytes() == b"foto"

        form = aiohttp.FormData()
        form.add_field("file", b"pdf", filename="../../informe.pdf")
        res = await client.post("/api/attach", data=form, headers=auth(token))
        path = (await res.json())["paths"][0]
        assert path.endswith("informe.pdf") and str(ctx["uploads"]) in path
    run(tmp_path, scenario)


def test_windows_apps_and_actions(tmp_path):
    async def scenario(client, ctx):
        token = await pair(client)
        res = await client.get("/api/windows", headers=auth(token))
        assert (await res.json())["windows"][0]["app"] == "Excel"
        res = await client.post("/api/action", json={"action": "lock"}, headers=auth(token))
        assert res.status == 200
        res = await client.post("/api/action", json={"action": "format c:"},
                                headers=auth(token))
        assert res.status == 400
        res = await client.post("/api/apps/launch", json={"id": 0}, headers=auth(token))
        assert res.status == 200
        await client.post("/api/windows/close", json={"id": 42}, headers=auth(token))
        assert ("action", "lock") in ctx["desktop"].calls
        assert ("launch", 0) in ctx["desktop"].calls
        assert ("close", 42) in ctx["desktop"].calls
    run(tmp_path, scenario)


async def next_json(ws, kind):
    while True:
        msg = await ws.receive(timeout=3)
        if msg.type == WSMsgType.TEXT and msg.json()["t"] == kind:
            return msg.json()


def test_claude_chat_over_websocket(tmp_path):
    async def scenario(client, _):
        token = await pair(client)
        ws = await client.ws_connect(f"/ws/claude?token={token}")
        hello = await ws.receive_json()
        assert hello["projects"] == ["claude"]
        await ws.send_json({"t": "prompt", "project": "claude", "text": "corre los tests"})
        perm = await next_json(ws, "perm")
        await ws.send_json({"t": "perm", "project": "claude", "id": perm["id"],
                            "allow": True})
        done = await next_json(ws, "done")
        assert done["ok"]

        await ws.send_json({"t": "open", "project": "claude"})
        history = await next_json(ws, "history")
        assert [e["t"] for e in history["events"]][0] == "user"

        await ws.send_json({"t": "prompt", "project": "nope", "text": "x"})
        assert "desconocido" in (await next_json(ws, "error"))["msg"]
        await ws.close()
    run(tmp_path, scenario)


def test_window_view_fits_streams_and_restores(tmp_path):
    async def scenario(client, ctx):
        token = await pair(client)
        ws = await client.ws_connect(f"/ws/screen?token={token}&window=42&vw=390&vh=700")
        hello = await ws.receive_json()
        assert hello == {"t": "hello", "w": 450, "h": 800}
        frame = await ws.receive(timeout=2)
        assert frame.type == WSMsgType.BINARY
        await ws.send_json({"t": "ack"})
        await ws.send_json({"t": "click", "b": "left"})
        await wait_for(lambda: ctx["input"].events)
        assert ctx["input"].events == [{"t": "click", "b": "left", "n": 1}]
        await ws.close()
        await wait_for(lambda: ("restore", 42) in ctx["desktop"].calls)
        await wait_for(lambda: ctx["capture"].region == ctx["capture"].monitor)
        assert ("fit", 42, 390, 700) in ctx["desktop"].calls
    run(tmp_path, scenario)


def test_new_screen_view_replaces_old_one(tmp_path):
    async def scenario(client, _):
        token = await pair(client)
        first = await client.ws_connect(f"/ws/screen?token={token}")
        await first.receive_json()
        second = await client.ws_connect(f"/ws/screen?token={token}")
        await second.receive_json()
        while True:
            msg = await first.receive(timeout=2)
            if msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSED):
                break
        assert msg.type == WSMsgType.CLOSE and msg.data == 4000
        await second.close()
    run(tmp_path, scenario)
