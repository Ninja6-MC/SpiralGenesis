"""Summarise a Sponge schematic (v2 or v3): size, offset, palette, layers."""
import sys
from collections import Counter
import nbtlib

root = nbtlib.load(sys.argv[1])
s = root.get("Schematic", root)  # v3 nests everything under "Schematic"
version = int(s.get("Version", 0))
w, h, l = int(s["Width"]), int(s["Height"]), int(s["Length"])
blocks = s["Blocks"] if version >= 3 else s
palette = {int(v): k for k, v in blocks["Palette"].items()}
raw = bytes(b & 0xFF for b in (blocks["Data"] if version >= 3 else s["BlockData"]))

ids, i = [], 0
while i < len(raw):  # varints
    val, shift = 0, 0
    while True:
        b = raw[i]; i += 1
        val |= (b & 0x7F) << shift; shift += 7
        if not b & 0x80:
            break
    ids.append(val)

print(f"version={version} dataVersion={int(s.get('DataVersion', 0))} size={w}x{h}x{l} (W x H x L)")
meta = s.get("Metadata", {})
print("offset", list(map(int, s.get("Offset", []))), "WE origin", {k: int(v) for k, v in meta.items() if k.startswith("WE")})
print("entities", len(s.get("Entities", [])), "block entities", len(blocks.get("BlockEntities", [])))
counts = Counter(palette[x] for x in ids)
for name, n in counts.most_common():
    print(f"  {n:4d}  {name}")
for y in range(h):
    print(f"-- y={y}")
    for z in range(l):
        row = ""
        for x in range(w):
            n = palette[ids[(y * l + z) * w + x]].split("[")[0].replace("minecraft:", "")
            row += "." if n == "air" else n[0].upper()
        print("   " + row)
