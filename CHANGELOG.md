# Changelog

All notable changes to **SpiralGenesis** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- **Spawn protection**, off by default and needing GriefPrevention. Switched on, a
  player's spawn point becomes the centre of a small claim only they can build in - 9x9 by
  default, four blocks out in each direction - so their bed, their first chest and the
  ground under their feet are covered the moment they arrive, with no command to run and
  nothing to learn. The rest of the 500x500 plot stays unclaimed and is theirs to claim in
  the normal way. A server that has never heard of GriefPrevention is unchanged in
  behaviour, including on Folia, where GriefPrevention cannot run at all: the provider
  resolves as absent, says so in one line, and `folia-supported: true` stays honest. The
  new `protection:` block is four keys - `enabled`, `provider`, `size` and `claim-as` -
  and every one of them is reloadable. `docs/ADMIN_GUIDE.md` section 6 is the reference;
  the notes below are what a server owner has to decide.
  - **`claim-as: ADMIN_CLAIM`, the default,** creates an administrative claim with the
    player trusted onto it. It consumes no claim blocks, so it works on a server whose
    starting balance is zero, and it is exempt from GriefPrevention's minimum claim size
    and per-player claim limit. What it costs is ownership: the player cannot resize,
    abandon or share the claim, and because GriefPrevention refuses overlapping claims,
    they cannot later claim the ground around their own spawn either. `PLAYER_CLAIM`
    creates a claim they own outright and pay for out of their claim-block balance.
  - **The number to check before choosing `PLAYER_CLAIM`:** GriefPrevention ships
    `MinimumArea: 100` and a 9x9 square is 81 blocks, so on an untouched GriefPrevention
    install `PLAYER_CLAIM` claims nothing, for every player, until `protection.size` is
    raised or that minimum is lowered. It is checked once at startup and reported once,
    naming both numbers, rather than failing per player. Under `PLAYER_CLAIM` the player's
    balance and GriefPrevention's per-player claim limit are checked here as well, because
    its API checks neither and would take a player into claim-block debt rather than
    refuse.
  - **Even `size` values are rounded up** to the next odd number, since an even square has
    no centre block for the player to stand on, and the correction is logged at warning on
    every load naming both the configured value and the value used. The range is 3-255.
  - **Every path that moves a spawn claims the new one and leaves the old claim
    standing**, naming it in the command output where there is one and in the server log
    where there is not - `/sgen reassign`, `/sgen setspawn` and a respawn revalidation
    that finds the stored point unsafe. `/sgen reassign <player> release` is the only
    thing in the plugin that deletes a claim, and even then only one that still matches
    what SpiralGenesis would have created there, so a claim the player has since resized
    over their house, or one belonging to somebody else, is reported and left alone. A
    spawn claim is a few blocks of ground on the day it is made and somebody's bed, chest
    and first night's work by the time anyone reassigns them.
  - **`/sgen protect` backfills players who predate the feature**, gated on the existing
    `spiralgenesis.admin` and adding no new permission node. It is idempotent with nothing
    recorded on disk to make it so - a second pass asks for squares that are already
    claimed and counts them as skipped - and it is bounded by a wall clock as well as a
    count, a few entries per tick, because under `PLAYER_CLAIM` reading an offline owner's
    balance makes GriefPrevention load that player's data synchronously. It reports
    created, skipped and failed out of a total, plus the first failure's reason.
  - **None of it can cost a player their plot.** The claim is asked for only after the
    spawn is final and written to storage, and a provider that refuses, returns nothing or
    throws still leaves the player allocated, teleported and with their respawn point set.
    That is what the tests pin. Claims are created on the thread that owns the region
    containing them; a call from anywhere else is refused without GriefPrevention being
    touched and logged at SEVERE, with a stack trace naming the call site the first time.
- CI now refuses the allocation teleport and asserts the retry lands. The limbo fixture can
  cancel every `PlayerTeleportEvent` while it holds a player, which is what LibreLogin does,
  so the action-timeout backstop allocates a plot the player cannot be moved onto - storage
  claiming a location its owner has never stood at, the one state the teleport re-assert
  exists to reconcile. The run then releases the limbo and asserts the plot teleport is
  re-asserted on the player's first uncancelled action. That path had unit-tested bookkeeping
  and no live coverage at all. Paper only: Folia does not route `teleportAsync` through
  `PlayerTeleportEvent`, so a login plugin cannot refuse a teleport there by cancelling it -
  measured, not assumed.
- The CI protocol client now dies and respawns. The run fills the plot it was just allocated
  with lava, kills it, and asserts it comes back somewhere else inside the same cell with its
  spiral index untouched - end-to-end evidence for respawn revalidation that no unit test can
  provide. It runs on Folia as well as Paper, which is what revealed that Folia never
  delivers `PlayerRespawnEvent`.
