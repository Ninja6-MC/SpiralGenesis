# Changelog

All notable changes to **SpiralGenesis** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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

### Fixed
- A player could be allocated twice, consuming two spiral indices, which are never
  reclaimed. The in-flight guard was released when the apply task was *scheduled* rather
  than when it ran, leaving a window in which storage still reported the player unassigned
  and nothing guarded them. The AuthMe fast path supplied the second caller: it allocated on
  `LoginEvent` without dropping the player from the action gate, so their next step released
  them again. Allocation now drops the player from the gate on every path, and holds the
  guard until the assignment has been written.
- A teleport that did not complete is no longer silent. The plot is recorded and the respawn
  point set before the teleport is attempted, so a cancelled teleport left storage claiming a
  location the player had never been moved to, with nothing logged and nothing retrying. A
  login plugin that cancels teleports for unauthenticated players triggers exactly this.

### Added
- `allocation.trigger` chooses when Java players are allocated: `FIRST_ACTION` (default,
  gates behind any login plugin) or `ON_JOIN` (for online-mode servers, and networks that
  authenticate at the proxy or on a separate backend).
- `allocation.action-timeout-seconds` (default 120) allocates a held player anyway if
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
