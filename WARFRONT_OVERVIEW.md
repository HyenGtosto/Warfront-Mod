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

5. **Native NBT Structure Templates Over Procedural Scripts (Strict Mandate):**
   * **Core Rule:** All full buildings, fortresses, castles, outposts, headquarters, and megabases MUST be created and loaded as native Minecraft Structure Templates (`.nbt` files) located under `src/main/resources/data/warfront/structure/...`.
   * **Hard Restriction on Procedural Scripts:** Hardcoded Java code that generates buildings block-by-block is **strictly forbidden** for architecture.
   * Procedural script block placement is strictly limited to:
     * Very small decorative or ambient elements (e.g. ambient rubble, small fire pits, banner flags).
     * Dynamic stepped foundation columns anchoring structures into uneven hillsides and slopes.
     * Dynamic air clearing passes that carve away clipping terrain and tree foliage within the build volume.

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

### 3.3. Structure Data Pipeline: Native NBT Templates (.nbt)
All structures are built and saved as native Minecraft Structure Templates (`.nbt` files) rather than procedural code scripts:
1. **Resource Location:** Bundled inside `src/main/resources/data/warfront/structure/<path>.nbt` (e.g. `base/outpost/pillager_outpost.nbt`).
2. **Template Placement Engine (`TemplatePremadeStructure`):**
   * Loads via vanilla `StructureTemplateManager.get(ResourceLocation)`.
   * Performs a 3D air-clearing pass within the bounding box ($sizeX \times sizeY \times sizeZ$) up to $Y+4$ above the roof to carve away clipping terrain, hills, and tree foliage.
   * Dynamically constructs stepped cobblestone foundation columns downwards beneath the perimeter down to solid ground so buildings never float on slopes.
   * Invokes `StructureTemplate.placeInWorld()` with full block palette fidelity.
3. **Workflow for Creating Structures:**
   * **In-Game Export Command:** `/warfront base export [name]` automatically captures the current base into `<world>/generated/warfront/structures/<name>.nbt`.
   * **Structure Block Helper:** `/warfront base structure-block` places a pre-configured vanilla Structure Block in `SAVE` mode with exact dimensions and bounding box ready for editing.
   * **Rule:** Never write full building generators in Java script. Hardcoded script placement is strictly restricted to tiny ambient/decorative dressing.

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

### 5.4. Tactical War Visualization Architecture
* **Subregion-Scoped Active Mission Mask:** Red combat mask and red sector borders only apply to $64 \times 64$ subregions with active, uncompleted missions (`ActiveCampaignMissionManager.hasActiveMission`). Won subregions dynamically render in Humanity blue, and unoccupied enemy sectors remain in normal faction/biome colors.
* **Pulsating Frontline Borders:** Continuous sine-wave breathing crimson perimeter with outer glow rendered around all $8 \times 8$ regions currently under war/siege.
* **Central War Status Badges:** Floating transparent PNG emblems (`war_attack.png` crossed swords for attacks, `war_defense.png` fortified shield for defenses) rendered in the center of active war regions.
* **Smart Mission Inspection Visibility:** Central war status badges automatically vanish when inspecting or selecting missions (upon clicking `LAUNCH ATTACK` or during defense) to avoid obscuring subregion buttons or mission icons, reappearing when the region is deselected or when browsing the map.
* **Synchronized War Network Payloads:** `ActiveWarData(regionX, regionZ, attackerFactionId, isDefense)` serialized in `RegionMapPayload` with `WARS_CODEC` ensuring cross-client awareness of active war theaters even between sorties.

### 5.5. Map Rendering Performance Architecture & High-Zoom Optimization
* **Hardware-Accelerated Arrowheads & Shafts (`RenderType.gui()`):**
  - Eliminated legacy CPU software rasterization (`fillTriangle`, `drawThickLine`, `drawSingleLine`) that performed pixel-by-pixel bounding-box loops and pushed up to 25,000 vertices per arrowhead per frame.
  - Replaced with hardware-accelerated vertex quads: arrow shaft rendered as 1 single quad (4 vertices), arrowhead fill as non-degenerate solid quads spanning both halves of the triangle via the base midpoint (dual-winding to ensure visibility across all GPU culling modes), and perimeter outline as 3 thin quads (12 vertices). Total: **24 vertices per arrow**, achieving a **>1,000× speedup** with a crisp solid crimson fill.
