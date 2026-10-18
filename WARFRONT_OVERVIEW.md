# Warfront Mod — Comprehensive Master Overview, Architecture & Milestone Dossier

**Target Platform:** Minecraft 1.21.1 / NeoForge 21.1.x / GeckoLib 4.7.4  
**Document Purpose:** Authoritative architectural blueprint, core design invariants guide, milestone checklist, and developer heads-up ledger. Consult this document before proposing or implementing changes.

---

## 1. Core Vision & Architectural Invariants

Warfront transforms Minecraft's localized mob spawning into a persistent, macro-strategic regional warfare ecosystem.

### Non-Negotiable Invariants:
1. **Decoupled Layer Architecture:**
   * **Strategic Layer:** $128 \times 128$ block regions, cluster generation, frontlines, ownership, domino capture, stability, and resistance.
   * **Tactical / Mission Layer:** $64 \times 64$ block subregions, wave encounters, objective tracking, HUD notifications, and capture thresholds.
   * **Physical Base Placement Layer:** Decoupled from mathematical region centers. Base anchors are stored authoritative block coordinates.
   * **Entity Combat / Animation Layer:** Custom GeckoLib 4.7.4 models, root-motion/stance state machines, telegraphing animations, and contact-frame damage delays. No combat code directly affects macro territory math.
2. **Deterministic Procedural Generation with Persistent Authority:**
   * Unvisited regions evaluate baseline territory, base tier, and anchors deterministically from `(worldSeed, regionX, regionZ, salt)`.
   * The moment a region is visited or modified, its `RegionState` (including `baseAnchor`) is **authoritative and persisted in NBT**.
   * Downstream systems (missions, spawners, map renderers) must **always** consume stored state rather than re-rolling procedural math.
3. **Graceful Degradation Over Destructive Vetoes:**
   * Faction clusters never disappear due to terrain or rivers.
   * Base placement attempts the desired tier (Mega Base $\rightarrow$ Headquarters $\rightarrow$ Outpost $\rightarrow$ NONE). If terrain cannot fit a structure, ownership cleanly remains with `BaseType.NONE` (territory flag).
4. **Zero-Chunk-Load Server Performance ($O(1)$ Worldgen Queries):**
   * Map rendering and regional queries must **never trigger chunk generation or 3D noise density router evaluations** (`getBaseHeight`).
   * Procedural terrain safety relies on $O(1)$ Voronoi multi-noise climate lookups (`level.getBiome`).
5. **Native NBT Structure Templates Over Procedural Scripts (Strict Mandate):**
   * All buildings, fortresses, castles, outposts, and headquarters MUST be native Minecraft Structure Templates (`.nbt` files) under `src/main/resources/data/warfront/structure/...`.
   * Hardcoded Java block-by-block generation is **strictly forbidden** for architecture. Procedural scripts are restricted to dynamic stepped foundation columns, bounding box air clearing, and minor ambient dressing.
6. **Non-Recursive Alliance Verification:**
   * Mobs within the same faction must never fight each other. All alliance checks route through `AlliedFactionHelper` using teams, tags, and IDs directly—never calling `Entity.isAlliedTo` re-entrantly.
7. **Universal Territory Baseline:**
   * All player territory captures across every source (Claim Cores, campaign domino, Field Battles, admin commands) initialize at flat **20.0 Resistance / 20.0 Stability** (subregions at 20.0 Stability).

---

## 2. World Partitioning & Strategic Territory

```text
               STRATEGIC REGION (128 × 128 Blocks / 8 × 8 Chunks)
    ┌───────────────────────────────┬───────────────────────────────┐
    │        Subregion (0, 0)       │        Subregion (1, 0)       │
    │     64 × 64 Blocks (4×4 Chk)  │     64 × 64 Blocks (4×4 Chk)  │
    ├───────────────────────────────┼───────────────────────────────┤
    │        Subregion (0, 1)       │        Subregion (1, 1)       │
    │     64 × 64 Blocks (4×4 Chk)  │     64 × 64 Blocks (4×4 Chk)  │
    └───────────────────────────────┴───────────────────────────────┘
```

