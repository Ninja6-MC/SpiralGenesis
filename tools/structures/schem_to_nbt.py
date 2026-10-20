"""Convert a Sponge v3 schematic to a vanilla structure file for an older data version.

Air reachable from the outside of the box is left out (placed as "leave as is", the same
as structure void); enclosed air is kept so rooms clear. --drop-layer Y=BLOCK leaves out
every BLOCK at layer Y (for example ground copied with the build). --keep-all-air stores
every air cell in the box, as enclosed air is, for an open-sided template whose spawn or
stairs terrain inside the box would otherwise block.
"""
import argparse
from collections import deque
import nbtlib
from nbtlib.tag import Compound, Int, List, String

ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("dst")
ap.add_argument("--data-version", type=int, default=3700)  # 1.20.4
ap.add_argument("--drop-layer", action="append", default=[])
ap.add_argument("--keep-all-air", action="store_true")
a = ap.parse_args()

s = nbtlib.load(a.src)["Schematic"]
assert int(s["Version"]) == 3, "Sponge v3 expected"
w, h, l = int(s["Width"]), int(s["Height"]), int(s["Length"])
assert not s.get("Entities") and not s["Blocks"].get("BlockEntities"), "entities not supported"
pal = {int(v): str(k) for k, v in s["Blocks"]["Palette"].items()}
raw = bytes(b & 0xFF for b in s["Blocks"]["Data"])
ids, i = [], 0
while i < len(raw):
    v = sh = 0
    while True:
        b = raw[i]; i += 1; v |= (b & 0x7F) << sh; sh += 7
        if not b & 0x80: break
    ids.append(v)
state = {(x, y, z): pal[ids[(y * l + z) * w + x]] for y in range(h) for z in range(l) for x in range(w)}

drop = set()
for spec in a.drop_layer:
    y, block = spec.split("=")
    drop |= {p for p, n in state.items() if p[1] == int(y) and n == block}

air = lambda p: state[p] == "minecraft:air" or p in drop
outside, q = set(), deque()
for p in state:
    x, y, z = p
    if air(p) and (x in (0, w - 1) or y in (0, h - 1) or z in (0, l - 1)):
        outside.add(p); q.append(p)
while q:
    x, y, z = q.popleft()
    for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
        n = (x + d[0], y + d[1], z + d[2])
        if n in state and n not in outside and air(n):
            outside.add(n); q.append(n)

# A room with 1-high windows is open in 3D, so also count air enclosed within its own
# layer, and air sandwiched between such layers (the window band), as inside.
def enclosed_2d(y):
    seen, q = set(), deque()
    for x in range(w):
        for z in range(l):
            p = (x, y, z)
            if air(p) and (x in (0, w - 1) or z in (0, l - 1)):
                seen.add(p); q.append(p)
    while q:
        x, _, z = q.popleft()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y, z + dz)
            if n in state and n not in seen and air(n):
                seen.add(n); q.append(n)
    return {(x, y, z) for x in range(w) for z in range(l) if air((x, y, z)) and (x, y, z) not in seen}

inside = set()
for y in range(h):
    inside |= enclosed_2d(y)
for y in range(1, h - 1):
    for p in [(x, y, z) for x in range(w) for z in range(l)]:
        if air(p) and p not in inside and (p[0], y - 1, p[2]) in inside and (p[0], y + 1, p[2]) in inside:
            inside.add(p)
outside -= inside
if a.keep_all_air:
    outside = set()

# Block light check: torches emit 14 and lose 1 per step through anything not opaque.
OPAQUE = ("cobblestone", "stone", "planks", "bricks", "log")
opaque = lambda p: state[p].split("[")[0].replace("minecraft:", "") in OPAQUE and p not in drop
light, q = {}, deque()
for p, n in state.items():
    if "torch" in n or "lantern" in n:
        light[p] = 14; q.append(p)
while q:
    p = q.popleft()
    for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
        n = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
        if n in state and not opaque(n) and light.get(n, 0) < light[p] - 1:
            light[n] = light[p] - 1; q.append(n)
standable = [p for p in state if p not in outside and state[p] == "minecraft:air" and p not in drop
             and (p[0], p[1] - 1, p[2]) in state and opaque((p[0], p[1] - 1, p[2]))]
if standable:
    darkest = min(standable, key=lambda p: light.get(p, 0))
    print(f"inside floor spots {len(standable)}, darkest block light {light.get(darkest, 0)} at {darkest}")

def parse(name):
    base, _, props = name.partition("[")
    c = Compound({"Name": String(base)})
    if props:
        c["Properties"] = Compound({k: String(v) for k, v in
                                    (kv.split("=") for kv in props.rstrip("]").split(","))})
    return c

kept = sorted((p for p in state if p not in outside and p not in drop), key=lambda p: (p[1], p[2], p[0]))
names = sorted({state[p] for p in kept})
index = {n: k for k, n in enumerate(names)}
nbt = nbtlib.File(Compound({
    "DataVersion": Int(a.data_version),
    "size": List[Int]([Int(w), Int(h), Int(l)]),
    "palette": List[Compound]([parse(n) for n in names]),
    "blocks": List[Compound]([Compound({"pos": List[Int]([Int(c) for c in p]), "state": Int(index[state[p]])}) for p in kept]),
    "entities": List[Compound]([]),
}))
nbt.save(a.dst, gzipped=True)
enclosed = sum(1 for p in kept if state[p] == "minecraft:air")
print(f"{a.dst}: size {w}x{h}x{l}, {len(kept)} positions kept ({enclosed} enclosed air), "
      f"{len(outside)} outside air and {len(drop)} dropped left untouched, palette {len(names)}")