* **Tactical Arrowhead Scaling Cap:**
  - Clamped arrowhead dimensions (`Math.clamp(1.8 * tileSize, 8.0, 22.0)` length, `Math.clamp(1.0 * tileSize, 5.0, 13.0)` width) to prevent arrowheads from ballooning to 100+ pixels when zoomed in, preserving crisp military UI aesthetics and zero screen clutter.
* **Strict Viewport Frustum Culling:**
  - Early-out axis-aligned bounding box intersection tests applied to siege arrows, frontline borders, and war status badges before calculating vertex transformations or checking visibility.
* **Visible-Frustum Region Marker Iteration:**
  - Replaced the full $128 \times 128$ chunk loop (16,384 iterations per frame) in `renderRegionMarkers` with viewport-bounded region iteration (`minRX..maxRX`, `minRZ..maxRZ`).
  - When zoomed in to 2–3 regions, only **4–16 regions** are inspected per frame rather than 16,384 chunks.
* **$O(1)$ Region Visited & Base Lookup Precomputations:**
  - `RegionMapState` precomputes `visitedRegions` (`Set<Long>`) and `regionBases` (`Map<Long, BaseType>`) once when `RegionMapPayload` arrives, replacing per-frame 64-chunk nested hash lookups in `isRegionVisible`.

---

## 6. Mission Semi-Randomness & Mandatory Base Missions Architecture

### 6.1. Design Overview
The tactical layer bridges macro strategic regions with localized combat encounters across the four 64×64 subregions: `(0,0)`, `(1,0)`, `(0,1)`, and `(1,1)`.
Missions are generated semi-randomly, strictly deterministic per region based on world seed and coordinate hashing, preventing players from rerolling objectives simply by disconnecting, walking away, or re-engaging.

### 6.2. Resistance Difficulty Tiering (50% Threshold)
Every regular subregion rolls a mission matching the region's current resistance level:
* **Low Resistance ($< 50\%$): Easy Missions**
  1. `PATROL_SWEEP` — Patrol Sweep (Eliminate light perimeter patrols)
  2. `SCOUT_INTERCEPTION` — Scout Interception (Hunt mobile recon squads)
  3. `BORDER_SKIRMISH` — Border Skirmish (Neutralize forward vanguard skirmishers)
  4. `SUPPLY_RAID` — Supply Raid (Ambush logistical escorts and supply lines)
* **High Resistance ($\ge 50\%$): Hard Missions**
  1. `HEAVY_SIEGE` — Heavy Siege (Assault fortified pillager defensive lines)
  2. `CHAMPION_HUNT` — Champion Hunt (Assassinate high-tier elite armored champions)
  3. `STRONGPOINT_ASSAULT` — Strongpoint Assault (Breach entrenched redoubts)
  4. `ATTRITION_STAND` — Attrition Stand (Withstand heavy reinforcement waves)

### 6.3. Seed Determinism & Post-War Reinforcement Salt
* **Deterministic Calculation:**
  $$\text{missionSeed} = \text{worldSeed} \oplus (\text{regionX} \times 73856093) \oplus (\text{regionZ} \times 19349663) \oplus \text{salt}$$
* **Anti-Exploit Invariant:** Under normal conditions, $\text{salt} = 0$. Re-opening the terminal or re-engaging the subregion always yields identical missions.
* **Reinforcement State Reroll:** When a partial attack fails or expires, the region enters a `ReinforcementState` with a newly generated random non-zero salt, deterministically refreshing all subregion missions for the next counter-offensive.

### 6.4. Mandatory Base Missions & Tier Footprints
When a region houses a physical base, the subregions containing the base footprint generate mandatory base missions marked with `★` and `isBaseMission = true`:
1. **Outpost (`BaseType.OUTPOST`):**
   * Occupies **1 subregion** (the anchor subregion).
   * Variant: `OUTPOST_DESTROY_BUILDING` (Demolish Outpost).
