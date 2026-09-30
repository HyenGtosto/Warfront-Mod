# Warfront Mod — Project Overview, Architecture, Gameplay Model, and Current Development State

**Document Version:** 2.0.0  
**Target Platform:** Minecraft 1.21.1 / NeoForge 21.1.x / GeckoLib 4.7.4  
**Date:** October 1, 2026  
**Status:** Phase 1–4 Complete (Strategic Territory, Mission Campaign, Frontline Siege Roamers, Full Pillager Custom Roster & Combat AI)

---

# 1. Project Overview & Design Philosophy

Warfront is a Minecraft total-overhaul warfare mod designed to transform the normally static, isolated hostile-mob environment into a persistent, macro-strategic regional warfare ecosystem.

Rather than arbitrarily spawning buffed mobs near the player, the entire world is partitioned into contiguous strategic territories held by rival factions. These factions dynamically expand across borders, stage coordinated sieges against neighboring strongholds, establish forward outposts and military headquarters, and generate frontline combat engagements.

The player interacts with this dynamic conflict directly via in-world **Strategic Map Terminals** and physical frontier excursions. From the terminal, commanders inspect territorial control, assess regional defensive resistance and stability, identify hostile incursions, and initiate tactical campaigns against enemy sectors.

```text
                  STRATEGIC WORLD MAP TERMINAL
                                ↓
                 FACTION-CONTROLLED TERRITORIES
                                ↓
             AI STRATEGIC EVALUATION & SIEGE ENGINE
                                ↓
                 TACTICAL FRONTLINE BATTLEFIELD
                                ↓
            PLAYER LAUNCHES WAR / DEFENDS TERRITORY
                                ↓
           SUBREGION MISSIONS & OBJECTIVES ACTIVATED
                                ↓
             DYNAMIC WAVE INVASIONS & SQUAD SPAWNS
                                ↓
       CUSTOM GECKOLIB COMBATANTS ENGAGE (AI & IMPACT FRAMES)
                                ↓
              SUBREGION CAPTURE & DOMINO RESOLUTION
                                ↓
               MACRO TERRITORIAL MAP RECALCULATION
```

The core design philosophy is strictly **modular and layered**:
* Strategic AI does not concern itself with entity animations or weapon swing frames.
* Mission handlers evaluate abstract victory metrics without hardcoding renderer logic.
* The combat and animation layer utilizes GeckoLib 4.7.4 to deliver authentic, high-fidelity mob combat with telegraphing and dodgeable impact contact frames.
* Systems remain decoupled so that entire rosters, weapon behaviors, and strategic algorithms can be expanded independently.

---

# 2. Strategic Territory System

The world is divided into contiguous macro-regions, which are further divided into localized tactical subregions.