* **Strategic Region ($128 \times 128$ blocks / $8 \times 8$ chunks):** Container for owner, resistance (0–100), stability (0–100), base type, cluster ID, base anchor, and active sieges.
* **Subregion ($64 \times 64$ blocks / $4 \times 4$ chunks):** 4 subregions per region: `(0,0)`, `(1,0)`, `(0,1)`, `(1,1)`. The atomic unit for missions, patrol squads, and wave defense.
* **Dynamic 3×3 Base Aura Boosting (`calculateSurroundingBaseBoost`):**
  - **Mega Base (+20.0):** Radiates strictly to its 4 cardinal neighbors (corners excluded).
  - **Headquarters (+15.0):** Radiates strictly to its 4 cardinal neighbors (corners excluded).
  - **Outpost (+10.0):** Radiates across the entire 3×3 grid (corners included).
  - Eliminates interior low-resistance corridors and enforces strategic progression: conquering satellite bases dynamically weakens central strongholds.
* **Domino Capture Invariant:**
  - Securing required adjacent subregions triggers a domino collapse to claim the entire region.
  - Baseline stability (38.0 on neutral ground) requires 2 sectors; harsh biomes allow 1 sector.
  - **Mandatory Base Rule:** Domino collapse is **strictly blocked** until all mandatory base missions in the region are completed (`areAllMandatoryBaseMissionsSecured == true`).

---

## 3. Factions & Combat Rosters

### 3.1. The Factions
* **Humanity (Player):** Expanding defensive alliance and player-created factions.
* **Pillager Conquerors:** Militaristic, disciplined army.
* **Zombie Horde:** Swarm-based organic blight (currently using vanilla mob proxies pending GeckoLib roster).

### 3.2. Custom Pillager Roster (GeckoLib 4.7.4)
1. **Pillager Warrior (`warfront:pillager_warrior`):** Shock infantry with an iron battleaxe and multipart tower shield (`PillagerWarriorShieldPart`). Physical shield hitbox absorbs bullets, arrows, and melee attacks; axe hits disable shield for 10 seconds (`shieldDisableTicks = 200`). Strikes actively while shield is raised (`performShieldAttack`).
2. **Pillager Scout (`warfront:pillager_scout`):** Agile flanker with an iron dagger. High movement speed, evasive retreat leaps when wounded, rapid 2-hit combo slashes.
3. **Pillager Marksman (`warfront:pillager_marksman`):** Long-range sniper crossbowman with 3-stage combat state machine (Aim $\rightarrow$ Shoot $\rightarrow$ Reload) and tactical distancing AI.
4. **Pillager Commander (`warfront:pillager_commander`):** Tactical field officer wielding a saber and back standard. Squad leader AI, rally horn buffs, and stationary war desk guard.
5. **Pillager Armored Elite (`warfront:pillager_armored_elite`):** Heavy frontline juggernaut with plate armor, high knockback resistance, and steady advance.

### 3.3. Friendly Fire & Alliance Invariants
* `AlliedFactionHelper.isAllied(...)` evaluates alliance via:
  1. `isPillager(a) && isPillager(b)`
  2. `a instanceof Zombie && b instanceof Zombie`
  3. Direct Scoreboard Teams: `a.getTeam().isAlliedTo(b.getTeam())` (never calls `Entity.isAlliedTo`)
  4. Persistent NBT `squadId` match or `faction` ID match.
* `LivingChangeTargetEvent` cancels ally targeting without re-entrantly calling `mob.setTarget(null)` to prevent event dispatch stack overflows.

---

## 4. Strategic Map, Visualization & Networking