2. **Medium Base / Headquarters (`BaseType.HEADQUARTERS`):**
   * Occupies **2 subregions** (anchor subregion + adjacent subregion based on 48×48 footprint bounds).
   * Anchor Subregion: Always `BASE_KILL_COMMANDER` (Eliminate Commander).
   * Shifted Subregion: 50/50 deterministic roll between `BASE_DESTROY_INTEL` (Destroy Intel Network) and `BASE_DESTROY_SUPPLIES` (Destroy Supply Cache).
3. **Mega Base (`BaseType.MEGA_BASE`):**
   * Occupies **all 4 subregions** ($96 \times 96$ footprint).
   * Anchor Subregion: Always `MEGA_ELIMINATE_COMMAND` (Eliminate High Command).
   * Remaining 3 Subregions: Deterministic shuffle of the remaining 5 variants:
     - `MEGA_POWER_GRID` (Sabotage Power Grid)
     - `MEGA_MUNITIONS_DEPOT` (Destroy Munitions Depot)
     - `MEGA_BREACH_GATE` (Breach Citadel Gate)
     - `MEGA_NEUTRALIZE_AIR` (Neutralize Anti-Air)
     - `MEGA_SEVER_COMMS` (Sever Communications)

### 6.5. Domino Capture Invariant
* In normal regions, securing 2 adjacent subregions can trigger a domino collapse to claim the entire region.
* **Mandatory Base Rule:** Domino collapse is **strictly blocked** until **all** mandatory base missions in the region are completed (`areAllMandatoryBaseMissionsSecured(...) == true`). A base fortress must always be personally defeated.

---

## 7. Current Implementation Ledger (What Is Complete)

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
- [x] **Mission Semi-Randomness & Mandatory Base Missions System:**
  - 8 new named tactical missions (4 easy for resistance $< 50\%$, 4 hard for resistance $\ge 50\%$) tied to kill count logic for testing.
  - Seed-deterministic mission rolling stopping re-engagement exploit, with salt rerolls during post-war reinforcement.
  - Base anchor signature missions for Outpost (1 subregion), HQ (2 subregions), and Mega Base (4 subregions).
  - Strict domino collapse blocking until all mandatory base missions are cleared.
- [x] **Native NBT Base Structure Pipeline:**
  - Full native Minecraft `.nbt` structure template pipeline using `TemplatePremadeStructure` and `StructureTemplateManager`.
  - First official fortress: Pillager Outpost (`warfront:base/outpost/pillager_outpost.nbt`, $27 \times 23 \times 27$ footprint).
  - Legacy block-by-block Java generation scripts deleted; strict project mandate established to only use native `.nbt` templates.
  - Automatic 3D air-clearing pass within bounding box plus dynamic stepped cobblestone foundation beneath perimeter columns.
  - Added in-game export utilities: `/warfront base export [name]` and `/warfront base structure-block`.
- [x] **Tactical War Visualization Overhaul:**
  - Split region-wide red mask into subregion-specific active mission masks.
  - Pulsating red frontline combat borders around $8 \times 8$ regions at war.
  - Transparent central war status badges (`war_attack.png`, `war_defense.png`) with contextual auto-hiding during mission selection and auto-display when unselected.
  - Network synchronization of active war campaigns via `ActiveWarData` in `RegionMapPayload`.

---

## 8. In Progress (Active Focus)

- [ ] **Headquarters & Mega Base NBT Blueprints:**
  - Designing, building, and exporting native `.nbt` templates for Big Base (HQ) and Mega Base tiers.
- [ ] **Dedicated Mission Gameplay Logic:**
  - Replacing the temporary kill-count testing logic with specialized mission objectives:
    - Block destruction (Intel Network, Supply Cache, Power Grid coils, Munitions barrels).
    - Specific entity assassinations (High Commander boss fight, Anti-Air gunners).
    - Gate breaching with siege charges.

---

## 9. Forward Roadmap & Planned Milestones

### 9.1. Regenerating / Indestructible Base Buildings (Grief Prevention)
* **Problem:** Players could use explosives, flint and steel, or mining to obliterate enemy fortresses before starting a mission.
* **Planned Solution:** 
  - Dynamic structure protection or snapshot-based state regeneration.
  - When a base mission initiates, structures reset to their pristine blueprint state, or fortress blocks gain temporary blast/break immunity outside active capture windows.

