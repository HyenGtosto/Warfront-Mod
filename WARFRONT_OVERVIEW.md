# Warfront Mod — Comprehensive Master Overview, Architecture & Design Dossier

**Target Platform:** Minecraft 1.21.1 / NeoForge 21.1.x / GeckoLib 4.7.4
**Document Purpose:** Living architectural blueprint, design philosophy guide, current milestone ledger, and forward roadmap. This document must be consulted by any new agent or developer session before proposing changes.

---

## 1. Core Vision & Design Philosophy

Warfront transforms Minecraft's static, localized mob spawning into a persistent, macro-strategic regional warfare ecosystem.

### Guiding Principles:
1. **Decoupled Layer Architecture:**
   * **Strategic Layer:** 128×128 block strategic regions, cluster generation, frontlines, ownership, domino capture, stability, and resistance.
   * **Tactical / Mission Layer:** 64×64 block subregions, wave encounters, objective tracking, HUD notifications, capture thresholds.
   * **Physical Base Placement Layer:** Decoupled from the mathematical region center. A base anchor is an authoritative block coordinate stored in persistent state.
   * **Entity Combat / Animation Layer:** Custom GeckoLib 4.7.4 models, root-motion/stance state machines, telegraphing animations, and contact-frame damage delays. No combat code directly affects macro territory math.

2. **Deterministic Procedural Generation with Persistent Authority:**
   * Unvisited/unmodified regions evaluate their baseline faction territory, base tier, and base anchor deterministically from `(worldSeed, regionX, regionZ, salt)`.
   * The moment a region is visited, modified, captured, or spawned into, its `RegionState` (including `baseAnchor`) is **authoritative and persisted in NBT**.
   * Downstream systems (e.g. future mission generators, structure spawners, map renderers) must **always** consume the stored `baseAnchor` rather than re-rolling procedural math or guessing the location.

3. **Graceful Degradation Over Destructive Vetoes:**
   * A strategic faction cluster must **never disappear** simply because a local river or mountain makes placing a fortress inconvenient.
   * Regions crossed by winding rivers remain valid faction territory.
   * Base placement attempts the desired tier (Mega Base $\rightarrow$ Headquarters $\rightarrow$ Outpost $\rightarrow$ Compact Outpost $\rightarrow$ NONE). If physical terrain cannot fit a structure, the region cleanly retains ownership with `BaseType.NONE` (territory flag).

4. **Zero-Chunk-Load Server Performance ($O(1)$ Worldgen Queries):**
   * Macro map rendering, fog-of-war calculations, and regional state queries must **never trigger chunk generation or 3D noise density router evaluations** (`getBaseHeight`).
   * Procedural terrain safety relies on $O(1)$ Voronoi multi-noise climate lookups (`level.getBiome`) rather than heavy 384-block density column iterations.

---

## 2. World Partitioning: Strategic Regions & Subregions

The entire Overworld is partitioned into a contiguous mathematical coordinate grid:

```text
               STRATEGIC REGION (128 × 128 Blocks / 8 × 8 Chunks)
    ┌───────────────────────────────┬───────────────────────────────┐
    │                               │                               │
    │        Subregion (0, 0)       │        Subregion (1, 0)       │
    │     64 × 64 Blocks (4×4 Chk)  │     64 × 64 Blocks (4×4 Chk)  │
    │                               │                               │
    ├───────────────────────────────┼───────────────────────────────┤
    │                               │                               │
    │        Subregion (0, 1)       │        Subregion (1, 1)       │
    │     64 × 64 Blocks (4×4 Chk)  │     64 × 64 Blocks (4×4 Chk)  │
    │                               │                               │
    └───────────────────────────────┴───────────────────────────────┘
```

* **Strategic Region ($128 \times 128$ blocks / $8 \times 8$ chunks):**
  * Identified by integer coordinates `(regionX, regionZ)`.
  * Container for: `Faction owner`, `float resistance` (0.0–100.0), `float stability` (0.0–100.0), `BaseType baseType`, `long clusterId`, `BlockPos baseAnchor`, and active `SiegeCampaign`.