```text
                    REGION (128 × 128 Blocks / 8 × 8 Chunks)
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

### Region Specifications:
* **Dimensions:** $128 \times 128$ blocks ($8 \times 8$ chunks).
* **Strategic Container:** Holds macro properties including controlling `Faction`, continuous `Resistance` ($0.0 - 100.0$), continuous `Stability` ($0.0 - 100.0$), `BaseType`, `ClusterId`, and active `SiegeCampaign` records.
* **Map Display:** Rendered on Strategic Terminals with procedural biome-derived coloration, faction overlays, and siege boundary indicators.

### Subregion Specifications:
* **Dimensions:** $64 \times 64$ blocks ($4 \times 4$ chunks).
* **Tactical Operational Unit:** Serves as the localized gameplay container for exploration encounters, mission objectives, wave spawns, sector capture, and combat arenas.
* **Granularity Benefit:** Prevents entire $128 \times 128$ regions from acting as monolithic, overwhelming battlegrounds.

---

# 3. Factions & Lore Overview

### 3.1. Humanity (Player Faction)
* **Identity:** The defender and expanding force representing player settlements and liberated frontiers.
* **Mechanics:** Gains territory by launching offensive campaigns into enemy sectors. Defends held territories when targeted by AI sieges.

### 3.2. Pillager Conquerors (Hostile Military AI)
* **Identity:** A disciplined, heavily armed militaristic faction operating hierarchical invasion forces with shocktroopers, agile scouts, snipers, armored bruisers, and tactical battlefield generals.
* **Strategic Behavior:** Expands methodically from military bases, prioritizing territorial cohesion, border security, and high-value outposts.
* **Unit Implementation:** 100% complete custom GeckoLib 4.7.4 roster with unique animations, AI behaviors, and impact delays.

### 3.3. Zombie Horde (Hostile Swarm AI)
* **Identity:** An aggressive, overwhelming organic swarm that spreads chaotic blight and seeks to overrun borders through sheer attrition.
* **Strategic Behavior:** Targets vulnerable neighboring sectors with horde momentum and retaliation strikes.
* **Unit Implementation:** Currently abstracted through `EnemyEntityResolver` using vanilla placeholders (Fodder, Chaser, Ranged Spewer, Tank, Hivemind Controller) awaiting GeckoLib model integration.

---

# 4. Strategic Parameters: Resistance vs. Stability

Every region features two distinct numerical metrics:

```text
┌───────────────────────────────────────┬───────────────────────────────────────┐
│              RESISTANCE               │               STABILITY               │
├───────────────────────────────────────┼───────────────────────────────────────┤
│ Represents physical defensive power   │ Represents strategic cohesion & order │
│ Governs enemy encounter group size    │ Governs defense duration timers       │
│ Determines tier of spawned units      │ Determines domino collapse threshold  │
│ Sets mission kill-count thresholds    │ Dictates territorial decay resistance │
└───────────────────────────────────────┴───────────────────────────────────────┘
```

### 4.1. Resistance Difficulty Tiers
Resistance directly determines unit tier unlocks and encounter sizes across both exploration roaming and siege attack waves:

```text
MINIMAL   (0.0 – 24.9%) : Group Size  4–6   [Marksmen only]
LOW      (25.0 – 44.9%) : Group Size  6–9   [Marksmen + Warriors]
MODERATE (45.0 – 64.9%) : Group Size  8–12  [Marksmen + Warriors + Scouts]
HIGH     (65.0 – 84.9%) : Group Size 12–14  [Marksmen + Warriors + Scouts + Elites + Commanders]
EXTREME  (85.0 – 100.0%): Group Size 14–16  [Stronghold Mega-Base Full Invasion Force]
```

### 4.2. Base Type Baselines
Base types provide baseline resistance and stability anchors before biome and neighbor cohesion modifiers:
* **None:** Base $22.0\%$ (rural frontier territory).
* **Outpost:** Base $45.0\%$ (fortified forward position).
* **Headquarters:** Base $65.0\%$ (major regional command center).
* **Mega Base:** Base $85.0\%$ (heavily fortified stronghold).

---

# 5. Regional Strength Generation & Biome Determinism

Regional resistance and stability are generated deterministically via `RegionalStrengthCalculator`:

1. **Base-Type Baseline:** Initial anchor ($22\%$, $45\%$, $65\%$, or $85\%$).
2. **64-Chunk Biome Analysis:** The system samples biomes across all 64 chunks in the region:
   * Mountain / Peaks: $+15\%$ Resistance, $-5\%$ Stability.
   * Swamp / Jungle: $+10\%$ Resistance, $-10\%$ Stability.
   * Desert / Badlands: $+5\%$ Resistance, $-5\%$ Stability.
   * Plains / Forest / Meadow: $+0\%$ Resistance, $+10\%$ Stability.
   * Snow / Ice: $-5\%$ Resistance, $+10\%$ Stability.
3. **Neighbor Cohesion:** Each adjacent cardinal neighbor held by the same faction contributes $+2.5\%$ Resistance and $+3.0\%$ Stability (with an additional $+1.0\%$ if in the same cluster).
4. **Deterministic Salted Variation:** High-entropy pseudo-random hash produces a smooth $\pm 3.0\%$ natural variance.

---

# 6. Performance Architecture: Raw Region State & Query Optimization

One of the most critical engineering hurdles resolved during development was integrated server thread stalls caused by deep procedural evaluations.

### The Problem
Original strategic queries called `regionAt(rx, rz)`, which, for ungenerated regions, invoked 64 chunk biome lookups, world seed hashing, and neighbor inspections. Evaluating entire $24 \times 24$ grids (576 regions) during AI ticks led to multi-second server lag spikes.

### The Solution: O(1) Raw State Separation
The codebase cleanly decouples **Raw Region State** from **Full Regional Strength**:
* **Raw Region State:** Contains only `owner`, `baseType`, `clusterId`, and `underSiege`. Computed via lightweight procedural mathematical lattice without inspecting chunks or biomes.
* **Cached Evaluation:** Cached in `proceduralRawStateCache` for instantaneous O(1) retrieval during AI targeting, BFS cluster traversals, and map snapshot creation.
* **Full Strength Evaluation:** Deferred until an active player enters proximity or launches an active mission.

---

# 7. Strategic Map Terminal & Client/Server Latency Resolution

The Strategic Map Terminal provides commanders with real-time intelligence:

* **Grid Resolution:** Displays up to $24 \times 24$ regions with zoom and pan support.
* **Biome-Accurate Underlay:** Reads biome registry colors to paint terrain naturally underneath faction territory boundaries.
* **Territory Highlights:** Renders faction colored overlays (Blue = Humanity, Red = Pillager Conquerors, Green = Zombie Horde, Gray = Unclaimed).
* **Siege Indicators:** Flashing red borders mark regions under active assault.
* **Interactive Mission Selector:** Clicking an enemy region brings up the tactical dossier displaying subregion objectives and allowing attack authorization.

### Map Latency Optimization
Originally, every individual AI attack in `AIAttackManager` sent an immediate map rebuild payload to all clients, resulting in dozens of redundant snapshot builds in a single second. The notification system was moved outside the expansion loop: a single atomic update payload is dispatched after all AI actions conclude, eliminating UI stutter completely.

---

# 8. Out-of-War Exploration Roaming System

When players explore hostile territory outside of active wars, the world feels populated and dangerous:

* **Trigger:** Driven by player proximity via `ExplorationSpawnManager`.
* **Subregion Spawning:** Hostile subregions within range of an active player trigger localized squad spawns.
* **Cooldowns:** Each subregion maintains an independent cooldown timer (8 seconds), allowing varied distribution without locking entire $128 \times 128$ territories.
* **Safety Rules:** Unclaimed sectors, Humanity lands, and active siege zones are excluded to prevent interfering with campaign objectives.

---

# 9. Distance-Based AI Caching & Hysteresis Activation

To ensure high performance with dozens of active entities:

* **Mob Tracking:** All roaming and mission entities are registered in `RoamingEntityTracker`.
* **Hysteresis Thresholds:**
  * **Player $\le 48$ blocks:** AI goals active and ticking.
  * **Player $> 64$ blocks:** Mob AI goals suspended (no pathfinding, no target searching).
* **Despawn Prevention:** Entities remain persistent in memory rather than despawning, preventing immersion-breaking pop-in when players step back and forth.

---

# 10. Active War Campaign Architecture

Active warfare occurs when the player attacks an enemy region or an AI faction launches a siege against Humanity:

```text
Player Attack: Flat 10-Minute Campaign (12,000 ticks)
AI Siege Defense: 10 to 20-Minute Campaign (Scaled by attacker resistance & flank vectors)
```

### Defense Timer Calculation
In `RegionData.calculateDefenseDurationTicks`:
* Base duration: 20 minutes (24,000 ticks).
* High average attacker resistance reduces defense time by up to $-25\%$.
* Multi-source flank attacks or total encirclement reduce defense time by $-25\%$.
* Enforces a strict 10-minute floor (12,000 ticks) so players always have time to react.

---

# 11. Mission Generation & Subregion Profiles

Missions generate dynamically based on region properties via `FactionMissionGenerator`:

* **`PillagerMissionGenerator`:** Focuses on eliminating military cadres, marksmen fireteams, elite bruisers, and tactical commanders.
* **`ZombieMissionGenerator`:** Focuses on thinning horde density, hunting fast chasers, and assassinating Hivemind Controllers.
* **Determinism:** Seeded by region coordinates, faction, base type, and world seed so players viewing the dossier see consistent objectives until territory changes.

---

# 12. Active Mission Gameplay & Objective Execution

The mission lifecycle is governed by `ActiveCampaignMissionManager` and `KillCountMissionHandler`:

1. **Authorization:** Player selects target subregions on the Map Terminal and clicks **Launch Attack**.
2. **Campaign Initialization:** `SiegeCampaign` is registered in `RegionData`; active subregions are flagged with the campaign mask.
3. **HUD Synchronization:** `ActiveMissionHudPayload` sends mission objectives to the client, rendered on screen via `ActiveMissionHudRenderer`.
4. **Targeted Wave Spawning:** `KillCountMissionHandler` evaluates active player position inside the target subregion, spawning waves composed of the required target roles.
5. **Entity Tracking & Death Verification:** `MissionEntityTracker` registers spawned mobs with `squadId` and `targetRoleName`. `MissionDeathEventHandler` listens for player kills, validates coordinates within subregion bounds, and increments progress counters.
6. **Sector Capture:** When target kills are met, the subregion immediately flips ownership to the conquering faction.

---

# 13. Strategic Warfront Domino Collapse & Territory Resolution

Regions do not require all 4 subregions to be captured one-by-one:

### Domino Threshold Calculation
In `RegionData.calculateDominoThreshold`:
* **Offensive Conquest (Attacking Enemy Territory):**
  * Stability $\le 35\%$: **1 Sector needed** (Fragile territory collapses immediately).
  * Stability $\le 55\%$: **2 Sectors needed**.
  * Stability $\le 78\%$: **3 Sectors needed**.
  * Stability $> 78\%$: **4 Sectors needed** (Fortified stronghold must be completely conquered).
* **Defensive Hold (Defending Humanity Territory):**
  * 1-Source Frontal Assault: 1 to 3 Sectors needed based on attacker resistance.
  * 2-Source Flank Assault: 3 Sectors needed.
  * Multi-Axis Offensive or Encirclement: All 4 Sectors must be held.

### Campaign Conclusion
* **Victory:** Clearing required sectors triggers Domino Collapse: entire region flips to Humanity, converts all subregions, generates base strength, clears the siege, and displays victory title announcements.
* **Defeat:** If campaign timer expires before the threshold is met, remaining enemy sectors reinforce, and the siege fails.

---

# 14. Frontline Siege & Invasion Warfare System

During active Pillager sieges against defending territory, `AttackRoamerManager` runs continuous invasion mechanics:

```text
                  ENEMY SOURCE REGION (Resistance: 65% - High)
                                       │
                      BORDER INVASION SPAWN POINT
                                       │
                         [Phase 1: March Advance]
                     Speed 0.42 / Incremental Waypoints
                                       │
             ┌─────────────────────────┴─────────────────────────┐
             │                                                   │
  Player / Ally Detected?                            Arrived at Frontline?
             │                                                   │
     [Phase 2: Combat Engagement]                     [Phase 3: Hold Ground]
   Melee Cleaves / Crossbow Kiting / Buffs           8×8 Area Spread Formation
