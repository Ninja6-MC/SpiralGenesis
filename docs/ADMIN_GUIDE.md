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
6. [Spawn protection](#6-spawn-protection)
7. [Stored data](#7-stored-data)
8. [Tuning with `/sgen simulate`](#8-tuning-with-sgen-simulate)
9. [Sizing and world generation](#9-sizing-and-world-generation)
10. [Testing checklist](#10-testing-checklist)
11. [Where the code lives](#11-where-the-code-lives)

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

## 6. Spawn protection

SpiralGenesis can claim a small square of ground around each player's spawn point, so
their bed, their first chest and the block they land on are covered the moment they
arrive. It is **off by default**, it needs
[GriefPrevention](https://github.com/TechFortress/GriefPrevention) installed, and it
changes nothing about allocation: a player gets the same plot, the same index and the same
teleport whether the claim succeeds, fails or is never attempted.

The claim is deliberately much smaller than the plot. See
[the claim is small on purpose](#the-claim-is-small-on-purpose) below, which is the part
players ask about.

### The `protection:` block

```yaml
protection:
  enabled: false
  provider: GRIEF_PREVENTION
  size: 9
  claim-as: ADMIN_CLAIM
```

| Key | Default | What it does |
| :--- | :--- | :--- |
| `enabled` | `false` | Whether any claim is created at all. Off is genuinely off: nothing is claimed, nothing is looked up, and the server behaves exactly as it did before this block existed. |
| `provider` | `GRIEF_PREVENTION` | Which plugin creates the claim. `GRIEF_PREVENTION` is the only implementation shipped. `NONE` claims nothing, the same as `enabled: false`. An unrecognised name falls back to `NONE`, not to the default, and is logged at warning. |
| `size` | `9` | The side of the claimed square in blocks, centred on the spawn point. `9` means four blocks out in every direction from the block the player lands on. Clamped to 3-255. |
| `claim-as` | `ADMIN_CLAIM` | Whether the claim is an administrative claim the player is trusted onto, or an ordinary claim the player owns and pays for. An unrecognised name falls back to `ADMIN_CLAIM`, logged at warning. |

All four are re-read by `/sgen reload`, which rebuilds the provider from scratch, so a
changed `size` or `claim-as` takes effect without a restart.

**`size` is always odd.** An even square has no centre block, so "the player stands in the
middle of their claim" cannot be honoured at that size without biasing the square a block
to one side. An even value is rounded **up** to the next odd number - `8` becomes `9` -
and the correction is logged at warning on every config load, naming both the configured
value and the value actually used:

```
protection.size is 8, which is even and so has no centre block for the spawn point
to sit on. Using 9 instead. Set an odd size to silence this.
```

The file still reads `8` afterwards, which is exactly why that warning repeats on every
load rather than being said once. A value outside 3-255 is clamped, and warned about the
same way. The clamp runs first and both ends of the range are odd, so a value that had to
be clamped never needs rounding as well: you get one line, not two.

### What the startup log tells you

One line at enable says which provider was chosen. If you read nothing else in this
section, read that line on your own server.

| Situation | Level | Line |
| :--- | :--- | :--- |
| Working | INFO | `Spawn protection: GriefPrevention, 9x9 ADMIN_CLAIM around each player's spawn.` |
| `enabled: false` | none | Silence is deliberate. This is the default install, and it has to read exactly as it did before the feature existed. |
| `provider: NONE` | INFO | `Spawn protection is enabled but its provider is NONE, so nothing will be claimed.` |
| GriefPrevention not installed | INFO | `Spawn protection is configured for GriefPrevention, which is not installed on this server. Nothing will be claimed.` |
| Folia | INFO | `Spawn protection is configured for GriefPrevention, which does not run on Folia. Nothing will be claimed, and everything else behaves exactly as it does without this feature.` |
| GriefPrevention present, its own startup failed | WARNING | `GriefPrevention is enabled but has not published its API, so no spawn claims will be created. This usually means its own startup failed - check the log above for its errors.` |
| `size` below GriefPrevention's minimum | WARNING | Names both numbers; see [the minimum claim size](#the-minimum-claim-size-the-one-that-catches-people) below. The "working" line above is then deliberately not printed. |

There is no per-player line for any of these. Every condition above is a server-wide
setting that would otherwise produce one identical line per joining player, which is how a
log gets trained out of being read.

### Without GriefPrevention, and on Folia

**Not installed.** Nothing is claimed and nothing else changes. The provider resolves as
absent at startup, every claim request answers "no provider" and stays silent, and
allocation, reassign, setspawn and revalidation all behave exactly as they do with
`enabled: false`. You can leave `protection.enabled: true` in the file on a server with no
claim plugin; it costs one INFO line at boot and nothing else.

**Folia.** GriefPrevention does not run on Folia at all - it declares no `folia-supported`
flag, so Folia refuses to load it, and no configuration on either side will make it
appear. SpiralGenesis detects the platform, picks the no-op provider, and says so once at
INFO rather than leaving a silence that reads like a bug. Nothing about
`folia-supported: true` in SpiralGenesis' own `plugin.yml` becomes dishonest by this: the
plugin still runs on Folia in full, and the one feature that cannot work there says so
instead of failing quietly.

**GriefPrevention installed but broken.** If GriefPrevention is enabled and has not
published its API - usually because its own startup failed, and its errors will be above
yours in the log - SpiralGenesis warns and falls back to claiming nothing. That case gets
its own line because a plugin visibly present, with protection visibly on, and no claims
appearing, is the hardest state in this feature to work out from the outside.

### `ADMIN_CLAIM` and `PLAYER_CLAIM`

This is the setting to decide before you turn the feature on, because the two values
behave differently in ways their names do not suggest.

| | `ADMIN_CLAIM` (default) | `PLAYER_CLAIM` |
| :--- | :--- | :--- |
| What GriefPrevention creates | An administrative claim, owned by the server, with the player granted `Build` trust on it | An ordinary claim the player owns outright |
| Claim blocks | None. Charged to nobody | The full area, charged to the player's balance |
| Works with a zero starting balance | Yes | No: every claim is refused until players are given blocks |
| `MinimumWidth` / `MinimumArea` | Exempt. GriefPrevention applies neither to admin claims | Enforced |
| `MaximumNumberOfClaimsPerPlayer` | Does not count against it | Counts against it |
| Player can resize, abandon or share it | No | Yes |

`ADMIN_CLAIM` is the default because it is the one that works on an untouched
GriefPrevention install, and on a server that starts players at zero claim blocks. Its
cost is ownership: the player is trusted to build there, which is what makes the claim
useful to them, but the claim is not theirs. They cannot resize it, cannot abandon it, and
cannot trust a friend onto it - only an administrator can, through GriefPrevention's own
commands.

`PLAYER_CLAIM` gives the player a claim that is genuinely theirs, and every one of
GriefPrevention's rules then applies to it. Read the next two sections before switching.

### The minimum claim size, the one that catches people

GriefPrevention refuses a player-owned claim smaller than either of two settings in
`plugins/GriefPrevention/config.yml`:

| Setting | Stock default |
| :--- | :--- |
| `GriefPrevention.Claims.MinimumWidth` | `5` |
| `GriefPrevention.Claims.MinimumArea` | `100` |

**A 9x9 square is 81 blocks, and 81 is below 100.** So on a stock GriefPrevention install,
`claim-as: PLAYER_CLAIM` with the default `size: 9` claims nothing, for every player, for
ever, until one of those two numbers changes. It is not an edge case and it is not
something you did wrong: it is what the two sets of defaults do when you put them
together.

That is why the default is `ADMIN_CLAIM`, which GriefPrevention exempts from both
settings.

SpiralGenesis checks this once at startup and warns naming both numbers, so the log says
which number to change instead of reporting an anonymous failure:

```
Spawn protection is configured for a 9x9 claim, which GriefPrevention will not accept
from a player on this server: GriefPrevention's MinimumArea is 100 and a 9x9 square is
81 blocks. No spawn claim will be created until protection.size is raised,
GriefPrevention's minimum is lowered, or protection.claim-as is set to ADMIN_CLAIM,
which the minimum does not apply to.
```

Your three options, if you want `PLAYER_CLAIM`:

* Raise `protection.size` to `11`. An 11x11 square is 121 blocks, which clears the stock
  `MinimumArea` of 100. **Read the next section before picking this one**: 121 is also
  more than the claim blocks a stock GriefPrevention gives a new player.
* Lower `GriefPrevention.Claims.MinimumArea` to 81 or less. That affects every claim on
  your server, not only spawn claims.
* Leave `claim-as: ADMIN_CLAIM`.

### Claim blocks

Under `PLAYER_CLAIM` the claim is paid for out of the player's claim-block balance, one
block of balance per block of area. The two GriefPrevention settings that decide whether a
new player can afford it are:

| Setting | Stock default | What it is |
| :--- | :--- | :--- |
| `GriefPrevention.Claims.InitialBlocks` | `100` | The balance a player starts with |
| `GriefPrevention.Claims.Claim Blocks Accrued Per Hour.Default` | `100` | What they earn per hour played |

A stock install therefore hands a brand new player 100 blocks. A 9x9 claim needs 81, which
they can afford - and it is refused anyway, by the minimum area above. An 11x11 claim
clears the minimum area at 121 blocks and is then **more than the 100 they start with**,
so it is refused for the other reason. Getting `PLAYER_CLAIM` working on an otherwise
stock GriefPrevention means changing at least one number on GriefPrevention's side
whichever route you take. On a server that starts players at zero, which is a common
hardening, `PLAYER_CLAIM` protects nobody at all.

A refusal for claim blocks is not permanent and not a fault: players accrue blocks for
time played, so the same square can succeed later. Nothing retries it on its own - run
`/sgen protect` when you want it retried. SpiralGenesis checks the balance itself before
asking GriefPrevention, because GriefPrevention's own API would take the player negative
rather than refuse, and the refusal names both numbers:

```
the player holds 40 claim blocks and the claim needs 81. Give new players a starting
balance, shrink protection.size, or set protection.claim-as to ADMIN_CLAIM, which costs
them nothing.
```

`GriefPrevention.Claims.MaximumNumberOfClaimsPerPlayer` is honoured under `PLAYER_CLAIM`
too. It is `0` - unlimited - on a stock install; if you have capped it, a player already
at the cap is refused rather than quietly handed one more claim than you allowed. Admin
claims count against nobody's cap.

### Overlapping claims

GriefPrevention refuses any new claim that overlaps an existing one, and SpiralGenesis
treats that as an ordinary outcome rather than an error. The player keeps their spawn and
loses the protection, nothing is retried, nothing is moved, and the existing claim is
never touched.

The overlap is recorded at `FINE`, naming the claim that got in the way and who owns it,
which means it is **not in your console** unless you have raised the log level. That is
deliberate: on an established server an overlap is an ordinary event, and one line per
joining player would be noise rather than news. Raise the level when you are working out
why a particular player has no claim.

That matters most in one direction, and it is the reverse of what people expect.

**Under the default `ADMIN_CLAIM`, a player cannot later claim the ground around their own
spawn themselves.** The admin claim is sitting there, GriefPrevention refuses an
overlapping claim, and a player can neither resize nor abandon an administrative claim to
make room. They can claim the rest of their plot right up against the square, but not
through it, and their own claim and the spawn square stay two separate things. If you want
players to be able to grow their spawn claim outward with a golden shovel in the ordinary
way, that is what `PLAYER_CLAIM` is for - and the two sections above are its price.

An overlap the other way round is ordinary on a server that has been running a while:
turning protection on for the first time, or running `/sgen protect`, will meet ground
people have already claimed for themselves. Those are reported as already claimed, counted
as skipped, and left alone.

### The claim is small on purpose

The claimed square is a few blocks across. The plot is `cell-size` blocks across, 500 by
default. **The rest of the plot is not claimed, and is not meant to be.**

That is the design rather than a limitation to work around. The spawn square exists so
that a player who has just arrived, owns nothing, and does not yet know the server's claim
commands cannot lose their bed and their first chest to the first person who walks past.
Claiming the whole 500x500 plot for them would hand every player who ever joins a claim
larger than most servers give anyone, would cost 250,000 claim blocks a head under
`PLAYER_CLAIM`, and would make the spiral's clean non-overlapping cells useless to anybody
who wanted to build together.

**Players are expected to claim the rest themselves, in the normal way** - a golden
shovel, or `/claim`, or whatever your server has taught them. Say so wherever you tell new
players what to do, because nothing in the game will tell them. The spiral layout is what
makes it easy: their cell is theirs alone, nobody else is ever allocated inside it, and
there is nothing to negotiate around except their own spawn square.

Raising `protection.size` well above the default is possible - the range runs to 255 - but
every block of it comes out of the ground the player would otherwise claim for themselves,
and under `PLAYER_CLAIM` out of their balance. A 255x255 claim is 65,025 blocks.

### When a spawn moves

Several paths create or move a spawn point, and each of them claims the new square. Two
rules hold across all of them:

* **A claim can never cost a player their plot.** The claim is asked for after the spawn
  is final and written to storage, and a provider that refuses, or breaks outright, still
  leaves the player allocated, teleported and with their respawn point set.
* **Nothing is deleted unless you ask in as many words.** Exactly one command argument in
  the whole plugin removes a claim.

| Path | The new spawn | The old claim |
| :--- | :--- | :--- |
| First allocation | Claimed last of all - after the spawn is stored, the respawn point set and the teleport requested | There is none |
| `/sgen reassign <player>` | Claimed | Left standing, with its coordinates and world named in the command output. See the note below on removing it |
| `/sgen reassign <player> release` | Claimed | Released, if it is still recognisably the claim SpiralGenesis made. The only thing in the plugin that deletes a claim |
| `/sgen setspawn <player> [x y z]` | Claimed | Left standing and reported in the command output. There is no `release` argument here |
| Respawn revalidation repair | Claimed at the repaired point | Left standing and reported to the server log, since this path has no command output |
| Teleport re-assert | Nothing at all. The stored spawn has not moved, so the claim already there is the right one | Unchanged |

**Removing a claim a reassignment has already left behind.** The line `reassign` prints
suggests re-running the command with `release`, and that is advice for next time rather
than an undo for the reassignment you just did: re-running
`/sgen reassign <player> release` moves the player on to a third plot and releases the
claim around the second, not the one you were just told about. To clear a square a
reassignment has already stranded, go to the coordinates in the message and use
GriefPrevention's own commands - the same answer `setspawn` gives below. Decide before you
run the command, not after, and the `release` argument does the whole job.

`setspawn` has no `release` argument for a grammatical reason rather than a different
answer to the same question: the command already ends in an optional `[x y z]`, so a
trailing word after it could not be told apart from a coordinate. Remove the old claim
with GriefPrevention's own commands if you want it gone; the line SpiralGenesis prints
tells you exactly where to stand.

A mistyped third argument on `reassign` is refused, not ignored.
`/sgen reassign Steve relase` does nothing at all rather than reassigning without
releasing, because the one word whose job is to authorise a deletion must never be
guessed at.

**What `release` will and will not delete.** It removes a claim only when everything still
matches what SpiralGenesis would have created at that point: the same square to the block,
no subdivisions inside it, and either the same owner under `PLAYER_CLAIM` or - under
`ADMIN_CLAIM`, where there is no owner to compare against - an administrative claim
carrying the explicit `Build` trust SpiralGenesis grants in the same breath as creating
one. A claim the player has since resized outward over their house, one belonging to
somebody else, or one made by hand is reported and left completely alone. Declining costs
you one stale square; deleting the wrong claim costs a player everything inside it.

Both `reassign` and `setspawn` act on an online player, as they always have, so neither is
a way to tidy up after somebody who has left.

### Backfilling players who predate the feature

Allocation runs once per player, for a player who has no plot yet. So turning protection
on protects the next person to join and nobody who was already there - everyone already in
`data.yml` has a spawn and no claim, and nothing in the ordinary run of the plugin will
ever revisit them.

```
/sgen protect
```

takes no arguments, is gated on the existing `spiralgenesis.admin` permission, and adds no
new permission node. It walks every stored spawn and claims the square around it.

* **It is safe to run twice.** A square that is already claimed answers "already claimed"
  and is counted as skipped. There is no flag in `data.yml`, nothing to migrate, and no
  way for an interrupted run to leave anything in a state a later run reads wrongly. Run
  it again after fixing a setting that was refusing claims.
* **It does not freeze the server.** The work has to happen on the main thread, and a
  large `data.yml` has thousands of entries, so the job does a bounded slice per tick - at
  most 50 entries, and at most two milliseconds - and carries on across ticks until it is
  done. You get an acknowledgement immediately and the report when it finishes.
* **It is far more expensive under `PLAYER_CLAIM`.** Reading an offline owner's
  claim-block balance makes GriefPrevention load that player's data from its own storage,
  synchronously. A backfill's owners are offline almost by definition, so several hundred
  entries is several hundred file or database reads. The default `ADMIN_CLAIM` never reads
  a balance at all. The wall-clock half of the bound above exists for exactly this, and it
  is why the command takes noticeably longer on a `PLAYER_CLAIM` server.
* **It refuses to start when protection is not active**, naming `protection.enabled` and
  `protection.provider` rather than reporting a failure, and it refuses to start a second
  run while one is in flight.
* **Spawns whose world is not loaded are skipped, not failed.** A multi-world server that
  has retired a world still has those players in `data.yml`, and there is nothing to claim
  around a point that does not currently exist.

The report is counts out of a total, plus the reason the first failure failed, because a
count of failures with no cause is a count nobody can act on:

```
Spawn protection backfill finished: 128 created, 12 skipped, 0 failed, out of 140
stored spawns.
```

It also goes to the server log, so a backfill started from the console and one started in
game leave the same record.

### Claim geometry, for the curious

The square is `size` blocks on a side, centred on the spawn block. Vertically it follows
GriefPrevention's own `/claim` command exactly: it starts one block below
`GriefPrevention.Claims.ExtendIntoGroundDistance` beneath the spawn block - five by
default, so six blocks down - and reaches the world's build height. Matching the command
matters, because a claim made any other way behaves differently the first time somebody
tries to resize it with a shovel.

A world where GriefPrevention has claiming switched off - its
`GriefPrevention.Claims.Worlds` setting - gets no claims, and that is reported once per
world rather than once per player. Nothing in SpiralGenesis can override it; it is
GriefPrevention's own per-world setting.

---

## 7. Stored data

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

## 8. Tuning with `/sgen simulate`

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

## 9. Sizing and world generation

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

## 10. Testing checklist

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

## 11. Where the code lives

All implementation is under `src/main/java/com/ninja6/spiralgenesis/`. The source is the
authoritative reference — this guide describes behaviour, not line numbers.

| Component | File | Responsibility | Section |
| :--- | :--- | :--- | :--- |
| Spiral math | `math/SpiralMath.java` | Index → grid and grid → world transforms | §2 |
| Allocation | `manager/SpawnManager.java` | Cell and candidate search, async probing, revalidation | §1, §3, §5 |
| Rejection reasons | `manager/RejectionReason.java` | The rule names reported by simulation | §3 |
| Simulation | `manager/SpawnSimulator.java` | `/sgen simulate` dry runs | §8 |
| Join lifecycle | `listeners/PlayerSpawnListener.java` | First-join and respawn hooks | §5 |
| Action gating | `listeners/PlayerActionGateListener.java` | Holds allocation until an uncancelled action | §5 |
| AuthMe fast path | `listeners/AuthMeHookListener.java` | Optional: allocates on AuthMe login | §5 |
| Bedrock detection | `hook/FloodgateHook.java` | Identifies Floodgate players | §5 |
| AuthMe bridge | `hook/AuthMeHook.java` | Detects whether AuthMe is installed | §5 |
| Persistence | `storage/YamlDataStorage.java` | Per-player registry in `data.yml` | §7 |
| Spawn protection | `protection/SpawnProtector.java` | The one place that asks a provider for anything | §6 |
| GriefPrevention claims | `protection/GriefPreventionProtectionProvider.java` | Creates and releases the spawn claim | §6 |
| Provider selection | `protection/ProtectionProviders.java` | Picks the provider, or the no-op | §6 |
| Protection backfill | `protection/SpawnProtectionBackfill.java` | `/sgen protect`, a bounded slice per tick | §6 |
| Commands | `commands/SpiralCommand.java` | `/sgen` command tree and permissions | — |
| Configuration | `config/PluginConfig.java` | `config.yml` parsing, clamping, validation | §3, §4 |

Unit tests for the maths and configuration logic live under
`src/test/java/com/ninja6/spiralgenesis/`.
