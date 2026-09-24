<!-- Generated from README.md by scripts/modrinth-description.py. Do not edit.
     The release workflow syncs everything below this comment to the Hangar resource page.
     Paste the same text into the Modrinth description editor. -->

**Every player starts somewhere of their own.** Instead of dropping everyone at one crowded
world spawn, SpiralGenesis gives each new player their own plot of land - 500x500 blocks by
default - laid out in an expanding spiral around a centre point you choose.

The plugin finds them somewhere sensible to stand: not in an ocean, not in a lava pool, not
at the bottom of a ravine or on the edge of a cliff. It happens on first join, off the main
thread, and the player keeps that spot as their respawn point for good.

Installing it on a server people already play on moves none of them: a player who was there
before the plugin keeps their position, bed and respawn anchor, and gets a plot only when an
operator gives them one with `/sgen reassign`.

---

## Quick start

1. **Requirements:** Paper 1.20 or newer, a Paper fork such as Purpur, or Folia.
   Java 21 for Minecraft 1.20 and 1.21; Minecraft 26.1 and newer require the server
   to run on Java 25, which is Mojang's requirement rather than this plugin's.
2. Drop `SpiralGenesis-x.y.z.jar` into your server's `plugins/` folder.
3. Start the server. `plugins/SpiralGenesis/config.yml` is generated on first run.
4. Stand where you want the spiral to begin and run `/sgen setcenter`.
5. That's it. The next player to join gets plot #0,0, centred on that spot.