* **Subregion ($64 \times 64$ blocks / $4 \times 4$ chunks):**
  * 4 subregions per region: `(0,0)`, `(1,0)`, `(0,1)`, `(1,1)`.
  * The atomic operational unit for missions, patrol squads, wave defense, and frontline border combat. Prevents entire 128×128 zones from triggering as a single unmanageable warzone.

---

## 3. Faction Clusters & Base Placement Architecture

### 3.1. Cluster Hierarchy & Tiers
Clusters generate deterministically using salt-hashed cellular spacing:
* **Mega Base (Size 3):** 15% roll. Requires minimum Manhattan Distance $\le 3$ land domain. Footprint: $6 \times 6$ chunks ($96 \times 96$ blocks).
* **Big Base / Headquarters (Size 2):** 60% roll. Requires MD $\le 2$ land domain. Footprint: $4 \times 4$ chunks ($64 \times 64$ blocks).
* **Small Base / Outpost (Size 1):** 25% roll. Center region must be viable land. Footprint: Standard $4 \times 4$ chunks ($64 \times 64$ blocks) or Compact $2 \times 2$ chunks ($32 \times 32$ blocks).

### 3.2. River & Ocean Filtering Rules
Minecraft 1.18+ river biomes form intricate continental webs. To prevent bad base generation while keeping cluster generation intact, the system enforces a multi-tier filter:
1. **Regional Land Viability (`isLandRegion`):**
   * Samples the 4×4 inner chunks ($chunkX \in [2..5], chunkZ \in [2..5]$).
   * Checks both `isOceanBiome` and `isRiverBiome`.
   * **Rule:** If water/river exceeds 50% ($> 8 / 16$ chunks), the region is rejected as a base center. Normal land regions with a winding river branch ($\le 50\%$) pass safely.
2. **Anchor Center Prohibition:**
   * The physical anchor center block must be solid dry land.
   * **Rule:** If `centerBiome` is `isOceanBiome` or `isRiverBiome` or surface fluid, the candidate offset is immediately rejected.
3. **Footprint Density Grid Validation (`isFootprintSuitable`):**
   * Deterministic grid tailored to base tier:
     * Mega Base ($96 \times 96$): $7 \times 7$ grid (49 sample points, ~14–16 block spacing).
     * Headquarters / Outpost ($64 \times 64$): $5 \times 5$ grid (25 sample points, ~12 block spacing).
     * Compact Outpost ($32 \times 32$): $5 \times 5$ grid (25 sample points, ~6 block spacing).
   * **Rule:** Max 20% water allowed across footprint. Any sample hitting `isOceanBiome` triggers instant rejection. Any sample hitting `isRiverBiome` or surface fluid counts toward the 20% water budget.
4. **Graceful Downgrade Cascade (`resolveBasePlacement`):**
   * If a desired tier cannot fit without drowning, it steps down:
     $$\text{MEGA\_BASE} \longrightarrow \text{HEADQUARTERS} \longrightarrow \text{OUTPOST} \longrightarrow \text{NONE}$$
   * If a region cannot fit an Outpost, it remains claimed by the faction with `BaseType.NONE` and `baseAnchor = null` (a frontier border territory marked by a banner/flag).
5. **Persistence Authority:**
   * The selected anchor is written to `RegionState.baseAnchor()`.
   * Saved into region NBT as `base_anchor_x`, `base_anchor_y`, `base_anchor_z`.
   * Loaded deterministically and preserved across server restarts. Future mission systems target this exact coordinate.

---

## 4. Entity AI, Roster & Friendly Fire Rules

### 4.1. The Factions
* **Humanity (Player):** Expanding defensive alliance.
* **Pillager Conquerors:** Militaristic, disciplined army.
* **Zombie Horde:** Swarm-based organic blight.