```

### Core Specifications:
* **Spawn Interval:** New invasion squad spawns every 12 seconds (240 ticks).
* **Concurrent Squad Cap:** Maximum 6 squads active simultaneously per besieged region.
* **Squad Elimination & Capacity Cycling:** When a squad takes $\ge 75\%$ casualties ($\le 25\%$ alive), it is marked wiped, freeing up squad capacity for fresh reinforcement waves.
* **Terminal Dispersal:** When a siege concludes, distant squad mobs despawn immediately; nearby mobs disperse with cloud particles.

---

# 15. Dynamic Frontline Geometry (`FrontlineShape`)

Invasion forces do not wander randomly; `FrontlineShape` computes an authentic mathematical frontline across the $128 \times 128$ territory:

```text
TYPE 1: 1 ENEMY BORDER             TYPE 2: 2 ADJACENT BORDERS
   (Triangular Wedge)                   (Diagonal Cut)
┌───────────────────────┐           ┌───────────────────────┐
│▲▲▲▲▲ INVASION ▲▲▲▲▲▲▲▲│           │▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲│
│ ╲                   ╱ │           │▲▲▲▲▲ INVASION ▲▲▲▲▲▲▲ │
│  ╲                 ╱  │           │▲▲▲▲▲▲▲                │
│   ╲               ╱   │           │▲▲▲          FRONTLINE │
│    ╲             ╱    │           │       ╲               │
│     ╲   (64,64) ╱     │           │        ╲              │
│      ╲    ▼    ╱      │           │         ╲             │
│       ╲       ╱       │           │          ╲            │
│        ╲     ╱        │           │           ╲           │
│         ╲   ╱         │           │            ╲          │
│          ╲ ╱          │           │             ╲         │
└───────────────────────┘           └───────────────────────┘