* **Map Terminal Blocks:** `MapTerminalBlock` and `TestingMapTerminalBlock` open the interactive `RegionMapScreen`.
* **Fog of War Views:** `SCOUT` (local player radius), `COMMAND` (visited regions in campaign), `DEBUG` (global omniscient view).
* **Hardware-Accelerated Rendering:** Arrow shafts and heads rendered as solid vertex quads (`RenderType.gui()`) with strict viewport frustum culling.
* **Tactical War Visualization:**
  - **Subregion Mission Mask:** Red combat mask applies strictly to $64 \times 64$ subregions with active, uncompleted missions. Secured sectors render in Humanity blue; unoccupied enemy sectors render in normal faction colors.
  - **Pulsating Frontline Borders:** Sine-wave breathing crimson perimeter with outer glow rendered around $8 \times 8$ regions at war.
  - **Central War Status Badges:** Floating transparent PNG emblems (`war_attack.png`, `war_defense.png`) centered on war regions, automatically hiding during subregion inspection/selection.
* **UI Controls:** Vertical 1-column stack of square subregion buttons with disabled states and tooltips when players are unaffiliated or outside allied territory.

---

## 5. Player Groups, Factions & PvP Warfare

* **Server-Side Data Storage:** `PlayerFactionData` persisted in `warfront_player_factions.dat`. Handles lifecycle: creation, join requests/approvals, invites, kicks, self-leaving, disbanding, and color customization.
* **In-Game Terminal GUI (FTB-Teams Style):** Top-header `FACTION` tab with scrollable member list, player skin avatars (`PlayerFaceRenderer`), custom chat history with interactive `[ACCEPT]` join requests, and owner settings.
* **Gameplay Enforcement:** Unaffiliated players are strictly forbidden from placing Claim Cores or initiating regional war campaigns.
* **Humanity vs. Humanity "Field Battle" (King of the Hill):**
  - Contested between rival player factions as a single unified $128 \times 128$ region-wide battle (not partitioned into subregions).
  - Differential player count scaling advances capture progress; equal combatant counts freeze in stalemate.
  - Dual progress bar HUD overlay (`ActiveMissionHudOverlay`) broadcast to players within the region.
* **War Protection & Anti-Hijack:** Contested regions are locked against third-party interference.
* **Bordering Region Attack Requirement:** Attacking factions must own at least one cardinal bordering region. Flank assaults from multiple borders provide converging attack arrows and combat bonuses.

---

## 6. Base Fortresses, Garrisons & Protection

### 6.1. Base Tiers & Footprints
* **Mega Base (Size 3):** $96 \times 96$ footprint ($6 \times 6$ chunks), 15% roll, requires MD $\le 3$ land domain.
* **Headquarters (Size 2):** $64 \times 64$ footprint ($4 \times 4$ chunks), 60% roll, requires MD $\le 2$ land domain.
* **Outpost (Size 1):** $32 \times 32$ to $64 \times 64$ footprint, 25% roll.
* **None:** Territory claim without a physical fortress structure.

### 6.2. Placement & River Filtering Rules
* **Land Viability:** Inner $4 \times 4$ chunks sampled; rejected if aquatic ratio $> 50\%$.
* **Anchor Center:** Must be solid dry land (non-ocean, non-river).
* **Footprint Density Grid:** Multi-tier grid sampling ($7 \times 7$ Mega, $5 \times 5$ HQ/Outpost) allowing max 20% water and zero ocean.
* **Downgrade Cascade:** $\text{MEGA\_BASE} \rightarrow \text{HEADQUARTERS} \rightarrow \text{OUTPOST} \rightarrow \text{NONE}$.
* **Persistence:** Chosen anchor stored in `RegionState.baseAnchor()` and serialized to NBT.

### 6.3. Outpost Structure & Garrison Ecosystem
* **Structure:** $29 \times 25 \times 29$ native template (`pillager_outpost.nbt`) placed via `TemplatePremadeStructure` with `yOffset = -1`, automatic 3D air clearing, and stepped foundation columns.
* **17-Mob Garrison Roster:**
  - 8 Tower Marksmen (NW, NE, SW, SE at $Y=8$) with stone brick stair traversal routines (`OutpostMarksmanPatrolGoal`) down to $Y=5$ perimeter wall walkways.
  - 1 Main Gate Sentry ($Y=5$, South-facing, permanent hold-post).
  - 4 Courtyard Warriors ($Y=1$) with quadrant leashes (`MissionPostGuardGoal`, $6\text{m}$ radius).
  - Multi-floor interior: Floor 1 sleeping quarters warrior, Floor 2 Commander beside war desks + 2 bodyguard warriors near stairs, Floor 3 ammo cache.
