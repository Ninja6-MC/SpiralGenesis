# SpiralGenesis — Admin & Architecture Guide

This is the reference for server owners who want to know exactly how SpiralGenesis picks a
spot, what it stores, and how to size a world for it. For installation and day-to-day use,
start with the [README](../README.md).

**Contents**

1. [How allocation works](#1-how-allocation-works)
2. [The spiral, precisely](#2-the-spiral-precisely)
3. [Terrain rules](#3-terrain-rules)
4. [Placement strategies](#4-placement-strategies)
5. [Join and respawn lifecycle](#5-join-and-respawn-lifecycle)
6. [Stored data](#6-stored-data)
7. [Tuning with `/sgen simulate`](#7-tuning-with-sgen-simulate)
8. [Sizing and world generation](#8-sizing-and-world-generation)
9. [Testing checklist](#9-testing-checklist)
10. [Where the code lives](#10-where-the-code-lives)

---

## 1. How allocation works

A player joins for the first time. SpiralGenesis:

1. Claims the next **spiral index** — a global counter, shared across all players, that
   maps to a grid cell. Claiming is atomic, so two players joining at the same moment can
   never land in the same cell.
2. Loads that cell's chunks asynchronously and probes **candidate points inside the cell**,
   spiralling outward from its centre.
3. Accepts the first (or best, depending on strategy) candidate that passes every terrain
   rule in §3.
4. If every candidate in the cell fails, claims another index and starts again — up to
   `safety.max-scan-attempts` cells.
5. If all attempts are exhausted, settles on the best candidate seen anywhere during the
   scan rather than dropping the player at world spawn.

Two nested searches, in other words: an outer one over cells, an inner one within a cell.
The inner search is what keeps one bad block from throwing away an entire 500×500 plot.

Probes are spread one per tick, and chunks load through Paper's async API, so allocation
never blocks the main thread. Worst case is `max-scan-attempts × max-candidates` probes —
96 at the defaults, and far fewer in practice. That bounds the number of ticks the search
occupies, not the wall-clock time: each probe waits on its chunk first, and on ungenerated
terrain that wait dominates. Pregenerating is what turns the bound into a fast join.

---

## 2. The spiral, precisely

Given an origin `(X₀, Z₀)` and a cell size `N`, the world coordinates at the centre of grid
cell `(u, v)` are:

```
X = X₀ + u · N
Z = Z₀ + v · N
```

Indices walk clockwise with segment lengths growing every two turns
(1, 1, 2, 2, 3, 3, 4, 4, …), moving East → South → West → North:

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

| Index | Cell (u, v) | Direction | World offset from origin (N = 500) |
| :--- | :--- | :--- | :--- |
| 0 | (0, 0) | origin | (0, 0) |
| 1 | (1, 0) | East | (+500, 0) |
| 2 | (1, 1) | South | (+500, +500) |
| 3 | (0, 1) | West | (0, +500) |
| 4 | (-1, 1) | West | (-500, +500) |
| 5 | (-1, 0) | North | (-500, 0) |
| 6 | (-1, -1) | North | (-500, -500) |
| 7 | (0, -1) | East | (0, -500) |
| 8 | (1, -1) | East | (+500, -500) |
| 9 | (2, -1) | East | (+1000, -500) |
| 10 | (2, 0) | South | (+1000, 0) |

Indices are consumed, not recycled. A cell that fails every candidate burns its index
permanently — which is why the in-cell search matters on ocean-heavy worlds.

---

## 3. Terrain rules

Each candidate point is checked against the surface height from the
`MOTION_BLOCKING_NO_LEAVES` heightmap plus eight terrain samples spread across the
candidate's own chunk. A candidate is rejected for any of these reasons, which are the same
names `/sgen simulate` reports:

| Reason | Rule | Config |
| :--- | :--- | :--- |
| `LOW_SURFACE` | Surface sits below the minimum height. | `safety.min-surface-y` (63) |
| `OCEAN` | Surface biome is any ocean variant. | — |
| `HAZARD` | Block underfoot is water, lava, ice, packed/blue ice, seagrass, kelp or powder snow. | — |
| `NO_HEADROOM` | Not enough clear space for a player to stand. | — |
| `PIT` | Sits more than the allowed depth below the median of surrounding terrain — ravines, canyons, sinkholes, craters. | `safety.max-pit-depth` (8) |
| `ROUGH` | Surroundings vary more sharply than allowed — cliff edges, spikes, jagged ground. | `safety.max-roughness` (12) |
| `NEARBY_HAZARD` | Lava or powder snow within the sampled surroundings. | — |

Two details worth knowing:

**Why `PIT` and `ROUGH` exist.** The heightmap reports a ravine floor as "the surface",
because nothing is above it. Without a shape check, a player drops 40 blocks into a canyon
and the plugin considers that a success. Comparing the candidate against the terrain around
it is what catches those cases.

**Water nearby is fine.** Only lava and powder snow disqualify a candidate by proximity.
Water or ice a few blocks away is a lake shore or river bank — a good spawn, not a bad one.

Terrain samples are taken from fixed positions inside the candidate's own chunk rather than
in a ring around the point. A ring would cross into neighbouring chunks whenever a candidate
sits near a chunk edge, which is common; reading those would force chunk generation, and
skipping them would quietly disable the checks.

---

## 4. Placement strategies

`placement.strategy` decides which candidate inside a cell wins:

| Strategy | Behaviour | Cost |
| :--- | :--- | :--- |
| `FIRST_SAFE` *(default)* | Take the cell centre if it passes, otherwise the first viable point spiralling outward. Stops as soon as it finds one. | Often a single chunk. |
| `FLATTEST` | Probe every candidate, take the one with the least height variation. | Always spends the full `max-candidates` budget. |
| `HIGHEST` | Probe every candidate, take the highest at or below `placement.height-ceiling`. | Always spends the full `max-candidates` budget. |

`height-ceiling` (default 110) keeps `HIGHEST` from stranding players on jagged mountain
peaks while still preferring hills over lowlands.

`placement.stride` (default 16) is the distance between candidates; 16 is one chunk and the
minimum, since anything smaller puts multiple candidates in the same chunk.
`placement.max-candidates` (default 12) is reduced automatically if `cell-size` and `stride`
cannot fit that many without the search leaving the cell.

---

## 5. Join and respawn lifecycle

### Bedrock (Geyser + Floodgate)

1. Bedrock client connects through Geyser; Floodgate authenticates the Xbox Live gamertag
   automatically (UUIDs of the form `00000000-0000-0000-0009-…`).
2. `PlayerJoinEvent` fires. If the UUID is absent from `data.yml`, allocation runs and the
   player is teleported to the result.

### Java (`allocation.trigger: FIRST_ACTION`, the default)

1. Java client connects. `PlayerJoinEvent` fires and the player is **held**, not allocated.
2. If a login plugin is installed it holds the player in limbo, suppressing their movement
   and interactions. SpiralGenesis observes only actions that arrive intact, so it waits.
3. The player authenticates. Their actions stop being suppressed.
4. Their first uncancelled movement, interaction or item drop allocates the plot, saves the
   coordinates, and teleports them.
5. Returning players have their respawn point restored.

Where no login plugin is installed, nothing cancels anything and step 4 happens on the
player's first step, effectively immediately.

This is deliberately **not** tied to a particular login plugin. There is no shared
authentication API in this ecosystem, but every login plugin enforces limbo by suppressing
what an unauthenticated player does. Gating on that covers AuthMe, nLogin, LibreLogin,
OpeNLogin and anything else, including plugins that do not exist yet.

Suppression takes more than one form and the gate reads all of them. This was verified by
reading how each plugin actually implements limbo, not assumed:

| Plugin | Movement | Interaction / drops |
| :--- | :--- | :--- |
| AuthMe 5.6.0 | `setTo(from)` at `HIGHEST`, never cancelled | cancelled at `LOWEST` |
| OpeNLogin | `setTo(from)` at `HIGH`, descent permitted | cancelled at `LOWEST` / `HIGH` |
| LibreLogin (Paper) | cancelled at `LOWEST` | cancelled at `LOWEST` |

Two of the three never cancel a move at all; they rewrite its destination instead, which
avoids the "too many packets" disconnect that repeated teleporting causes. An event pinned
that way arrives uncancelled, so the gate checks that a move actually changed the block the
player occupies rather than trusting that it was delivered. Descending movement never counts,
because OpeNLogin declines to pin a falling player at all and applies no block-column test
when doing so, so a held player drifting sideways as they fall would otherwise open the
gate. Once free, walking produces level moves continuously, so this costs nothing.

### When the gate cannot read your login plugin

If players are never placed, or are only placed once `action-timeout-seconds` fires, the
gate cannot see your login plugin's limbo. A plugin that suppresses actions below the Bukkit
event layer produces nothing for the gate to observe.

Every login plugin can run console commands on a successful login. Point one at
SpiralGenesis and the guesswork disappears:

```
# AuthMe: plugins/AuthMe/commands.yml
onLogin:
  placePlayer:
    command: 'sgen allocate %p'
    executor: CONSOLE
```

`/sgen allocate <player>` places a player who has no plot yet and does nothing for anyone
who already has one, so it is safe to run on every login. It is not `/sgen reassign`, which
discards an existing plot and consumes a fresh spiral index.

Consult your own plugin's documentation for the equivalent section; the placeholder for the
player's name differs between them.

### Limitations

Three limitations follow, all minor. Clicking air cannot open the gate, because Bukkit
reports every air interaction as cancelled regardless of who is listening; a block
interaction works. If AuthMe is configured with `AllowUnauthedMovement` enabled, an
unauthenticated player really can walk, so movement stops being proof and the gate may open
early. And `nLogin` and `JPremium` are closed source, so their limbo implementations have
not been verified the way the three above were; if you run either, watch the startup log and
confirm players are not placed before they log in.

If AuthMe specifically is installed, its `RegisterEvent` / `LoginEvent` allocates the player
the instant they authenticate rather than on their next action. This is a convenience, not a
requirement: allocation is idempotent, so whichever path fires first wins, and if the AuthMe
API cannot be bound the action gate still covers it. A warning is logged in that case.

`allocation.action-timeout-seconds` (default 300) is the backstop. A player connected that
long without producing a single uncancelled action is allocated anyway, with a warning. This
prevents a login plugin whose limbo cannot be read from leaving players permanently
unallocated. Set it to `0` to wait indefinitely instead.

### Java (`allocation.trigger: ON_JOIN`)

Allocation runs on `PlayerJoinEvent`, exactly as it does for Bedrock.

Correct on an online-mode server, and on a network that authenticates at the proxy or on a
separate backend server, because a player reaching this server is already authenticated.
**Do not use it on a server running a login plugin locally**: it allocates before the player
has proven anything, which permanently burns a spiral index per connection and races the
login plugin's own position restore. SpiralGenesis logs a warning at startup if it sees a
known login plugin while this trigger is set.

### Respawn

The plugin sets the player's respawn location at allocation time and handles
`PlayerRespawnEvent` at `HIGHEST` priority. A bed or respawn anchor always takes priority;
the genesis plot is the fallback, replacing world spawn.

The plot is re-checked on the way through, because it was validated once - when it was
allocated - and cells are 500 blocks wide with no claim or protection system. Anyone can
flood a spawn, pour lava on it, or dig the ground out from under it, and without a re-check
the owner respawns into it, dies, and respawns into it again.

The check starts **on death**, not on respawn. That buys the seconds a player spends on the
death screen, so a plot that can be repaired in time is usually already fixed before they
click respawn, and it is the only half that works on Folia (see below). It loads the plot's
chunk, re-checks whether the player still fits, whether the ground is still there, and
whether anything lethal is at their feet, head or underfoot, and repairs the plot if not.

`PlayerRespawnEvent` then acts as the backstop for a repair that has not finished yet. That
event is synchronous and cannot wait for a chunk, so it judges only what is already
resident:

* **Plot chunk loaded and still safe.** Respawn at the plot, as before. Costs nothing extra.
* **Plot chunk loaded and unsafe.** Respawn at world spawn as a holding position, with the
  repair already running.
* **Plot chunk not loaded.** Respawn at the plot as before. Sending every unverifiable
  respawn to world spawn would penalise the ordinary case - a plot nobody is standing on -
  to catch the rare one.

> **Folia.** Folia's respawn computes the position itself and never fires
> `PlayerRespawnEvent`; the method that does fire it throws `UnsupportedOperationException`
> there. On Folia the whole of the above rests on the death-time repair, which updates the
> player's respawn point before the respawn reads it. Both platforms are covered by a CI
> entry that kills a real client in-world and asserts where it comes back.

**Repair never moves a player out of their own cell.** It re-searches the cell they already
hold, using the same terrain rules and the same `placement.max-candidates` budget as
allocation, and their spiral index does not advance. That is deliberate: relocating them
would make griefing somebody's spawn a way to evict them from their land. On success the new
point is written to `data.yml` under the same index, their respawn point is updated, and they
are teleported to it - logged at `INFO`.

If every sampled candidate in the cell fails, the player is sent to world spawn with a
warning and **their plot assignment is left unchanged**. `max-candidates` defaults to 12 of
the 961 points that fit a 500-block cell, so "no candidate passed" is not evidence the plot
is unusable, and discarding the assignment over it would lose their land for good. Raise
`placement.max-candidates` if you see this warning repeatedly.

### Re-asserting a teleport that never happened

Allocation records the plot and then teleports the player to it. A login plugin holding an
unauthenticated player can refuse that teleport - LibreLogin cancels every
`PlayerTeleportEvent` while its limbo has them - which used to leave `data.yml` claiming a
location the player had never been to, with nothing to reconcile it.

The teleport is now retried once, on that player's next uncancelled action: the same signal
the allocation gate uses, and by then whatever was refusing teleports has let go. Both the
original failure and the retry are logged.

This is session-scoped. A player who disconnects before producing any action is not retried
on their next login, because nothing recorded distinguishes them from a player who reached
their plot and walked away. `/sgen tp` and `/sgen setspawn` remain the manual remedies.

### Visiting a plot with `/sgen tp`

`/sgen tp <player>` re-checks the plot before it sends you, using the same live-block check
the respawn path uses, and then **teleports you either way**. A plot that fails the check
gets you a warning first:

```
That plot is no longer safe to stand on. Going anyway - watch yourself.
```

It warns rather than refuses because seeing the damage is the reason to run it. This is the
command the section above and the refused-teleport warning both name as the manual remedy,
so a version that declined to go where it was pointed would be useless for the case it
exists to serve. It does not relocate you either: you asked for one specific point, not a
safe point near it.

The check loads the plot's chunk, so there is a short pause before you move; the plot is
almost never resident when you run this, since nobody is standing on it. If the check itself
fails - the chunk cannot be loaded, say - you are told so, sent anyway, and the cause is
logged.

The teleport itself can still be refused, by the same kind of plugin the section above
describes. `/sgen tp` reports that rather than claiming to have moved you, so running it as
the manual remedy tells you whether the remedy worked.

Nothing here repairs the plot. `/sgen tp` is for looking; `/sgen setspawn` and
`/sgen reassign` are for fixing.

---

## 6. Stored data

`plugins/SpiralGenesis/config.yml` holds settings you edit. `plugins/SpiralGenesis/data.yml`
holds runtime state you generally should not — including `current-spiral-index`, the live
spiral position.

```yaml
# data.yml
current-spiral-index: 12

players:
  # Bedrock player (Floodgate)
  00000000-0000-0000-0009-01f4c3a2b100:
    name: "*BedrockWarrior"
    client: "BEDROCK"
    assigned-index: 0
    grid-u: 0
    grid-v: 0
    x: 0.5
    y: 72.0
    z: 0.5
    world: "world"
    assigned-date: "2026-08-17T02:00:00Z"

  # Java player (AuthMe)
  a1b2c3d4-e5f6-7890-abcd-ef1234567890:
    name: "JavaCrafter"
    client: "JAVA"
    assigned-index: 1
    grid-u: 1
    grid-v: 0
    x: 500.5
    y: 68.0
    z: 0.5
    world: "world"
    assigned-date: "2026-08-17T02:05:00Z"
```

Writes are coalesced and flushed off the main thread. To reset a single player, use
`/sgen reassign <player>` rather than editing the file by hand.

---

## 7. Tuning with `/sgen simulate`

```
/sgen simulate 50
```

Runs 50 allocations against your live terrain (any count from 1 to 500) and reports
indices consumed per spawn,
fallback use, the range of surface heights chosen, and a breakdown of which rule rejected
what. It uses a throwaway counter, so the live spiral index never moves — but it *does*
generate chunks, so run it on a test world or somewhere you intend to pregenerate anyway.

Reading the output:

* **High index burn** — most cells are failing outright. Usually an ocean-heavy world;
  raise `max-candidates`, or move the origin onto a continent.
* **Mostly `ROUGH`** — `max-roughness` is too strict for mountainous terrain.
* **Mostly `LOW_SURFACE`** — `min-surface-y` is above much of your terrain.
* **Frequent fallbacks** — the scan is running out of attempts; raise `max-scan-attempts`,
  accepting the longer worst-case allocation time.

CI runs this same command against a generated world and fails the build on regressions in
index burn, fallback use, or spawns below `min-surface-y`.

---

## 8. Sizing and world generation

Allocation generates chunks while it searches, so pregenerate with a tool such as Chunky
before opening the doors.

| Expected players | Cell size | Minimum world radius | Chunky command |
| :--- | :--- | :--- | :--- |
| 20 | 500 × 500 | 2,000 blocks | `chunky radius 2000` |
| 50 | 500 × 500 | 3,000 blocks | `chunky radius 3000` |
| 100 | 500 × 500 | 4,500 blocks | `chunky radius 4500` |
| 50 | 1,000 × 1,000 | 6,000 blocks | `chunky radius 6000` |

Add headroom on worlds with a lot of ocean: skipped cells consume indices, so the spiral
reaches further out than the player count alone suggests. `/sgen simulate` will tell you how
much further.

Also check that your world border is larger than the radius you plan to fill.

---

## 9. Testing checklist

Worth running once on a staging server before going live:

| Check | How | Expected |
| :--- | :--- | :--- |
| Sequential allocation | Join with 4 test accounts in sequence. | Plots at (0,0), (N,0), (N,N), (0,N) offsets. |
| Ocean skipping | Set the origin so a cell centre lands in deep ocean. | Cell skipped, next index allocated on dry land. |
| Bedrock first join | Connect a Bedrock client via Geyser/Floodgate. | Allocated and teleported on join. |
| Action gating | Connect a Java client with any login plugin installed. | No allocation until `/register` or `/login` completes. |
| Gate backstop | Connect, then stand still past `action-timeout-seconds`. | Allocated anyway, with a warning in the console. |
| Death and respawn | `/kill` with no bed set. | Respawn at your own plot, not world spawn. |
| Griefed plot | Pour lava on a player's plot, then `/kill` them. | Moved to another point in the same cell; index unchanged in `data.yml`. |
| Admin override | `/sgen setspawn <player> 1200 70 -400`. | Spawn and respawn point update immediately. |
| Visiting a griefed plot | Pour lava on a plot, then `/sgen tp <player>`. | Warned that it is no longer safe, and teleported there anyway. |
| Manual release | `/sgen allocate <player>` for a held player, then again. | Placed on the first call, "already has a plot" on the second. |

---

## 10. Where the code lives

All implementation is under `src/main/java/com/ninja6/spiralgenesis/`. The source is the
authoritative reference — this guide describes behaviour, not line numbers.

| Component | File | Responsibility | Section |
| :--- | :--- | :--- | :--- |
| Spiral math | `math/SpiralMath.java` | Index → grid and grid → world transforms | §2 |
| Allocation | `manager/SpawnManager.java` | Cell and candidate search, async probing, revalidation | §1, §3, §5 |
| Rejection reasons | `manager/RejectionReason.java` | The rule names reported by simulation | §3 |
| Simulation | `manager/SpawnSimulator.java` | `/sgen simulate` dry runs | §7 |
| Join lifecycle | `listeners/PlayerSpawnListener.java` | First-join and respawn hooks | §5 |
| Action gating | `listeners/PlayerActionGateListener.java` | Holds allocation until an uncancelled action | §5 |
| AuthMe fast path | `listeners/AuthMeHookListener.java` | Optional: allocates on AuthMe login | §5 |
| Bedrock detection | `hook/FloodgateHook.java` | Identifies Floodgate players | §5 |
| AuthMe bridge | `hook/AuthMeHook.java` | Detects whether AuthMe is installed | §5 |
| Persistence | `storage/YamlDataStorage.java` | Per-player registry in `data.yml` | §6 |
| Commands | `commands/SpiralCommand.java` | `/sgen` command tree and permissions | — |
| Configuration | `config/PluginConfig.java` | `config.yml` parsing, clamping, validation | §3, §4 |

Unit tests for the maths and configuration logic live under
`src/test/java/com/ninja6/spiralgenesis/`.