TYPE 3: 3 ENEMY BORDERS            TYPE 4: 4 ENEMY BORDERS
   (Inverted Wedge)                    (Center Convergence)
┌───────────────────────┐           ┌───────────────────────┐
│▲▲▲▲▲ INVASION ▲▲▲▲▲▲▲▲│           │▲▲▲▲▲▲▲▲▲▲▲▼▲▲▲▲▲▲▲▲▲▲▲│
│▲▲▲                 ▲▲▲│           │▲▲                   ▲▲│
│▲▲▲                 ▲▲▲│           │▲▲                   ▲▲│
│▲▲▲       (64,64)   ▲▲▲│           │▲▲                   ▲▲│
│▲▲▲        ╱ ╲      ▲▲▲│           │▲▲         X         ▲▲│
│▲▲▲       ╱   ╲     ▲▲▲│           │►►►     (64,64)   ◄◄◄◄│
│▲▲▲      ╱     ╲    ▲▲▲│           │▼▼                   ▼▼│
│▲▲▲     ╱       ╲   ▲▲▲│           │▼▼                   ▼▼│
│       ╱         ╲     │           │▼▼                   ▼▼│
│      ╱           ╲    │           │▼▼▼▼▼▼▼▼▼▲▼▼▼▼▼▼▼▼▼▼▼▼▼│
└───────────────────────┘           └───────────────────────┘
```

1. **Type 1 (1 Enemy Border):** Forms an isosceles triangular battle line with its $90^\circ$ apex at the center of the region $(64, 64)$.
2. **Type 2 (2 Adjacent Borders):** Cuts the region diagonally like a sandwich.
3. **Type 3 (3 Enemy Borders):** Forms an inverted triangle wedge safeguarding the remaining friendly border.
4. **Type 4 (4 Enemy Borders / Encircled):** Incursions converge simultaneously from all four borders onto the center.

---

# 16. Frontline Squad Phases: Marching, Combat, & 8x8 Formation Hold

Squad members execute a 3-phase AI behavioral routine:

* **Phase 1: Directional Advance (`AdvanceToLocationGoal`):**
  * Navigates toward the assigned frontline destination using incremental 20-block waypoints.
  * Yields immediately if a player or allied mob is detected.
* **Phase 2: Tactical Engagement:**
  * Combat goals take over: Warriors charge with axes, Scouts flank with daggers, Marksmen kite with crossbows, Commanders direct troops from the backline.
* **Phase 3: Formation Hold Ground (`HoldGroundGoal`):**
  * When arriving within holding distance without an active target, mobs position in an $8 \times 8$ defensive spread formation, anchoring the frontline.

---

# 17. Siege Squad Attacker Resistance & Scaling Architecture

### The Former Marksman-Only Issue (Root Cause)
Previously, `AttackRoamerManager` passed defending region coordinates `(rx, rz)` to evaluate resistance. In defensive campaigns where the player defended Humanity territory, resistance was evaluated at $0.0F$ (Minimal Tier). Because Warriors require Low, Scouts Moderate, and Elites/Commanders High tier, only Marksmen were permitted to spawn.

### The Source-Region Resolution
`AttackRoamerManager` now determines the exact **border edge** where the squad physically spawns:
* North border $\rightarrow$ queries attacking region $(rx, rz - 1)$
* South border $\rightarrow$ queries attacking region $(rx, rz + 1)$
* West border $\rightarrow$ queries attacking region $(rx - 1, rz)$
* East border $\rightarrow$ queries attacking region $(rx + 1, rz)$

If an edge case produces non-positive resistance, it evaluates the maximum resistance across all declared `campaign.sources()`, with a guaranteed minimum floor of $35\%$ (`LOW` tier).

### Squad Size Scaling
Squad sizes scale dynamically from $4$ up to $14$ units:
* **Minimal Tier ($<25\%$):** 4–6 mobs (Marksmen only).
* **Low Tier ($25–44.9\%$):** 6–9 mobs (Warriors + Marksmen).
* **Moderate Tier ($45–64.9\%$):** 8–12 mobs (Warriors + Scouts + Marksmen).
* **High Tier ($65–84.9\%$):** 12–14 mobs (Commanders + Elites + Scouts + Warriors + Marksmen).
* **Extreme Tier ($85–100\%$):** 14 mobs (Full invasion army).

---

# 18. Tactical Squad Formation & Leader Selection

In [`EnemyEncounterSpawner.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/spawn/EnemyEncounterSpawner.java):