- CI now drives allocation with a real player. A protocol client joins the test server over
  the wire, so the server creates a genuine player and runs the join listeners; the run holds
  it in limbo, asserts nothing was allocated, releases the limbo, and asserts allocation
  followed. Until now nothing had ever exercised `PlayerJoinEvent`, the gate holding or
  releasing anyone, or the teleport - allocation was reached only through `/sgen simulate`,
  which calls the allocator directly.

### Changed
- Allocation for Java players is no longer gated on AuthMe specifically. It now waits for
  the player's first action that was not suppressed, which is how *every* login plugin
  enforces its limbo, so AuthMe, nLogin, LibreLogin, OpeNLogin and anything else are all
  covered without SpiralGenesis depending on any of them. Suppression is read in both the
  forms these plugins use, both confirmed by reading their implementations: an event
  cancelled outright (LibreLogin), and a move whose destination is rewritten back to its
  origin without the cancel flag ever being set (AuthMe, OpeNLogin). Descending movement
  never opens the gate, because OpeNLogin declines to pin a falling player at all and
  applies no block-column test when doing so. Previously a server running
  any login plugin other than AuthMe silently took the "no login plugin" path and allocated
  players while they were still held in limbo, permanently burning a spiral index per
  connection. AuthMe remains a fast path where installed, allocating on its login event
  instead of the next action, and a failure to bind its API now logs a warning and falls
  through to the generic gate rather than breaking plugin enable.

### Removed
- `AuthMeHook.isAuthenticated`. It had no callers and failed open, returning `true` on any
  `Throwable`, so anything that had used it would have treated an AuthMe API breakage as
  proof of authentication.

### Fixed
- `/sgen tp` sent an admin to a stored plot after checking only that the record existed and
  its world was loaded, while the respawn path re-checked the same point against live
  blocks. Both `docs/ADMIN_GUIDE.md` and the warning logged when a plot teleport is refused
  name `/sgen tp` as the manual remedy, so the documented fix could drop an admin into the
  exact hazard revalidation exists to prevent. The command now re-checks the plot first and
  warns before it goes. It still goes: looking at a plot that has been flooded or dug out is
  the reason to run it, so refusing would remove the tool from the case it serves, and
  relocating the admin would answer a question they did not ask. The check runs through
  `SpawnManager`, which awaits the chunk before reading a block - the only ordering Folia
  permits, and necessary here because the plot is almost never resident. The command also
  no longer reports success for a teleport that was refused, which matters because it is
  what the admin guide names when a plot teleport is refused in the first place.
- A stored spawn was validated once, when it was allocated, and never again. Cells are 500
  blocks wide and everything outside the optional spawn square is unclaimed, so anyone
  could flood a plot, pour lava on it or dig out the ground, and its owner would then
  respawn into it, die, and respawn into it again. The stored point is now re-checked
  against live blocks on death, which uses the time the player spends on the death screen
  and is also the only
  moment Folia offers - Folia's respawn computes its own position and never fires
  `PlayerRespawnEvent`, whose usual source throws `UnsupportedOperationException` there.
  `PlayerRespawnEvent` remains as a backstop on Paper for a repair that has not finished
  yet: it is synchronous and cannot await a chunk load, so it judges a resident plot inline
  and lets an unloaded one through, rather than penalising the ordinary case - a plot nobody
  is standing on - to catch the rare one. A plot that fails is repaired by
  re-searching **the same cell**, and the player's spiral index never advances - their
  builds are in that cell, and relocating them would make griefing a spawn a way to evict
  its owner. If every sampled candidate in the cell fails, the player is sent to world
  spawn with a warning and their assignment is left alone: `placement.max-candidates`
  samples 12 of the 961 points a cell holds, so that is not evidence the plot is unusable.
- A plot recorded in `data.yml` but never actually reached is now reconciled. A login plugin
  that cancels teleports for unauthenticated players (LibreLogin cancels every
  `PlayerTeleportEvent` in limbo) left storage claiming a location the player had never
  been to, and nothing ever retried. The teleport is re-asserted once on that player's next
  uncancelled action - the same signal the allocation gate already trusts, by which point
  whatever was refusing teleports has let go.
- A player could be allocated twice, consuming two spiral indices, which are never
  reclaimed. The in-flight guard was released when the apply task was *scheduled* rather
  than when it ran, leaving a window in which storage still reported the player unassigned
  and nothing guarded them. The AuthMe fast path supplied the second caller: it allocated on
  `LoginEvent` without dropping the player from the action gate, so their next step released
  them again. Allocation now drops the player from the gate on every path, and holds the
  guard until the assignment has been written.
- Players who already own a plot are no longer held by the allocation gate on join. They
  had nothing to allocate, but were still tracked and given a timeout, so anyone who joined
  and stood still triggered a warning about a limbo that was not holding them.
- A teleport that did not complete is no longer silent. The plot is recorded and the respawn
  point set before the teleport is attempted, so a cancelled teleport left storage claiming a
  location the player had never been moved to, with nothing logged and nothing retrying. A
  login plugin that cancels teleports for unauthenticated players triggers exactly this.

