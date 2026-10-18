#!/usr/bin/env python3
"""
Genera patrones/biblioteca.json (y la copia en app/src/main/assets/) a partir de los perfiles
de cada brawler. Cada patrón se escribe como acciones de alto nivel que la app traduce a
toques según la calibración del usuario:

  moves: joystick, secuencial desde t=0 → {"dir": up|down|left|right|upleft|...|none, "ms": n}
  acts:  botones → {"t": ms, "btn": attack|super|gadget|hyper, "aim"?: dir, "hold"?: ms, "power"?: 0..1}

"up" = hacia el enemigo (en Brawl Stars tu lado siempre queda abajo de la pantalla).
Uso: python3 tools/gen_biblioteca.py
"""
import json
import os
import sys

VERSION = 1
UPDATED = "2026-10-18"

MODES = [
    ("gemgrab", "Atrapagemas"),
    ("brawlball", "Balón Brawl"),
    ("showdown", "Supervivencia"),
    ("heist", "Atraco"),
    ("bounty", "Caza estelar"),
    ("hotzone", "Zona restringida"),
    ("knockout", "Noqueo"),
]

# Cadencia de taps de ataque según recarga (ms entre disparos).
CADENCE = {"fast": 380, "normal": 560, "slow": 780}


# ----------------------------------------------------------------------------- helpers

class P:
    """Constructor de un patrón."""

    def __init__(self, name, desc, modes=("all",)):
        self.name, self.desc, self.modes = name, desc, list(modes)
        self.moves, self.acts, self.t_move = [], [], 0

    def move(self, d, ms, power=None):
        m = {"dir": d, "ms": int(ms)}
        if power is not None:
            m["power"] = power
        self.moves.append(m)
        self.t_move += ms
        return self

    def wait(self, ms):
        return self.move("none", ms)

    def tap(self, btn, t):
        self.acts.append({"t": int(t), "btn": btn})
        return self

    def aim(self, btn, t, d, hold=320, power=None):
        a = {"t": int(t), "btn": btn, "aim": d, "hold": int(hold)}
        if power is not None:
            a["power"] = power
        self.acts.append(a)
        return self

    def attacks(self, start, end, every):
        t = start
        while t < end:
            self.tap("attack", t)
            t += every
        return self

    def super_(self, b, t):
        """Lanza el súper según cómo se apunta en este brawler."""
        kind = b["super"]
        if kind == "forward":
            return self.aim("super", t, "up", 320)
        if kind == "back":
            return self.aim("super", t, "down", 320)
        if kind == "self":
            return self.aim("super", t, "up", 260, power=0.12)
        return self.tap("super", t)

    def hyper_super(self, b, t):
        """Hipercarga y, enseguida, súper (el súper sale hipercargado)."""
        if b.get("hyper", True):
            self.tap("hyper", t)
            t += 250
        return self.super_(b, t)

    def json(self):
        acts = sorted(self.acts, key=lambda a: a["t"])
        return {"name": self.name, "desc": self.desc, "modes": self.modes,
                "moves": self.moves, "acts": acts}


# ----------------------------------------------------------------------------- plantillas

def kite(b):
    e = CADENCE[b["reload"]]
    p = P("Poke y esquiva", "Se mueve de lado a lado disparando con auto-apuntado y retrocede un poco.")
    p.move("left", 700).move("right", 700).move("down", 400).move("up", 400)
    p.attacks(80, 2200, e)
    return p


def full_combo(b):
    e = CADENCE[b["reload"]]
    order = b.get("combo", "gadget-super")
    p = P("Combo completo", f"{b['gadget_desc']} → hipercarga + súper → ataques. Úsalo con súper e hipercarga listos.")
    if b["style"] in ("assassin", "tank"):
        p.move("up", 900).move("up", 900).move("down", 500)
        if order == "gadget-super":
            p.tap("gadget", 300)
            p.hyper_super(b, 650)
        else:
            p.hyper_super(b, 300)
            p.tap("gadget", 900)
        p.attacks(1100, 2300, e)
    else:
        p.move("left", 600).move("right", 600).move("down", 500)
        if order == "gadget-super":
            p.tap("gadget", 100)
            p.hyper_super(b, 450)
        else:
            p.hyper_super(b, 100)
            p.tap("gadget", 700)
        p.attacks(900, 1700, e)
    return p


def engage(b):
    e = CADENCE[b["reload"]]
    p = P("Entrada agresiva", "Avanza hacia el enemigo, lanza el súper y remata con ataques.")
    p.move("up", 1100).move("upleft", 400).move("upright", 400)
    p.super_(b, 500)
    p.attacks(900, 1900, e)
    return p


def escape(b):
    p = P("Escape", "Se retira en diagonal" + (" usando el súper para huir" if b["super"] == "back" else "")
          + (" y activa el gadget defensivo" if b.get("gadget_defensive") else "") + ".")
    p.move("downleft", 600).move("downright", 600).move("down", 300)
    if b.get("gadget_defensive"):
        p.tap("gadget", 50)
    if b["super"] == "back":
        p.aim("super", 200, "down", 300)
    return p