* **16-Point Hold-Ground Grid:**
  ```java
  private static final int[][] SQUAD_SPREAD_OFFSETS = new int[][] {
      {  0,  0 }, // Center (Squad Leader)
      { -2, -2 }, {  2, -2 }, { -2,  2 }, {  2,  2 },
      {  0, -3 }, {  0,  3 }, { -3,  0 }, {  3,  0 },
      { -3, -3 }, {  3, -3 }, { -3,  3 }, {  3,  3 },
      { -1,  2 }, {  1, -2 }, {  2, -1 }
  };
  ```
* **Squad Leader Assignment:** In High and Extreme tier assaults, index 0 (centered at `{0, 0}`) has a $65\%$ chance to spawn as a **Pillager Commander**.
* **Commander Limit:** Strictly enforces **at most 1 Commander per squad**, preventing duplicate generals in small fireteams.

---

# 19. GeckoLib 4.7.4 Entity Implementation Architecture

All Pillager Conqueror units are built on GeckoLib 4.7.4 with dedicated renderers, models, and animation controllers:

```text
AbstractIllager (Minecraft Entity)
       │
   GeoEntity (GeckoLib Interface)
       ├── AnimatableInstanceCache (GeckoLibUtil.createInstanceCache)
       ├── registerControllers:
       │     ├── "movement" Controller (idle, walk, run transitions)
       │     └── "attack" / "action" Controller (swings, stances, bows)
       └── Model Hook (GeoModel):
             └── setCustomAnimations (dynamic bone visibility & scale)
```

---

# 20. Pillager Conqueror Custom Unit Roster: Comprehensive Matrix

| Entity | Mod Entity ID | Role | HP | Speed | Dmg | Armor | KB Res | Contact Frame | Primary Weapon & AI Behavior |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |
| **Pillager Marksman** | `warfront:pillager_marksman` | Ranged | 26 | 0.26 | 4.0 | 4.0 | 0.00 | Instant / Arrow | Precision Crossbow. 3-stage sniper AI (aiming, shooting, reloading). Kites if approached $< 6$ blocks. |
| **Pillager Warrior** | `warfront:pillager_warrior` | Fighter | 32 | 0.27 | 8.0 | 6.0 | 0.20 | Tick 9 / 20 | Heavy War Axe. Aggressive frontline shocktrooper with over-shoulder diagonal cleave. |
| **Pillager Scout** | `warfront:pillager_scout` | Scout | 22 | 0.33 | 5.0 | 3.0 | 0.00 | Tick 5 / 10 | Dual Daggers. Fast skirmisher ($1.35\times$ sprint). Unsheathes daggers on target, executes 2-hit slash combos. |
| **Pillager Commander** | `warfront:pillager_commander` | General | 36 | 0.28 | 8.5 | 7.0 | 0.25 | Tick 8 / 18 | Heavy Sword & Banner. Stays 5–7 blocks behind allies, casts banner swings & sword raises. Draws sword in melee. |
| **Pillager Armored Elite** | `warfront:pillager_armored_elite` | Tank | 40 | 0.28 | 9.0 | 8.0 | 0.35 | Instant / Contact | Dual Battleaxes. Vanguard juggernaut with plate armor and heavy knockback resistance. |

---

# 21. Deep Dive: Pillager Armored Elite (`warfront:pillager_armored_elite`)

* **Java Class:** [`PillagerArmoredEliteEntity.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/entity/PillagerArmoredEliteEntity.java)
* **Model & Renderer:** [`PillagerArmoredEliteModel.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/model/PillagerArmoredEliteModel.java), [`PillagerArmoredEliteRenderer.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/renderer/PillagerArmoredEliteRenderer.java)
* **Geometry:** `pillager_armored_elite.geo.json` (14 bones)
* **Texture:** `pillager_armored_elite.png`
* **Combat Role:** Heavy vanguard shocktrooper. Absorbs heavy damage on the frontline while swinging dual battleaxes.
* **Animations:** `idle`, `walk`, `run`, `attack`.

---

# 22. Deep Dive: Pillager Warrior (`warfront:pillager_warrior`)

* **Java Class:** [`PillagerWarriorEntity.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/entity/PillagerWarriorEntity.java)
* **Model & Renderer:** [`PillagerWarriorModel.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/model/PillagerWarriorModel.java), [`PillagerWarriorRenderer.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/renderer/PillagerWarriorRenderer.java)
* **Geometry:** `pillager_warrior.geo.json` (12 bones)
* **Texture:** `pillager_warrior.png`
* **Combat Role:** Standard frontline combatant wielding a two-handed heavy war axe.
* **Delayed Contact Frame:** When swinging, the attack animation lasts 20 ticks (1.0s). Damage is applied on tick 9. Reach is validated at impact, allowing players to back away to dodge.

---

# 23. Deep Dive: Pillager Scout (`warfront:pillager_scout`)