* **Lifecycle & Respawn Rules:**
  - 3-Minute Passive Respawns: Missing garrison mobs respawn after 3,600 ticks outside active missions.
  - Mission Start Instant Reset: Surviving guards are healed and enlisted; dead guards instantly respawn; structure instantly repairs (`instantRepairBase`). Periodic repair is suspended during active combat.
  - Regional Conquest Cleanup: When territory flips to Humanity, surviving garrison mobs disperse (`POOF` particles) and all respawn timers are permanently purged.
  - Curated Chest Loot (`OutpostLootManager`): Restrained supplies in 4 tower chests, 2 barracks chests, and 1 double chest. Inventories wiped cleanly via `clearContent()` on sortie defeat.

---

## 7. Tactical Missions & Salvage System

### 7.1. Deterministic Missions & Difficulty Tiering
* Deterministic seed: $\text{missionSeed} = \text{worldSeed} \oplus (\text{regionX} \times 73856093) \oplus (\text{regionZ} \times 19349663) \oplus \text{salt}$.
* High-entropy SplitMix64 mixing with regional quota cap (`MAX_MISSION_TYPE_INSTANCES_PER_REGION = 2`) and repeat dampening ($0.45$).
* **Easy Tier ($< 50\%$ Resistance):**
  1. `KILL_COUNT` — Eliminate Hostiles (Neutralize boosted patrol squads; 40–50 kills).
  2. `SUPPLY_CONVOY` — Supply Convoy (Intercept moving GeckoLib wagon cart + trailer + 6 escorts).
  3. `FORWARD_OUTPOST` — Forward Outpost (Demolish $8 \times 8$ watchtower; $\ge 60\%$ destruction or core breach).
  4. `SCOUT_NETWORK` — Scout Network (Sabotage 3 distributed sniper observation nests).
* **Hard Tier ($\ge 50\%$ Resistance):**
  1. `STRONGPOINT_ASSAULT` — Strongpoint Assault (Assault $20 \times 20$ bunker; dual victory: disarm 2 shield relays or $\ge 50\%$ block demolition).
  2. `OFFICER_HUNT` — Officer Hunt (Infiltrate camp, assassinate Commander + survive 45s alarm counter-assault).
  3. `ARTILLERY_BATTERY` — Artillery Battery (Assault 3 mortar pits actively shelling sky-exposed players; sabotage ammo crates).
  4. `WAR_HORN_RELAY` — War Horn Signal Tower (Scale 14-block mast within 45s to sabotage summit fuse; repel border incursion waves).

### 7.2. Mandatory Base Missions
* Outpost: `OUTPOST_DESTROY_BUILDING` (1 subregion).
* Headquarters: `BASE_KILL_COMMANDER` + deterministic secondary (`BASE_DESTROY_INTEL` / `BASE_DESTROY_SUPPLIES`) (2 subregions).
* Mega Base: `MEGA_ELIMINATE_COMMAND` + 3 secondary variants (4 subregions).

### 7.3. Structure Spawning & Decay Lifecycle
* **$3 \times 3$ Subregion Trigger ($192 \times 192$ Blocks):** Structures and garrisons generate early when an attacking player approaches within $\pm 1$ subregion.
* **Anti-Stacking Guard:** Persistent `temporaryStructuresBuilt` flag prevents duplicate builds across unloads or repeated triggers.
* **Three Decay Scenarios:**
  1. *Zero Missions Completed on Timeout:* Immediate decay via `MissionSiteSnapshotManager` with POOF particles.
  2. *Subregion Won, War Lost:* Prize blocks disarm; player can salvage during war; decays once war timer expires.
  3. *War Won:* Conquered structures with unmined salvage blocks persist indefinitely via `PendingSalvageManager`. Harvesting the final prize block triggers site decay.
