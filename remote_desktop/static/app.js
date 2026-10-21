"use strict";

// --- Utilidades -----------------------------------------------------------

const $ = (id) => document.getElementById(id);

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch (e) {
    return null;
  }
}

// --- Login ----------------------------------------------------------------

let token = store("rd-token");

$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("login-error").textContent = "";
  try {
    const res = await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password: $("password").value }),
    });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || "Error al conectar");
    token = body.token;
    store("rd-token", token);
    $("password").value = "";
    showViewer();
  } catch (err) {
    $("login-error").textContent = err.message;
  }
});

function showLogin(message) {
  token = null;
  store("rd-token", null);
  $("viewer").classList.add("hidden");
  $("login").classList.remove("hidden");
  $("login-error").textContent = message || "";
}

function showViewer() {
  $("login").classList.add("hidden");
  $("viewer").classList.remove("hidden");
  connect();
}

// --- Conexión -------------------------------------------------------------

const canvas = $("screen");
const ctx = canvas.getContext("2d");
let ws = null;
let failedAttempts = 0;
let reconnectTimer = null;

function setStatus(text) {
  $("status").textContent = text;
  $("status").classList.toggle("hidden", !text);
}

function send(msg) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
}

function connect() {
  clearTimeout(reconnectTimer);
  if (!token) return showLogin();
  setStatus("Conectando…");
  const scheme = location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${scheme}://${location.host}/ws?token=${encodeURIComponent(token)}`);
  ws.binaryType = "arraybuffer";
  let gotHello = false;

  ws.onmessage = async (ev) => {
    if (typeof ev.data === "string") {
      const msg = JSON.parse(ev.data);
      if (msg.t === "hello") {
        gotHello = true;
        failedAttempts = 0;
        setStatus("");
        applyQuality();
      } else if (msg.t === "error") {
        console.warn("Servidor:", msg.msg);
      }
      return;
    }
    try {
      const bitmap = await createImageBitmap(new Blob([ev.data], { type: "image/jpeg" }));
      if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
        fitCanvas();
      }
      ctx.drawImage(bitmap, 0, 0);
      bitmap.close();
    } finally {
      send({ t: "ack" });
    }
  };

  ws.onclose = (ev) => {
    ws = null;
    if (!token || $("viewer").classList.contains("hidden")) return;
    if (ev.code === 4000) {
      setStatus("Otra sesión tomó el control. Toca para reconectar.");
      $("stage").addEventListener("click", connect, { once: true });
      return;
    }
    if (!gotHello && ++failedAttempts >= 3) {
      return showLogin("La sesión expiró o el PC no responde. Ingresa de nuevo.");
    }
    setStatus("Reconectando…");
    reconnectTimer = setTimeout(connect, 2000);
  };
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && token && !ws &&
      !$("viewer").classList.contains("hidden")) {
    connect();
  }
});

// Ajusta la imagen al espacio libre (cambia al abrir el teclado o girar).
function fitCanvas() {
  if (!canvas.width) return;
  const stageBox = $("stage").getBoundingClientRect();
  const scale = Math.min(stageBox.width / canvas.width, stageBox.height / canvas.height);
  canvas.style.width = `${Math.floor(canvas.width * scale)}px`;
  canvas.style.height = `${Math.floor(canvas.height * scale)}px`;
}

new ResizeObserver(fitCanvas).observe($("stage"));

// --- Coordenadas y cursor -------------------------------------------------

const cursor = $("cursor");

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
  send({ t: "move", x: p.x, y: p.y });
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

// --- Zoom (pellizco con dos dedos) ----------------------------------------

const surface = $("surface");
const view = { s: 1, tx: 0, ty: 0 };

function applyView() {
  surface.style.transform = `translate(${view.tx}px, ${view.ty}px) scale(${view.s})`;
}

function resetView() {
  view.s = 1;
  view.tx = 0;
  view.ty = 0;
  applyView();
}

function stagePoint(t) {
  const r = $("stage").getBoundingClientRect();
  return { x: t.clientX - r.left, y: t.clientY - r.top };
}

let pinch = null;

function startPinch(touches) {
  const a = stagePoint(touches[0]);
  const b = stagePoint(touches[1]);
  const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
  const ox = surface.offsetLeft;
  const oy = surface.offsetTop;
  pinch = {
    dist: Math.hypot(a.x - b.x, a.y - b.y) || 1,
    s: view.s,
    // Punto de la superficie que queda bajo los dedos.
    ux: (mid.x - ox - view.tx) / view.s,
    uy: (mid.y - oy - view.ty) / view.s,
    ox,
    oy,
  };
}

function updatePinch(touches) {
  const a = stagePoint(touches[0]);
  const b = stagePoint(touches[1]);
  const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
  const dist = Math.hypot(a.x - b.x, a.y - b.y);
  view.s = Math.min(Math.max(pinch.s * dist / pinch.dist, 1), 6);
  view.tx = mid.x - pinch.ox - pinch.ux * view.s;
  view.ty = mid.y - pinch.oy - pinch.uy * view.s;
  applyView();
}

$("zoom-reset").addEventListener("click", resetView);

// --- Gestos ---------------------------------------------------------------

