"""Perfiles de brawlers para gen_biblioteca.py (fuentes en tools/FUENTES.md)."""

BRAWLERS = [
    {
        "id": "charlie", "name": "Charlie", "role": "Controladora", "style": "controller",
        "super": "auto", "reload": "slow", "hyper": True,
        "gadget_desc": "Gadget", "gadget_defensive": False, "combo": "super-gadget",
        "tip": "Capullo con auto-apuntado al más cercano",
    },
    {
        "id": "barley", "name": "Barley", "role": "Lanzador", "style": "thrower",
        "super": "forward", "reload": "normal", "hyper": True,
        "gadget_desc": "Gadget", "gadget_defensive": False, "combo": "gadget-super",
        "tip": "Lanza por encima de muros",
    },
]