* **Secure-to-Salvage Protocol:**
  - **Sabotage Blocks:** Volatile hazard targets (Ammo Crates, Shield Relays, Signal Fuses) with 0.5F blast resistance advancing combat objectives.
  - **Salvage Prize Blocks:** Armored machinery (Mortar Cannon, Machinery Vault, War Desk, Horn Bell) unbreakable during combat (`DISARMED = false`), disarming on victory to drop pristine cores (`SALVAGED_MORTAR_CORE`, `HEAVY_PIVOT_BEARING`, `REINFORCED_REDOUBT_PLATE`, `ACOUSTIC_WAR_HORN_RELAY`, `COMMANDER_BATTLE_STANDARD`).
* **War Commitment:** Voluntary cancel button removed. Assault timeout results in defending sector hardening (+10 Resistance & +10 Stability).

---

## 8. Encountered & Fixed Pitfalls (Heads-Up for Developers & Agents)

To prevent regression of critical architectural bugs, keep these lessons in mind:

1. **Alliance Recursion & Event Loops:**
   - *Problem:* Calling `Entity.isAlliedTo` inside `AlliedFactionHelper` caused infinite mutual recursion and stack overflows. Re-entrantly calling `mob.setTarget(null)` inside `LivingChangeTargetEvent` caused recursive event dispatches.
   - *Rule:* Always evaluate teams, tags, squads, and IDs directly in `AlliedFactionHelper`. Use `event.setNewAboutToBeSetTarget(null)` and `event.setCanceled(true)` inside target listeners.
2. **Noise Router Lag During Worldgen Queries:**
   - *Problem:* Calling `getBaseHeight` or standard density routers across unloaded chunks in macro map/region loops caused severe 2–3s server freezes.
   - *Rule:* Strategic territory queries must use $O(1)$ Voronoi climate lookups (`level.getBiome`) and never force chunk generation or 3D noise router sampling.
3. **Objective Mob Distance Despawning:**
   - *Problem:* Generic distance culling ($>64$ blocks) discarded mission-critical objective mobs (Commanders, convoy wagons, mortar guards) when players kited or took cover.
   - *Rule:* Tag critical mission mobs with `preventActiveDespawn = true` and `setPersistenceRequired()`. Ensure `onCleanup` explicitly discards tracked entities on sortie completion.
4. **GeckoLib Animation vs Programmatic Overrides:**
   - *Problem:* Hardcoding bone positions in Java renderers (e.g. Mortar barrel `setPosZ(0.0F)`) unconditionally crushed active GeckoLib keyframe animations. Inverting rotation angles on Y axes caused Ammo Racks to face backwards.
   - *Rule:* Let GeckoLib keyframes control bone positions; only programmatic dynamic adjustments (like turret pitch/yaw aim) should modify bones. Respect standard coordinate rotations (`WEST = 90.0F`, `EAST = 270.0F`).
5. **Duplicate Structure Stacking on Terminal Confirm:**
   - *Problem:* Confirming additional subregions in an ongoing campaign re-instantiated mission progress, resetting `temporaryStructuresBuilt` and generating duplicate overlapping structures.
   - *Rule:* Check `if (existing != null) continue;` when confirming campaigns, and guard builders with `if (progress.temporaryStructuresBuilt()) return;`.
6. **Warrior Shield AI Freeze:**
   - *Problem:* Raising the tower shield disabled melee attack goals, causing warriors to stand frozen next to players in a passive trance.
   - *Rule:* Maintain active melee strike capability (`performShieldAttack`) within `WarriorShieldDefenseGoal` while keeping the shield raised for axe-stun counterplay.
7. **Runaway Movement Speeds from Sprint Multipliers:**
   - *Problem:* Calling `setSprinting(true)` applied vanilla's $+30\%$ speed multiplier on top of elevated base speed and goal modifiers, making warriors move at 16 blocks/s.
   - *Rule:* Do not invoke `setSprinting(true)` on custom GeckoLib combat mobs; configure their speed directly via base attributes and goal modifiers.
