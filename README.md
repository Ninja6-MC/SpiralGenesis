<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/icon-transparent-dark.svg">
    <img src="docs/assets/icon-transparent-light.svg" width="112" height="112" alt="">
  </picture>
</p>

<h1 align="center">SpiralGenesis</h1>

<p align="center">
  <a href="https://github.com/Ninja6-MC/SpiralGenesis/actions/workflows/ci.yml"><img src="https://github.com/Ninja6-MC/SpiralGenesis/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html"><img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java 21"></a>
  <a href="https://papermc.io"><img src="https://img.shields.io/badge/PaperMC-1.20%2B-green.svg" alt="PaperMC 1.20+"></a>
</p>

**Every player starts somewhere of their own.** Instead of dropping everyone at one crowded
world spawn, SpiralGenesis gives each new player their own plot of land — 500×500 blocks by
default — laid out in an expanding spiral around a centre point you choose.

The plugin finds them somewhere sensible to stand: not in an ocean, not in a lava pool, not
at the bottom of a ravine or on the edge of a cliff. It happens on first join, off the main
thread, and the player keeps that spot as their respawn point for good.

**[⬇ Download](https://github.com/Ninja6-MC/SpiralGenesis/releases)** ·
[Modrinth](https://modrinth.com/plugin/spiralgenesis) ·
[Hangar](https://hangar.papermc.io/Ninja6-MC/SpiralGenesis)

---

## Quick start

1. **Requirements:** Paper 1.20 or newer, a Paper fork such as Purpur, or Folia.
   Java 21 for Minecraft 1.20 and 1.21; Minecraft 26.1 and newer require the server
   to run on Java 25, which is Mojang's requirement rather than this plugin's.
2. Drop `SpiralGenesis-x.y.z.jar` into your server's `plugins/` folder.
3. Start the server. `plugins/SpiralGenesis/config.yml` is generated on first run.
4. Stand where you want the spiral to begin and run `/sgen setcenter`.
5. That's it. The next player to join gets plot #1.

Optional: install **Floodgate** if you run Bedrock cross-play. If your Java players log in
with a password, any login plugin works (AuthMe, nLogin, LibreLogin and the rest) with no
configuration: SpiralGenesis waits for the player to be released from limbo before placing
them. On an online-mode server, or a network authenticating at the proxy, set
`allocation.trigger: ON_JOIN` to place players the moment they connect.

> **Pregenerate your world first.** Allocation generates chunks as it searches. On a fresh
> world that is fine, but a pregenerated area makes first joins near-instant. See
> [sizing guidance](docs/ADMIN_GUIDE.md#8-sizing-and-world-generation) for how big
> to make it.

---

## What it does

* **One plot per player, never reused.** Plots are handed out along a square spiral, so
  players spread outward evenly instead of piling into the same valley.
* **Skips bad ground.** Oceans, lava, ice, deep water, ravines, sinkholes, cliff edges and
  jagged peaks are all rejected. The plugin searches *inside* a plot for a good landing
  spot before giving up on it.
* **Respawns at home.** Die without a bed or anchor and you return to your own plot, not to
  world spawn.
* **Cross-play aware.** Bedrock players (Geyser/Floodgate) are placed the moment they join.
  Java players behind any login plugin are held until they authenticate, so nobody burns a
  plot before proving who they are. This works without naming a login plugin, so it covers
  AuthMe, nLogin, LibreLogin and anything else.
* **Doesn't stall the server.** Chunks load through Paper's async API and each candidate
  spot is checked on its own tick. Saves are batched off the main thread.
* **Runs on Folia.** The same jar uses region, entity and async schedulers throughout.

### What it does *not* do

SpiralGenesis gives each player **space**, not **ownership**. It does not protect blocks,
create claims, or stop another player from walking over and breaking things. If you want
land protection, pair it with a claim plugin such as GriefPrevention or Lands — the spiral
layout gives those plugins clean, non-overlapping regions to work with.

---

## Configuration

`plugins/SpiralGenesis/config.yml`, with the defaults that matter most:

```yaml
origin:
  world: "world"
  x: 0
  z: 0

# Size of each player's territory in blocks (500 = 500x500).
cell-size: 500

placement:
  # FIRST_SAFE (default) - centre of the plot if it works, otherwise the first
  #                        good spot found spiralling outward. Fastest.
  # FLATTEST             - check every candidate, pick the most level ground.
  # HIGHEST              - check every candidate, pick the highest below height-ceiling.
  strategy: FIRST_SAFE
  stride: 16          # blocks between candidate spots (16 = one chunk)
  max-candidates: 12  # spots tried inside a plot before moving to the next one
  height-ceiling: 110 # HIGHEST only: ignore anything above this Y

safety:
  min-surface-y: 63     # reject spots below this height
  max-scan-attempts: 8  # plots to try before settling for the best seen so far
  max-pit-depth: 8      # reject spots this far below the surrounding land (ravines)
  max-roughness: 12     # reject spots whose surroundings are this uneven (cliffs)
```

Every value is range-checked on load, so a typo degrades to a sane value instead of
breaking joins. The full annotated file ships inside the jar; see the
[admin guide](docs/ADMIN_GUIDE.md) for what each rule actually rejects and how to tune it.

Player assignments and the current spiral position live in `data.yml` — leave that one
alone unless you are deliberately resetting the grid.

---

## Commands

All commands require the `spiralgenesis.admin` permission (default: operators).
`/spiralgenesis` works as an alias for `/sgen`.

| Command | What it does |
| :--- | :--- |
| `/sgen setcenter` | Set the centre of the spiral to where you're standing. |
| `/sgen setcenter <x> <z>` | Set the centre to explicit coordinates. |
| `/sgen setspawn <player>` | Move a player's spawn to your position. |
| `/sgen setspawn <player> <x> <y> <z>` | Move a player's spawn to exact coordinates. |
| `/sgen reassign <player>` | Give a player a fresh plot further along the spiral. |
| `/sgen tp <player>` | Teleport yourself to a player's plot. |
| `/sgen info <player>` | Show a player's plot number, grid cell and coordinates. |
| `/sgen simulate <count>` | Dry-run 1–500 allocations against your real terrain and report what it found. Generates chunks; does not move the live spiral forward. |
| `/sgen reload` | Reload `config.yml`. |

`setspawn` and `reassign` act on the live player, so the target has to be online. `tp` and
`info` read from storage and work for offline players too.

`/sgen simulate 50` is the fastest way to check your settings against your world before
players arrive — it reports how many plots were skipped, how often the search fell back,
and exactly which safety rule did the rejecting.

---

## How the spiral works

Plot 1 lands on the centre. Each following plot moves one cell along an expanding clockwise
square spiral, so plot *n* is always `cell-size` blocks from its neighbours:

```
        (-1,-1) ───> (0,-1) ───> (1,-1) ───> (2,-1)
           ▲                                    │
           │    (0,0) [P1] ───> (1,0) [P2]      │
           │                      │             ▼
        (-1,0)                    ▼           (2,0)
           ▲                   (1,1) [P3]       │
           │                      │             ▼
        (-1,1) <──── (0,1) <──────┘           (2,1)
         [P5]         [P4]                      │
                                                ▼
```

A cell's world position is simply `x = origin.x + u * cell-size` and
`z = origin.z + v * cell-size`. The full derivation, terrain rules and lifecycle hooks are
in the [admin guide](docs/ADMIN_GUIDE.md).

---

## Compatibility

| | Supported |
| :--- | :--- |
| Paper, Purpur and other Paper forks | ✅ 1.20.x, 1.21.x, 26.x |
| Folia | ✅ 1.20.x, 1.21.x, 26.x |
| Spigot / CraftBukkit | ❌ — allocation needs Paper's async chunk and teleport APIs |
| Fabric / NeoForge | ❌ — mod loaders, not plugin platforms |
| Velocity / BungeeCord | ❌ — proxies have no world to allocate in; install on the backend servers |

Built against the 1.20.4 API, which newer servers still accept. CI boots the plugin on
Paper and Folia at both ends of the supported range - 1.20.4 and 26.2 - and runs allocation
against real generated terrain on each, so the range is checked on every change rather than
assumed.

Minecraft 26.1 and newer refuse to start on anything below **Java 25**. That is a server
requirement from Mojang, not this plugin: the jar is Java 21 bytecode, which a Java 25
runtime runs unchanged.

---

## Troubleshooting

**Players still spawn at world spawn.** They are probably not new. Only players without a
recorded plot are allocated one; everyone else keeps the spawn they already have until you
`/sgen reassign` them (they must be online for that).

**Players land in the wrong world.** If `origin.world` doesn't match a loaded world, the
plugin logs a warning and falls back to the server's first world rather than refusing to
allocate — so allocation looks healthy while everyone is placed somewhere unintended. Check
the startup log for `Could not find target world`.

**First join takes a few seconds.** The plugin is generating chunks to look for safe
ground. Pregenerate the area (see the sizing table in the admin guide) and it disappears.

**Players get placed before logging in.** Check `allocation.trigger` is `FIRST_ACTION`, not
`ON_JOIN`. The startup log states which trigger is active and which login plugins it saw.

**Players never get placed.** Something is suppressing every action they take, so the gate
never opens. The console warns when the `action-timeout-seconds` backstop fires; if you see
that repeatedly, an anti-cheat or region plugin is the usual cause. If it is your login
plugin, point its on-login command hook at `sgen allocate %p` and the gate is bypassed
entirely. See the [admin guide](docs/ADMIN_GUIDE.md#5-join-and-respawn-lifecycle).

**Someone landed somewhere terrible.** Run `/sgen simulate 50` — the per-rule rejection
breakdown usually shows the rule that needs loosening, most often `min-surface-y` or
`max-roughness` on mountainous or ocean-heavy worlds.

---

## Docs, source and contributing

* [Admin & architecture guide](docs/ADMIN_GUIDE.md) — terrain rules, lifecycle, storage
  format, sizing, testing matrix.
* [Changelog](CHANGELOG.md) · [Security policy](https://github.com/Ninja6-MC/SpiralGenesis/security/policy)
* Bugs and feature requests: [GitHub Issues](https://github.com/Ninja6-MC/SpiralGenesis/issues)
* Patches welcome — [CONTRIBUTING.md](CONTRIBUTING.md) covers setup and PR rules, and
  [RELEASE_PROCESS.md](RELEASE_PROCESS.md) covers how releases are cut.

Building it yourself:

```bash
./gradlew build
```

The jar lands in `build/libs/`.

---

## License & credits

[GNU General Public License v3.0](LICENSE).

Inspired by [Block4Block / DynamicSpawnPlugin](https://github.com/Block4Block/DynamicSpawnPlugin),
which pioneered spiral-pattern spawn distribution for Paper servers. SpiralGenesis is an
independent implementation.

---

<p align="center">
  <sub>A <a href="https://github.com/Ninja6-MC">Ninja6</a> project.</sub>
</p>