### 4.2. Custom Pillager Roster (GeckoLib 4.7.4)
1. **Pillager Warrior (`warfront:pillager_warrior`):**
   * Heavy shock infantry equipped with an iron battleaxe and an authentic sub-hitbox tower shield (`PillagerWarriorShieldPart`).
   * Custom blocking mechanics: Physical shield hitbox absorbs bullets, arrows, and melee attacks. Left forearm guard reaction. Axe hits disable shield for 10 seconds.
   * Delayed impact contact frame (Tick 9) for authentic telegraphing.
2. **Pillager Scout (`warfront:pillager_scout`):**
   * Agile flanker equipped with an iron dagger.
   * High movement speed, evasive retreat leaps when wounded, rapid 2-hit combo slashes (Tick 5 impact frame).
3. **Pillager Marksman (`warfront:pillager_marksman`):**
   * Long-range sniper crossbowman.
   * 3-stage combat state machine: Aiming (laser focus / raised crossbow) $\rightarrow$ Shooting (high-velocity projectile) $\rightarrow$ Reloading (distinct reload animation).
   * Tactical retreat and distancing AI to prevent point-blank crossbow fire.
4. **Pillager Commander (`warfront:pillager_commander`):**
   * Tactical field officer wielding a commanding saber and back-mounted faction standard.
   * Squad leader AI: stays behind frontlines, blows battle horns to rally troops.
5. **Pillager Armored Elite (`warfront:pillager_armored_elite`):**
   * Heavy frontline juggernaut with fortified armor plating and high knockback resistance.

### 4.3. Friendly Fire & Alliance Invariants (`AlliedFactionHelper`)
Hostile mobs within the same faction must **never** attack each other or retaliate against accidental friendly projectile fire.
* **No Mutual Recursion Invariant:**
  * All 5 custom pillager entities override `isAlliedTo(Entity other)` and delegate to `AlliedFactionHelper.isAllied(this, other)`.
  * `AlliedFactionHelper.isAllied(...)` evaluates alliance via:
    1. `isPillager(a) && isPillager(b)`
    2. `a instanceof Zombie && b instanceof Zombie`
    3. Direct Scoreboard Teams: `a.getTeam().isAlliedTo(b.getTeam())` (never calls `Entity.isAlliedTo`)
    4. Persistent NBT `squadId` match
    5. Persistent NBT `faction` ID match
* **Target Cancellation Event Listener:**
  * `LivingChangeTargetEvent` intercepts target assignments.
  * If the target is an ally, it calls `event.setNewAboutToBeSetTarget(null)` and `event.setCanceled(true)`.
  * **Rule:** Never invoke `mob.setTarget(null)` re-entrantly inside the listener to prevent event dispatch stack overflows.

---

## 5. Map Terminal, Networking & Fog of War

### 5.1. Terminal Blocks & Item
* `MapTerminalBlock` and `TestingMapTerminalBlock`.
* Right-clicking opens the interactive strategic map GUI (`RegionMapScreen`).
* Provides live panning, zooming, faction overlays, frontline borders, and active siege vectors.

### 5.2. Fog of War Views (`MapViewType`)
* **`SCOUT` View:** Local player visibility radius (surrounding chunks).
* **`COMMAND` View:** Full campaign mode. Only shows regions previously visited and recorded in `RegionData.isRegionVisited(rx, rz)`.
* **`DEBUG` View:** Global omniscient view showing all procedural regions for testing.

### 5.3. High-Performance Worldgen Rendering ($O(1)$)
* `isSurfaceWaterAt(...)` detects unloaded chunks via fast Voronoi climate lookups (`isOceanBiome || isRiverBiome`) without invoking the Minecraft 3D noise router (`getBaseHeight`).
* Eliminates the 2–3× server TPS freeze when opening terminals or crossing region thresholds.

---

## 6. Current Implementation Ledger (What Is Complete)