8. **Elevated Marksman AI Flag Contention:**
   - *Problem:* Retaining `Flag.MOVE` and `Flag.LOOK` in elevated post/patrol goals completely suppressed lower-priority attack goals, paralyzing snipers in combat.
   - *Rule:* Yield movement and look flags in post/patrol goals when an active target is present within vertical bounds, and suppress ground-closing navigation for elevated garrison marksmen.
9. **Synchronous Disk Reads in Hot Getters / Tick Paths:**
   - *Problem:* Calling file reading operations (`Files.readAllLines`) inside high-frequency getters (`RegionData.get`) accessed via the server tick loop (`AIAttackManager.onServerTick`) caused blocking disk I/O 20+ times per second.
   - *Rule:* `RegionData.get` must strictly be an in-memory $O(1)$ operation relying on authoritative NBT `SavedData`. Text event logs must be written via append-only writes, and tick-driven siege audits throttled.
10. **Redundant Iterative Spatial Scans in AI Expansion:**
    - *Problem:* Scanning 31×31 regional grids for every strategic cluster and repeatedly querying `getRegionsOwnedBy(HUMANITY)` inside inner candidate loops caused $O(N \times M)$ memory allocations and tens of thousands of duplicate evaluations per AI tick.
    - *Rule:* Precompute Humanity regions and player positions once per cycle. Filter scan origins to perimeter regions (adjacent to non-Humanity), and cache discovered faction frontline regions across clusters within the same evaluation cycle.
11. **Biped Bone Hierarchy & Action Combat Decoupling:**
    - *Problem:* Inheriting vanilla Minecraft's flat root-level bone hierarchy (head and arms as siblings to body) caused upper-body rotations and combat swings to disconnect from the chest, breaking fluid animation.
    - *Rule:* Parent `head`, `right_arm`, and `left_arm` under `body` (torso) so natural posture and combat swings carry shoulders and head organically. Keep `right_leg` and `left_leg` at root level to decouple running and walking from upper body combat.
12. **Melee Dogpiling & Anti-Lynching Coordination:**
    - *Problem:* Unrestricted pathfinding allowed arbitrary numbers of melee enemies to simultaneously dogpile ("lynch") players, creating chaotic and unreadable melee combat.
    - *Rule:* Restrict active melee combat against players to 2 shared engagement slots (`MeleeEngagementManager`). Excess melee mobs hold ground 5–6 blocks away (warriors maintaining shield stance), holding ground if approached, striking if player enters personal reach, and replacing dead combatants with Elite priority.
13. **Marksman Retreat-Aiming Desynchronization:**
    - *Problem:* Pathing backwards away from close players turned marksmen bodies in the direction of travel, causing arrows to shoot out of their backs while running away.
    - *Rule:* Halt retreat navigation and lock body yaw directly to the target during `STATE_AIMING` and `STATE_SHOOTING`. Resume retreat navigation only during reloading (`STATE_RELOADING`) or idle.
14. **Decoupled Combat Combo Extension vs Hitbox Arcs:**
    - *Problem:* Checking frontal look angle for combo continuation caused combat chains to prematurely break on swing 1 if the player circle-strafed, paralyzing aggressive duelists.
    - *Rule:* Chain combo sequences based strictly on target proximity (<= 3.5m) to maintain offensive momentum and tracking, while retaining directional frontal arc checks strictly on the individual strike damage ticks so players can dodge attacks via timing and positioning.
15. **Idle Goal Contention in Tick-Driven Custom AI:**
    - *Problem:* Unconditioned idle goals (`RandomStrollGoal`, `RandomLookAroundGoal`) in `goalSelector` randomly triggered during combat, turning duelists away from targets and causing attacks to swing into empty space.
    - *Rule:* Condition all idle movement and look goals on `!mob.isAggressive() && mob.getTarget() == null`. Explicitly synchronize body and head yaw (`yBodyRot`, `yHeadRot`, `setYRot`) directly to targets during active combat ticks so swings and lunges face opponents.

