"use strict";

// =========================================================================
// Utilidades
// =========================================================================

const $ = (id) => document.getElementById(id);

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
}

function icon(name, cls = "") {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", `ic ${cls}`);
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", `#i-${name}`);
  svg.append(use);
  return svg;
}

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch (e) {
    return null;
  }
  return null;
}

let toastTimer = null;
function toast(text) {
  const box = $("toast");
  box.textContent = text;
  box.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.classList.add("hidden"), 2800);
}

function formatSize(bytes) {
  if (bytes === null || bytes === undefined) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${Math.round(bytes / 1024)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1).replace(".", ",")} MB`;
  return `${(bytes / 1024 ** 3).toFixed(1).replace(".", ",")} GB`;
}

function formatDate(seconds) {
  const date = new Date(seconds * 1000);
  const now = new Date();
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString("es", { hour: "2-digit", minute: "2-digit" });
  }
  return date.toLocaleDateString("es", { day: "numeric", month: "short" });
}

// =========================================================================
// Conexión con el PC
// =========================================================================

let token = store("mipc-token");
let status = null;

class Unauthorized extends Error {}

async function api(path, options = {}) {
  const headers = Object.assign({}, options.headers || {});
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.json !== undefined) {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(options.json);
  }
  const res = await fetch(path, Object.assign({}, options, { headers }));
  if (res.status === 401) throw new Unauthorized();
  const type = res.headers.get("Content-Type") || "";
  const body = type.includes("json") ? await res.json() : await res.text();
  if (!res.ok) throw new Error((body && body.error) || body || `Error ${res.status}`);
  return body;
}

function rawUrl(root, path, download) {
  const q = new URLSearchParams({ root, path, token });
  if (download) q.set("download", "1");
  return `/api/files/raw?${q}`;
}

function wsUrl(path, params = {}) {
  const q = new URLSearchParams(Object.assign({ token }, params));
  const scheme = location.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${location.host}${path}?${q}`;
}

function showScreen(id) {
  for (const s of ["splash", "pair", "app", "viewer"]) $(s).classList.toggle("hidden", s !== id);
}

let retryTimer = null;

async function start() {
  clearTimeout(retryTimer);
  if (!token) return showPair();
  showScreen("splash");
  $("splash-text").textContent = "Conectando a tu PC…";
  $("splash-bar").classList.remove("hidden");
  $("splash-retry").classList.add("hidden");
  try {
    status = await api("/api/status");
  } catch (err) {
    if (err instanceof Unauthorized) return showPair("Este celular ya no está vinculado.");
    $("splash-text").textContent =
      "No se pudo conectar. Revisa que el PC esté encendido y Tailscale activo en el celular.";
    $("splash-bar").classList.add("hidden");
    $("splash-retry").classList.remove("hidden");
    retryTimer = setTimeout(start, 8000);
    return;
  }
  enterApp();
}

$("splash-retry").addEventListener("click", start);

function showPair(message) {
  token = null;
  store("mipc-token", null);
  showScreen("pair");
  $("pair-error").textContent = message || "";
}

$("pair-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("pair-error").textContent = "";
  try {
    const body = await api("/api/pair", {
      method: "POST",
      json: { password: $("pair-password").value, device: navigator.userAgent.slice(0, 60) },
    });
    token = body.token;
    store("mipc-token", token);
    $("pair-password").value = "";
    start();
  } catch (err) {
    $("pair-error").textContent = err.message;
  }
});

function lostSession() {
  showPair("Este celular ya no está vinculado.");
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible" || !token) return;
  if (!$("app").classList.contains("hidden")) {
    chat.ensureConnected();
    if (currentTab === "pc") pc.refresh();
  }
});

// =========================================================================
// App: pestañas
// =========================================================================

let currentTab = "claude";
const TITLES = { claude: "Claude", files: "Archivos", pc: "Mi PC" };

function enterApp() {
  showScreen("app");
  $("top-sub").textContent = `${status.pc} · conectado`;
  chat.ensureConnected();
  selectTab(currentTab);
}

function selectTab(name) {
  currentTab = name;
  for (const btn of document.querySelectorAll(".tabs button")) {
    btn.classList.toggle("active", btn.dataset.tab === name);
  }
  for (const tab of ["claude", "files", "pc"]) $(`tab-${tab}`).classList.toggle("hidden", tab !== name);
  $("top-title").textContent = TITLES[name];
  $("composer").classList.toggle("hidden", name !== "claude");
  $("project-btn").classList.toggle("hidden", name !== "claude" || !status.projects.length);
  $("files-refresh").classList.toggle("hidden", name !== "files");
  $("pc-refresh").classList.toggle("hidden", name !== "pc");
  if (name === "files") files.render();
  if (name === "pc") pc.refresh();
  if (name === "claude") chat.scrollToEnd();
}