def finisher(b):
    e = CADENCE[b["reload"]]
    p = P("Remate", "Ráfaga de ataques avanzando un poco, con súper si está cargado.")
    p.move("up", 500).move("up", 500, power=0.5)
    p.attacks(0, 1000, max(250, e - 120))
    p.super_(b, 1050)
    return p


def poke_long(b):
    e = CADENCE[b["reload"]]
    p = P("Presión a distancia", "Mantiene la distancia: dispara, retrocede en zigzag y vuelve.")
    p.move("downleft", 500).move("downright", 500).move("upleft", 500).move("upright", 500)
    p.attacks(100, 2000, e)
    return p


def support(b):
    e = CADENCE[b["reload"]]
    p = P("Apoyo al equipo", "Súper junto a ti (curación, torreta o escudo) y disparos de cobertura.")
    p.super_(b, 0)
    p.move("left", 600).move("right", 600)
    p.attacks(400, 1200, e)
    return p


# ---- por modo

def m_gemgrab(b):
    e = CADENCE[b["reload"]]
    p = P("Controlar el centro", "Se mantiene en la zona de gemas esquivando y disparando sin avanzar de más.", ["gemgrab"])
    p.move("left", 500).move("right", 500).move("up", 300).move("down", 300).move("left", 250).move("right", 250)
    p.attacks(60, 2100, e)
    return p


def m_brawlball(b):
    e = CADENCE[b["reload"]]
    p = P("Avance al arco", "Avanza hacia el arco rival disparando; con el balón, el ataque lo patea.", ["brawlball"])
    p.move("up", 800).move("upleft", 400).move("upright", 400).move("up", 400)
    p.attacks(200, 2000, e)
    if b["super"] in ("forward", "auto"):
        p.super_(b, 900)
    return p


def m_showdown(b):
    e = CADENCE[b["reload"]]
    p = P("Emboscada", "Espera quieto (ideal en un arbusto), sale con gadget + súper y se retira.", ["showdown"])
    p.wait(500).move("up", 700).move("down", 600)
    p.tap("gadget", 520)
    p.super_(b, 750)
    p.attacks(1000, 1800, e)
    return p


def m_heist(b):
    e = CADENCE[b["reload"]]
    p = P("Presión a la caja fuerte", "Avanza recto disparando sin parar y se repliega.", ["heist"])
    p.move("up", 1300).move("down", 500)
    p.attacks(0, 1800, max(250, e - 100))
    p.super_(b, 900)
    return p


def m_bounty(b):
    e = CADENCE[b["reload"]]
    p = P("Kite seguro", "Nunca avanza: dispara retrocediendo en zigzag para no regalar estrellas.", ["bounty", "knockout"])
    p.move("downleft", 450).move("downright", 450).move("left", 400).move("right", 400)
    p.attacks(50, 1700, e)
    return p


def m_hotzone(b):
    e = CADENCE[b["reload"]]
    p = P("Defender la zona", "Pasos cortos dentro de la zona, disparando y lanzando el súper.", ["hotzone"])
    p.move("left", 350, power=0.6).move("right", 350, power=0.6).move("up", 300, power=0.6).move("down", 300, power=0.6)
    p.attacks(0, 1300, e)
    p.super_(b, 600)
    return p


def build(b):
    pats = [full_combo(b), kite(b) if b["style"] not in ("tank",) else engage(b)]
    if b["style"] in ("assassin", "tank", "brawler"):
        if b["style"] != "tank":
            pats.append(engage(b))
        pats.append(finisher(b))
    else:
        pats.append(poke_long(b))
    if b["style"] == "support":
        pats.append(support(b))
    pats.append(escape(b))
    pats += [m_gemgrab(b), m_brawlball(b), m_showdown(b), m_heist(b), m_bounty(b), m_hotzone(b)]
    return {"id": b["id"], "name": b["name"], "role": b["role"], "tip": b.get("tip", ""),
            "patterns": [p.json() for p in pats]}


# ----------------------------------------------------------------------------- perfiles
# style: assassin | tank | brawler | sharpshooter | thrower | support | controller
# super: forward (apuntar al enemigo) | auto (tap) | self (junto a ti) | back (para huir)
# reload: fast | normal | slow

from perfiles import BRAWLERS  # noqa: E402  (tools/perfiles.py)


def main():
    lib = {"version": VERSION, "updated": UPDATED,
           "modes": [{"id": i, "name": n} for i, n in MODES],
           "brawlers": [build(b) for b in BRAWLERS]}
    text = json.dumps(lib, ensure_ascii=False, separators=(",", ":"))
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for path in ("patrones/biblioteca.json", "app/src/main/assets/biblioteca.json"):
        full = os.path.join(root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)
    n = sum(len(b["patterns"]) for b in lib["brawlers"])
    print(f"{len(lib['brawlers'])} brawlers, {n} patrones, {len(text)} bytes")


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main()