### Added
- `allocation.trigger` chooses when Java players are allocated: `FIRST_ACTION` (default,
  gates behind any login plugin) or `ON_JOIN` (for online-mode servers, and networks that
  authenticate at the proxy or on a separate backend).
- `allocation.action-timeout-seconds` (default 300) allocates a held player anyway if
  nothing they do is ever uncancelled, so an unreadable limbo delays players rather than
  stranding them. Logged at warning when it fires. Set to `0` to wait indefinitely.
- `/sgen allocate <player>` places a player the action gate is holding. Intended for a login
  plugin's own on-login command hook, which gives servers running a plugin the gate cannot
  read - one suppressing actions below the Bukkit event layer, or a closed-source one like
  nLogin or JPremium - a supported path that needs no integration on either side. Unlike
  `/sgen reassign` it never moves a player who already has a plot, so it is safe to run on
  every login.
- The startup log now states which allocation trigger is in effect and which known login
  plugins were detected, and warns when `ON_JOIN` is set on a server that has one.
- Minecraft 26.1 and 26.2 are now supported and declared on both registries. CI boots the
  plugin on Paper and Folia at 26.2 as well as 1.20.4 and runs allocation against real
  terrain on each, so the supported range is verified rather than assumed. Note that
  Minecraft 26.1 and newer require the **server** to run on Java 25; the plugin itself is
  unchanged Java 21 bytecode.
- `/sgen reassign`, `/sgen setcenter` and `/sgen setspawn` now log what they did to the
  server console: who ran the command, the target, and the resulting plot, grid cell and
  coordinates. Previously only the actor's own chat saw the outcome, so a spawn change
  left no trace an admin could read afterwards.

## [0.9.0] - 2026-08-18

First published build. Everything below has been in the repository since the initial
commit; this is the point at which it became downloadable.

### Changed
- Spawn allocation now searches **within** a cell before abandoning it. Previously a single
  unsafe block at the cell centre discarded the whole `cell-size` plot and permanently
  consumed a spiral index; candidates are now probed outward from the centre first
  (`placement.stride`, `placement.max-candidates`).
- Candidates are rejected when they sit below the surrounding terrain
  (`safety.max-pit-depth`), when their surroundings are too uneven
  (`safety.max-roughness`), when lava or powder snow is adjacent, or when there is no
  headroom for a player. This is what keeps spawns out of ravines, sinkholes and cliff
  edges, whose floors the heightmap reports as "the surface".
- Exhausting `safety.max-scan-attempts` now settles on the best candidate seen during the
  scan rather than whichever one happened to be probed last.
- `safety.max-scan-attempts` now defaults to `8` rather than `50`. Worst-case allocation is
  `max-scan-attempts * max-candidates` ticks, and cells rarely fail outright now that the
  search looks inside them.

### Added
- Tagging a release now publishes to Modrinth and Paper Hangar as well as GitHub
  Releases. Both steps are skipped when their token is absent, so a tag can be cut
  before the registry projects exist. Release notes come from this file.
- The release workflow rejects malformed tags and refuses to publish a stable release
  whose version has no changelog section.
- `placement.strategy` — `FIRST_SAFE` (default, keeps the player at the cell centre when it
  is viable and stops probing there), `FLATTEST`, or `HIGHEST` with a `height-ceiling` that
  keeps ranking off jagged peaks.
- `/sgen simulate <count>` — runs allocation against the live world and reports indices
  consumed per spawn, fallbacks, surface range, and a per-rule rejection breakdown. Uses a
  throwaway counter, so it never advances the live spiral index. Allocation is otherwise
  only reachable by a player joining, which made the terrain rules untestable without a
  game client.
- CI now drives `/sgen simulate` against a generated world and fails on regressions in
  index burn, fallback use, or spawns below `min-surface-y` — so a safety threshold that is
  too strict for real terrain is caught before release.
- `scripts/dev-server.sh` for local testing. `./gradlew runServer` is currently unusable:
  run-paper 2.x resolves servers through PaperMC's retired v2 API, and the 3.x line that
  speaks v3 requires Gradle 9.
- `.gitattributes` pinning shell scripts to LF. With `core.autocrlf=true`, `gradlew` was
  checked out with CRLF and failed under bash with `/bin/sh^M: bad interpreter`.
- Initial project scaffolding with Gradle Kotlin DSL and Java 21 toolchain.
- Deterministic 2D square spiral coordinate transformation engine (`SpiralMath`).
- Asynchronous Paper chunk and heightmap probing with ocean and hazard filtering (`SpawnManager`).
- Cross-play authentication parity for Bedrock (`Floodgate`) and Java (`AuthMe-Reloaded`).
- Persistent per-player YAML storage (`data.yml`).
- Administrative `/sgen` command suite (`setcenter`, `setspawn`, `reassign`, `tp`, `info`, `reload`).
- Automated multi-tier release workflows for GitHub Releases, Modrinth, and Paper Hangar.
- JUnit 5 test suite for mathematical verification.