### 9.2. Zombie Horde Custom GeckoLib Roster
* Replacing vanilla placeholder zombies with complete custom GeckoLib 4.7.4 models and animations:
  1. **Fodder:** Fast, low-health shambler.
  2. **Chaser:** Quadrupedal sprinting stalker.
  3. **Spewer:** Acid/bile ranged mortar.
  4. **Tank:** Massive brute capable of smashing through player defenses.
  5. **Hivemind Controller:** Backline summoner that buffs surrounding undead.

### 9.3. Catapult / Stationary Artillery Units
* Heavy siege engines spawned during Mega Base defense sieges to shell attacking enemies from distance.

---

## 10. Walkthrough of Latest Changes (Mission System Milestone)

### 10.1. Mission Data & Registry
* **`MissionType.java`:** Added 18 total mission types: 4 easy (`PATROL_SWEEP`, `SCOUT_INTERCEPTION`, `BORDER_SKIRMISH`, `SUPPLY_RAID`), 4 hard (`HEAVY_SIEGE`, `CHAMPION_HUNT`, `STRONGPOINT_ASSAULT`, `ATTRITION_STAND`), 1 outpost (`OUTPOST_DESTROY_BUILDING`), 3 headquarters (`BASE_KILL_COMMANDER`, `BASE_DESTROY_INTEL`, `BASE_DESTROY_SUPPLIES`), and 6 mega base (`MEGA_ELIMINATE_COMMAND`, `MEGA_POWER_GRID`, `MEGA_MUNITIONS_DEPOT`, `MEGA_BREACH_GATE`, `MEGA_NEUTRALIZE_AIR`, `MEGA_SEVER_COMMS`). Added classification helpers `isBaseMission()`, `isEasy()`, and `isHard()`.
* **`SubRegionMission.java`:** Added `boolean isBaseMission`, constructor overloads, and `★` prefix to display labels for base missions.
* **`WeightedMissionSelector.java`:** Propagates `isBaseMission` flag to instantiated `SubRegionMission`.

### 10.2. Procedural Mission Generation
* **`FactionMissionGenerator.java`:** Added `generateMissionsWithSeedAndBase(...)` with fallback default implementation.
* **`MissionProfile.java`:** Added `getOccupiedBaseSubRegionsMask(rx, rz, baseType, anchor)` and `getAnchorSubRegionBit(rx, rz, anchor)` calculating exact subregion occupation masks based on physical base footprints.
* **`PillagerMissionGenerator.java` & `ZombieMissionGenerator.java`:**
  - Registered all 8 regular missions partitioned strictly by resistance ($< 50\%$ vs $\ge 50\%$).
  - Implemented deterministic mission rolling derived from `seed` and subregion coordinate offsets.
  - Hardcoded base anchor subregion to signature variant (`OUTPOST_DESTROY_BUILDING`, `BASE_KILL_COMMANDER`, `MEGA_ELIMINATE_COMMAND`).
  - Implemented deterministic secondary rolls for HQ (1 of 2 variants) and Mega Base (3 of 5 variants).
* **`DefaultMissionGenerator.java`:** Updated with resistance partition and modern generator interface support.

### 10.3. Server State & Domino Gating
* **`RegionData.java`:**
  - Added `long salt` to `ReinforcementState` with NBT serialization.
  - Implemented `calculateMissionSeed(rx, rz)` incorporating world seed, coordinates, and reinforcement salt.
  - Added `getMandatoryBaseSubRegionsMask(...)` and `areAllMandatoryBaseMissionsSecured(...)`.
  - Updated `claimSubRegion(...)`: Domino collapse is strictly blocked if any mandatory base missions in the region remain unsecured.
* **`ActiveCampaignMissionManager.java`:** Updated `startCampaign(...)` to pass authoritative `calculateMissionSeed` and `baseAnchor` into the generator.