Installing on a server people already play on? Read
[Installing on an existing server](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/docs/ADMIN_GUIDE.md#10-installing-on-an-existing-server)
before the first start: a new player who joins before step 4 is placed around `(0, 0)`,
and the origin should be chosen away from existing builds and claims.

Optional: install **Floodgate** if you run Bedrock cross-play. If your Java players log in
with a password, any login plugin works (AuthMe, nLogin, LibreLogin and the rest) with no
configuration: SpiralGenesis waits for the player to be released from limbo before placing
them. On an online-mode server, or a network authenticating at the proxy, set
`allocation.trigger: ON_JOIN` to place players the moment they connect.

> **Pregenerate your world first.** Allocation generates chunks as it searches. On a fresh
> world that is fine, but a pregenerated area makes first joins near-instant. See
> [sizing guidance](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/docs/ADMIN_GUIDE.md#9-sizing-and-world-generation) for how big
> to make it.

---

## What it does

* **One plot per player, never reused.** Plots are handed out along a square spiral, so
  players spread outward evenly instead of piling into the same valley.
* **Skips bad ground.** Oceans, lava, ice, deep water, ravines, sinkholes, cliff edges and
  jagged peaks are all rejected. The plugin searches *inside* a plot for a good landing
  spot before giving up on it.
* **Respawns at home.** Die and you return to your own plot, not to world spawn - unless
  a bed, a respawn anchor, a `/spawnpoint` set elsewhere or another plugin's respawn
  location says otherwise.
* **Cross-play aware.** Bedrock players (Geyser/Floodgate) are placed the moment they join.
  Java players behind any login plugin are held until they authenticate, so nobody burns a
  plot before proving who they are. This works without naming a login plugin, so it covers
  AuthMe, nLogin, LibreLogin and anything else.
* **Doesn't stall the server.** Chunks load through Paper's async API and each candidate
  spot is checked on its own tick. Saves are batched off the main thread.
* **Runs on Folia.** The same jar uses region, entity and async schedulers throughout.
* **Can protect the spawn point.** Optional, off by default, and needs GriefPrevention:
  switch it on and every player gets a small claim around their spawn that only they can
  build in, so their bed and first chest are covered the moment they arrive. It never costs
  anyone their plot when it cannot be created. See the
  [admin guide](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/docs/ADMIN_GUIDE.md#6-spawn-protection) for the settings and the two
  GriefPrevention numbers worth checking first.

## What it does *not* do

SpiralGenesis gives each player **space**, and at most a few blocks of **ownership**.
With `protection:` switched on it claims a small square around each player's spawn point
through GriefPrevention, and that is the whole of it - the rest of the 500x500 plot is
unclaimed, and players are expected to claim it themselves in the normal way. Left off,
which is the default, it protects nothing at all and nothing stops another player walking
over and breaking things. Either way, pair it with a claim plugin such as GriefPrevention
or Lands for anything beyond the spawn square - the spiral layout gives those plugins
clean, non-overlapping regions to work with.

---

## Configuration

`plugins/SpiralGenesis/config.yml`, with the defaults that matter most:

```yaml
origin:
  world: "world"
  x: 0
  z: 0

cell-size: 500        # size of each player's territory in blocks (500 = 500x500)

placement:
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

`placement.strategy` decides which spot inside a plot a player lands on:

| Value | What it does |
| :--- | :--- |
| `FIRST_SAFE` (default) | Centre of the plot if it works, otherwise the first good spot found spiralling outward. Fastest. |
| `FLATTEST` | Check every candidate, pick the most level ground. |
| `HIGHEST` | Check every candidate, pick the highest one below `height-ceiling`. |

Every value is range-checked on load, so a typo degrades to a sane value instead of
breaking joins. The full annotated file ships inside the jar; see the
[admin guide](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/docs/ADMIN_GUIDE.md) for what each rule actually rejects and how to tune it.

Player assignments and the current spiral position live in `data.yml` - leave that one
alone unless you are deliberately resetting the grid.

---

## Commands

All commands require the `spiralgenesis.admin` permission (default: operators).
`/spiralgenesis` works as an alias for `/sgen`.

| Command | What it does |
| :--- | :--- |
| `/sgen setcenter` | Set the centre's X and Z to where you're standing. The world is always `origin.world`. |
| `/sgen setcenter <x> <z>` | Set the centre to explicit coordinates. |
| `/sgen setspawn <player>` | Move a player's spawn to your position. |
| `/sgen setspawn <player> <x> <y> <z>` | Move a player's spawn to exact coordinates. |
| `/sgen reassign <player>` | Give a player a fresh plot further along the spiral. |
| `/sgen reassign <player> release` | The same, and release the claim around their old spawn. |
| `/sgen protect` | Claim the spawn square for players allocated before spawn protection was switched on. Safe to run twice. |
| `/sgen release-all confirm` | Release the spawn claim around every player's current plot, for uninstalling. Refused under `PLAYER_CLAIM`. See [Uninstalling](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/docs/ADMIN_GUIDE.md#11-uninstalling). |
| `/sgen tp <player>` | Teleport yourself to a player's plot. Warns first if the plot is no longer safe, then goes anyway. |
| `/sgen info <player>` | Show a player's plot number, grid cell and coordinates. |
| `/sgen simulate <count>` | Dry-run 1-500 allocations against your real terrain and report what it found. Generates chunks; does not move the live spiral forward. |
| `/sgen reload` | Reload `config.yml`. |

`setspawn` and `reassign` act on the live player, so the target has to be online. `tp` and
`info` read from storage and work for offline players too.

`/sgen simulate 50` is the fastest way to check your settings against your world before
players arrive - it reports how many plots were skipped, how often the search fell back,
and exactly which safety rule did the rejecting.

---

## How the spiral works

The first plot, #0,0, lands on the centre. Each following plot moves one cell along an
expanding clockwise square spiral, so plot *n* is always `cell-size` blocks from its
neighbours:

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
in the [admin guide](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/docs/ADMIN_GUIDE.md).

---

## Compatibility

| | Supported |
| :--- | :--- |
| Paper, Purpur and other Paper forks | Yes 1.20.x, 1.21.x, 26.x |
| Folia | Yes 1.20.x, 1.21.x, 26.x |
| Spigot / CraftBukkit | No - allocation needs Paper's async chunk and teleport APIs |
| Fabric / NeoForge | No - mod loaders, not plugin platforms |
| Velocity / BungeeCord | No - proxies have no world to allocate in; install on the backend servers |

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

**Nobody gets a plot and the log names `origin.world`.** The plugin binds only to the
world `origin.world` names and never substitutes another. If that world is not loaded it
logs `Configured world '...' (origin.world) is not loaded` at SEVERE, lists the worlds
that are, and allocates nothing. New players are held where they joined and allocated as
soon as the world is bound: correct `origin.world` and run `/sgen reload`. A world that a
world manager loads after SpiralGenesis starts needs no reload; it is picked up the next
time a player without a plot passes the gate or a held player acts. Multiverse-Core is
always enabled first, so its worlds are already loaded when SpiralGenesis starts.

**First join takes a few seconds.** The plugin is generating chunks to look for safe
ground. Pregenerate the area (see the sizing table in the admin guide) and it disappears.

**Players get placed before logging in.** Check `allocation.trigger` is `FIRST_ACTION`, not
`ON_JOIN`. The startup log states which trigger is active and which login plugins it saw.

**Players never get placed.** Something is suppressing every action they take, so the gate
never opens. The console warns when the `action-timeout-seconds` backstop fires; if you see
that repeatedly, an anti-cheat or region plugin is the usual cause. If it is your login
plugin, point its on-login command hook at `sgen allocate %p` and the gate is bypassed
entirely. See the [admin guide](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/docs/ADMIN_GUIDE.md#5-join-and-respawn-lifecycle).

**Someone landed somewhere terrible.** Run `/sgen simulate 50` - the per-rule rejection
breakdown usually shows the rule that needs loosening, most often `min-surface-y` or
`max-roughness` on mountainous or ocean-heavy worlds.

---

## Docs, source and contributing

* [Admin & architecture guide](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/docs/ADMIN_GUIDE.md) - terrain rules, lifecycle, storage
  format, sizing, testing matrix.
* [Changelog](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/CHANGELOG.md) - [Security policy](https://github.com/Ninja6-MC/SpiralGenesis/security/policy)
* Bugs and feature requests: [GitHub Issues](https://github.com/Ninja6-MC/SpiralGenesis/issues)
* Patches welcome - [CONTRIBUTING.md](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/CONTRIBUTING.md) covers setup and PR rules, and
  [RELEASE_PROCESS.md](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/RELEASE_PROCESS.md) covers how releases are cut.

Building it yourself:

```bash
./gradlew build
```

The jar lands in `build/libs/`.

---

## License & credits

[GNU General Public License v3.0](https://github.com/Ninja6-MC/SpiralGenesis/blob/main/LICENSE).

Inspired by [Block4Block / DynamicSpawnPlugin](https://github.com/Block4Block/DynamicSpawnPlugin),
which pioneered spiral-pattern spawn distribution for Paper servers. SpiralGenesis is an
independent implementation.
