"""Arma assets/brawlers1.json desde los combos_*.json de los agentes, normalizando cada patrón.

Normalización (el lenguaje deja el joystick empujado hasta «parar» u otro «mover»):
  1. Después de un «mover X t» con tiempo, si lo siguiente no es otro «mover», se inserta «parar»
     (lo que los agentes quisieron decir: moverse t segundos y quedarse quieto).
  2. Se simula la vuelta: si el desplazamiento neto supera 0,1 s de caminata, se agrega al final un
     movimiento de vuelta (en diagonal o recto) para que el bucle no arrastre al brawler.
  3. Se valida la gramática básica y la duración (0,8–3,2 s). Se informa todo cambio.
Uso: python armar_lote.py <salida.json> <combos_a.json> [...]
"""
import io
import json
import math
import re
import sys
import unicodedata

D = {"derecha": (1, 0), "izquierda": (-1, 0), "arriba": (0, 1), "abajo": (0, -1)}
NOMBRES = {(1, 0): "derecha", (-1, 0): "izquierda", (0, 1): "arriba", (0, -1): "abajo",
           (1, 1): "arriba-derecha", (-1, 1): "arriba-izquierda", (1, -1): "abajo-derecha", (-1, -1): "abajo-izquierda"}
MODOS = {"balon brawl": "Balón Brawl", "atrapagemas": "Atrapagemas", "atraco": "Atraco", "zona restringida": "Zona restringida",
         "noqueo": "Noqueo", "supervivencia": "Supervivencia", "supervivencia duo": "Supervivencia dúo", "caza estelar": "Caza estelar"}


def sin_tildes(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower()


def vec(d):
    x = y = 0
    for p in d.split("-"):
        x += D[p][0]
        y += D[p][1]
    n = math.hypot(x, y)
    return x / n, y / n


def intervalo(lines):
    for l in lines:
        m = re.match(r"disparo \w+ cada ([\d.]+)", l)
        if m:
            return float(m.group(1))
    return 0.5


def simular(lines):
    iv = intervalo(lines)
    t, cur, pos = 0.0, None, [0.0, 0.0]

    def adv(dt):
        nonlocal t
        if cur:
            v = vec(cur)
            pos[0] += v[0] * dt
            pos[1] += v[1] * dt
        t += dt

    for l in lines:
        w = l.split()
        if not w or w[0].startswith("#") or w[0] == "disparo":
            continue
        c = sin_tildes(w[0])
        if c == "mover":
            cur = w[1]
            secs = float(w[2]) if len(w) > 2 and re.match(r"^[\d.]+$", w[2]) else 0
            adv(secs)
        elif c == "parar":
            cur = None
        elif c == "disparar":
            n = int(w[1]) if len(w) > 1 and w[1].isdigit() else 1
            adv(n * max(iv, 0.35 if "apuntado" in w else 0.12))
        elif c == "super":
            adv(0.4 if "apuntado" in w else 0.3)
        elif c == "gadget":
            adv(0.2)
        elif c == "hiper":
            adv(0.25)
        elif c == "esperar":
            adv(float(w[1]))
        else:
            raise ValueError("instrucción desconocida: " + l)
    return t, pos


def normalizar(script):
    lines = [l.strip() for l in script.split("\n") if l.strip()]
    out, cambios = [], []
    for i, l in enumerate(lines):
        out.append(l)
        w = l.split()
        if sin_tildes(w[0]) == "mover" and len(w) > 2 and re.match(r"^[\d.]+$", w[2]):
            nxt = next((x for x in lines[i + 1:] if not x.startswith("#")), None)
            if nxt is None or sin_tildes(nxt.split()[0]) not in ("mover", "parar"):
                out.append("parar")
                cambios.append("parar tras «%s»" % l)
    t, pos = simular(out)
    dx, dy = pos
    if math.hypot(dx, dy) > 0.1:
        # Vuelta: primero la parte diagonal, luego la recta que sobre.
        sx, sy = -dx, -dy
        diag = min(abs(sx), abs(sy))
        back = []
        if diag > 0.05:
            back.append("mover %s %.2f" % (NOMBRES[(int(math.copysign(1, sx)), int(math.copysign(1, sy)))], diag * math.sqrt(2)))
        rx, ry = sx - math.copysign(diag, sx), sy - math.copysign(diag, sy)
        if abs(rx) > 0.05:
            back.append("mover %s %.2f" % (NOMBRES[(int(math.copysign(1, rx)), 0)], abs(rx)))
        if abs(ry) > 0.05:
            back.append("mover %s %.2f" % (NOMBRES[(0, int(math.copysign(1, ry)))], abs(ry)))
        if out and out[-1] == "parar":
            out.pop()
        out += back + ["parar"]
        cambios.append("vuelta al origen: " + " + ".join(back))
    t2, pos2 = simular(out)
    return "\n".join(out), cambios, t2, pos2


def main():
    salida, entradas = sys.argv[1], sys.argv[2:]
    patterns, sets, informe = [], [], []
    for f in entradas:
        data = json.load(io.open(f, encoding="utf-8"))
        for b in data["brawlers"]:
            nombre = b["nombre"]
            for m in b["modos"]:
                modo = MODOS.get(sin_tildes(m["modo"]).replace("brawl ball", "balon brawl"), m["modo"])
                slots = []
                for p in m["patrones"]:
                    script, cambios, t, pos = normalizar(p["script"])
                    pid = re.sub(r"[^a-z0-9_]", "", sin_tildes(p["id"]))
                    patterns.append({"id": pid, "name": p["nombre"], "script": script})
                    slots.append(pid)
                    estado = "OK" if 0.8 <= t <= 3.2 else "DURACIÓN %.2f s" % t
                    informe.append("%-28s %5.2f s neto=(%+.2f,%+.2f) %s %s" % (pid, t, pos[0], pos[1], estado,
                                                                               "; ".join(cambios)))
                sid = re.sub(r"[^a-z0-9_]", "_", sin_tildes(nombre + "_" + modo))
                sets.append({"id": sid, "brawler": nombre, "mode": modo, "slots": slots})
    json.dump({"lote": "brawlers1", "patterns": patterns, "sets": sets},
              io.open(salida, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("\n".join(informe))
    print("%d sets, %d patrones" % (len(sets), len(patterns)))


main()