### 10.4. Networking & Client Map HUD
* **`RegionDetailsPayload.java`:** Included `BlockPos baseAnchor` (nullable) and `long missionSeed` in payload and StreamCodec.
* **`RequestRegionDetailsPayload.java` & `CancelAttackPayload.java`:** Synchronized server-to-client transmission of `baseAnchor` and `missionSeed`.
* **`SelectedRegion.java` & `RegionMapState.java`:** Stored mission seed and base anchor on client; updated mission cache to invalidate automatically when mission seed changes.
* **`RegionMapScreen.java`:** Passed mission seed and base anchor to mission generators when updating action buttons and launching attacks.
* **`RegionMapRenderer.java`:** Added `MISSION_TYPE_TEXTURES` map loading all 18 PNG icons; rendered icons dynamically in the subregion mission panel; added `(Base Req)` requirement text for mandatory base subregions.
* **Icon Assets:** 18 PNG icons saved in `src/main/resources/assets/warfront/textures/gui/map/`.

### 10.5. Mission Spawn Caps, Cooldown Pacing & War Overlay Fixes
* **Living Mission Enemy Cap & Spawn Pacing:**
  - `MissionEntityTracker.java`: Added `getLivingMissionMobCount(missionInstanceId, level)`.
  - `KillCountMissionHandler.java`: Added `MAX_LIVING_MISSION_ENEMIES = 8`. Reinforcement wave spawns are strictly blocked while the player has $> 2$ active living mission enemies. Increased `REINFORCEMENT_COOLDOWN_TICKS` from 6s (120 ticks) to 35s (700 ticks). Clamped wave size to $\min(6, \max(2, \text{remainingKills} - \text{livingCount}))$.
  - `EnemyEncounterSpawner.java`: Added `spawnMissionEncounter(..., maxEncounterSize)` overload clamping encounter size against remaining quota.
  - `SubregionPatrolManager.java`: Disallowed ambient patrol squad spawning in subregions with active campaign missions (`ActiveCampaignMissionManager.hasActiveMission`). Reduced max concurrent squads per subregion from 3 to 1 and increased patrol spawn cooldown from 40s to 90s (1800 ticks).
* **War State & Under Attack Red Overlay Persistence:**
  - `RegionData.java`: Prevented premature domino collapse / campaign erasure while `activeRemainingMask != 0` (player is actively engaged in launched campaign missions). Handled clean sortie conclusions for partial attacks.
  - `RequestRegionMapPayload.java`: Ensured all unconquered subregions in an actively besieged region retain `underSiege = true` so the red overlay persists throughout the siege.
  - `RegionMapState.java`: Fixed bug in `processMapPayload` where the loop broke on chunk (0,0), incorrectly clearing `regionStillSieged` as soon as subregion (0,0) was secured.

### 10.6. Mission Randomization Variety & Regional Frequency Quota Overhaul
* **Problem Addressed:** Previous subregion mission generation evaluated each subregion in isolation. With a 4-definition candidate pool, uniform random rolls caused over 26% of regions to be dominated by 3 or 4 identical missions, or produce repetitive pairs biased by biome multipliers.
* **Algorithmic Solution:**
  - **`WeightedMissionSelector.java`:**
    - High-Entropy Mixing: Integrated SplitMix64 (`mix64`) to eliminate coordinate-shift bit correlation and modulo bias.
    - Regional Quota Cap: Enforced `MAX_MISSION_TYPE_INSTANCES_PER_REGION = 2`. Any `MissionType` that has already appeared 2 times in the region is strictly excluded from the eligible candidate pool for subsequent subregions.
    - Repeat Weight Dampening: Applied `REPEAT_DAMPENING_FACTOR = 0.45` to candidates that have already been selected once in the region. This creates natural diversity without rigid templates or hardcoded slot allocations.
    - Dynamic Selection API: Updated `selectAndGenerate(...)` to accept a mutable `Map<MissionType, Integer> regionTypeCounts` and increment counts upon selection, with backward-compatible overloads.
  - **`PillagerMissionGenerator.java` & `ZombieMissionGenerator.java`:**
    - Two-Pass Generation: Pass 1 resolves and assigns mandatory base missions for occupied subregions and records their types in `regionTypeCounts`; Pass 2 resolves regular missions for unoccupied subregions under the regional quota and dampening rules.
    - SplitMix64 Seed Mixing: Applied `mix64` to HQ secondary rolls and Mega Base variant shuffle RNGs.
    - Unique Type Pools: Replaced duplicate `STRONGPOINT_ASSAULT` in Zombie hard pool with `sporeBattery` (`ARTILLERY_BATTERY`), guaranteeing all 4 hard missions have distinct types.
  - **`DefaultMissionGenerator.java`:**
    - Upgraded fallback generator from assigning 4 identical missions to cataloging 4 easy and 4 hard generic definitions evaluated through `WeightedMissionSelector` with regional frequency tracking.
  - **Simulation & Verification:**
    - Tested across 80,000 generated regions: 0 violations of the max 2 limit. In 4-slot wilderness, ~66.6% exhibit 1 double and 2 singles (focused frontline with variety), ~25.9% exhibit all 4 distinct missions, and ~7.5% exhibit two pairs, with balanced $\approx 25\%$ overall slot coverage across every mission type.