for (const btn of document.querySelectorAll(".tabs button")) {
  btn.addEventListener("click", () => selectTab(btn.dataset.tab));
}

// =========================================================================
// Hoja inferior genérica
// =========================================================================

function openSheet(title, sub, ...content) {
  $("sheet-title").textContent = title;
  $("sheet-sub").textContent = sub || "";
  $("sheet-body").replaceChildren(...content);
  $("sheet").classList.remove("hidden");
}

function closeSheet() {
  $("sheet").classList.add("hidden");
  $("sheet-body").replaceChildren();
}

$("sheet-close").addEventListener("click", closeSheet);
$("sheet").addEventListener("click", (e) => { if (e.target === $("sheet")) closeSheet(); });

function confirmSheet(title, text, okLabel, onOk) {
  openSheet(title, "",
    el("p", { class: "muted" }, text),
    el("div", { class: "grid2" },
      el("button", { class: "btn", onclick: closeSheet }, "Cancelar"),
      el("button", { class: "btn primary", onclick: () => { closeSheet(); onOk(); } }, okLabel)));
}

// =========================================================================
// Claude
// =========================================================================

function renderMarkdown(text) {
  // Mínimo y seguro: bloques ```código```, `código`, **negrita** y párrafos.
  const box = el("div", { class: "msg-claude" });
  const parts = String(text).split(/```[\w-]*\n?/);
  parts.forEach((part, i) => {
    if (i % 2 === 1) {
      box.append(el("pre", {}, el("code", {}, part.replace(/\n$/, ""))));
      return;
    }
    for (const para of part.split(/\n{2,}/)) {
      if (!para.trim()) continue;
      const p = el("p");
      para.split(/(`[^`]+`|\*\*[^*]+\*\*)/).forEach((chunk) => {
        if (/^`[^`]+`$/.test(chunk)) p.append(el("code", {}, chunk.slice(1, -1)));
        else if (/^\*\*[^*]+\*\*$/.test(chunk)) p.append(el("strong", {}, chunk.slice(2, -2)));
        else {
          chunk.split("\n").forEach((line, j) => {
            if (j) p.append(el("br"));
            p.append(line);
          });
        }
      });
      box.append(p);
    }
  });
  return box;
}

const chat = {
  ws: null,
  projects: [],
  project: store("mipc-project"),
  events: {},
  busy: {},
  open: new Set(),
  retry: null,

  ensureConnected() {
    if (this.ws && this.ws.readyState <= WebSocket.OPEN) return;
    clearTimeout(this.retry);
    const ws = new WebSocket(wsUrl("/ws/claude"));
    this.ws = ws;
    let welcomed = false;
    ws.onmessage = (ev) => this.onMessage(JSON.parse(ev.data), () => { welcomed = true; });
    ws.onclose = () => {
      if (this.ws === ws) this.ws = null;
      $("dot").classList.add("off");
      if (!token) return;
      if (!welcomed) {
        api("/api/status").then(() => {}, (err) => { if (err instanceof Unauthorized) lostSession(); });
      }
      this.retry = setTimeout(() => this.ensureConnected(), 3000);
    };
  },

  send(msg) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(Object.assign({ project: this.project }, msg)));
      return true;
    }
    toast("Sin conexión con el PC, reintentando…");
    this.ensureConnected();
    return false;
  },

  onMessage(msg, welcomed) {
    if (msg.t === "hello") {
      welcomed();
      $("dot").classList.remove("off");
      this.projects = msg.projects;
      this.busy = msg.busy;
      if (!this.projects.includes(this.project)) this.project = this.projects[0] || null;
      this.updateProject();
      if (this.project) this.send({ t: "open" });
      else this.render();
      return;
    }
    if (msg.t === "error") return toast(msg.msg);
    if (msg.t === "history") {
      this.events[msg.project] = msg.events;
      this.busy[msg.project] = msg.busy;
    } else if (msg.t === "status") {
      this.busy[msg.project] = msg.busy;
    } else if (msg.project) {
      (this.events[msg.project] = this.events[msg.project] || []).push(msg);
      if (msg.t === "perm" && msg.project !== this.project) {
        toast(`Claude pide permiso en ${msg.project}`);
      }
    }
    if (msg.project === this.project) this.render();
  },

  updateProject() {
    store("mipc-project", this.project || null);
    $("project-name").textContent = this.project || "—";
    $("project-btn").classList.toggle("hidden", currentTab !== "claude" || !this.projects.length);
  },

  choose(name) {
    this.project = name;
    this.updateProject();
    this.send({ t: "open" });
    closeSheet();
  },

  scrollToEnd() {
    const tab = $("tab-claude");
    requestAnimationFrame(() => { tab.scrollTop = tab.scrollHeight; });
  },

  render() {
    const box = $("chat");
    const isBusy = !!this.busy[this.project];
    $("send").classList.toggle("hidden", isBusy);
    $("stop").classList.toggle("hidden", !isBusy);
    if (!this.project) {
      box.replaceChildren(el("div", { class: "empty" },
        "Aún no hay proyectos para Claude. En el PC, agrega tus carpetas en ",
        el("code", {}, "config.json"), " (sección \"projects\") y reinicia Mi PC."));
      return;
    }
    const events = this.events[this.project] || [];
    const nodes = [];
    const results = {};
    const decided = {};
    for (const e of events) {
      if (e.t === "tool_result") results[e.id] = e;
      if (e.t === "perm_done") decided[e.id] = e.allow;
    }
    let steps = null;
    const flush = () => {
      if (steps) nodes.push(this.stepsCard(steps, results));
      steps = null;
    };
    events.forEach((e, index) => {
      if (e.t === "tool") {
        (steps = steps || { key: `s${index}`, items: [] }).items.push(e);
        return;
      }
      if (e.t === "tool_result" || e.t === "perm_done" || e.t === "status") return;
      flush();
      if (e.t === "user") nodes.push(el("div", { class: "msg-user" }, e.text));
      else if (e.t === "text") nodes.push(renderMarkdown(e.text));
      else if (e.t === "diff") nodes.push(this.diffCard(e, `d${index}`));
      else if (e.t === "perm") nodes.push(this.permCard(e, decided[e.id]));
      else if (e.t === "done" && !e.ok) {
        nodes.push(el("div", { class: "msg-error" }, `Se detuvo: ${e.error || "error desconocido"}`));
      }
    });
    flush();
    if (isBusy) nodes.push(el("div", { class: "working" }, `Claude está trabajando en ${status.pc}…`));
    if (!nodes.length) {
      nodes.push(el("div", { class: "empty" },
        `Pídele a Claude lo que necesites en ${this.project}. Trabaja directo en tu PC.`));
    }
    const tab = $("tab-claude");
    const atEnd = tab.scrollHeight - tab.scrollTop - tab.clientHeight < 80;
    box.replaceChildren(...nodes);
    if (atEnd) this.scrollToEnd();
  },

  stepsCard(steps, results) {
    const failed = steps.items.filter((s) => results[s.id] && !results[s.id].ok).length;
    const n = steps.items.length;
    const label = `${n} ${n === 1 ? "paso" : "pasos"} en tu PC${failed ? ` · ${failed} con error` : ""}`;
    const open = this.open.has(steps.key);
    return el("button", {
      class: "card-box",
      "aria-expanded": open ? "true" : "false",
      onclick: () => { this.toggle(steps.key); },
    },
    el("span", { class: "card-head" }, icon("check"), el("span", { class: "grow" }, label),
      open ? "Ocultar" : "Ver"),
    open && el("ul", { class: "steps" }, steps.items.map((s) => {
      const r = results[s.id];
      return el("li", { class: r && !r.ok ? "bad" : "" }, `· ${s.summary}${r && !r.ok ? " (falló)" : ""}`);
    })));
  },

  diffCard(e, key) {
    const open = this.open.has(key);
    return el("button", { class: "card-box", "aria-expanded": open ? "true" : "false",
      onclick: () => this.toggle(key) },
    el("span", { class: "card-head" }, icon("file"), el("span", { class: "diff-file" }, e.file),
      el("span", { class: "plus" }, `+${e.added}`), el("span", { class: "minus" }, `−${e.removed}`)),
    open && el("div", { class: "diff-lines" }, e.lines.map((l) =>
      el("div", { class: l.kind === "add" ? "plus" : "minus" }, `${l.kind === "add" ? "+" : "-"} ${l.text}`))),
    el("span", { class: "sub" }, open ? "Toca para ocultar el cambio" : "Toca para ver el cambio"));
  },

  permCard(e, decision) {
    const pending = decision === undefined;
    return el("div", { class: "perm" },
      el("div", { class: "perm-title" }, "Claude pide permiso para:"),
      el("div", {}, e.summary),
      e.detail && el("div", { class: "perm-cmd" }, e.detail),
      pending
        ? el("div", { class: "grid2" },
          el("button", { class: "btn", onclick: () => this.send({ t: "perm", id: e.id, allow: false }) }, "Rechazar"),
          el("button", { class: "btn primary", onclick: () => this.send({ t: "perm", id: e.id, allow: true }) }, "Permitir"))
        : el("div", { class: "sub" }, decision ? "Permitido" : "Rechazado"));
  },

  toggle(key) {
    if (this.open.has(key)) this.open.delete(key);
    else this.open.add(key);
    this.render();
  },

  submit() {
    const text = $("prompt").value.trim();
    if (!text || !this.project) return;
    if (this.send({ t: "prompt", text })) {
      $("prompt").value = "";
      autosize();
      this.scrollToEnd();
    }
  },
};

function autosize() {
  const box = $("prompt");
  box.style.height = "auto";
  box.style.height = `${Math.min(box.scrollHeight, 140)}px`;
}

$("prompt").addEventListener("input", autosize);
$("send").addEventListener("click", () => chat.submit());
$("stop").addEventListener("click", () => chat.send({ t: "stop" }));
$("prompt").addEventListener("keydown", (e) => {
  // En el celular Enter hace salto de línea; con teclado físico, Ctrl+Enter envía.
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    chat.submit();
  }
});

$("project-btn").addEventListener("click", () => {
  openSheet("Proyecto", "Dónde trabaja Claude en tu PC",
    ...chat.projects.map((name) => el("button", {
      class: `option mono${name === chat.project ? " current" : ""}`,
      onclick: () => chat.choose(name),
    }, name)),
    el("button", { class: "btn block", onclick: () => {
      confirmSheet("Nueva conversación", `Claude olvida la conversación actual de ${chat.project}.`,
        "Empezar de nuevo", () => chat.send({ t: "reset" }));
    } }, "Nueva conversación"));
});

$("attach").addEventListener("change", async (e) => {
  const list = [...e.target.files];
  e.target.value = "";
  if (!list.length) return;
  toast("Subiendo al PC…");
  try {
    const form = new FormData();
    for (const f of list) form.append("file", f, f.name);
    const body = await api("/api/attach", { method: "POST", body: form });
    const lines = body.paths.map((p) => `Archivo adjunto: ${p}`).join("\n");
    const box = $("prompt");
    box.value = box.value ? `${box.value}\n${lines}` : `${lines}\n`;
    autosize();
    box.focus();
    toast("Adjunto listo en el PC");
  } catch (err) {
    if (err instanceof Unauthorized) return lostSession();
    toast(`No se pudo subir: ${err.message}`);
  }
});

// =========================================================================
// Archivos
// =========================================================================

const TEXT_EXT = /\.(txt|md|csv|log|json|py|ps1|bat|js|ts|html|css|xml|yml|yaml|ini|cfg|sql|sh)$/i;
const IMAGE_EXT = /\.(jpe?g|png|gif|webp|bmp)$/i;

const files = {
  root: null,
  path: [],
  entries: [],
  dir: "",
  sep: "\\",

  async load() {
    if (!this.root) {
      this.entries = status.folders.map((name) => ({ name, dir: true, root: true }));
      return;
    }
    const q = new URLSearchParams({ root: this.root, path: this.path.join("/") });
    const body = await api(`/api/files?${q}`);
    this.entries = body.entries;
    this.dir = body.dir;
    this.sep = body.sep;
  },

  async render() {
    try {
      await this.load();
    } catch (err) {
      if (err instanceof Unauthorized) return lostSession();
      toast(err.message);
      this.root = null;
      this.path = [];
      await this.load();
    }
    const where = this.root ? [this.root, ...this.path] : [];
    $("files-up").classList.toggle("hidden", !this.root);
    $("files-crumbs").textContent = ["Mi PC", ...where].join(" › ");
    $("files-title").textContent = where.length ? where[where.length - 1] : "Carpetas";
    $("files-upload-label").classList.toggle("hidden", !this.root);
    const list = $("files-list");
    if (!this.entries.length) {
      list.replaceChildren(el("li", { class: "empty" }, "Carpeta vacía"));
      return;
    }
    list.replaceChildren(...this.entries.map((e) => el("li", {},
      el("button", { class: "row", onclick: () => this.open(e) },
        icon(e.dir ? "folder" : "file", `kind ${e.dir ? "dir" : "doc"}`),
        el("span", { class: "row-text" },
          el("span", { class: "row-name" }, e.name),
          el("span", { class: "row-meta" },
            e.root ? "Carpeta permitida" : e.dir ? formatDate(e.mtime) : `${formatSize(e.size)} · ${formatDate(e.mtime)}`)),
        icon("next", "go")))));
  },

  open(entry) {
    if (entry.root) {
      this.root = entry.name;
      this.path = [];
      return this.render();
    }
    if (entry.dir) {
      this.path.push(entry.name);
      return this.render();
    }
    this.showFile(entry);
  },

  up() {
    if (this.path.length) this.path.pop();
    else this.root = null;
    this.render();
  },

  relPath(name) {
    return [...this.path, name].join("/");
  },

  async showFile(entry) {
    const rel = this.relPath(entry.name);
    const preview = el("div", { class: "preview" }, "Cargando vista previa…");
    const absPath = `${this.dir}${this.sep}${entry.name}`;
    openSheet(entry.name, `${formatSize(entry.size)} · ${formatDate(entry.mtime)}`,
      preview,
      el("a", { class: "btn primary block", href: rawUrl(this.root, rel, true), download: entry.name },
        "Descargar al celular"),
      el("div", { class: "grid2" },
        el("button", { class: "btn", onclick: () => this.share(entry, rel) }, "Compartir"),
        el("button", { class: "btn", onclick: () => {
          closeSheet();
          selectTab("claude");
          $("prompt").value = `Revisa el archivo "${absPath}" y `;
          autosize();
          $("prompt").focus();
        } }, "Preguntar a Claude")));
    try {
      if (IMAGE_EXT.test(entry.name)) {
        preview.replaceChildren(el("img", { src: rawUrl(this.root, rel), alt: entry.name }));
      } else if (TEXT_EXT.test(entry.name) && entry.size < 2 * 1024 * 1024) {
        const res = await fetch(rawUrl(this.root, rel), { headers: { Range: "bytes=0-8191" } });
        const text = await res.text();
        preview.replaceChildren(el("pre", {}, text + (entry.size > 8192 ? "\n…" : "")));
      } else if (/\.pdf$/i.test(entry.name)) {
        preview.replaceChildren(el("a", { class: "btn", href: rawUrl(this.root, rel), target: "_blank",
          rel: "noopener" }, "Abrir el PDF"));
      } else {
        preview.textContent = "Sin vista previa para este tipo de archivo";
      }
    } catch (err) {
      preview.textContent = "No se pudo cargar la vista previa";
    }
  },

  async share(entry, rel) {
    try {
      const res = await fetch(rawUrl(this.root, rel));
      const blob = await res.blob();
      const file = new File([blob], entry.name, { type: blob.type });
      if (navigator.canShare && navigator.canShare({ files: [file] })) {
        await navigator.share({ files: [file], title: entry.name });
      } else {
        toast("Este navegador no permite compartir archivos; usa Descargar");
      }
    } catch (err) {
      if (err.name !== "AbortError") toast(`No se pudo compartir: ${err.message}`);
    }
  },

  async upload(list) {
    if (!list.length || !this.root) return;
    toast(`Subiendo ${list.length === 1 ? list[0].name : `${list.length} archivos`}…`);
    try {
      const form = new FormData();
      for (const f of list) form.append("file", f, f.name);
      const q = new URLSearchParams({ root: this.root, path: this.path.join("/") });
      const body = await api(`/api/files/upload?${q}`, { method: "POST", body: form });
      toast(`Guardado en el PC: ${body.saved.join(", ")}`);
      this.render();
    } catch (err) {
      if (err instanceof Unauthorized) return lostSession();
      toast(`No se pudo subir: ${err.message}`);
    }
  },
};

$("files-up").addEventListener("click", () => files.up());
$("files-refresh").addEventListener("click", () => files.render());
$("files-upload").addEventListener("change", (e) => {
  const list = [...e.target.files];
  e.target.value = "";
  files.upload(list);
});

// =========================================================================
// Mi PC
// =========================================================================

const BADGE_COLORS = ["#3466c2", "#1f7a4a", "#8a4fbf", "#b5532f", "#2b3442", "#127a8a"];

const pc = {
  async refresh() {
    const list = $("windows-list");
    if (!status.windows) {
      $("pc-hint").textContent = "La lista de ventanas solo existe en Windows. Usa la pantalla completa.";
      list.replaceChildren();
      return;
    }
    try {
      const body = await api("/api/windows");
      if (!body.windows.length) {
        list.replaceChildren(el("li", { class: "empty" }, "No hay ventanas abiertas"));
        return;
      }
      list.replaceChildren(...body.windows.map((w, i) => el("li", {},
        el("button", { class: "win", onclick: () => viewer.open(w) },
          el("span", { class: "badge", style: `background:${BADGE_COLORS[i % BADGE_COLORS.length]}` },
            w.app.slice(0, 1).toUpperCase()),
          el("span", { class: "row-text" },
            el("span", { class: "row-name" }, w.app),
            el("span", { class: "row-meta ellipsis" }, w.title)),
          icon("next", "go")))));
    } catch (err) {
      if (err instanceof Unauthorized) return lostSession();
      toast(err.message);
    }
  },

  async action(name, done) {
    try {
      await api("/api/action", { method: "POST", json: { action: name } });
      if (done) toast(done);
    } catch (err) {
      if (err instanceof Unauthorized) return lostSession();
      toast(err.message);
    }
  },

  async apps() {
    const search = el("input", { type: "search", placeholder: "Buscar programa…", "aria-label": "Buscar programa" });
    const results = el("div", { class: "stack" }, el("p", { class: "muted" }, "Cargando…"));
    openSheet("Abrir programa", "Se abre en tu PC", search, results);
    let apps = [];
    try {
      apps = (await api("/api/apps")).apps;
    } catch (err) {
      results.replaceChildren(el("p", { class: "muted" }, err.message));
      return;
    }
    const draw = () => {
      const q = search.value.trim().toLowerCase();
      const shown = apps.filter((a) => a.name.toLowerCase().includes(q)).slice(0, 40);
      results.replaceChildren(...(shown.length ? shown.map((a) => el("button", {
        class: "option",
        onclick: async () => {
          closeSheet();
          try {
            await api("/api/apps/launch", { method: "POST", json: { id: a.id } });
            toast(`Abriendo ${a.name} en el PC`);
            setTimeout(() => pc.refresh(), 2500);
          } catch (err) {
            toast(err.message);
          }
        },
      }, a.name)) : [el("p", { class: "muted" }, "Sin resultados")]));
    };
    search.addEventListener("input", draw);
    draw();
  },
};

$("pc-refresh").addEventListener("click", () => pc.refresh());
$("pc-full").addEventListener("click", () => viewer.open(null));
for (const btn of document.querySelectorAll("[data-pc]")) {
  btn.addEventListener("click", () => {
    const what = btn.dataset.pc;
    if (what === "apps") pc.apps();
    else if (what === "lock") {
      confirmSheet("Bloquear PC", "La sesión de Windows queda bloqueada. Claude y los archivos siguen funcionando.",
        "Bloquear", () => pc.action("lock", "PC bloqueado"));
    } else if (what === "sleep") {
      confirmSheet("Suspender PC", "Ojo: suspendido, la app no podrá conectarse hasta que alguien lo despierte en casa.",
        "Suspender igual", () => pc.action("sleep"));
    } else if (what === "volume") {
      openSheet("Volumen", "Del PC",
        el("div", { class: "grid2" },
          el("button", { class: "btn", onclick: () => pc.action("volume_down") }, "Bajar"),
          el("button", { class: "btn", onclick: () => pc.action("volume_up") }, "Subir")),
        el("button", { class: "btn block", onclick: () => pc.action("mute", "Silencio activado/desactivado") }, "Silenciar"));
    }
  });
}

// =========================================================================
// Visor de ventana / pantalla
// =========================================================================

const canvas = $("screen");
const ctx2d = canvas.getContext("2d");
const cursor = $("cursor");
const surface = $("surface");
const stage = $("stage");
const QUALITIES = [["Baja", 35, 8], ["Media", 60, 10], ["Alta", 80, 15]];

const viewer = {
  ws: null,
  target: null,
  quality: Number(store("mipc-quality") || 1) % 3,
  mode: "click",
  view: { s: 1, tx: 0, ty: 0 },

  open(win) {
    this.target = win;
    $("viewer-title").textContent = win ? win.app : "Pantalla completa";
    $("viewer-sub").textContent = "Conectando…";
    $("viewer-close").classList.toggle("hidden", !win);
    $("viewer-quality").textContent = QUALITIES[this.quality][0];
    ctx2d.clearRect(0, 0, canvas.width, canvas.height);
    this.resetView();
    showScreen("viewer");
    requestAnimationFrame(() => this.connect());
  },

  connect() {
    const box = stage.getBoundingClientRect();
    const params = { vw: Math.round(box.width), vh: Math.round(box.height) };
    if (this.target) params.window = this.target.id;
    const ws = new WebSocket(wsUrl("/ws/screen", params));
    ws.binaryType = "arraybuffer";
    this.ws = ws;
    ws.onmessage = async (ev) => {
      if (typeof ev.data === "string") {
        const msg = JSON.parse(ev.data);
        if (msg.t === "hello") {
          $("viewer-sub").textContent = this.target ? "Ajustada al celular" : `${status.pc}`;
          this.applyQuality();
        } else if (msg.t === "error") {
          toast(msg.msg);
        }
        return;
      }
      try {
        const bitmap = await createImageBitmap(new Blob([ev.data], { type: "image/jpeg" }));
        if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
          canvas.width = bitmap.width;
          canvas.height = bitmap.height;
          this.fit();
        }
        ctx2d.drawImage(bitmap, 0, 0);
        bitmap.close();
      } finally {
        this.send({ t: "ack" });
      }
    };
    ws.onclose = (ev) => {
      if (this.ws !== ws) return;
      this.ws = null;
      if (ev.code === 4000) $("viewer-sub").textContent = "Se abrió otra vista";
      else if (!$("viewer").classList.contains("hidden")) {
        $("viewer-sub").textContent = "Reconectando…";
        setTimeout(() => { if (!this.ws && !$("viewer").classList.contains("hidden")) this.connect(); }, 2000);
      }
    };
  },

  close() {
    const ws = this.ws;
    this.ws = null;
    if (ws) ws.close();
    $("keybar").classList.add("hidden");
    showScreen("app");
    selectTab("pc");
  },

  send(msg) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  },

  applyQuality() {
    const [, quality, fps] = QUALITIES[this.quality];
    store("mipc-quality", String(this.quality));
    $("viewer-quality").textContent = QUALITIES[this.quality][0];
    this.send({ t: "settings", quality, fps });
  },

  fit() {
    if (!canvas.width) return;
    const box = stage.getBoundingClientRect();
    const scale = Math.min(box.width / canvas.width, box.height / canvas.height);
    canvas.style.width = `${Math.floor(canvas.width * scale)}px`;
    canvas.style.height = `${Math.floor(canvas.height * scale)}px`;
  },

  resetView() {
    this.view = { s: 1, tx: 0, ty: 0 };
    surface.style.transform = "";
  },
};

new ResizeObserver(() => viewer.fit()).observe(stage);

$("viewer-back").addEventListener("click", () => viewer.close());
$("viewer-quality").addEventListener("click", () => {
  viewer.quality = (viewer.quality + 1) % 3;
  viewer.applyQuality();
});
$("viewer-zoom").addEventListener("click", () => viewer.resetView());
$("viewer-mode").addEventListener("click", () => {
  viewer.mode = viewer.mode === "click" ? "scroll" : "click";
  $("viewer-mode").querySelector("span").textContent = viewer.mode === "click" ? "Clic" : "Rueda";
});
$("viewer-kbd").addEventListener("click", () => {
  const bar = $("keybar");
  bar.classList.toggle("hidden");
  if (!bar.classList.contains("hidden")) $("typing").focus();
});
$("viewer-close").addEventListener("click", () => {
  const win = viewer.target;
  confirmSheet("Cerrar ventana", `Se cierra ${win.app} en el PC (si hay algo sin guardar, te lo preguntará en la pantalla).`,
    "Cerrar", async () => {
      try {
        await api("/api/windows/close", { method: "POST", json: { id: win.id } });
      } catch (err) {
        toast(err.message);
      }
      viewer.close();
    });
});

function normalized(touch) {
  const r = canvas.getBoundingClientRect();
  return {
    x: Math.min(Math.max((touch.clientX - r.left) / r.width, 0), 1),
    y: Math.min(Math.max((touch.clientY - r.top) / r.height, 0), 1),
  };
}

function moveTo(p) {
  cursor.style.left = `${p.x * 100}%`;
  cursor.style.top = `${p.y * 100}%`;
  viewer.send({ t: "move", x: p.x, y: p.y });
}

let pendingMove = null;
function queueMove(p) {
  if (!pendingMove) {
    requestAnimationFrame(() => {
      moveTo(pendingMove);
      pendingMove = null;
    });
  }
  pendingMove = p;
}

function stagePoint(t) {
  const r = stage.getBoundingClientRect();
  return { x: t.clientX - r.left, y: t.clientY - r.top };
}

let pinch = null;
let gesture = null;
const TAP_SLOP = 10;
const LONG_PRESS_MS = 550;
const SCROLL_STEP = 25;

function startPinch(touches) {
  const a = stagePoint(touches[0]);
  const b = stagePoint(touches[1]);
  const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
  const v = viewer.view;
  pinch = {
    dist: Math.hypot(a.x - b.x, a.y - b.y) || 1,
    s: v.s,
    ux: (mid.x - surface.offsetLeft - v.tx) / v.s,
    uy: (mid.y - surface.offsetTop - v.ty) / v.s,
  };
}

function updatePinch(touches) {
  const a = stagePoint(touches[0]);
  const b = stagePoint(touches[1]);
  const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
  const v = viewer.view;
  v.s = Math.min(Math.max(pinch.s * Math.hypot(a.x - b.x, a.y - b.y) / pinch.dist, 1), 6);
  v.tx = mid.x - surface.offsetLeft - pinch.ux * v.s;
  v.ty = mid.y - surface.offsetTop - pinch.uy * v.s;
  surface.style.transform = `translate(${v.tx}px, ${v.ty}px) scale(${v.s})`;
}

function cancelGesture() {
  if (!gesture) return;
  clearTimeout(gesture.timer);
  if (gesture.dragging) viewer.send({ t: "up", b: "left" });
  gesture = null;
}

stage.addEventListener("touchstart", (e) => {
  e.preventDefault();
  if (e.touches.length >= 2) {
    cancelGesture();
    startPinch(e.touches);
    return;
  }
  const t = e.touches[0];
  gesture = { startX: t.clientX, startY: t.clientY, lastX: t.clientX, lastY: t.clientY,
    accX: 0, accY: 0, moved: false, dragging: false, done: false, start: normalized(t) };
  if (viewer.mode === "click") {
    gesture.timer = setTimeout(() => {
      if (gesture && !gesture.moved) {
        gesture.done = true;
        moveTo(gesture.start);
        viewer.send({ t: "click", b: "right" });
        if (navigator.vibrate) navigator.vibrate(30);
      }
    }, LONG_PRESS_MS);
  }
}, { passive: false });

stage.addEventListener("touchmove", (e) => {
  e.preventDefault();
  if (pinch && e.touches.length >= 2) return updatePinch(e.touches);
  if (!gesture || gesture.done) return;
  const t = e.touches[0];
  if (!gesture.moved && Math.hypot(t.clientX - gesture.startX, t.clientY - gesture.startY) > TAP_SLOP) {
    gesture.moved = true;
    clearTimeout(gesture.timer);
  }
  if (!gesture.moved) return;
  if (viewer.mode === "scroll") {
    gesture.accX += t.clientX - gesture.lastX;
    gesture.accY += t.clientY - gesture.lastY;
    const dy = Math.trunc(gesture.accY / SCROLL_STEP);
    const dx = Math.trunc(gesture.accX / SCROLL_STEP);
    if (dx || dy) {
      // Desplazamiento "natural": el contenido sigue al dedo.
      viewer.send({ t: "scroll", dx: -dx, dy });
      gesture.accX -= dx * SCROLL_STEP;
      gesture.accY -= dy * SCROLL_STEP;
    }
  } else {
    if (!gesture.dragging) {
      gesture.dragging = true;
      moveTo(gesture.start);
      viewer.send({ t: "down", b: "left" });
    }
    queueMove(normalized(t));
  }
  gesture.lastX = t.clientX;
  gesture.lastY = t.clientY;
}, { passive: false });

stage.addEventListener("touchend", (e) => {
  e.preventDefault();
  if (pinch) {
    if (e.touches.length < 2) pinch = null;
    return;
  }
  if (!gesture) return;
  clearTimeout(gesture.timer);
  if (gesture.dragging) viewer.send({ t: "up", b: "left" });
  else if (!gesture.moved && !gesture.done && viewer.mode === "click") {
    moveTo(gesture.start);
    viewer.send({ t: "click", b: "left" });
  }
  gesture = null;
}, { passive: false });

stage.addEventListener("touchcancel", () => {
  pinch = null;
  cancelGesture();
});

// Teclado: el campo refleja lo que se tecleó en el PC (sirve con autocorrector
// y con teclados de Android que no reportan teclas sueltas).
const typing = $("typing");
let typed = "";

typing.addEventListener("input", () => {
  const now = typing.value;
  let common = 0;
  while (common < typed.length && common < now.length && typed[common] === now[common]) common++;
  for (let i = common; i < typed.length; i++) viewer.send({ t: "key", k: "backspace" });
  const added = now.slice(common);
  if (added) viewer.send({ t: "text", s: added });
  typed = now;
});

typing.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    viewer.send({ t: "key", k: "enter" });
    typing.value = "";
    typed = "";
  } else if (e.key === "Backspace" && typing.value === "") {
    viewer.send({ t: "key", k: "backspace" });
  }
});

for (const btn of document.querySelectorAll("#keybar [data-key], #keybar [data-combo]")) {
  btn.addEventListener("click", () => {
    if (btn.dataset.key) viewer.send({ t: "key", k: btn.dataset.key });
    else viewer.send({ t: "combo", k: btn.dataset.combo.split(",") });
    typing.value = "";
    typed = "";
  });
}

// =========================================================================
// Inicio
// =========================================================================

if ("serviceWorker" in navigator && window.isSecureContext) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

start();
