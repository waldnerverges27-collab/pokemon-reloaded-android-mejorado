#!/usr/bin/env python3
"""Genera src/OpenGMK/gm8emulator/src/key_defaults.rs a partir del volcado GML del juego.

Uso: gen-key-defaults.py <volcado.gml> <salida.rs>

Lee el script DefaultKeyAssignment: cada `key_xxx=valor;` es la tecla por defecto de una accion.
Las teclas del segundo jugador (key_p2_*) se dejan fuera: no son de quien juega con el movil.
"""
import re
import sys

VK = {
    "vk_backspace": 8, "vk_tab": 9, "vk_enter": 13, "vk_return": 13, "vk_shift": 16, "vk_control": 17,
    "vk_alt": 18, "vk_escape": 27, "vk_space": 32, "vk_pageup": 33, "vk_pagedown": 34, "vk_end": 35,
    "vk_home": 36, "vk_left": 37, "vk_up": 38, "vk_right": 39, "vk_down": 40, "vk_insert": 45,
    "vk_delete": 46, "vk_multiply": 106, "vk_add": 107, "vk_subtract": 109, "vk_decimal": 110,
    "vk_divide": 111, "vk_lshift": 160, "vk_rshift": 161, "vk_lcontrol": 162, "vk_rcontrol": 163,
    "vk_lalt": 164, "vk_ralt": 165,
}
for n in range(10):
    VK[f"vk_numpad{n}"] = 96 + n
for n in range(1, 13):
    VK[f"vk_f{n}"] = 111 + n


def value(text):
    text = text.strip()
    m = re.fullmatch(r"ord\('(.)'\)", text)
    if m:
        return ord(m.group(1).upper())
    if text in VK:
        return VK[text]
    if re.fullmatch(r"\d+", text):
        return int(text)
    raise SystemExit(f"valor desconocido: {text!r}")


def main():
    dump, out = sys.argv[1], sys.argv[2]
    lines = open(dump, encoding="latin-1").read().split("\n")
    start = next(i for i, l in enumerate(lines) if l.strip() == "### SCRIPT DefaultKeyAssignment")
    body = []
    for line in lines[start + 1:]:
        if line.startswith("### SCRIPT"):
            break
        body.append(line)
    pairs = []
    for name, val in re.findall(r"\b(key_[A-Za-z0-9_]+)\s*=\s*([^;\n]+)", " ".join(body)):
        if name.startswith("key_p2_"):
            continue
        pairs.append((name, value(val)))
    seen = set()
    rows = []
    for name, code in pairs:
        if name not in seen:
            seen.add(name)
            rows.append(f'    ("{name}", {code}),')
    with open(out, "w") as f:
        f.write("//! Generado por tools/gen-key-defaults.py a partir de DefaultKeyAssignment. No editar a mano.\n")
        f.write("//!\n//! La tecla por defecto de cada accion del juego (codigos de tecla de Windows).\n\n")
        f.write("pub const KEY_DEFAULTS: &[(&str, i32)] = &[\n")
        f.write("\n".join(rows))
        f.write("\n];\n")
    print(len(rows), "teclas")


main()