---

## 9. Current Milestone Implementation Ledger (What Is Complete)

- [x] **Strategic Grid Core:** $128 \times 128$ macro regions, $64 \times 64$ tactical subregions, persistent NBT world data storage.
- [x] **Procedural Placement & River Filtering:** River-aware viability thresholds, solid dry-land anchors, footprint sampling grids, and downgrade cascade.
- [x] **TPS Worldgen Optimization:** Bypassed 3D noise router for unloaded chunks during procedural evaluation.
- [x] **Pillager GeckoLib Combat Roster:** Custom models, textures, animations, and combat AI for Warrior, Scout, Marksman, Commander, and Pillager Elite (1v1 specialist duelist with two-handed greatsword & repeating crossbow).
- [x] **Tactical Tower Shield Sub-Entity:** Custom multipart collision hitbox with projectile absorption, crit particle feedback, and axe stun vulnerability.
- [x] **Allied Anti-Friendly-Fire System:** Centralized `AlliedFactionHelper`, non-recursive team/tag verification, target-change interception.
- [x] **Interactive Strategic Map GUI:** Zoom/pan viewport, faction color blending, hardware-accelerated vertex quad arrows, Fog of War pipeline, and 1-column square button layout.
- [x] **Tactical War Visualization:** Subregion-scoped active mission masks, pulsating frontline borders, and transparent central war status badges.
- [x] **Player Group & Faction System:** Server-side persistent storage (`PlayerFactionData`), FTB-Teams GUI, join approvals, chat history, and territory claiming restrictions.
- [x] **Humanity vs. Humanity Field Battles:** Region-wide $128 \times 128$ King of the Hill PvP warfare, live differential scaling, and dual progress bar HUD.
- [x] **Strategic Attack Requirements:** Cardinal bordering region attack requirement and flank assault converging arrows.
- [x] **Territory Initialization Parity:** Universal 20 Resistance & 20 Stability baseline across all player captures.
- [x] **Deterministic Mission Generation:** SplitMix64 mixing, regional quota cap (max 2 per type), and resistance tiering ($<50\%$ Easy, $\ge 50\%$ Hard).
- [x] **Easy-Tier Tactical Missions:** `KILL_COUNT` (patrol squads), `SUPPLY_CONVOY` (moving wagon + trailer + escorts), `FORWARD_OUTPOST` (watchtower), `SCOUT_NETWORK` (3 sniper nests).
- [x] **Hard-Tier Tactical Missions:** `STRONGPOINT_ASSAULT` (bunker), `OFFICER_HUNT` (camp), `ARTILLERY_BATTERY` (mortar pits), `WAR_HORN_RELAY` (signal mast).
- [x] **Mandatory Base Missions:** Signature tier objectives (Outpost, HQ, Mega Base) blocking domino collapse until cleared.
- [x] **Temporary Structure Spawning & Decay:** $3 \times 3$ subregion trigger, duplicate build guards, and 3-scenario decay lifecycle via `PendingSalvageManager` and `MissionSiteSnapshotManager`.
- [x] **Decoupled Sabotage vs. Salvage Blocks:** Volatile hazard targets vs armored prize blocks disarming on victory to drop pristine installation cores.
- [x] **Commitment to War:** Removal of voluntary cancel and defender hardening (+10/+10) on timeout.
- [x] **Dynamic 3×3 Base Boosting:** Mega Base (+20 cardinal), HQ (+15 cardinal), Outpost (+10 3×3) boosting neighbor resistance and stability.
- [x] **Pillager Outpost Structure & Garrison Ecosystem:** $29 \times 25 \times 29$ native NBT template, 17-mob structured garrison, wall patrol routines, 3-minute passive respawns, instant repair on sortie start, poof cleanup on conquest, and curated loot tables.
- [x] **Melee Combat Engagement Management & Anti-Lynching Slots:** Centralized 2-slot melee engagement system between Warriors and Elites (`MeleeEngagementManager`), 5–6 block perimeter holding ground, Warrior shield perimeter defense, and Elite death replacement priority.
- [x] **Crossbow Marksman Aiming & Retreat Decoupling:** Stationary aim-and-shoot body alignment towards player with tactical spacing and retreat relegated strictly to reloading windows.
- [x] **Pillager Elite Duelist 4-Stage Combo Chain:** Multi-stage greatsword combo chain (`attack_1` through `attack_4` finisher) with distance-based combo chaining ($\le 3.5\text{m}$), forward lunge impulses, directional frontal damage arcs, finisher recovery freeze, and dynamic stance switching between greatsword and repeating crossbow.
- [x] **Pillager Elite Shield-Punish Kick Attack:** 50% replacement roll against shield-wielding targets; at $0.60\text{s}$ contact disables blocking shields (5.0s cooldown), deals 1.0 damage, and inflicts 4 blocks of knockback. Whiffing against lowered shields incurs an extended 1.75s vulnerability freeze.

