# Changelog

All notable changes to **SpiralGenesis** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Changed
- **`origin.world` is matched exactly, and no other world is ever substituted for it.**
  Previously a name that matched no loaded world fell back to whichever world the server
  loaded first, silently, so a typo carved spiral plots into a lobby or the Nether and
  overwrote the respawn points of everyone it allocated. The plugin now binds nothing,
  reports the configured name and the loaded worlds at SEVERE, and tells `/sgen reload`
  what it bound. **On upgrade, a server whose `origin.world` does not name a loaded world
  stops allocating entirely** rather than allocating into the wrong one; correct the name
  and run `/sgen reload`. A world created after the plugin enables is still picked up on
  the first join that needs it.
- **The Hangar resource page is synced on release.** After the version upload succeeds,
  the release workflow writes `docs/modrinth-description.md`, without its generated
  comment, to the Hangar resource page, then reads the page back and fails the job unless
  Hangar holds exactly that text. The version stays published when only the sync fails;
  `RELEASE_PROCESS.md` has the command to run the sync on its own. The Hangar API key now
  needs `edit_page` in addition to `create_version`.
- **Hangar versions declare their optional dependencies.** Floodgate, GriefPrevention and
  AuthMeReloaded are listed as optional Paper dependencies, matching `softdepend` and the
  Modrinth upload. AuthMeReloaded is not on Hangar and is declared as an external link.
- **Registry tokens are scoped to the steps that use them.** `MODRINTH_TOKEN` and
  `HANGAR_API_TOKEN` are no longer in the release job's environment, so the build and the
  tests run without either.

### Fixed
- **A player can share their own spawn plot again.** Under the default
  `protection.claim-as: ADMIN_CLAIM` the spawn claim granted the player
  `ClaimPermission.Build` and nothing else, and `Build` does not imply `Manage` in
  GriefPrevention's permission model - the two are separate grants and `isGrantedBy`
  connects neither to the other. `/trust` checks for `Manage`, so the player could build on
  the plot the plugin had made for them but could not invite anybody onto it, and because
  administrative claims are administered through `griefprevention.adminclaims`, no
  ordinary player could work around it. The claim now carries both grants. Resizing,
  subdividing and deleting still belong to the server, because GriefPrevention refuses to
  delegate `ClaimPermission.Edit` on an administrative claim at all; `PLAYER_CLAIM` remains
  the setting for a plot the player owns outright. No default changed and no configuration
  key was added or renamed. Claims created before this fix carry `Build` alone and are
  repaired in place by `/sgen protect`, which under `ADMIN_CLAIM` now adds the missing
  grant to a claim it still recognises as one of its own and says so in the skip reason.
- **Allocation no longer places a player outside the world border.** Candidate scoring
  checked terrain only, so once the spiral grew past the border a player was teleported
  outside it and had their respawn point forced onto the same spot, leaving them taking
  border damage with every respawn returning them to it. Candidates outside the border are
  now rejected before their chunk is even requested, so a cell lying wholly outside is
  skipped without generating terrain nobody may stand on, and the spiral advances. A scan
  that spends `max-scan-attempts` without reaching inside the border fails with a console
  message naming the border instead of placing the player: they stay where they are, and
  the operator is told to widen the border or move `origin.x`/`origin.z`. Allocations after
  that are refused on the spot, without claiming a spiral index, so a player rejoining
  cannot walk the spiral outward a scan at a time with no plot to show for it. The refusal
  is held against the border's position and size rather than as a latch, so widening or
  moving the border resumes allocation with nothing for an operator to reset. The console
  is told once, in plain text and without a stack trace: by the scan that gives up, not by
  the refusals that follow or by other scans in flight that give up against the same
  border. It is told again when a changed border is exhausted again, and when the border
  is put back where a scan already gave up, by the first join refused on its return.
  `/sgen reassign` that finds no plot tells the operator why in chat. `/sgen
  simulate` is outside all of this in both directions: it scans from the origin rather than
  from where the live spiral has reached, so it still runs and reports after a live
  allocation has given up, and a run of its own can never refuse a joining player.
