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
   │ • Internal / Staging    │                 │ • Public Testing        │
   │ • GitHub Pre-release    │                 │ • Modrinth/Hangar Beta  │
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
| **Alpha** | `vX.Y.Z-alpha.N` | `main` | Experimental | GitHub Releases (*Pre-release*), CI Artifacts, Modrinth (*alpha*) |
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

### Step 2: Publishing an Alpha / Beta
```bash
# Alpha and beta tags are cut from main, same as GA
git checkout main
git pull origin main

# Tag alpha or beta
git tag -a v1.0.0-beta.1 -m "SpiralGenesis v1.0.0 Beta 1: Feature-complete cross-play testing"

# Push tag to trigger CI
git push origin v1.0.0-beta.1
```

### Step 3: Publishing a Market / GA Release
```bash
# 1. Confirm every PR for this version is merged and CI is green on main
# 2. Checkout main locally
git checkout main
git pull origin main

# 3. Create production tag
git tag -a v1.0.0 -m "SpiralGenesis v1.0.0: Initial Public Release"

# 4. Push tag to trigger automated publication
git push origin v1.0.0
```

### Step 4: Automated CI Actions
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