- [x] **Strategic Grid Core:** $128 \times 128$ macro regions, $64 \times 64$ tactical subregions, persistent world data storage.
- [x] **Faction Expansion & AI Strategic Engine:** Deterministic cellular cluster generation, BFS territorial connectivity, siege campaigns, target scoring.
- [x] **River Filtering & Placement Decoupling:**
  - River-aware `isLandRegion` ($>50\%$ aquatic threshold for base centers).
  - Dry-land anchor center requirement.
  - Multi-tier dense footprint sampling ($7 \times 7$ Mega, $5 \times 5$ HQ/Outpost, $5 \times 5$ Compact).
  - Automatic downgrade cascade (Mega $\rightarrow$ HQ $\rightarrow$ Outpost $\rightarrow$ NONE).
  - Persistent `baseAnchor` stored in `RegionState` and serialized to NBT.
- [x] **TPS Worldgen Optimization:** Bypassed 3D noise router `getBaseHeight` for unloaded chunks during procedural evaluation.
- [x] **Pillager GeckoLib Combat Roster:** Complete models, textures, animations, and combat AI for Warrior, Scout, Marksman, Commander, and Armored Elite.
- [x] **Tactical Tower Shield Sub-Entity:** Custom multipart collision hitbox with projectile absorption, crit particle feedback, and axe stun vulnerability.
- [x] **Allied Anti-Friendly-Fire System:** Centralized `AlliedFactionHelper`, non-recursive team/tag verification, target-change interception.
- [x] **Interactive Strategic Map GUI:** Zoom/pan viewport, faction color blending, siege arrow rendering, Fog of War pipeline.
- [x] **Frontline Siege & Roamer Spawners:** Dynamic frontline marching lines (Types 1–4), $8 \times 8$ defensive hold-ground formations, squad leader assignment.

---

## 7. In Progress (Active Focus)

- [ ] **Base Structure Placement & Spawner Pipeline:**
  - Generating actual physical NBT structure templates at the stored `baseAnchor` coordinates.
  - Ensuring physical buildings align properly with terrain height without floating or suffocating in hills.
- [ ] **Mandatory Base Missions:**
  - Creating a specialized mission type linked directly to `baseAnchor`.
  - Capturing a region that possesses a physical base must require destroying/capturing that specific fortress regardless of domino or frontier thresholds.

---

## 8. Forward Roadmap & Planned Milestones

### 8.1. Regenerating / Indestructible Base Buildings (Grief Prevention)
* **Problem:** Players could use explosives, flint and steel, or mining to obliterate enemy fortresses before starting a mission.
* **Planned Solution:** 
  - Dynamic structure protection or snapshot-based state regeneration.
  - When a base mission initiates, structures reset to their pristine blueprint state, or fortress blocks gain temporary blast/break immunity outside active capture windows.

### 8.2. Zombie Horde Custom GeckoLib Roster
* Replacing vanilla placeholder zombies with complete custom GeckoLib 4.7.4 models and animations:
  1. **Fodder:** Fast, low-health shambler.
  2. **Chaser:** Quadrupedal sprinting stalker.
  3. **Spewer:** Acid/bile ranged mortar.
  4. **Tank:** Massive brute capable of smashing through player defenses.
  5. **Hivemind Controller:** Backline summoner that buffs surrounding undead.

### 8.3. Catapult / Stationary Artillery Units
* Heavy siege engines spawned during Mega Base defense sieges to shell attacking enemies from distance.

---

## 9. Developer Rules for Future Iterations

1. **Always Check Stored State First:** Never re-derive or guess a base location or region owner if `RegionData` already has it stored.
2. **Never Re-Introduce Region-Wide Bans on Rivers:** Keep river checks localized to footprint viability and base-center aquatic ratio ($\le 50\%$).
3. **Keep `AlliedFactionHelper` Free of Recursion:** Never call `livingA.isAlliedTo(livingB)` inside `AlliedFactionHelper.isAllied`. Check tags, teams, squads, and IDs directly.
4. **Preserve GeckoLib Performance:** Do not run client-side animation state logic on the server thread.
5-1. **Document Every Milestone:** Update this file at the conclusion of every major feature or refactor.
5-2. **Document Latest Changes:** Create a walkthrough of what code and logic was changed in a file.