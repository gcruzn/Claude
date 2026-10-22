import asyncio

from remote_desktop.claude_agent import ClaudeHub, diff_event, summarize_tool
from remote_desktop.config import StateStore
from tests.fakes import FakeClaudeClient, wait_for


def test_summaries_are_short_spanish_lines():
    assert summarize_tool("Read", {"file_path": "C:/r/app.py"}) == "Leyó app.py"
    assert summarize_tool("Bash", {"command": "pytest  -q\n tests"}) == "Ejecutó pytest -q tests"
    assert summarize_tool("Grep", {"pattern": "TODO"}) == "Buscó «TODO»"
    assert summarize_tool("Raro", None) == "Usó Raro"
    assert summarize_tool("Write", {"file_path": "/x/a.md"}, request=True) == "Crear a.md"
    assert len(summarize_tool("Bash", {"command": "x" * 500})) <= 89


def test_diff_event_counts_lines():
    event = diff_event("Edit", {"file_path": "a/b.py", "old_string": "x", "new_string": "y\nz"})
    assert event["file"] == "b.py" and event["added"] == 2 and event["removed"] == 1
    assert diff_event("Read", {"file_path": "x"}) is None


def run_hub(tmp_path, scenario):
    async def main():
        FakeClaudeClient.instances.clear()
        store = StateStore(tmp_path / "state.json")
        hub = ClaudeHub({"claude": tmp_path}, store, extra_dirs=[tmp_path / "Descargas"],
                        client_factory=FakeClaudeClient)
        received = []

        async def listener(event):
            received.append(event)

        hub.listeners.add(listener)
        await scenario(hub, received)
        await hub.close()
    asyncio.run(main())


def test_full_turn_with_permission_from_phone(tmp_path):
    async def scenario(hub, received):
        session = hub.get("claude")
        await session.prompt("arregla los tests")
        await wait_for(lambda: any(e["t"] == "perm" for e in received))
        perm = next(e for e in received if e["t"] == "perm")
        assert perm["summary"] == "Ejecutar pytest tests/" and perm["detail"] == ""
        session.resolve(perm["id"], True)
        await wait_for(lambda: any(e["t"] == "done" for e in received))

        kinds = [e["t"] for e in received if e["t"] != "status"]
        assert kinds == ["user", "text", "tool", "tool_result", "tool", "diff",
                         "perm", "perm_done", "text", "done"]
        await wait_for(lambda: not session.busy)
        statuses = [e["busy"] for e in received if e["t"] == "status"]
        assert statuses == [True, False]
        assert next(e for e in received if e["t"] == "text" and "pasan" in e["text"])
        assert session.session_id == "sess-123"
        options = FakeClaudeClient.instances[0].options
        assert options.cwd == str(tmp_path)
        assert options.setting_sources == ["user", "project", "local"]
        assert str(tmp_path / "Descargas") in options.add_dirs
    run_hub(tmp_path, scenario)


def test_denied_permission_and_resume_after_restart(tmp_path):
    async def first(hub, received):
        session = hub.get("claude")
        await session.prompt("hola")
        await wait_for(lambda: any(e["t"] == "perm" for e in received))
        perm = next(e for e in received if e["t"] == "perm")
        session.resolve(perm["id"], False)
        await wait_for(lambda: any(e["t"] == "done" for e in received))
        assert any(e["t"] == "text" and "No corrí" in e["text"] for e in received)
    run_hub(tmp_path, first)

    async def second(hub, received):
        session = hub.get("claude")
        assert session.events, "el historial sobrevive al reinicio"
        await session.prompt("sigue")
        await wait_for(lambda: FakeClaudeClient.instances)
        assert FakeClaudeClient.instances[0].options.resume == "sess-123"
        await session.reset()
        assert session.events == [] and session.session_id is None
    run_hub(tmp_path, second)


def test_rejects_prompt_while_busy(tmp_path):
    async def scenario(hub, received):
        session = hub.get("claude")
        await session.prompt("uno")
        try:
            await session.prompt("dos")
        except RuntimeError:
            pass
        else:
            raise AssertionError("debió rechazar")
        await wait_for(lambda: any(e["t"] == "perm" for e in received))
        session.resolve(next(e for e in received if e["t"] == "perm")["id"], True)
        await wait_for(lambda: not session.busy)
    run_hub(tmp_path, scenario)