const TAP_SLOP = 10;
const LONG_PRESS_MS = 550;
const SCROLL_STEP = 25;

let mode = "click"; // "click" o "scroll"
let gesture = null;

$("mode").addEventListener("click", () => {
  mode = mode === "click" ? "scroll" : "click";
  $("mode").textContent = mode === "click" ? "🖱️ Clic" : "↕️ Rueda";
});

function cancelGesture() {
  if (!gesture) return;
  clearTimeout(gesture.timer);
  if (gesture.dragging) send({ t: "up", b: "left" });
  gesture = null;
}

const stage = $("stage");

stage.addEventListener("touchstart", (e) => {
  e.preventDefault();
  if (e.touches.length >= 2) {
    cancelGesture();
    startPinch(e.touches);
    return;
  }
  const t = e.touches[0];
  gesture = {
    startX: t.clientX,
    startY: t.clientY,
    lastX: t.clientX,
    lastY: t.clientY,
    accX: 0,
    accY: 0,
    moved: false,
    dragging: false,
    done: false,
    start: normalized(t),
  };
  if (mode === "click") {
    gesture.timer = setTimeout(() => {
      if (gesture && !gesture.moved) {
        gesture.done = true;
        moveTo(gesture.start);
        send({ t: "click", b: "right" });
        if (navigator.vibrate) navigator.vibrate(30);
      }
    }, LONG_PRESS_MS);
  }
}, { passive: false });

stage.addEventListener("touchmove", (e) => {
  e.preventDefault();
  if (pinch && e.touches.length >= 2) {
    updatePinch(e.touches);
    return;
  }
  if (!gesture || gesture.done) return;
  const t = e.touches[0];
  if (!gesture.moved &&
      Math.hypot(t.clientX - gesture.startX, t.clientY - gesture.startY) > TAP_SLOP) {
    gesture.moved = true;
    clearTimeout(gesture.timer);
  }
  if (!gesture.moved) return;

  if (mode === "scroll") {
    gesture.accX += t.clientX - gesture.lastX;
    gesture.accY += t.clientY - gesture.lastY;
    const dy = Math.trunc(gesture.accY / SCROLL_STEP);
    const dx = Math.trunc(gesture.accX / SCROLL_STEP);
    if (dx || dy) {
      // Desplazamiento "natural": el contenido sigue al dedo.
      send({ t: "scroll", dx: -dx, dy });
      gesture.accX -= dx * SCROLL_STEP;
      gesture.accY -= dy * SCROLL_STEP;
    }
  } else {
    if (!gesture.dragging) {
      gesture.dragging = true;
      moveTo(gesture.start);
      send({ t: "down", b: "left" });
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
  if (gesture.dragging) {
    send({ t: "up", b: "left" });
  } else if (!gesture.moved && !gesture.done && mode === "click") {
    moveTo(gesture.start);
    send({ t: "click", b: "left" });
  }
  gesture = null;
}, { passive: false });

stage.addEventListener("touchcancel", () => {
  pinch = null;
  cancelGesture();
});

// --- Teclado --------------------------------------------------------------

const typing = $("typing");
let typed = "";

$("kbd").addEventListener("click", () => {
  const bar = $("keybar");
  bar.classList.toggle("hidden");
  if (!bar.classList.contains("hidden")) typing.focus();
});

// El campo de texto refleja lo que se tecleó en el PC: cada cambio se
// traduce en borrados + texto nuevo. Funciona con autocorrector y con
// teclados de Android que no reportan teclas individuales.
typing.addEventListener("input", () => {
  const now = typing.value;
  let common = 0;
  while (common < typed.length && common < now.length && typed[common] === now[common]) {
    common++;
  }
  for (let i = common; i < typed.length; i++) send({ t: "key", k: "backspace" });
  const added = now.slice(common);
  if (added) send({ t: "text", s: added });
  typed = now;
});

typing.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    send({ t: "key", k: "enter" });
    typing.value = "";
    typed = "";
  } else if (e.key === "Backspace" && typing.value === "") {
    send({ t: "key", k: "backspace" });
  }
});

document.querySelectorAll("#keybar [data-key], #keybar [data-combo]").forEach((btn) => {
  btn.addEventListener("click", () => {
    if (btn.dataset.key) send({ t: "key", k: btn.dataset.key });
    else send({ t: "combo", k: btn.dataset.combo.split(",") });
    typing.value = "";
    typed = "";
  });
});

// --- Ajustes y salida -----------------------------------------------------

function applyQuality() {
  const [quality, fps] = $("quality").value.split(",").map(Number);
  store("rd-quality", $("quality").value);
  send({ t: "settings", quality, fps });
}

const savedQuality = store("rd-quality");
if (savedQuality && document.querySelector(`#quality option[value="${savedQuality}"]`)) {
  $("quality").value = savedQuality;
}
$("quality").addEventListener("change", applyQuality);

$("logout").addEventListener("click", () => {
  const socket = ws;
  fetch("/api/logout", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token }),
  }).catch(() => {});
  showLogin();
  if (socket) socket.close();
});

// --- Inicio ---------------------------------------------------------------

if (token) showViewer();