### 10.7. Tactical War Visualization Overhaul: Subregion Mask Splitting, Pulsating Borders & Central War Badges
* **Problem Addressed:** Previously, any region undergoing an active campaign applied a solid red tint mask across the entire $8 \times 8$ region regardless of subregion status. If a player finished all selected sortie missions, or during the pause between defensive waves before timer expiration, the region would either remain entirely red (hiding progress and secured subregions) or lose all indication of being at war.
* **Architectural Solutions:**
  - **Subregion-Scoped Mission Masking:**
    - `RequestRegionMapPayload.java`: Scoped `isSieged` per chunk strictly to `ActiveCampaignMissionManager.hasActiveMission(rx, rz, subX, subZ)`.
    - `RegionMapRenderer.java`: `isChunkSieged(...)` applies the dynamic texture red mask and red subregion border strictly to subregions with active, uncompleted missions. Secured sectors render Humanity blue (`Faction.HUMANITY`), and unengaged enemy sectors render normal faction/biome colors.
  - **Pulsating Strategic Borders (Idea 1):**
    - `RegionMapRenderer.renderFrontlineBorders(...)`: Renders a 2px-wide pulsating crimson perimeter with soft glow around the entire $8 \times 8$ region boundary using a time-based sine wave pulse (`120..255` alpha).
  - **Central War Status Badges (Idea 3):**
    - Transparent PNG assets: `war_attack.png` (crossed steel/gold blades with battle gleam) and `war_defense.png` (fortified heater shield with golden bastion tower and defense glow) with zero-alpha transparent backgrounds and proportional padding.
    - `RegionMapRenderer.renderWarStatusIcons(...)`: Renders the badge centered in the $8 \times 8$ region ($3.5 \times 3.5$ chunk footprint).
    - Smart auto-hide rule: When the player selects the region and is in inspection/mission-selection mode (`isActivated == true`), the badge hides automatically so subregions, base icons, and mission logos remain unobstructed. When deselected or browsing, the badge re-appears.
  - **Cross-Client War Network Synchronization:**
    - `RegionMapPayload.java`: Added `record ActiveWarData(int regionX, int regionZ, int attackerFactionId, boolean isDefense)`, `WARS_CODEC`, and integrated into `STREAM_CODEC`.
    - `RegionMapState.java`: Tracks active wars, provides `isRegionAtWar(...)` and `getActiveWar(...)`, and checks regional war state directly in `updateMapData(...)` instead of relying on chunk sampling.
    - `RegionMapScreen.java`: Explicitly calls `renderer.markTextureDirty()` on `onLaunchAttack`, `onCancelAttack`, and `onConfirmCampaign` for snappy UI updates.

### 10.8. Iteration 2: Dynamic & Cheap Easy Missions Implementation
* **Polymorphic Mission Execution Architecture:**
  - `MissionObjectiveHandler.java`: Replaced monolithic kill-count logic with a polymorphic interface supporting `onPlayerInSubregion`, `onEntityKilled`, `onBlockBroken`, and `onCleanup`.
  - `MissionHandlerRegistry.java`: Central registry mapping `MissionType` to dedicated objective handlers, with seamless fallback to `KillCountMissionHandler`.
  - `ActiveCampaignMissionManager.java`: Dispatches gameplay events through `MissionHandlerRegistry`, adds `completeMission(...)` (capturing sector to Humanity, triggering domino check, and notifying map terminals) and `broadcastHudUpdate(...)`.