- **A broken bed on Folia no longer costs a player their plot.** Sleeping in a bed replaces
  the respawn point allocation set, and on Paper a later respawn with that bed gone is
  sent back to the plot by `PlayerRespawnEvent`. Folia never fires that event for a death
  respawn, so the player landed at world spawn instead. When the server finds the bed or
  anchor gone during the respawn and clears the respawn point (`PlayerSetSpawnEvent` with
  cause `PLAYER_RESPAWN`), the plot is now stored in its place and, on Folia, the player is
  moved there as soon as they are placed, after the plot passes a fresh safety check;
  with `doImmediateRespawn` as well. The respawn itself still lands at world spawn for
  that one moment. When the point the server rejected is the plot itself, it is restored
  as the respawn point all the same; a flooded or otherwise unsafe plot fails the safety
  check, so the player stays at world spawn while the existing repair runs. A player left
  with no respawn point at all has the plot restored when they die. A bed or anchor that
  still works, and a point forced elsewhere such as by `/spawnpoint`, is left alone on
  both platforms.
- **Building on your own spawn point no longer moves it.** The death-time re-check failed
  a plot whenever the block at the player's feet or head could not be walked through, so
  a chest, crafting table, bed, door or slab placed on the landing spot made the plot
  "unsafe", and the repair rewrote the stored spawn to another point in the cell, away
  from what had been built. The re-check now fails a plot only for what can hurt a player:
  a missing floor, or water, lava, powder snow, underwater plants, cactus, magma or a
  campfire at the feet, head or underfoot. An obstruction is not one of them. Instead
  the player respawns at the first clear position above the plot, on top of the build:
  on Paper that position is the respawn location itself, since Paper 1.21.11 and later
  place a respawning player exactly where they are sent, inside any block there; on
  Folia, which declines such a point and places the player at world spawn, the player is
  moved there once they are placed. The stored spawn is not changed by the lift. If the
  column has no clear position below the build limit, or the first one sits on something
  that hurts, the player is held at world spawn. On Paper the client still shows the
  vanilla "no respawn block" message on such a death; the placement is unaffected. Ice
  no longer fails the re-check either, so an ice floor or ice road through the spawn is
  kept; allocation still rejects ice as a surface for new plots.
- **A save that keeps failing no longer floods the console.** The background flush retries
  `data.yml` every five seconds, and each failure logged a SEVERE stack trace, so a full
  disk or read-only mount filled the console for as long as it lasted. The first failure
  is still logged in full; the retries after it are logged at FINE, and one line is logged
  when a save succeeds again, with the number of failed attempts. Removing a leftover
  `data.yml.tmp` at startup and on `/sgen reload` now also waits for a save in progress
  instead of deleting the scratch file out from under it.
- **Repairing a plot no longer takes away a working bed or anchor.** When the death-time
  re-check found a plot unsafe and the repair moved it within the cell, the player's
  respawn point was forced onto the repaired point unconditionally, so a player with a bed,
  a charged anchor or a point set elsewhere by `/spawnpoint` lost it the next time their
  plot was repaired, and a player who had already respawned at their bed was teleported to
  the plot. The repaired point is still recorded as the plot, but the respawn point now
  follows it only when it is unset or is the old plot, matched on its block column in the
  plot's world so a player lifted on top of a build still counts. Anything else is left
  alone, and so is the player. Whether the bed still works is not checked: one that has
  stopped working is handled when the server clears the point on respawn.
- **A plot left outside a shrunken world border no longer passes its re-check.** The
  re-check on death and respawn looked at terrain only, so after an operator drew the
  border in past an existing plot, its owner kept respawning outside the border and
  taking border damage. A stored point outside the border now fails the re-check, even
  when its chunk is not loaded. The in-cell repair then looks for a point inside the
  border in the same cell. When the whole cell is outside, it finds none and the player
  respawns at the main world's spawn, which is the plot world's only while the plot world
  is the main world; Folia always uses the overworld. On Folia, which accepts a forced
  respawn point without looking at the border, the plot is taken off the player's respawn
  point at death, before the respawn can use it; a bed, anchor or point set elsewhere is
  left alone. No new spiral index is claimed and the stored plot is not rewritten. Once
  the border takes the plot back in, the player's next death restores it as their respawn
  point and re-checks it. Separately, on Folia a player whose repair finds no safe point
  while they are still on the death screen now respawns at the overworld's spawn instead
  of back on the unsafe plot.
