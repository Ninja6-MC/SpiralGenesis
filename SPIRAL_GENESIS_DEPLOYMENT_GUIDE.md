# SpiralGenesis Plugin Architecture & Deployment Guide

This document defines the mathematical specification, runtime architecture, configuration, and implementation details for **SpiralGenesis** — the per-player **Square Spiral ($N \times N$) Distributed Spawn & Territory Engine** on the PaperMC + Geyser/Floodgate + AuthMe hybrid server.

---

## 1. Upstream References & Source Credits

| Resource | Link | Description |
| :--- | :--- | :--- |
| **Plugin Name** | **SpiralGenesis** (`com.ninja6.spiralgenesis`) | Custom per-player spiral distribution & genesis engine. |
| **Upstream Base** | [Block4Block / DynamicSpawnPlugin (GitHub)](https://github.com/Block4Block/DynamicSpawnPlugin) | Open-source base reference for spiral & square spawn mechanics. |
| **Upstream Modrinth** | [DynamicSpawnPlugin (Modrinth)](https://modrinth.com/plugin/dynamicspawnplugin) | Upstream Modrinth release page and plugin documentation. |

### Architectural Extensions in SpiralGenesis
While upstream `DynamicSpawnPlugin` alters the *global world spawn* across a single point for the entire server, **SpiralGenesis** implements:
1. **Per-Player UUID Registry**: Unique, permanent default spawn allocated per player rather than shifting the single global world spawn.
2. **Ocean & Hazard Filtering**: Automatic asynchronous chunk and heightmap probing to reject water/ocean cells and advance the spiral to valid dry land.
3. **Cross-Play Authentication Parity**: Specialized hooks for Bedrock (Floodgate instant auth) and Java (AuthMe `/login` and `/register` completion).

---

## 2. System Overview & Core Objectives

### 2.1 Objective
Distribute incoming players across the world in an expanding clockwise **square spiral grid** where each player receives a unique, private territory of size $N \times N$ blocks. If an assigned grid cell falls on water, ocean biomes, or hazardous terrain, the engine automatically skips the cell and advances to the next spiral coordinate until safe dry land is secured.

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

### 2.2 Key Capabilities
1. **Deterministic Grid Expansion**: Spiral index $k \in [0, \infty)$ deterministically maps to grid coordinates $(u, v)$.
2. **Dynamic Ocean & Hazard Avoidance**: Asynchronous chunk and heightmap probing rejects water/ocean cells and advances to the next index.
3. **Cross-Play Authentication Parity**:
   * **Bedrock (Floodgate)**: Automatic spawn assignment and teleportation on `PlayerJoinEvent`.
   * **Java (AuthMe-Reloaded)**: Spawn assignment and teleportation triggers strictly on `LoginEvent` / `RegisterEvent` (preventing unauthenticated chunk loading).
4. **Per-Player Persistence & Respawn Override**: Sets `player.setRespawnLocation(loc, true)` and hooks into `PlayerSpawnLocationEvent` and `PlayerRespawnEvent`.
5. **Administrative Controls**: Full `/sgen` command suite for overriding global origin, setting individual player spawns, reassigning players, and inspecting assignments.

---

## 3. Mathematical Specification

### 3.1 Coordinate Transformation Formulas

Let:
* $(X_0, Z_0)$ be the global spiral center / origin.
* $N$ be the cell dimension (e.g. $500$ blocks).
* $(u, v) \in \mathbb{Z}^2$ be the discrete grid cell index.

The world coordinates $(X, Z)$ representing the center of cell $(u, v)$ are given by:
$$X = X_0 + u \cdot N$$
$$Z = Z_0 + v \cdot N$$

### 3.2 Spiral Sequence Progression

The progression steps follow clockwise movement with segment lengths increasing every two turns:
$$\text{Lengths: } 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, \dots$$
$$\text{Directions: } \text{East } (+u) \to \text{South } (+v) \to \text{West } (-u) \to \text{North } (-v)$$

| Player / Step Index ($k$) | Grid Cell $(u, v)$ | Direction Moved | World Offset from Origin ($N=500$) |
| :--- | :--- | :--- | :--- |
| **0 (Player 1)** | $(0, 0)$ | Origin | $(0, 0)$ |
| **1 (Player 2)** | $(1, 0)$ | Right (East) | $(+500, 0)$ |
| **2 (Player 3)** | $(1, 1)$ | Down (South) | $(+500, +500)$ |
| **3 (Player 4)** | $(0, 1)$ | Left (West) | $(0, +500)$ |
| **4 (Player 5)** | $(-1, 1)$ | Left (West) | $(-500, +500)$ |
| **5 (Player 6)** | $(-1, 0)$ | Up (North) | $(-500, 0)$ |
| **6 (Player 7)** | $(-1, -1)$ | Up (North) | $(-500, -500)$ |
| **7 (Player 8)** | $(0, -1)$ | Right (East) | $(0, -500)$ |
| **8 (Player 9)** | $(1, -1)$ | Right (East) | $(+500, -500)$ |
| **9 (Player 10)** | $(2, -1)$ | Right (East) | $(+1000, -500)$ |
| **10 (Player 11)** | $(2, 0)$ | Down (South) | $(+1000, 0)$ |

---

## 4. Terrain Validation & Water-Filtering Engine

To prevent spawning players in oceans, underwater, or inside lava:

```
                            [ Candidate Coordinate (X, Z) ]
                                           │
                                           ▼
                           [ world.getChunkAtAsync(X>>4, Z>>4) ]
                                           │
                                           ▼
                   [ Get Surface Y: MOTION_BLOCKING_NO_LEAVES ]
                                           │
                                           ▼
                   ┌───────────────────────────────────────────────┐
                   │               Validation Checks:              │
                   │  1. Surface Y <= SeaLevel (62)                │
                   │  2. Block is WATER / LAVA / ICE / KELP        │
                   │  3. Biome is OCEAN / DEEP_OCEAN / FROZEN      │
                   └───────────────────────┬───────────────────────┘
                                           │
                           ┌───────────────┴───────────────┐
                          FAIL                            PASS
                           │                               │
                           ▼                               ▼
                 [ Increment Index k++ ]         [ Save & Assign Location ]
                 [ Recurse Next Cell ]           [ Teleport Player to (X, Y+1, Z) ]
```

### Biome and Block Blacklists
* **Blocked Biomes**: `OCEAN`, `DEEP_OCEAN`, `WARM_OCEAN`, `LUKEWARM_OCEAN`, `DEEP_LUKEWARM_OCEAN`, `COLD_OCEAN`, `DEEP_COLD_OCEAN`, `FROZEN_OCEAN`, `DEEP_FROZEN_OCEAN`.
* **Blocked Surface Materials**: `WATER`, `LAVA`, `ICE`, `PACKED_ICE`, `BLUE_ICE`, `SEAGRASS`, `TALL_SEAGRASS`, `KELP`, `KELP_PLANT`.
* **Minimum Surface Elevation**: $Y \ge 63$ (above standard sea level).

---

## 5. Lifecycle Hooks & Cross-Play Architecture

### 5.1 Bedrock Edition Flow (Geyser + Floodgate)
1. Bedrock client joins via UDP Playit tunnel $\to$ Geyser translator.
2. Floodgate authenticates the Xbox Live gamertag automatically (UUID format `00000000-0000-0000-0009-...`).
3. `PlayerJoinEvent` fires:
   * System checks if UUID exists in `plugins/SpiralGenesis/data.yml`.
   * If missing $\to$ execute spiral allocation $\to$ teleport to assigned coordinate.

### 5.2 Java Edition Flow (AuthMe-Reloaded)
1. Java client connects to TCP port 25565.
2. AuthMe holds player at AuthMe spawn lobby in limbo mode.
3. Player executes `/register <password> <password>` or `/login <password>`.
4. AuthMe fires `fr.xephi.authme.events.RegisterEvent` or `LoginEvent`:
   * System checks if UUID exists in `plugins/SpiralGenesis/data.yml`.
   * If missing $\to$ execute spiral allocation $\to$ save coordinates $\to$ teleport player.
   * If already registered $\to$ ensure player respawn point is restored.

---

## 6. Per-Player Data Persistence & Storage Schema

Data is persisted in `plugins/SpiralGenesis/data.yml` (or SQLite database `spiralgenesis.db`):

```yaml
# Global Configuration (config.yml)
origin:
  world: "world"
  x: 0
  z: 0
cell-size: 500
current-spiral-index: 12

# Per-Player Registry (Keyed by permanent Player UUID in data.yml)
players:
  # Bedrock Player (Floodgate)
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

  # Java Player (AuthMe)
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

---

## 7. Administrative Command Interface (`/sgen` or `/spiralgenesis`)

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/sgen setcenter` | `spiralgenesis.admin` | Sets the global spiral center $(X_0, Z_0)$ to the admin's current location. |
| `/sgen setcenter <x> <z>` | `spiralgenesis.admin` | Overrides the global spiral center to explicit coordinates $(X, Z)$. |
| `/sgen setspawn <player>` | `spiralgenesis.admin` | Overrides the target player's personal spawn to admin's current position. |
| `/sgen setspawn <player> <x> <y> <z>` | `spiralgenesis.admin` | Manually assigns exact coordinates $(X, Y, Z)$ to a player. |
| `/sgen reassign <player>` | `spiralgenesis.admin` | Clears player's previous location, advances index, and assigns a fresh spiral plot. |
| `/sgen tp <player>` | `spiralgenesis.admin` | Teleports admin to the target player's assigned genesis spawn. |
| `/sgen info <player>` | `spiralgenesis.admin` | Displays player's assigned coordinates, index $k$, and $(u, v)$ grid cell. |
| `/sgen reload` | `spiralgenesis.admin` | Reloads `config.yml` and refreshes storage. |

---

## 8. Production Plugin Source Code Reference

### 8.1 `SpiralMath.java`
```java
package com.ninja6.spiralgenesis.math;

public class SpiralMath {
    /**
     * Converts a 1D sequence index k (0, 1, 2, ...) into 2D grid coordinates (u, v).
     * Follows clockwise square spiral: (0,0) -> (1,0) -> (1,1) -> (0,1) -> (-1,1) ...
     */
    public static int[] indexToGrid(int index) {
        if (index <= 0) return new int[]{0, 0};

        int u = 0, v = 0;
        int du = 1, dv = 0;
        int segmentLength = 1;
        int segmentPassed = 0;

        for (int i = 0; i < index; i++) {
            u += du;
            v += dv;
            segmentPassed++;

            if (segmentPassed == segmentLength) {
                segmentPassed = 0;
                // Rotate 90 degrees clockwise (East -> South -> West -> North)
                int temp = du;
                du = -dv;
                dv = temp;
                if (dv != 0) {
                    segmentLength++;
                }
            }
        }
        return new int[]{u, v};
    }
}
```

### 8.2 `SpawnManager.java`
```java
package com.ninja6.spiralgenesis.manager;

import com.ninja6.spiralgenesis.math.SpiralMath;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class SpawnManager {
    private final JavaPlugin plugin;
    private final World world;
    private final int originX;
    private final int originZ;
    private final int cellSize;

    private static final Set<Material> HAZARD_MATERIALS = EnumSet.of(
            Material.WATER, Material.LAVA, Material.ICE, Material.PACKED_ICE,
            Material.BLUE_ICE, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT
    );

    private static final Set<Biome> OCEAN_BIOMES = EnumSet.of(
            Biome.OCEAN, Biome.DEEP_OCEAN, Biome.WARM_OCEAN, Biome.LUKEWARM_OCEAN,
            Biome.DEEP_LUKEWARM_OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN,
            Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN
    );

    public SpawnManager(JavaPlugin plugin, World world, int originX, int originZ, int cellSize) {
        this.plugin = plugin;
        this.world = world;
        this.originX = originX;
        this.originZ = originZ;
        this.cellSize = cellSize;
    }

    public CompletableFuture<LocationResult> allocateNextSafeSpawn(int startIndex) {
        return findSafeRecursive(startIndex);
    }

    private CompletableFuture<LocationResult> findSafeRecursive(int index) {
        int[] grid = SpiralMath.indexToGrid(index);
        int targetX = originX + (grid[0] * cellSize);
        int targetZ = originZ + (grid[1] * cellSize);

        // Async chunk loading using Paper API
        return world.getChunkAtAsync(targetX >> 4, targetZ >> 4).thenCompose(chunk -> {
            Biome biome = world.getBiome(targetX, 64, targetZ);
            if (OCEAN_BIOMES.contains(biome)) {
                // Reject ocean biome, advance index
                return findSafeRecursive(index + 1);
            }

            int surfaceY = world.getHighestBlockYAt(targetX, targetZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block block = world.getBlockAt(targetX, surfaceY, targetZ);

            if (surfaceY <= 62 || HAZARD_MATERIALS.contains(block.getType())) {
                // Reject water/hazard, advance index
                return findSafeRecursive(index + 1);
            }

            // Safe dry land location verified
            Location loc = new Location(world, targetX + 0.5, surfaceY + 1.0, targetZ + 0.5);
            return CompletableFuture.completedFuture(new LocationResult(loc, index, grid[0], grid[1]));
        });
    }

    public record LocationResult(Location location, int index, int gridU, int gridV) {}
}
```

### 8.3 `PlayerSpawnListener.java`
```java
package com.ninja6.spiralgenesis.listeners;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.geysermc.floodgate.api.FloodgateApi;

public class PlayerSpawnListener implements Listener {
    private final SpiralGenesisPlugin plugin;

    public PlayerSpawnListener(SpiralGenesisPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Only auto-allocate on join for Bedrock players (Floodgate)
        // Java players wait for AuthMe Login/Register event
        boolean isFloodgate = plugin.isFloodgateInstalled() && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());

        if (isFloodgate) {
            plugin.handlePlayerFirstJoin(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        
        // If player has no bed or anchor, enforce their spiral plot spawn
        if (!event.isBedSpawn() && !event.isAnchorSpawn()) {
            if (plugin.getDataStorage().hasSpawn(player.getUniqueId())) {
                event.setRespawnLocation(plugin.getDataStorage().getSpawn(player.getUniqueId()));
            }
        }
    }
}
```

### 8.4 `AuthMeHookListener.java`
```java
package com.ninja6.spiralgenesis.listeners;

import com.ninja6.spiralgenesis.SpiralGenesisPlugin;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RegisterEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class AuthMeHookListener implements Listener {
    private final SpiralGenesisPlugin plugin;

    public AuthMeHookListener(SpiralGenesisPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAuthMeRegister(RegisterEvent event) {
        Player player = event.getPlayer();
        plugin.handlePlayerFirstJoin(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAuthMeLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getDataStorage().hasSpawn(player.getUniqueId())) {
            plugin.handlePlayerFirstJoin(player);
        }
    }
}
```

---

## 9. Sizing & World Generation Guidelines

| Expected Player Capacity | Cell Size ($N$) | Minimum World Radius | Chunky Pregen Command |
| :--- | :--- | :--- | :--- |
| **20 Players** | $500 \times 500$ | $2,000$ blocks | `chunky radius 2000` |
| **50 Players** | $500 \times 500$ | $3,000$ blocks | `chunky radius 3000` |
| **100 Players** | $500 \times 500$ | $4,500$ blocks | `chunky radius 4500` |
| **50 Players** | $1,000 \times 1,000$ | $6,000$ blocks | `chunky radius 6000` |

---

## 10. Operational Testing Matrix

| Test Case | Procedure | Expected Result |
| :--- | :--- | :--- |
| **1. Sequential Grid Allocation** | Join with 4 mock accounts in sequence. | Players land at $(0,0)$, $(N,0)$, $(N,N)$, $(0,N)$ offsets. |
| **2. Ocean / Water Skipping** | Target cell center lands in Ocean / Deep Ocean. | Scanner skips cell, increments index, and allocates adjacent land plot. |
| **3. Bedrock First Join** | Connect via Bedrock client (Geyser/Floodgate). | Automatically allocated and teleported on join. |
| **4. Java AuthMe Isolation** | Connect via Java client. | Spawn allocated only **after** `/register` or `/login`. |
| **5. Death & Respawn** | Execute `/kill` without setting a bed. | Respawns at assigned genesis coordinates instead of world spawn $(0, 64, 0)$. |
| **6. Admin Override** | Execute `/sgen setspawn <player> 1200 70 -400`. | Player's spawn and respawn point are updated immediately. |
