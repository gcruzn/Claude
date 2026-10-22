"""Puente entre el chat del celular y Claude Code corriendo en el PC.

Cada proyecto de config.json tiene su propia sesión de Claude Code
(Claude Agent SDK) con su carpeta, CLAUDE.md, skills y configuración.
Lo que hace Claude se traduce a eventos simples para el celular, y los
permisos se aprueban o rechazan desde el teléfono.
"""

import asyncio
import itertools
import logging
from pathlib import Path

log = logging.getLogger("remote_desktop.claude")

MAX_EVENTS = 400
PERMISSION_TIMEOUT = 30 * 60


def _short(text, limit=90):
    text = " ".join(str(text or "").split())
    return text if len(text) <= limit else text[:limit - 1] + "…"


def _name(path):
    return Path(str(path or "")).name or str(path or "")


def summarize_tool(name, data, request=False):
    """Describe en una línea, en castellano, lo que hizo (o quiere hacer) una herramienta."""
    data = data if isinstance(data, dict) else {}

    def verb(past, infinitive):
        return infinitive if request else past

    if name == "Read":
        return f"{verb('Leyó', 'Leer')} {_name(data.get('file_path'))}"
    if name == "Write":
        return f"{verb('Creó', 'Crear')} {_name(data.get('file_path'))}"
    if name in ("Edit", "MultiEdit", "NotebookEdit"):
        target = _name(data.get("file_path") or data.get("notebook_path"))
        return f"{verb('Editó', 'Editar')} {target}"
    if name == "Bash":
        return f"{verb('Ejecutó', 'Ejecutar')} {_short(data.get('command'), 80)}"
    if name in ("Glob", "Grep"):
        return f"{verb('Buscó', 'Buscar')} «{_short(data.get('pattern'), 60)}»"
    if name == "WebSearch":
        return f"{verb('Buscó', 'Buscar')} en la web «{_short(data.get('query'), 60)}»"
    if name == "WebFetch":
        return f"{verb('Abrió', 'Abrir')} {_short(data.get('url'), 70)}"
    if name in ("Task", "Agent"):
        return f"{verb('Delegó', 'Delegar')}: {_short(data.get('description'), 70)}"
    if name == "TodoWrite":
        return f"{verb('Actualizó', 'Actualizar')} su lista de tareas"
    if name == "Skill":
        return f"{verb('Usó', 'Usar')} la skill {_short(data.get('skill') or data.get('name'), 50)}"
    return f"{verb('Usó', 'Usar')} {name}"


def diff_event(name, data):
    """Resumen de un cambio de archivo (+/− líneas y un extracto)."""
    if not isinstance(data, dict):
        return None
    if name == "Write":
        new = str(data.get("content", "")).splitlines()
        old = []
    elif name == "Edit":
        old = str(data.get("old_string", "")).splitlines()
        new = str(data.get("new_string", "")).splitlines()
    elif name == "MultiEdit":
        old, new = [], []
        for edit in data.get("edits") or []:
            old += str(edit.get("old_string", "")).splitlines()
            new += str(edit.get("new_string", "")).splitlines()
    else:
        return None
    lines = [{"kind": "del", "text": t} for t in old[:20]]
    lines += [{"kind": "add", "text": t} for t in new[:20]]
    return {"t": "diff", "file": _name(data.get("file_path")),
            "added": len(new), "removed": len(old), "lines": lines}


def _result_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = [c.get("text", "") for c in content if isinstance(c, dict)]
        return " ".join(p for p in parts if p)
    return ""


def message_events(message):
    """Convierte un mensaje del Agent SDK en eventos para el celular."""
    from claude_agent_sdk import (AssistantMessage, ResultMessage, TextBlock,
                                  ToolResultBlock, ToolUseBlock, UserMessage)

    events = []
    if isinstance(message, AssistantMessage):
        for block in message.content:
            if isinstance(block, TextBlock) and block.text.strip():
                events.append({"t": "text", "text": block.text})
            elif isinstance(block, ToolUseBlock):
                events.append({"t": "tool", "id": block.id,
                               "summary": summarize_tool(block.name, block.input)})
                diff = diff_event(block.name, block.input)
                if diff:
                    events.append(diff)
    elif isinstance(message, UserMessage) and isinstance(message.content, list):
        for block in message.content:
            if isinstance(block, ToolResultBlock):
                events.append({"t": "tool_result", "id": block.tool_use_id,
                               "ok": not block.is_error,
                               "summary": _short(_result_text(block.content), 120)})
    elif isinstance(message, ResultMessage):
        events.append({"t": "done", "ok": not message.is_error,
                       "error": _short(message.result, 200) if message.is_error else ""})
    return events


def default_client_factory(options):
    from claude_agent_sdk import ClaudeSDKClient

    return ClaudeSDKClient(options=options)


