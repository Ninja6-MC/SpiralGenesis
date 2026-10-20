# Spawn plot structure sources

The two templates shipped in `src/main/resources/structures/` are generated from the
schematics here, so they can be reviewed and rebuilt.

| Template | Schematic | Spawn spot |
| :--- | :--- | :--- |
| `spawn-hut.nbt` | `schematics/spawn-hut.schem` | 3, 1, 3: centre of the floor |
| `spawn-tower.nbt` | `schematics/spawn-tower.schem` | 3, 1, 4: beside the ladder, facing east |

Both were built in game and saved with WorldEdit as Sponge v3 schematics.
`schem_to_nbt.py` converts one to a vanilla structure file at DataVersion 3700
(Minecraft 1.20.4), the oldest supported version, so the server can upgrade it on every
later one:

* air connected to the outside of the box is left out, so placing the template leaves the
  terrain around the building alone, as structure void does;
* air enclosed by the building is kept, so rooms clear when it is placed. Air enclosed
  within its own horizontal layer counts as inside, and so does air between two such
  layers, which keeps a one-high window band from letting terrain into a room;
* `--drop-layer Y=BLOCK` leaves out every `BLOCK` in layer `Y`, for ground copied with
  the build;
* it prints the darkest block light on an inside floor spot, from the torch positions.

Schematics with entities or block entity data are refused.

## Regenerating

Requires Python 3 and [nbtlib](https://pypi.org/project/nbtlib/) 2.x. From the repository
root:

```sh
python -I tools/structures/schem_to_nbt.py tools/structures/schematics/spawn-hut.schem \
    src/main/resources/structures/spawn-hut.nbt
python -I tools/structures/schem_to_nbt.py tools/structures/schematics/spawn-tower.schem \
    src/main/resources/structures/spawn-tower.nbt --drop-layer 0=minecraft:stone
```

The output is byte-for-byte deterministic only up to gzip headers; compare with nbtlib
rather than with a hash. `inspect_schem.py <file.schem>` prints a schematic's size,
palette and each layer, which is the quickest way to find a spawn spot.

The CI smoke test places both shipped templates on every Minecraft version in the matrix;
see `.github/scripts/smoke-test.sh`.