---

## 10. Active Development & Forward Roadmap

### Active Focus (In Progress):
- [ ] **Headquarters & Mega Base NBT Blueprints:** Designing, building, and exporting native `.nbt` templates for Big Base (HQ, $64 \times 64$) and Mega Base ($96 \times 96$) tiers.
- [ ] **Cluster Mobile Field Armies & Maneuver Warfare:**
  - Capacity: 1 army per Medium Base (HQ) in the cluster; armies occupy only non-base regions.
  - Interval Movement: Relocates across contiguous friendly cardinal neighbors back to cluster base.
  - Encirclement & Pocketing: Severing friendly connections immobilizes the army in an isolated pocket.
  - Stat Impact: Raises region Resistance to 80–90 levels and multiplies attack value in offensive sieges.
  - Decapitation Rule: Capturing parent HQ cuts army stat bonus in half (40–45 Resistance).
  - Combat Encounter: Replaces 4 subregion missions with a single region-wide "Field Battle / Army Extermination" mission.
- [ ] **Mob Component Drops & Bulk Logistics Loot Tables:** Tiered military component drops for mobs (rivets, fletchings, prisms, ciphers) and bulk engineering logistics loot tables for Supply Wagons.

### Planned Milestones:
- [ ] **Physical Player Fortifications:** Salvaged installation cores (mortar cores, redoubt plates, war horn relays) used to construct watchtowers, mounted ballistas, and artillery to fortify player territory beyond 20/20.
- [ ] **Zombie Horde Custom GeckoLib Roster:** Custom models, animations, and combat AI (Fodder, Chaser, Spewer, Tank, Hivemind Controller) replacing vanilla placeholders.
- [ ] **Future Incursion Factions (Piglin Expedition, Ender Eclipse):** Unclaimed frontier portal incursions, mercenary bartering, and reality-bending endgame siege mechanics.
- [ ] **Kinetic Military Automation Hooks:** Optional mechanical automation interfaces for player fortifications (Create mod compatibility).

---

## 11. Developer Rules for Future Iterations

1. **Always Check Stored State First:** Never re-derive or guess a base location or region owner if `RegionData` already has it stored.
2. **Never Re-Introduce Region-Wide Bans on Rivers:** Keep river checks localized to footprint viability and base-center aquatic ratio ($\le 50\%$).
3. **Keep `AlliedFactionHelper` Free of Recursion:** Never call `livingA.isAlliedTo(livingB)` inside `AlliedFactionHelper.isAllied`. Check tags, teams, squads, and IDs directly.
4. **Zero Improvised Behaviors:** Implement ONLY what the user explicitly directs and approves. Do not invent behaviors, UI layouts, or mechanics.
5. **Always Ask For Confirmation:** Create an implementation plan artifact for every task and wait for explicit user approval before modifying files.
6. **Preserve GeckoLib Performance:** Do not run client-side animation state logic on the server thread.
7. **Maintain Milestone Checklist & Invariants:** Keep this overview file clean. Update milestone checkmarks (`- [x]`) and record high-level architectural rules; do NOT append code changelogs, class walkthroughs, or bug post-mortems.