def build_options(cwd, can_use_tool, resume, add_dirs):
    from claude_agent_sdk import ClaudeAgentOptions

    return ClaudeAgentOptions(
        cwd=str(cwd),
        can_use_tool=can_use_tool,
        resume=resume,
        add_dirs=[str(d) for d in add_dirs],
        setting_sources=["user", "project", "local"],
    )


class ProjectSession:
    def __init__(self, hub, name, cwd):
        self.hub = hub
        self.name = name
        self.cwd = cwd
        saved = hub.store.get(self._key, {}) if hub.store else {}
        self.session_id = saved.get("session_id")
        self.events = saved.get("events", [])
        self.client = None
        self.busy = False
        self.task = None
        self.pending = {}
        self._ids = itertools.count(1)

    @property
    def _key(self):
        return f"claude:{self.name}"

    def _save(self):
        if self.hub.store:
            self.hub.store.set(self._key, {"session_id": self.session_id,
                                           "events": self.events[-MAX_EVENTS:]})

    async def emit(self, event):
        event = dict(event, project=self.name)
        self.events.append(event)
        del self.events[:-MAX_EVENTS]
        await self.hub.broadcast(event)

    async def _can_use_tool(self, tool_name, tool_input, context):
        from claude_agent_sdk import PermissionResultAllow, PermissionResultDeny

        perm_id = f"p{next(self._ids)}"
        future = asyncio.get_running_loop().create_future()
        self.pending[perm_id] = future
        summary = summarize_tool(tool_name, tool_input, request=True)
        detail = ""
        if isinstance(tool_input, dict):
            detail = _short(tool_input.get("command") or tool_input.get("file_path") or "", 300)
        if detail and detail in summary:
            detail = ""  # el resumen ya lo muestra completo
        await self.emit({"t": "perm", "id": perm_id, "summary": summary, "detail": detail})
        try:
            allow = await asyncio.wait_for(future, PERMISSION_TIMEOUT)
        except asyncio.TimeoutError:
            allow = False
        finally:
            self.pending.pop(perm_id, None)
        await self.emit({"t": "perm_done", "id": perm_id, "allow": allow})
        if allow:
            return PermissionResultAllow()
        return PermissionResultDeny(message="El usuario lo rechazó desde el celular")

    async def _ensure_client(self):
        if self.client is None:
            options = build_options(self.cwd, self._can_use_tool, self.session_id,
                                    self.hub.extra_dirs)
            self.client = self.hub.client_factory(options)
            await self.client.connect()

    async def _run(self, text):
        try:
            await self._ensure_client()
            await self.client.query(text)
            async for message in self.client.receive_response():
                sid = getattr(message, "session_id", None)
                if sid:
                    self.session_id = sid
                for event in message_events(message):
                    await self.emit(event)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - se informa al celular
            log.exception("Error en la sesión de Claude")
            await self.emit({"t": "done", "ok": False, "error": _short(str(exc), 200)})
            await self._drop_client()
        finally:
            self.busy = False
            self._save()
            await self.hub.broadcast({"t": "status", "project": self.name, "busy": False})

    async def prompt(self, text):
        if self.busy:
            raise RuntimeError("Claude sigue trabajando; espera o deténlo")
        self.busy = True
        await self.emit({"t": "user", "text": text})
        await self.hub.broadcast({"t": "status", "project": self.name, "busy": True})
        self.task = asyncio.create_task(self._run(text))

    def resolve(self, perm_id, allow):
        future = self.pending.get(perm_id)
        if future and not future.done():
            future.set_result(bool(allow))

    async def interrupt(self):
        if self.client is not None and self.busy:
            await self.client.interrupt()

    async def _drop_client(self):
        client, self.client = self.client, None
        if client is not None:
            try:
                await client.disconnect()
            except Exception:  # noqa: BLE001
                pass

    async def reset(self):
        for future in list(self.pending.values()):
            if not future.done():
                future.set_result(False)
        if self.task and not self.task.done():
            self.task.cancel()
        await self._drop_client()
        self.session_id = None
        self.events = []
        self.busy = False
        self._save()


class ClaudeHub:
    def __init__(self, projects, store=None, extra_dirs=(),
                 client_factory=default_client_factory):
        self.store = store
        self.extra_dirs = list(extra_dirs)
        self.client_factory = client_factory
        self.sessions = {name: ProjectSession(self, name, Path(cwd))
                         for name, cwd in projects.items()}
        self.listeners = set()

    def project_names(self):
        return list(self.sessions)

    def get(self, name):
        session = self.sessions.get(name)
        if session is None:
            raise KeyError(f"proyecto desconocido: {name!r}")
        return session

    async def broadcast(self, event):
        for send in list(self.listeners):
            try:
                await send(event)
            except Exception:  # noqa: BLE001 - celular desconectado
                self.listeners.discard(send)

    async def close(self):
        for session in self.sessions.values():
            if session.task and not session.task.done():
                session.task.cancel()
            await session._drop_client()