- **The respawn listener no longer keeps an entry for every player who has left.** Each
  respawn that fired `PlayerRespawnEvent` without its point failing left the player in a
  set that only their next death cleared, so one entry stayed behind per player who quit
  before dying again. The entry is now dropped on quit.
- **A step, slab or trapdoor under your spawn point no longer moves it.** The death-time
  re-check treated anything a player can pass through as a missing floor, so a staircase
  dug down from the spawn point or an open trapdoor over it failed, and the repair moved
  the plot away from the build. The floor is now whatever has a collision shape under
  the centre of the column: slabs, stairs, closed trapdoors, carpet, two or more layers of
  snow and a closed fence gate all count, and air, fluids, plants, a single snow layer, a
  door, an open trapdoor or an open fence gate do not. When the block under the spawn is
  not a floor, a step of one block down onto a floor is accepted too. Anything deeper
  still fails, so a spawn over a real drop is still repaired. The floor that is used,
  and the block stepped into, get the same checks as before for water, lava, magma,
  cactus and campfires. Allocation of new plots is unchanged.
- **A player who cannot be allocated because no world is bound stays held until one is.**
  The log said such a player would be retried once `origin.world` named a loaded world,
  but the hold lasted one action: their next step retried while the world was still
  missing, and afterwards nothing retried them for the rest of the session. They now stay
  held across every action and are allocated as soon as a world is bound, by
  `/sgen reload` or by a world that loads late, without having to act again. The hold is
  logged once per player at WARNING instead of at SEVERE on every retry, and the missing
  world is still reported at SEVERE once per configured name, now also when two threads
  retry the bind together.
- **An unreadable `data.yml` no longer restarts the spiral from index 0.** A file that
  did not parse loaded as an empty one, indistinguishable from a fresh install, so the
  counter reset and every returning player was allocated a new plot in cells other
  players already held; the next save then wrote that empty state over the original.
  Such a file is now reported once at SEVERE and copied aside as
  `data.yml.broken-<timestamp>`, and storage is marked failed: nothing is saved by the
  flush, `/sgen reload` or shutdown, nobody is allocated, and no respawn point is changed.
  Joining players wait in the same hold as when no world is bound, operators with
  `spiralgenesis.admin` are told in chat when they join, and the commands that read or
  write player records are refused. A `/sgen reload` that reads the repaired file clears
  the state and allocates everyone held; one that still cannot read it reports again
  without copying the same file twice. A missing or empty file is still a fresh install.
- **`/sgen simulate` no longer discards its report when a sample fails.** One sample
  that found no plot inside the world border, or failed for any other reason, aborted the
  whole run with "Simulation failed" and none of the samples already taken. A sample that
  exhausts against the border is now counted and the run carries on: the report shows how
  many samples exhausted, the first one that did and the spiral index it scanned from, and
  the console summary line gains `exhausted=`. Any other failure ends the run at that
  sample and the report for the samples before it is still delivered, with the failure
  reported alongside it and logged in full.
- **A respawn lifted above an open trapdoor or a door checks the drop beneath it.** When
  a build over the spawn point lifts the player onto a block that leaves the column
  centre clear, the player falls through it onto the build below, and only the cells at
  the lifted position were checked. The fall is now followed to the first floor, and the
  cells passed and that floor get the same checks for water, lava, magma, cactus and
  campfires. A drop onto or through one of them, one that falls past the step below the
  spawn point, or one longer than 3 blocks, the most a fall takes without damage, holds
  the player at world spawn as a hazard on top of the build does.