* **Zero-Lag Temporary Mission Sites & Clean World Restoration:**
  - `MissionSiteSnapshot.java` & `MissionSiteSnapshotManager.java`: Captures original `BlockState` prior to placing temporary structures or props; restores terrain cleanly top-to-bottom on mission completion, expiration, or cancellation, preventing world griefing and material duplication.
  - `MissionSiteAnchorResolver.java`: Deterministically resolves dry-land surface anchors within the $64 \times 64$ subregion keeping a 12-block border margin, evaluating `findDryLandSurfaceY` for solid footing.
  - `TemporaryStructureBuilder.java`: Builds compact field installations with stepped cobblestone/log foundation columns downward to solid ground so buildings never float on uneven slopes.
* **The 4 Easy Tier Missions & Spawning Overhaul:**
  - **1. Eliminate Hostiles / Kill Count (`KILL_COUNT` - `ELIMINATE_TARGETS`):**
    - Replaced `FORWARD_PATROL`. Instead of spawning independent wave mobs, the mission leverages `SubregionPatrolManager` which increases patrol cap to 4 squads with a 20-second respawn cooldown.
    - Patrol mobs carry origin coordinates and patrol tags; kills are cleanly credited to the active mission subregion.
    - Deterministic target count variations of 40, 45, or 50 kills. Uses `mission_kill_count.png` on the map.
  - **2. Supply Convoy (`SUPPLY_CONVOY` - `INTERCEPT`):**
    - Handled by `SupplyConvoyMissionHandler.java`. Spawns a moving supply convoy:
      - Front Wagon: `SupplyWagonCartEntity` (GeckoLib) pulled by 2 harnessed horses, continuously pathfinding through 3 subregion border waypoints (with 10-second pauses upon arrival). Halts if both horses die; moves continuously while alive.
      - Rear Wagon: `SupplyWagonExtensionEntity` (GeckoLib) trailer coupled behind the front cart via hitch and rope leash without independent AI. Features an interactive 27-slot chest with randomized military logistics loot and drops all items without loss upon destruction.
      - Escort Squads: 6 armed guards (2 Warriors and 4 Marksmen, 3 per side) marching in formation. Governed by `ConvoyEscortGoal` with dynamic leashes (8 blocks max for marksmen, 24 blocks max for warriors) and an anti-glitch full-retreat mechanic requiring them to sprint all the way back to the cart station before re-engaging.
    - Mission completes when the extension wagon is destroyed or looted.
  - **3. Forward Outpost (`FORWARD_OUTPOST` - `DESTROY_STRUCTURE`):**
    - Handled by `ForwardOutpostMissionHandler.java`. Generates a compact $8 \times 8$ palisade watchtower with stepped foundations and an embedded central Command Core (`WarfrontBlocks.MISSION_TARGET_CORE`).
    - Base Destruction Mechanic: Tracks all structural blocks placed. Clearing $\ge 60\%$ of the base structure (via hand mining or TNT detonations) clears the mission.
  - **4. Scout Network (`SCOUT_NETWORK` - `DESTROY_OBJECTIVES`):**
    - Handled by `ScoutNetworkMissionHandler.java`. Guarantees at least 3 distributed $3 \times 3$ wooden lookout nests across the subregion using multi-attempt sector search and anchor resolver fallbacks.
    - Each nest is manned by a sniper Marksman and contains an Observation Relay (`WarfrontBlocks.MISSION_TARGET_CORE`).
* **Active War Patrol Spawning:**
  - Removed previous restriction blocking patrols in active mission zones. Active war subregions now run with a cap of 3 squads and 30-second cooldown (boosted to 4 squads and 20-second cooldown for `KILL_COUNT`).
  - Out-of-war exploration patrol timers updated to 3 squads max with a 45-second cooldown (900 ticks).
* **Incentivizing TNT Usage (`MISSION_TARGET_CORE` & Explosion Detonation):**
  - Registered `WarfrontBlocks.MISSION_TARGET_CORE` with Obsidian hardness (`destroyTime = 50.0F`) and fragile blast resistance (`explosionResistance = 0.5F`).
  - `MissionBlockEventHandler.java`: Intercepts both `BlockEvent.BreakEvent` and `ExplosionEvent.Detonate`, crediting structural and core destruction from TNT explosives in active mission sectors.
