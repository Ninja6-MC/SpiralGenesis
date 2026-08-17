# SpiralGenesis 🌀

[![CI](https://github.com/Ninja6-MC/SpiralGenesis/actions/workflows/ci.yml/badge.svg)](https://github.com/Ninja6-MC/SpiralGenesis/actions/workflows/ci.yml)
[![Release](https://github.com/Ninja6-MC/SpiralGenesis/actions/workflows/release.yml/badge.svg)](https://github.com/Ninja6-MC/SpiralGenesis/actions/workflows/release.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html)
[![PaperMC](https://img.shields.io/badge/PaperMC-1.20%2B-green.svg)](https://papermc.io)

**SpiralGenesis** is a modern, high-performance distributed spawn and private territory engine for PaperMC, Purpur, and hybrid cross-play Minecraft servers.

Instead of confining all players to a single crowded world spawn, SpiralGenesis distributes incoming players in an expanding clockwise **square spiral grid** ($N \times N$ block plots). Each player receives an isolated, safe, permanent genesis plot while retaining individual respawn points.

---

## 🌟 Key Features

* **Deterministic Square Spiral Distribution**: Maps player index $k \in [0, \infty)$ deterministically to 2D grid coordinates $(u, v)$ with offset $N$.
* **Asynchronous Ocean & Hazard Avoidance**: Asynchronously scans chunks and heightmaps before allocation; automatically rejects oceans, deep water, lava, ice, and hazardous biomes, advancing the spiral until dry safe land is reached.
* **Cross-Play Authentication Parity**:
  * **Bedrock Edition (Geyser / Floodgate)**: Seamless automatic spawn allocation and teleportation on `PlayerJoinEvent`.
  * **Java Edition (AuthMe-Reloaded)**: Teleportation is strictly gated until `/login` or `/register` is executed (preventing unauthenticated chunk loading / exploits).
* **Per-Player Respawn Override**: Automatically enforces individual genesis coordinates on death respawns if no active bed or respawn anchor is present.
* **Full Administrative Command Suite**: Powerful `/sgen` CLI to set origin coordinates, inspect player plots, manually relocate players, or reassign plots.
* **Tick-Friendly Allocation**: Chunks are loaded through Paper's asynchronous API and each candidate cell is probed on its own tick, so terrain scanning never stalls the server. Storage writes are coalesced and flushed off the main thread.

---

## 📐 Mathematical Specification

Given global origin $(X_0, Z_0)$ and plot dimension $N$ (default $500$ blocks):

$$X = X_0 + u \cdot N$$
$$Z = Z_0 + v \cdot N$$

The sequence progresses in expanding clockwise rings ($1, 1, 2, 2, 3, 3, 4, 4 \dots$):

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

---

## 📦 Installation & Setup

**Requirements:** PaperMC (or a fork such as Purpur) and Java 21. Built against the
1.20.4 API; verify on your exact server version before deploying to production.

1. Download the latest release from [GitHub Releases](https://github.com/Ninja6-MC/SpiralGenesis/releases), [Modrinth](https://modrinth.com/plugin/spiralgenesis), or [Paper Hangar](https://hangar.papermc.io/Ninja6-MC/SpiralGenesis).
2. Drop `SpiralGenesis-x.y.z.jar` into your server's `plugins/` directory.
3. (Optional) Install **Floodgate** for Bedrock cross-play and/or **AuthMe-Reloaded** for Java authentication.
4. Restart your server to generate `plugins/SpiralGenesis/config.yml`.
5. Run `/sgen setcenter` in-game to set the center of your spiral grid, or configure coordinates in `config.yml`.

---

## ⚙️ Configuration (`config.yml`)

```yaml
# Global origin for spiral calculation
origin:
  world: "world"
  x: 0
  z: 0

# Dimensions of each player's territory in blocks (N x N)
# Clamped to 50-100000.
cell-size: 500

# Safety probing rules
safety:
  # Minimum surface Y for a cell to be accepted. Clamped to -64..320.
  min-surface-y: 63
  # Candidate cells probed before falling back to a best-effort surface
  # location. One probe per tick. Clamped to 1-500.
  max-scan-attempts: 50
```

> The live spiral progression index is persisted in `data.yml`
> (`current-spiral-index`), not in `config.yml`.

---

## 💻 Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/sgen setcenter` | `spiralgenesis.admin` | Sets the global origin $(X_0, Z_0)$ to your current position. |
| `/sgen setcenter <x> <z>` | `spiralgenesis.admin` | Sets explicit coordinates for the global origin. |
| `/sgen setspawn <player>` | `spiralgenesis.admin` | Sets the target player's spawn to your current location. |
| `/sgen setspawn <player> <x> <y> <z>` | `spiralgenesis.admin` | Explicitly sets coordinates for target player. |
| `/sgen reassign <player>` | `spiralgenesis.admin` | Clears target player's spawn and allocates a fresh safe spiral plot. |
| `/sgen tp <player>` | `spiralgenesis.admin` | Teleports you to the player's assigned genesis coordinates. |
| `/sgen info <player>` | `spiralgenesis.admin` | Displays player's index $k$, grid cell $(u, v)$, and world coordinates. |
| `/sgen reload` | `spiralgenesis.admin` | Reloads `config.yml` and refreshes storage. |

---

## 🛠️ Building from Source

```bash
# Clone the repository
git clone https://github.com/Ninja6-MC/SpiralGenesis.git
cd SpiralGenesis

# Run test suite
./gradlew test

# Build production shadow JAR
./gradlew build
```

Compiled JAR will be in `build/libs/SpiralGenesis-<version>.jar`.

---

## 🚀 Release Process & Contribution

* For release flows (Alpha, Beta, and Market GA), read [RELEASE_PROCESS.md](RELEASE_PROCESS.md).
* For development setup, branching rules, and PR guidelines, read [CONTRIBUTING.md](CONTRIBUTING.md).
* Security vulnerabilities should be reported following [SECURITY.md](SECURITY.md).

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

---

## 🙏 Acknowledgements

Inspired by [Block4Block / DynamicSpawnPlugin](https://github.com/Block4Block/DynamicSpawnPlugin),
which pioneered spiral-pattern spawn distribution for Paper servers. SpiralGenesis is an
independent implementation.
