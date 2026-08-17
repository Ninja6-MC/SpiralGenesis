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

### Java behind AuthMe-Reloaded

1. Java client connects; AuthMe holds the player in limbo at its own lobby.
2. Player runs `/register <password> <password>` or `/login <password>`.
3. AuthMe's `RegisterEvent` / `LoginEvent` fires. If the UUID is absent from `data.yml`,
   allocation runs, coordinates are saved, and the player is teleported.
4. Returning players have their respawn point restored.

Gating on the AuthMe event rather than on join is deliberate: it stops unauthenticated
sessions from loading chunks.

### Java without AuthMe

Allocation runs on `PlayerJoinEvent`, exactly as it does for Bedrock.

### Respawn

The plugin sets the player's respawn location at allocation time and handles
`PlayerRespawnEvent` at `HIGHEST` priority. A bed or respawn anchor always takes priority;
the genesis plot is the fallback, replacing world spawn.

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
| AuthMe gating | Connect a Java client with AuthMe installed. | No allocation until `/register` or `/login`. |
| Death and respawn | `/kill` with no bed set. | Respawn at your own plot, not world spawn. |
| Admin override | `/sgen setspawn <player> 1200 70 -400`. | Spawn and respawn point update immediately. |

---

## 10. Where the code lives

All implementation is under `src/main/java/com/ninja6/spiralgenesis/`. The source is the
authoritative reference — this guide describes behaviour, not line numbers.

| Component | File | Responsibility | Section |
| :--- | :--- | :--- | :--- |
| Spiral math | `math/SpiralMath.java` | Index → grid and grid → world transforms | §2 |
| Allocation | `manager/SpawnManager.java` | Cell and candidate search, async probing | §1, §3 |
| Rejection reasons | `manager/RejectionReason.java` | The rule names reported by simulation | §3 |
| Simulation | `manager/SpawnSimulator.java` | `/sgen simulate` dry runs | §7 |
| Join lifecycle | `listeners/PlayerSpawnListener.java` | First-join and respawn hooks | §5 |
| Java auth gating | `listeners/AuthMeHookListener.java` | Defers allocation until AuthMe login | §5 |
| Bedrock detection | `hook/FloodgateHook.java` | Identifies Floodgate players | §5 |
| AuthMe bridge | `hook/AuthMeHook.java` | Soft-dependency wrapper around the AuthMe API | §5 |
| Persistence | `storage/YamlDataStorage.java` | Per-player registry in `data.yml` | §6 |
| Commands | `commands/SpiralCommand.java` | `/sgen` command tree and permissions | — |
| Configuration | `config/PluginConfig.java` | `config.yml` parsing, clamping, validation | §3, §4 |

Unit tests for the maths and configuration logic live under
`src/test/java/com/ninja6/spiralgenesis/`.