* **Java Class:** [`PillagerScoutEntity.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/entity/PillagerScoutEntity.java)
* **Model & Renderer:** [`PillagerScoutModel.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/model/PillagerScoutModel.java), [`PillagerScoutRenderer.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/renderer/PillagerScoutRenderer.java)
* **Geometry:** `pillager_scout.geo.json` (14 bones)
* **Texture:** `pillager_scout.png`
* **Combat Role:** Agile flanker with base movement speed of $0.33$ and pursuit modifier of $1.35\times$.
* **Dynamic Stance Machine:**
  * Detects hostile target $\rightarrow$ plays `unsheathe` animation (18 ticks) and sets `daggers_drawn = true`.
  * Target lost $\rightarrow$ plays `sheathe` animation (18 ticks) and sets `daggers_drawn = false`.
  * Alternates between `attack1` and `attack2` dual-dagger slashes (10 ticks duration, damage inflicted at tick 5).

---

# 24. Deep Dive: Pillager Marksman (`warfront:pillager_marksman`)

* **Java Class:** [`PillagerMarksmanEntity.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/entity/PillagerMarksmanEntity.java)
* **Model & Renderer:** [`PillagerMarksmanModel.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/model/PillagerMarksmanModel.java), [`PillagerMarksmanRenderer.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/renderer/PillagerMarksmanRenderer.java)
* **Geometry:** `pillager_marksman.geo.json` (20 bones)
* **Texture:** `pillager_marksman.png`
* **Combat Role:** Dedicated marksman wielding a customized precision crossbow.
* **3-Stage Sniper AI (`MarksmanAttackGoal`):**
  * **Aiming State:** Tracks target for 20 ticks (1.0s) while playing the `aiming` animation.
  * **Shooting State:** Fires a physical `minecraft:arrow` entity with authentic trajectory velocity and crossbow audio, playing `shooting`.
  * **Reloading State:** Plays crossbow cocking sound and `reloading` animation for 18 ticks before re-aiming.
  * **Tactical Spacing:** Maintains 12–18 blocks distance; backpedals if enemies close within 6 blocks.

---

# 25. Deep Dive: Pillager Commander (`warfront:pillager_commander`)

