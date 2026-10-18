"""
Perfiles de brawlers para gen_biblioteca.py.

Meta de octubre de 2026 (uso en ranked SeeMeta 17-sep → 17-oct-2026, cruzado con
timesaver.gg, brawltime.ninja y noff.gg). Kits según noff.gg y la wiki de Fandom.
Fuentes completas en tools/FUENTES.md.

Campos:
  style: assassin | tank | brawler | sharpshooter | thrower | support | controller
  super: forward (apuntar al enemigo) | auto (tap) | self (junto a ti / aliado)
  reload: fast | normal | slow
  combo: gadget-super | super-gadget | attacks-gadget-super
  gadget_in_combo: False si el gadget es de movimiento (no conviene en el combo)
  gadget_defensive: True si el gadget sirve para escapar o aguantar
"""

BRAWLERS = [
    {
        "id": "gus", "name": "Gus", "role": "Apoyo", "style": "support",
        "super": "self", "reload": "normal",
        "gadget_desc": "Ataques que sueltan espíritus → Explosión espiritual",
        "combo": "attacks-gadget-super", "gadget_defensive": False,
        "tip": "Súper = escudo para ti o un aliado; gadget recomendado: Explosión espiritual",
    },
    {
        "id": "brock", "name": "Brock", "role": "Tirador", "style": "sharpshooter",
        "super": "forward", "reload": "slow",
        "gadget_desc": "Hipercarga + Lluvia de cohetes",
        "combo": "gadget-super", "gadget_in_combo": False, "gadget_defensive": True,
        "tip": "Gadget Zapatillas explosivas: salta en la dirección en que te mueves (úsalo en Escape)",
    },
    {
        "id": "colette", "name": "Colette", "role": "Daño", "style": "brawler",
        "super": "forward", "reload": "normal",
        "gadget_desc": "Disparo curativo",
        "combo": "gadget-super", "gadget_defensive": False,
        "tip": "Su súper va y vuelve: es seguro lanzarlo a través de los enemigos",
    },
    {
        "id": "wendy", "name": "Wendy", "role": "Apoyo", "style": "support",
        "super": "self", "reload": "fast",
        "gadget_desc": "Generador de escudo",
        "combo": "gadget-super", "gadget_in_combo": False, "gadget_defensive": True,
        "tip": "Súper = generador de escudo junto al equipo; gadget Energía eólica para reposicionarte",
    },
    {
        "id": "amber", "name": "Amber", "role": "Controladora", "style": "controller",
        "super": "forward", "reload": "fast",
        "gadget_desc": "Frasco de fuego → ataque para encenderlo → Danza de llamas",
        "combo": "super-gadget", "gadget_defensive": True,
        "tip": "Lanza el súper y dispárale para encender el charco",
    },
    {
        "id": "shade", "name": "Shade", "role": "Asesino", "style": "assassin",
        "super": "forward", "reload": "fast",
        "gadget_desc": "Súper atravesando muros → Brazos largos",
        "combo": "super-gadget", "gadget_defensive": False,
        "tip": "Hipercarga antes de lanzarte sobre un enemigo frágil",
    },
    {
        "id": "elprimo", "name": "El Primo", "role": "Tanque", "style": "tank",
        "super": "forward", "reload": "fast",
        "gadget_desc": "Codazo volador → Vuelta de tuerca",
        "combo": "super-gadget", "gadget_defensive": False,
        "tip": "Salta encima, golpea y usa el gadget para lanzar al enemigo hacia tu equipo",
    },
    {
        "id": "juju", "name": "Juju", "role": "Lanzadora", "style": "thrower",
        "super": "auto", "reload": "normal",
        "gadget_desc": "Elementalista",
        "combo": "gadget-super", "gadget_defensive": True,
        "tip": "Lanza desde el pasto para más alcance; el muñeco busca solo al enemigo",
    },
    {
        "id": "charlie", "name": "Charlie", "role": "Controladora", "style": "controller",
        "super": "forward", "reload": "slow",
        "gadget_desc": "Capullo → Arañas",
        "combo": "super-gadget", "gadget_defensive": False,
        "tip": "Capullo al objetivo prioritario y remata con las arañas",
    },
    {
        "id": "barley", "name": "Barley", "role": "Lanzador", "style": "thrower",
        "super": "forward", "reload": "slow",
        "gadget_desc": "Jarabe pegajoso",
        "combo": "gadget-super", "gadget_defensive": True,
        "tip": "Lanza por encima de muros; el gadget frena a quien se te acerque",
    },
]