- **A held player's bind retry can no longer undo a `/sgen reload` that bound a world.**
  On Folia the reload and a held player's retry run on different threads, and a retry
  that had read the old `origin.world` could clear the manager just after the reload
  bound the new one. The log then said the world was bound while held players stayed
  held until they acted again. Binding is now serialised, so the reload's bind stands and
  releases everyone held.
- **A spawn that storage refused to record no longer moves anyone.** A `/sgen reload`
  that failed to read `data.yml` could land after a first allocation or `/sgen reassign`
  had checked storage and before it wrote the new spawn. The write was refused, but the
  player was still teleported, their respawn point set and the new plot claimed, and
  reassign's `release` removed the old claim, all for a record that did not exist.
  Everything after the write is now gated on the write itself. A refused first
  allocation leaves the player where they are, logs one line and holds them until
  storage reads again, and the line says they left instead if they disconnected before
  they could be held; a refused reassign changes nothing and tells the operator so. The
  refused plot's index is recorded against nobody, so it is never shared.
  `/sgen setspawn` and the in-cell repair are gated the same way.
- **A scan in flight across a failed `/sgen reload` no longer shares its plot.** An
  allocation reserves its spiral index when it starts and records the player when it
  finishes. If a reload failed to read `data.yml` in between, and a later reload read back
  a file saved before the reservation, the counter was restored below the scan's index:
  the scan recorded its player there and the next player was handed the same plot. A
  reservation made between a reload's save and its load could be handed out twice the
  same way. A load now never restores the counter below an index already reserved or
  recorded in this run, except one whose write was refused, which is handed out again.
- **A player who disconnects during their terrain scan keeps the plot it finds.** The
  scan reserves a spiral index when it starts, and a player who left before the result
  was applied had it dropped: the index was recorded against nobody and they were
  allocated a second one on their next join, leaving a permanent gap in the spiral. The
  plot is now recorded against them anyway, and they are placed on it when they return -
  respawn point, teleport and claim - without another index being reserved. Placement
  happens when a new player would have been allocated: under the default `FIRST_ACTION`,
  on their first uncancelled action after they join, and at once under `ON_JOIN` and for
  Bedrock players. The same holds for a player who rejoined while the scan was still
  running, once it finishes. A write refused because `data.yml` could not be read records
  nothing, as for a connected player. The pending placement is saved with the record as
  an optional `placement-owed` key in `data.yml`, so it survives a restart, and is
  removed once the player is placed; a file written before the key existed loads with
  nobody owed a placement.
- **Installing on a server people already play on no longer moves them.** Every player
  without a record was treated as new, so each one who had played there before the
  install was allocated a plot on their next visit: their bed or respawn anchor was
  overwritten and they were teleported away from their base, with the first of them landing
  on whatever already stood around the spiral origin. A player who played on the server
  before the plugin was installed is now left alone: no index is reserved, nothing is set,
  moved or claimed, and they are not gated. The console says so once per player per run,
  and `/sgen reassign` gives them a plot when an operator wants them to have one;
  `/sgen allocate` refuses them and says the same. "Before" is the server's first-played
  time against a new `installed-at` key in `data.yml`, not whether they have played
  before, so a player who first joined after the install and left before being placed is
  still allocated. A fresh install records the current time. A file written by an earlier
  version records its earliest `assigned-date`, since that version allocated the first
  player to join on their first action, or the current time if it assigns nobody. Every
  record write rewrites its `assigned-date`, so that can be later than the real install,
  which leans toward leaving players alone. A player
  with a record is never skipped, so one owed a placement is still placed.
- **A respawn point forced elsewhere is kept on Paper 1.21 and later.** The respawn
  handler left a respawn alone only when Paper flagged it as a bed or anchor spawn. Paper
  1.21.11 and later flag neither for a forced point, so a working point set by
  `/spawnpoint`, EssentialsX or Multiverse was replaced with the plot for every player who
  had one, and so was a location another plugin chose for the respawn, such as EssentialsX
  respawn-at-home. Paper 1.20.4 flags every working point as a bed, the plot included, so
  the respawn-time re-check of the plot never ran there. The handler now acts only on a
  respawn headed for the plot's block column, or one whose point failed and fell back to
  world spawn, and leaves a working bed, anchor or forced point and a location another
  plugin set alone on every version.