* **Java Class:** [`PillagerCommanderEntity.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/entity/PillagerCommanderEntity.java)
* **Model & Renderer:** [`PillagerCommanderModel.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/model/PillagerCommanderModel.java), [`PillagerCommanderRenderer.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/client/renderer/PillagerCommanderRenderer.java)
* **Geometry:** `pillager_commander.geo.json` (14 bones)
* **Texture:** `pillager_commander.png`
* **Combat Role:** Battlefield general commanding troops from safety.
* **Tactical State Machine:**
  * **Backline Positioning (`CommanderTacticsGoal`):** Scans for allied Illagers within 20 blocks, calculates the frontline vector, and maneuvers to stay **5–7 blocks behind the center of allies**.
  * **Rally / Buff Ceremonies (Every 8–12 seconds):** Alternates between casting `banner_swing` (playing `item.raid_horn`) and `sword_raise` (playing `entity.pillager.celebrate`).
  * **Defensive Melee (`CommanderMeleeGoal`):** If rushed directly ($< 4$ blocks or attacked), draws sword, plays unsheathe, and engages in melee combat (18 ticks cycle, impact contact frame on tick 8).

---

# 26. Combat Animation Engine: Delayed Impact Contact Frames

Vanilla Minecraft applies melee damage immediately on tick 0 when `doHurtTarget()` is called, which feels disconnected from visible weapon swing animations.

Warfront implements a generalized **Windup & Impact Countdown Engine**:
1. When melee goal triggers `doHurtTarget()`, the entity sets:
   * `actionState = ATTACK`
   * `attackImpactTicks = CONTACT_FRAME_DELAY`
   * `pendingAttackTarget = target`
2. Each tick in `aiStep()`, the entity ticks down `attackImpactTicks` while continuously locking look rotation onto `pendingAttackTarget`.
3. When `attackImpactTicks == 0`:
   * Distance to target is re-validated against bounding box reach $+ 1.2$ blocks.
   * If target remains in range, `super.doHurtTarget(target)` executes with weapon particles and critical impact audio.
   * If the player dodged or stepped back during the windup, the attack misses harmlessly.

---

# 27. Dynamic Weapon Visibility & Bone Keyframe Integration

Both the Scout (dual daggers) and Commander (heavy sword) carry their weapons sheathed on their backs/hips when not in active combat.

### The Challenge
If weapon bones are hidden via code during unsheathe animations, the weapon model disappears while the arm moves to draw it. Conversely, if bones are always visible, duplicate weapons appear in hands and sheaths simultaneously.

### The Solution: Hybrid Keyframe + Stance Visibility
Implemented in `PillagerScoutModel` and `PillagerCommanderModel`:
```java
@Override
public void setCustomAnimations(Entity animatable, long instanceId, AnimationState<Entity> animationState) {
    super.setCustomAnimations(animatable, instanceId, animationState);

    boolean duringTransition = actionState == ACTION_UNSHEATHE || actionState == ACTION_SHEATHE;

    if (duringTransition) {
        // Leave both bones active: Blockbench scale keyframes (scale [0,0,0] -> [1,1,1]) animate the draw
        if (sheathedBone != null) sheathedBone.setHidden(false);
        if (heldBone != null) heldBone.setHidden(false);
    } else {
        // Enforce persistent visibility based on stance
        if (sheathedBone != null) sheathedBone.setHidden(isDrawn);
        if (heldBone != null) heldBone.setHidden(!isDrawn);
    }
}
```

---

# 28. Server-Client Locomotion Synchronization (Walk-to-Run Transitions)

To ensure mobs smoothly transition from walking to sprinting:

1. **Server Flag Synchronization:** In `aiStep()`, mobs evaluate whether they have an active target and are moving, calling:
   ```java
   this.setAggressive(hasActiveTarget);
   this.setSprinting(hasActiveTarget && isMoving);
   ```
   Both flags sync automatically to the client via Minecraft's `DATA_SHARED_FLAGS_ID`.
2. **GeckoLib Movement Predicate:**
   ```java
   if (this.isSprinting() || this.isAggressive()) {
       return event.setAndContinue(RUN);
   } else {
       return event.setAndContinue(WALK);
   }
   ```
   This guarantees that mobs charging players in combat immediately switch into their run animations.

---

# 29. Creative Integration & Spawn Eggs

Registered 5 custom spawn eggs in [`WarfrontBlocks.java`](file:///c:/Users/Administrator/Desktop/Codes/Java/Warfront%20Mod/src/main/java/com/warfront/block/WarfrontBlocks.java):
* `PILLAGER_ARMORED_ELITE_SPAWN_EGG`
* `PILLAGER_WARRIOR_SPAWN_EGG`
* `PILLAGER_SCOUT_SPAWN_EGG`
* `PILLAGER_MARKSMAN_SPAWN_EGG`
* `PILLAGER_COMMANDER_SPAWN_EGG`

Each spawn egg features custom 16x16 pixel-art egg textures in `assets/warfront/textures/item/` and standard NeoForge `minecraft:item/template_spawn_egg` models, fully integrated into the **Warfront Items** creative tab.

---

# 30. Zombie Horde Roster (Current State & Placeholder Architecture)

The Zombie Horde currently operates using the abstract `EnemyEntityResolver` mapping:
* **FODDER:** Standard zombie walker.
* **FAST_CHASER:** Sprinting zombie pursuer.
* **RANGED:** Acid-spitting zombie placeholder.
* **TANK:** High-HP, slow-moving heavy zombie.
* **HIVEMIND_CONTROLLER:** Support zombie that applies strength and speed buffs to nearby zombies.

*Next phase will import dedicated GeckoLib models and animations for the complete Zombie roster.*

---

# 31. Network Payload Architecture

Warfront maintains efficient client-server networking using NeoForge's `CustomPacketPayload`:

* **`RequestRegionMapPayload` / `RegionMapPayload`:** Transmits region ownership, cluster IDs, and siege states to client map terminals.
* **`RequestRegionDetailsPayload` / `RegionDetailsPayload`:** Transmits resistance, stability, base type, and domino thresholds for selected regions.
* **`LaunchAttackPayload` / `CancelAttackPayload`:** Client commands authorizing or canceling war campaigns.
* **`ActiveMissionHudPayload`:** Transmits active subregion mission objectives, kill targets, and progress counters directly to the player's overlay.

---

# 32. Complete Verified Codebase Structure & File Map

```text
src/main/java/com/warfront/
├── Warfront.java                             // Mod entry point & event bus setup
├── ai/
│   ├── AIAttackManager.java                 // AI faction strategic expansion engine
│   ├── goal/
│   │   ├── AdvanceToLocationGoal.java       // Phase 1: Marching to frontline waypoint
│   │   └── HoldGroundGoal.java              // Phase 3: Defensive 8x8 spread formation
│   └── strategy/
│       ├── AttackCandidate.java             // AI target scoring
│       ├── FactionAttackStrategy.java       // Faction behavioral strategies
│       └── FrontlineShape.java              // Types 1-4 mathematical battle lines
├── block/
│   ├── WarfrontBlocks.java                  // Terminal blocks & spawn egg item registrations
│   └── WarfrontBlockEntities.java          // Map terminal tile entity
├── client/
│   ├── hud/
│   │   └── ActiveMissionHudRenderer.java    // In-game top-center mission tracker UI
│   ├── map/
│   │   ├── RegionMapScreen.java             // Interactive map terminal GUI
│   │   └── RegionMapRenderer.java           // Biome underlay & territory renderer
│   ├── model/
│   │   ├── PillagerArmoredEliteModel.java   // GeckoLib GeoModel for Armored Elite
│   │   ├── PillagerWarriorModel.java        // GeckoLib GeoModel for Warrior
│   │   ├── PillagerScoutModel.java          // GeckoLib GeoModel (with weapon visibility)
│   │   ├── PillagerMarksmanModel.java       // GeckoLib GeoModel for Marksman
│   │   └── PillagerCommanderModel.java      // GeckoLib GeoModel (with weapon visibility)
│   └── renderer/
│       └── [5 Custom GeoEntityRenderer classes]
├── entity/
│   ├── ModEntities.java                     // DeferredRegister for EntityTypes
│   ├── PillagerArmoredEliteEntity.java      // Armored Elite entity logic
│   ├── PillagerWarriorEntity.java           // Warrior entity logic (Tick 9 contact frame)
│   ├── PillagerScoutEntity.java             // Scout entity logic (Tick 5 combo slashes)
│   ├── PillagerMarksmanEntity.java          // Marksman entity logic (3-stage sniper AI)
│   └── PillagerCommanderEntity.java         // Commander entity logic (Backline tactics & rally)
├── mission/
│   ├── ActiveCampaignMissionManager.java    // Active war campaign state & lifecycle
│   ├── KillCountMissionHandler.java         // Mission wave spawning & objective monitoring
│   └── FactionMissionGenerator.java         // Deterministic mission assignment
├── region/
│   ├── RegionData.java                      // Strategic territory container & sieges
│   └── strength/
│       └── RegionalStrengthCalculator.java  // Biome-derived resistance & stability
└── spawn/
    ├── AttackRoamerManager.java             // Siege squad manager & border evaluation
    ├── EnemyEncounterSpawner.java           // Wave spawner & 16-point hold-ground grid
    ├── EnemyEntityResolver.java             // Role-to-entity factory mapping
    ├── EnemyResistanceTier.java             // Minimal to Extreme resistance thresholds
    └── RoamingEntityTracker.java            // AI distance hysteresis optimizer
```

---

# 33. Verification & Testing Guide

### 33.1. Entity Summoning Commands
```mcfunction
# Test individual custom units
/summon warfront:pillager_marksman ~ ~ ~
/summon warfront:pillager_warrior ~ ~ ~
/summon warfront:pillager_scout ~ ~ ~
/summon warfront:pillager_commander ~ ~ ~
/summon warfront:pillager_armored_elite ~ ~ ~

# Test mixed formation (Commander in rear, Warriors in front, Marksman supporting)
/execute run summon warfront:pillager_warrior ~2 ~ ~
/execute run summon warfront:pillager_warrior ~-2 ~ ~
/execute run summon warfront:pillager_marksman ~ ~ ~3
/execute run summon warfront:pillager_commander ~ ~ ~-4
```

### 33.2. Strategic Campaign Testing
1. **Place & Open Map Terminal:** Right-click the terminal block to open the interactive strategic map.
2. **Select Enemy Region:** Click an adjacent hostile Pillager region (red territory).
3. **Verify Dossier:** Inspect calculated resistance, stability, base type, and assigned subregion missions.
4. **Authorize Attack:** Select subregions and launch attack.
5. **Observe Invasion Squads:** Hostile squads emerge from the border, march along frontline geometry, and hold formation. Check server logs for:
   `[ATTACK ROAMER] Spawned squad ... from Source Region (X, Z) [Attacker Res: ... -> Tier: ...]`
6. **Engage in Combat:** Experience telegraphing axe swings, quick dagger slashes, physical crossbow projectile fire, and commander buff horns.

---

# 34. Current Implementation State

* [x] **Strategic Grid & Subregions:** $128 \times 128$ regions, $64 \times 64$ subregions, procedural generation, fast raw-state caching.
* [x] **Faction Expansion AI:** AI attack candidates, BFS cluster evaluation, siege campaigns, defense timers.
* [x] **Mission Campaign Engine:** Attack launching, active campaign tracker, objective progress, sector capture, domino collapse.
* [x] **Interactive Strategic Terminal:** Biome underlay, zoom/pan, territory color overlays, siege boundary effects, atomic update networking.
* [x] **In-Game Mission HUD:** Real-time top-center objective tracking and kill counter display.
* [x] **Frontline Siege Engine:** Mathematical frontline shapes (Types 1–4), directional advance waypoints, $8 \times 8$ hold-ground formations.
* [x] **Siege Squad Scaling:** Border source resistance resolution, Minimal to Extreme difficulty tiers, squad scaling from 4 to 14 units.
* [x] **Squad Composition & Leaders:** Centered squad leaders with 65% Commander spawn chance at High/Extreme, strictly 1 Commander per squad.
* [x] **Full Pillager Custom Roster (GeckoLib 4.7.4):** Warrior, Scout, Marksman, Commander, Armored Elite.
* [x] **Combat AI Specialization:** Sniper distancing, kiting, backline command positioning, rally buff ceremonies, dynamic unsheathe/sheathe stance machines.
* [x] **Combat Animation Polish:** Delayed damage impact contact frames, dynamic weapon bone visibility, server-synced sprinting locomotion.
* [x] **Creative Tab & Spawn Eggs:** 5 custom spawn eggs with pixel-art textures and item models.

---

# 35. Immediate Roadmap & Next Horizons

### Next Immediate Goals:
1. **Commander Buff Gameplay Effects:** Implement active stat manipulation when the Commander performs rally moves (e.g. `banner_swing` granting Resistance/Absorption to allies, `sword_raise` granting Speed/Strength).
2. **Zombie Horde GeckoLib Roster:** Design and integrate custom models and animations for the Zombie faction (Fodder, Fast Chaser, Ranged Spewer, Tank, Hivemind Controller).
3. **Catapult Base-Defense Unit:** Implement the stationary siege weapon unit for major Pillager fortresses and mega bases.
4. **Varied Tactical Objective Types:** Expand beyond kill counts into sabotage (destroy ammunition caches), capture points (hold radar beacon), and defend/extract missions.