# SpiralGenesis Release Lifecycle & Publishing Guide

This document defines the release policies, branch strategy, versioning rules, and multi-platform publishing procedures for **SpiralGenesis**.

---

## 1. Versioning Rules (SemVer 2.0.0)

Every release follows `MAJOR.MINOR.PATCH[-PRERELEASE]`:
* **MAJOR (`X.0.0`)**: Incompatible API breaks, architectural redesigns, or database schema breaks.
* **MINOR (`1.X.0`)**: New features (e.g. SQLite engine, new biome filter modes, multi-world spirals).
* **PATCH (`1.0.X`)**: Bug fixes and performance patches.
* **Pre-releases**:
  * `v1.0.0-alpha.1` (Internal experimental builds)
  * `v1.0.0-beta.1` (Public feature-complete testing builds)
  * `v1.0.0-rc.1` (Release candidate)

---

## 2. Release Tiers & Distribution Channels

Every tier is cut by tagging a commit on `main`. The tag decides the tier, not the branch —
there is no separate release branch.

```
                    [ feat/… fix/… docs/… topic branches ]
                                      │
                                      ▼
                            [ PR squashed into main ]
                                      │
                ┌─────────────────────┴─────────────────────┐
                ▼                                           ▼
   ┌─────────────────────────┐                 ┌─────────────────────────┐
   │   ALPHA (vX.Y.Z-alpha)  │                 │    BETA (vX.Y.Z-beta)   │
   │ • Experimental          │                 │ • Feature-Complete      │
   │ • GitHub Pre-release    │                 │ • Public Testing        │
   │ • Modrinth/Hangar Alpha │                 │ • Modrinth/Hangar Beta  │
   └────────────┬────────────┘                 └────────────┬────────────┘
                │                                           │
                └─────────────────────┬─────────────────────┘
                                      ▼
                         ┌─────────────────────────┐
                         │   MARKET / GA (vX.Y.Z)  │
                         │ • Production Stable     │
                         │ • GitHub Latest Release │
                         │ • Modrinth Featured     │
                         │ • Hangar Release        │
                         │ • SpigotMC Resource     │
                         └─────────────────────────┘
```

| Tier | Git Tag Pattern | Source Branch | Stability Level | Published Channels |
| :--- | :--- | :--- | :--- | :--- |
| **Alpha** | `vX.Y.Z-alpha.N` | `main` | Experimental | GitHub Releases (*Pre-release*), CI Artifacts, Modrinth (*alpha*), Paper Hangar (*Alpha*) |
| **Beta / RC** | `vX.Y.Z-beta.N` | `main` | Feature-Complete | GitHub Releases (*Pre-release*), Modrinth (*beta*), Paper Hangar (*Beta*) |
| **Market (GA)** | `vX.Y.Z` | `main` | Production Stable | GitHub Releases (*Latest*), Modrinth (*Featured*), Paper Hangar (*Release*), SpigotMC |

---

## 3. How to Execute a Release

### Step 1: Pre-Release Checklist
1. All target PRs merged into `main`, with CI green on the merge commit.
2. Run test suite locally:
   ```bash
   ./gradlew test
   ```
3. Update `CHANGELOG.md` under the target version header.

### Step 2: Cut the Tag

Every tier is cut the same way, with `scripts/release.sh`. Pass the version without the
leading `v`:

```bash
scripts/release.sh 1.0.0-beta.1
```

```bash
scripts/release.sh 1.0.0
```

The script refuses to tag unless all of the following hold, then prints the tag, the
commit, the channel and the release notes and asks you to type the tag to confirm:

| Check | Why |
| :--- | :--- |
| Version matches the tag grammar | Same rule CI enforces, applied before the tag is public. |
| Not a linked worktree | Worktrees sit on their own branches at older commits. This is how a tag ends up pointing several merges behind `main`. |
| On `main` | Releases are cut from the trunk. |
| Working tree clean | A tag names a commit; uncommitted work is not in the release. |
| Local `main` equals `origin/main` | Behind means the release misses merged work; ahead means it contains unreviewed commits. |
| Tag unused locally and on origin | Re-tagging is how a published version ends up pointing at different code than its tag. |
| Changelog section exists | Stable releases only. Pre-releases may ship without one. |

Add `--dry-run` to run every check and print the summary without tagging or pushing:

```bash
scripts/release.sh 1.0.0 --dry-run
```

On Windows, `scripts\release.ps1` takes the same arguments and runs the same script
through Git Bash, so PowerShell works without opening a second shell:

```powershell
scripts\release.ps1 1.0.0 --dry-run
```

The checks live in `release.sh` only. The wrapper locates Git Bash and hands over, so
there is one implementation rather than two that can drift apart.

Tagging by hand still works and CI still gates it, but the failures then happen after the
tag is public, when undoing them means deleting a tag that two registries have already
seen.

### Step 3: Automated CI Actions
GitHub Actions (`.github/workflows/release.yml`) will:
1. Reject the tag unless it matches `vMAJOR.MINOR.PATCH` with an optional `-alpha.N`,
   `-beta.N` or `-rc.N` suffix. The tag decides the tier, so it has to be exact.
2. Require a `## [MAJOR.MINOR.PATCH]` section in `CHANGELOG.md` for a stable release, and
   use it as the release notes. Pre-releases may ship without one.
3. Compile with Java 21 and run JUnit 5 tests.
4. Build optimized `SpiralGenesis-<version>.jar`.
5. Compute SHA-256 checksums (`SpiralGenesis-<version>.jar.sha256`).
6. Publish release notes and JARs to GitHub Releases.
7. Publish to Modrinth, then to Paper Hangar.

### Repository Secrets

| Secret | Used by | Publishing is skipped if absent |
| :--- | :--- | :--- |
| `MODRINTH_TOKEN` | Modrinth step (`mc-publish`) | Yes |
| `HANGAR_API_TOKEN` | Hangar step (`publishPluginPublicationToHangar`) | Yes |

Neither is required for a release to succeed. Without them the workflow still tests,
builds and publishes to GitHub Releases, and simply skips the registry it has no token
for, which is what makes it safe to cut a tag before both registry projects exist.

### Registry Settings That Live Outside This Repository

Some values cannot be set from CI and must already be correct on the registry side:

* **Hangar channels.** `Alpha`, `Beta` and `Release` must exist on the Hangar project.
  The workflow selects one by name; it cannot create them.
* **Modrinth project ID.** Hard-coded as `spiralgenesis` in the workflow. If the slug
  changes, the workflow changes with it.
* **Supported Minecraft versions.** Declared in two places that must stay in step: the
  `game-versions` list in the workflow (Modrinth) and the `hangarPlatformVersions`
  property in `build.gradle.kts` (Hangar). Both must contain versions the registry
  recognises, or the publish is rejected. Widen them when a new Minecraft release is
  supported.