- **The pre-install log line warns that `/sgen reassign` replaces a bed.** The line that
  recommends reassigning a player from before the install now says that it replaces
  their bed or respawn anchor with the new plot, as `/sgen setspawn` also does. The admin
  guide has a new section on installing onto a server people already play on: writing
  `config.yml` before the first start, choosing an origin away from existing builds and
  claims, and what happens to the players already there. The README quick start said the
  first player gets plot #1; the first plot is #0.
- **SpiralGenesis loads after Multiverse-Core.** Multiverse-Core creates its worlds in its
  own startup, and nothing ordered the two, so an `origin.world` that Multiverse loads could
  still be missing when SpiralGenesis bound it: the log said no spawn would be allocated,
  and the world was only bound on a later join. Multiverse-Core is now a soft dependency,
  and an optional dependency on Hangar and Modrinth. The error for a world that is not
  loaded now says allocation starts once it is, and that a wrong name needs correcting and
  `/sgen reload`, rather than that no spawn will be allocated. The README's respawn line
  and the admin guide's respawn section now name every respawn that outranks the plot, a
  point forced elsewhere and a location another plugin sets included, and the guide and
  the respawn re-check no longer say the plugin has no claim or protection system.
- **Moving the spiral centre or changing `cell-size` no longer puts new plots on top of
  existing ones.** A plot's cell was the configured origin plus its index's grid position
  times the configured cell size, with one running index, so after `/sgen setcenter` or an
  edit to `origin` or `cell-size` the next indices landed in cells other players already
  held. Each geometry is now its own spiral centre, recorded in a `centres` table in
  `data.yml` with its own counter, and every record carries the `centre` its index is on.
  Plots are named `#centre,index` in commands and logs, and `/sgen info` shows the centre's
  origin and cell size. Before a cell is used it is tested against the whole cell of every
  plot on another centre, every point set with `/sgen setspawn`, and every cell another
  scan is still searching; one that overlaps is skipped and logged, and does not count
  toward `max-scan-attempts`. Returning to an earlier geometry resumes its centre's
  counter. A file written by an earlier version loads with all of its plots on centre 0,
  recorded at the `origin` and `cell-size` configured when it is first loaded; a centre
  moved under an earlier alpha is not detected. `current-spiral-index` remains the active
  centre's counter, so an earlier version still loads the file. With an even `cell-size`
  the in-cell search no longer reaches the first column of the neighbouring cell, so every
  candidate stays inside its own cell.

## [1.0.0-alpha.1] - 2026-08-27

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
- The line `/sgen reassign` prints about the claim it leaves behind told an admin to
  re-run the command with `release` "to remove it", and that would not have removed it.
  `release` acts on the spawn the player is leaving at the moment it runs, and `reassign`
  always allocates a fresh plot, so following the advice moved the player on to a third
  plot and released the claim around the second - never the square the message had just
  named, which was left exactly where it was. It cost a second reassignment and deleted
  the one claim nobody had raised. The line now points at the protection plugin's own
  commands, which are what actually reach that square, and explains what `release` does so
  it is decided before a reassignment rather than reached for after one. `setspawn`
  already said the right thing and is unchanged. The behaviour of `release` itself is
  correct and is not touched; nothing outside it can still delete a claim.
- An overlapping claim was logged at `FINE`, below the default level, so a server owner
  had no way at all to learn that a player had silently ended up with no spawn protection:
  not the console, not the command output, not the player. It is now at `INFO`, and names
  the claim in the way and its owner. `FINE` had been chosen on the grounds that the line
  would otherwise be one per joining player, and that is not what the call sites are: a
  claim is asked for when a spawn is created or moved and nowhere else, so the ceiling is
  the ceiling the successful-claim line already sits at. A square refused for being below
  GriefPrevention's minimum stays at `FINE`, because that one is a permanent server-wide
  condition that the startup report already names both numbers for, and repeating it per
  player would only teach an owner to skim the log.
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