* **In-World Event Integration:**
  - `MissionBlockEventHandler.java`: Intercepts `BlockEvent.BreakEvent` and `ExplosionEvent.Detonate`, resolving subregion pos and routing to `ActiveCampaignMissionManager.onBlockBroken(...)`.
  - Registered in `Warfront.java`.
  - `MissionDeathEventHandler.java`: Passes exact `Mob` instance to `ActiveCampaignMissionManager.onEntityKilled(...)`, falling back to current coordinates if mobs crossed subregion borders.

### 10.9. Persistent Base Self-Repair, Controlled Decay & Placeholder Architecture
* **5-Scenario Lifecycle Enforcement (`PersistentBaseProtectionManager.java`):**
  1. **Scenario 1 (Peace/Vandalism):** Player destroys base blocks while no mission/war is active $\rightarrow$ Base reconstructs itself on the spot in batches of up to 16 blocks per interval with villager sparkles and anvil chime.
  2. **Scenario 2 (Mission Engaged):** Player enters active mission subregion $\rightarrow$ Both decay and repair are strictly paused so combat destruction is permanent.
  3. **Scenario 3 (Sortie Cleared, War Ongoing):** Player wins base sortie but regional siege is still active $\rightarrow$ Base remains stay frozen in place.
  4. **Scenario 4 (Region Conquered by Humanity):** Player wins the war and captures the region $\rightarrow$ Remaining base blocks decay gradually with POOF dissolution particles.
  5. **Scenario 5 (War Lost / Expired):** Regional siege times out or fails $\rightarrow$ Region remains/returns to enemy control and bases self-repair back to full integrity.
* **Accurate NBT Structure Template Extraction (`TemplatePremadeStructure.java`):**
  - Replaced Mojang `filterBlocks` mapping query with direct palette and block tag deserialization from native NBT.
  - Correctly offsets local coordinates against the base anchor and guarantees non-empty blueprint retrieval.
  - Safe caching in `BLUEPRINT_CACHE`: only non-empty blueprints are cached to avoid cache poisoning.
* **Foundation Invariant:**
  - Foundations beneath ground level ($y < anchor.getY()$) are strictly excluded from both the repair goal and the demolition/decay quota.
* **Controlled Decay Speed (`MissionSiteSnapshotManager.java`):**
  - Cut decay speed in half: 2 blocks/tick (with players nearby) instead of 4 blocks/tick for smooth visual pacing.
* **Monolith Placeholder Distinction (`BaseBuildingRegistry.java`):**
  - The Cobblestone Monolith is strictly a **placeholder** for tiers and factions not yet implemented (e.g. Mega Bases, Spires), **never** a fallback for implemented structures. If an implemented template encounters an issue, it logs an error rather than silently generating a monolith.
* **Visual & Audio Effects Separation:**
  - `POOF` particle effects are strictly reserved for structural decay, demolition, and mob despawns. Self-repair produces only `HAPPY_VILLAGER` green sparkles and `ANVIL_USE` sound effects.

---

## 11. Developer Rules for Future Iterations

1. **Always Check Stored State First:** Never re-derive or guess a base location or region owner if `RegionData` already has it stored.
2. **Never Re-Introduce Region-Wide Bans on Rivers:** Keep river checks localized to footprint viability and base-center aquatic ratio ($\le 50\%$).
3. **Keep `AlliedFactionHelper` Free of Recursion:** Never call `livingA.isAlliedTo(livingB)` inside `AlliedFactionHelper.isAllied`. Check tags, teams, squads, and IDs directly.
4. **Do Not generate your idea of a behavior:** The user explains what needs to be accomplished in their prompt. Do not add your idea of what should be done to that process.
5. **Always Ask For Confirmation:** You are required to create an implementation plan for every task given to you to then present to the user. Only move to the implementation part if user accepts your plan, otherwise ask for what to change.
6. **Preserve GeckoLib Performance:** Do not run client-side animation state logic on the server thread.
7. **Document Every Milestone:** Update this file at the conclusion of every major feature or refactor.
8. **Document Latest Changes:** Maintain a walkthrough of what code and logic was changed in each file.