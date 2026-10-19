#!/usr/bin/env python3
"""Regenerates android/app/src/main/assets/controls-layout.json from the engine's table of controls
(src/OpenGMK/gm8emulator/src/touch_controls.rs), so the layout editor starts from the same
positions the game uses. Run it from the repository root after changing the table."""
import json
import re

SRC = 'src/OpenGMK/gm8emulator/src/touch_controls.rs'
OUT = 'android/app/src/main/assets/controls-layout.json'
CONTEXTS = {'MENU': 1, 'WORLD': 2, 'BATTLE': 4, 'DIALOG': 8, 'CHARACTER': 16, 'CHOICE': 32, 'SAFARI': 64, 'MENU_NAV': 128}

text = open(SRC).read()
names = dict(re.findall(r'pub const (\w+): &str = "([^"]+)";', text))
floats = {k: float(v) for k, v in re.findall(r'(?:pub )?const (\w+): f32 = ([0-9.]+);', text)}
pattern = re.compile(
    r'Control \{ name: ([^,]+), key: [^,]+, shape: Shape::Circle \{ cx: ([^,]+), cy: ([^,]+), r: ([^ ]+) \}, '
    r'label: Label::(\w+)(?:\("([^"]*)"\))?[^,]*, contexts: ([^}]+)\}')
controls = []
for name, cx, cy, r, kind, label, ctx in pattern.findall(text):
    name = names.get(name, name.strip('"'))
    try:
        cx, cy, r = (floats[v.strip()] if v.strip() in floats else float(v) for v in (cx, cy, r))
    except KeyError:
        continue
    mask = 0
    for part in ctx.split('|'):
        mask |= CONTEXTS[part.strip()]
    if mask & 8 or kind != 'Text':  # the dialogue box and the picture buttons are not movable
        continue
    controls.append(dict(name=name, label=label, cx=cx, cy=cy, r=r, contexts=mask))

stick = dict(cx=floats['JOYSTICK_CX'], cy=floats['JOYSTICK_CY'], r=floats['JOYSTICK_R'])
json.dump(dict(joystick=stick, controls=controls), open(OUT, 'w'), indent=1)
print(len(controls), 'controls')
