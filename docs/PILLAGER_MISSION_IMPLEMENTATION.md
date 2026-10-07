# Warfront — Pillager Mission System Redesign & Implementation Blueprint

**Document Version:** 1.0.0  
**Target Platform:** Minecraft 1.21.1 / NeoForge 21.1.x / GeckoLib 4.7.4  
**Status:** Living Design Specification & Architectural Roadmap  

---

## 1. Current Mission Architecture

The current Warfront mission system operates as a lightweight, procedural layer designed primarily for early territorial warfare testing:

1. **Procedural Generation & Selection:**
   * `FactionMissionGenerator`: Interface providing `generateMissionsWithSeedAndBase(...)`.
   * `PillagerMissionGenerator` & `ZombieMissionGenerator`: Static definition catalogs (`MissionDefinition`) containing `baseCountMin/Max`, `minResistance/maxResistance`, `baseWeight`, and `targetRoles`.
   * `WeightedMissionSelector`: Performs eligibility filtering and deterministic rolling using `seed ^ salt`.
   * `SubRegionMission`: Immutable record describing a subregion assignment (`type`, `targetCount`, `targetRoleName`, `displayName`, `isBaseMission`).
   * `MissionProfile`: Central dispatcher resolving generator by faction.

2. **Active Campaign Execution:**
   * `ActiveCampaignMissionManager`: Stores an in-memory `ConcurrentHashMap<Long, Map<Integer, ActiveSubRegionProgress>>`.
   * `ActiveSubRegionProgress`: Mutable tracker storing `currentKills`, `requiredKills`, `completed`.
   * `WarMissionHandler` / `KillCountMissionHandler`: Listens for player presence (`onPlayerInSubregion`) and mob kills (`onEntityKilled`), triggering `RegionData.claimSubRegion(...)` when `currentKills >= requiredKills`.

3. **Entity Lifecycle & Spawning:**
   * `EnemyEncounterSpawner`: Procedurally resolves entity roles and spawns mobs in dry-land positions within a 12-block scatter.
   * `MissionEntityTracker`: Tags spawned mobs with `missionInstanceId`, enforces a 64-block player tether, and executes staggered despawn with particle bursts when terminal.

4. **Strategic Base Placement:**
   * `BasePlacementManager` & `BaseBuildingRegistry`: Scans loaded chunks around players and places persistent strategic bases (e.g. `CobblestoneMonolithGenerator`, `PillagerOutpostCastleStructure`) at the authoritative `baseAnchor` coordinate.

5. **Networking & HUD:**
   * `ActiveMissionHudPayload` / `ActiveMissionHudOverlay`: Sends and renders `currentKills / requiredKills` and a simple progress bar on the client screen.

---

## 2. Problems with Current Implementation

While the current architecture successfully achieves deterministic rolling and zero-tick map browsing performance, it suffers from severe systemic limitations:

1. **Monolithic Kill-Count Paradigm:**
   * Every single mission type (`PATROL_SWEEP`, `HEAVY_SIEGE`, `MEGA_BREACH_GATE`, `BASE_DESTROY_INTEL`, etc.) delegates to `KillCountMissionHandler`.
   * Gameplay is identical across all missions: kill $N$ enemies. There is no concept of destroying structures, defending an objective, escorting a convoy, assassinating a specific VIP, or interacting with devices.

2. **No Mission Infrastructure Model:**
   * There is a strict dichotomy between **persistent strategic bases** (placed permanently at worldgen) and **empty fields** (where mobs spawn).
   * There is zero support for **temporary mission sites** (e.g., a field command tent, an artillery emplacement, an ammo depot) that appear when a mission begins and cleanly restore when it concludes.

3. **Inflexible Active State & Hardcoded Handlers:**
   * `ActiveCampaignMissionManager` directly invokes `KillCountMissionHandler.getInstance()`. It lacks polymorphic dispatch for different objective types.
   * `ActiveSubRegionProgress` only holds kill integers; it cannot track multi-stage progress, destroyed block coordinates, surviving VIP entity UUIDs, or defense timers.

4. **Zero Mission State Persistence:**
   * `ACTIVE_CAMPAIGN_MISSIONS` is held exclusively in static RAM. If a dedicated server restarts while players are mid-mission, all active campaign progress and instance IDs are lost, leaving the campaign in an orphaned state.

5. **HUD Coupled to Kill Quota:**
   * `ActiveMissionHudPayload` serializes `int currentKills, int requiredKills`. It cannot display dynamic task strings such as *"Artillery Pieces Destroyed: 2/3"* or *"Evacuation Convoy: Defend (01:45)"*.

---

## 3. New Mission Objective Categories

To break out of the kill-count trap, missions are governed by an extensible `ObjectiveType` enum and polymorphic `MissionObjectiveHandler` execution architecture:

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        OBJECTIVE TYPE TAXONOMY                         │
├─────────────────────┬───────────────────┬──────────────────────────────┤
│ Objective Type      │ Primary Goal      │ Success Criteria             │
├─────────────────────┼───────────────────┼──────────────────────────────┤
│ ELIMINATE_TARGET    │ Single mob hunt   │ Specific target mob slain    │
│ ELIMINATE_LEADER    │ Fortified boss    │ Named Commander/Officer dead │
│ ELIMINATE_TARGETS   │ Distributed squad │ Multiple tracked mobs dead   │
│ DESTROY_STRUCTURE   │ Single structure  │ Core block/structure broken  │
│ DESTROY_OBJECTIVES  │ Distributed assets│ Multiple props/blocks broken │
│ INTERCEPT           │ Moving group      │ Waypoint convoy halted/slain │
│ MULTI_OBJECTIVE     │ Mixed tasks       │ Props broken + escort dead   │
│ SURVIVE_ASSAULT     │ Timed defense     │ Survive attack for duration  │
│ PROTECT_OBJECTIVE   │ Asset defense     │ Objective block/entity alive │
│ ESCORT              │ Ally protection   │ Allied entity reaches target │
│ BREACH              │ Barrier breach    │ Gate/barrier structure broken│
│ MULTI_PHASE         │ Sequential stages │ Phase 1 done -> Phase 2 done │
└─────────────────────┴───────────────────┴──────────────────────────────┘
```

---

## 4. The 18 Pillager Missions: Detailed Specification

### 4.1. Easy Missions (Resistance < 50%)

#### 1. Forward Patrol
* **Concept:** Disrupt a light reconnaissance patrol operating near the frontline.
* **Objective Type:** `ELIMINATE_TARGETS`
* **Infrastructure:** **Dynamic Encounter** (no structures).
* **Mechanics:** Spawns a cohesive 4–5 mob patrol squad (Scouts & Warriors) walking along a subregion border path. Killing all squad members completes the mission.

#### 2. Supply Convoy
* **Concept:** Intercept a logistical supply pack moving between hostile positions.
* **Objective Type:** `INTERCEPT` followed by `DESTROY_OBJECTIVES`
* **Infrastructure:** **Dynamic Encounter** with temporary prop.
* **Mechanics:** Spawns a marching pack beast (or logistics runner) escorted by 3 guards traversing toward an exit boundary. Players must kill the escort and smash the dropped/carried Supply Crate prop.

#### 3. Forward Outpost
* **Concept:** Demolish an entrenched forward scouting watchpost.
* **Objective Type:** `DESTROY_STRUCTURE`
* **Infrastructure:** **Temporary Mission Site** (Small: $12 \times 12$ footprint).
* **Mechanics:** Places a small wooden palisade watchtower with a central Command Beacon / Banner block. Demolishing the central block and clearing the 4 garrison guards secures the subregion.

#### 4. Scout Network
* **Concept:** Neutralize multiple forward observation posts before intelligence reaches high command.
* **Objective Type:** `DESTROY_OBJECTIVES`
* **Infrastructure:** **Dynamic Encounter** with small props ($3 \times 3$ lookout nests).
* **Mechanics:** 2 to 3 small lookout observation nests generate across the subregion, each occupied by a sniper Marksman and an Observation Relay block. Destroying all observation posts completes the mission.

---

### 4.2. Hard Missions (Resistance $\ge$ 50%)

#### 5. Strongpoint Assault
* **Concept:** Breach and neutralize a heavily fortified pillager redoubt.
* **Objective Type:** `DESTROY_STRUCTURE`
* **Infrastructure:** **Temporary Mission Site** (Medium: $24 \times 24$ footprint).
* **Mechanics:** A reinforced cobblestone and dark oak redoubt housing elite defenders. Players must breach the redoubt core and destroy the Fortified Core block while enduring garrison fire.

#### 6. Officer Hunt
* **Concept:** Infiltrate a defended field headquarters and assassinate a high-ranking Pillager Officer.
* **Objective Type:** `ELIMINATE_LEADER`
* **Infrastructure:** **Temporary Mission Site** (Medium: $20 \times 20$ fortified compound).
* **Mechanics:** Generates an enclosed officer compound. Spawns a unique named field commander mob (`Pillager Officer`, boosted armor, battle standard) guarded by Armored Elites. Mission succeeds the moment the Officer is slain.

#### 7. Artillery Battery
* **Concept:** Sabotage heavy field mortar / catapult emplacements shelling the frontline.
* **Objective Type:** `DESTROY_OBJECTIVES`
* **Infrastructure:** **Temporary Mission Site** (Medium: $24 \times 24$ gun pit).
* **Mechanics:** Generates 2 fortified artillery gun pit emplacements, each containing an Artillery Gun objective block and munitions barrels. Destroying both artillery assets secures the objective.

#### 8. Cut the Supply Line
* **Concept:** Sever a distributed logistics route by striking multiple supply targets.
* **Objective Type:** `MULTI_OBJECTIVE`
* **Infrastructure:** **Temporary Mission Site** (Distributed: 3 separate small sites across the subregion).
* **Mechanics:** Generates 3 distinct logistics targets spaced 20–30 blocks apart: (A) Supply Depot crate cluster, (B) Fuel/Munitions wagon, and (C) Quartermaster tent. All 3 must be destroyed.

---

### 4.3. Mega-Base Missions (Associated with Persistent Base Anchor)

These missions integrate directly with the $96 \times 96$ persistent Mega Base citadel or its satellite subregions:

#### 9. Command Bunker (Anchor Subregion Signature)
* **Concept:** Infiltrate the citadel inner sanctum and eliminate Pillager High Command.
* **Objective Type:** `ELIMINATE_LEADER`
* **Infrastructure:** **Persistent Strategic Base** (Citadel Keep).
* **Mechanics:** Targets the High Commander located inside the fortress throne/bunker room. Defeating the Supreme Commander breaks the base command hierarchy.

#### 10. Break the War Room
* **Concept:** Obliterate the central tactical war room and strategic map charts.
* **Objective Type:** `DESTROY_OBJECTIVES`
* **Infrastructure:** **Persistent Strategic Base** (HQ Tactical Chamber).
* **Mechanics:** Destroy 3 strategic War Maps and the Central Telemetry Table within the fortified headquarters wing.

#### 11. Munitions Depot
* **Concept:** Infiltrate and detonate the primary explosive stockpiles fueling the enemy war effort.
* **Objective Type:** `DESTROY_OBJECTIVES`
* **Infrastructure:** **Persistent Strategic Base** or **Adjacent Fortified Bunker**.
* **Mechanics:** Locate and ignite/destroy 4 Heavy Munitions Powder Kegs in the fortress magazine, triggering secondary decorative blast poofs.

#### 12. Break the Gates
* **Concept:** Shatter the heavy reinforced citadel blast gates to allow friendly vanguard breach.
* **Objective Type:** `BREACH`
* **Infrastructure:** **Persistent Strategic Base** (Citadel Gatehouse).
* **Mechanics:** Destroy the massive reinforced iron Portcullis / Gate blocks guarding the main entryway while fending off arrow slits and murder holes.

#### 13. Silence the Guns
* **Concept:** Knock out the fortress heavy artillery and anti-air batteries protecting air corridors.
* **Objective Type:** `DESTROY_OBJECTIVES`
* **Infrastructure:** **Persistent Strategic Base** (Bastion Ramparts).
* **Mechanics:** Scale fortress bastions and disable 3 heavy wall-mounted artillery emplacements.

#### 14. Sever Communications
* **Concept:** Destroy the long-range transmitter array linking the base to the faction high command.
* **Objective Type:** `DESTROY_OBJECTIVES`
* **Infrastructure:** **Persistent Strategic Base** (Communications Tower).
* **Mechanics:** Destroy the communications relay dish and 2 subterranean relay conduits.

---

### 4.4. Defense Missions (Defending Allied / Contested Territory)

#### 15. Hold the Line
* **Concept:** Defend a frontier forward position against sustained assault waves.
* **Objective Type:** `SURVIVE_ASSAULT`
* **Infrastructure:** **Dynamic Encounter** (or existing frontier trenches).
* **Mechanics:** A 3-minute survival countdown. Pillagers attack in 3 distinct timed assault waves. Surviving the duration with player alive inside the subregion secures the defense.

#### 16. Protect the Strongpoint
* **Concept:** Defend an allied bunker, radar relay, or beacon from being demolished by Pillager shock troops.
* **Objective Type:** `PROTECT_OBJECTIVE`
* **Infrastructure:** **Temporary Mission Site** (Allied Bunker / Objective Block).
* **Mechanics:** A central allied Strongpoint Core block has a health pool (or hit durability). Pillagers spawn targeting the core. The player must protect it until the siege assault timer expires.

#### 17. Emergency Evacuation
* **Concept:** Escort a civilian / engineer evacuation transport safely across the warzone.
* **Objective Type:** `ESCORT` / `PROTECT_OBJECTIVE`
* **Infrastructure:** **Dynamic Encounter** (Escort route).
* **Mechanics:** An allied NPC transport wagon or officer marches along a designated route toward an extraction subregion border. Player must defend the transport from ambushing Pillager skirmishers.

#### 18. Counterattack
* **Concept:** Endure the initial enemy breakthrough, then strike back and eliminate the attacking field commander.
* **Objective Type:** `MULTI_PHASE` (`SURVIVE_ASSAULT` $\rightarrow$ `ELIMINATE_LEADER`)
* **Infrastructure:** **Dynamic Encounter** followed by **Temporary Command Tent**.
* **Mechanics:**
  * **Phase 1 (Defense):** Repel a 90-second assault wave.
  * **Phase 2 (Counterattack):** Once the assault breaks, enemy Commander flees to a rear staging tent. Player has 2 minutes to assault the tent and eliminate the leader.

---

## 5. Infrastructure Classification Matrix

```text
┌────┬─────────────────────────┬──────────────────────┬──────────────────────────────────┐
│ #  │ Mission Name            │ Objective Type       │ Infrastructure Classification    │
├────┼─────────────────────────┼──────────────────────┼──────────────────────────────────┤
│ 1  │ Forward Patrol          │ ELIMINATE_TARGETS    │ Dynamic Mission Encounter        │
│ 2  │ Supply Convoy           │ INTERCEPT            │ Dynamic Mission Encounter + Prop │
│ 3  │ Forward Outpost         │ DESTROY_STRUCTURE    │ Temporary Mission Site (Small)   │
│ 4  │ Scout Network           │ DESTROY_OBJECTIVES   │ Dynamic Encounter + Props        │
│ 5  │ Strongpoint Assault     │ DESTROY_STRUCTURE    │ Temporary Mission Site (Medium)  │
│ 6  │ Officer Hunt            │ ELIMINATE_LEADER     │ Temporary Mission Site (Medium)  │
│ 7  │ Artillery Battery       │ DESTROY_OBJECTIVES   │ Temporary Mission Site (Medium)  │
│ 8  │ Cut the Supply Line     │ MULTI_OBJECTIVE      │ Temporary Mission Site (Spaced)  │
│ 9  │ Command Bunker          │ ELIMINATE_LEADER     │ Persistent Strategic Base        │
│ 10 │ Break the War Room      │ DESTROY_OBJECTIVES   │ Persistent Strategic Base        │
│ 11 │ Munitions Depot         │ DESTROY_OBJECTIVES   │ Persistent Strategic Base        │
│ 12 │ Break the Gates         │ BREACH               │ Persistent Strategic Base        │
│ 13 │ Silence the Guns        │ DESTROY_OBJECTIVES   │ Persistent Strategic Base        │
│ 14 │ Sever Communications    │ DESTROY_OBJECTIVES   │ Persistent Strategic Base        │
│ 15 │ Hold the Line           │ SURVIVE_ASSAULT      │ Dynamic Mission Encounter        │
│ 16 │ Protect the Strongpoint │ PROTECT_OBJECTIVE    │ Temporary Mission Site (Allied)  │
│ 17 │ Emergency Evacuation    │ ESCORT               │ Dynamic Mission Encounter        │
│ 18 │ Counterattack           │ MULTI_PHASE          │ Dynamic -> Temporary Site        │
└────┴─────────────────────────┴──────────────────────┴──────────────────────────────────┘
```

---

## 6. Site Generation Lifecycle & Zero-Lag Bounded Placement

### 6.1. The Principle of Lazy Materialization
* When a player browses the map or generates missions, **NO blocks are placed in the world**. The mission exists only as an immutable mathematical descriptor.
* Blocks are **ONLY placed when the campaign is actively launched** (`LaunchAttackPayload`), and only if players approach or engage the subregion.

### 6.2. Deterministic Anchor Resolution
For temporary mission sites, the anchor position is computed deterministically:
$$\text{siteSeed} = \text{missionSeed} \oplus (\text{subX} \times 104729) \oplus (\text{subZ} \times 224737)$$
* A cheap bounded search scans candidate offsets inside the $64 \times 64$ subregion bounding box (staying 12 blocks away from borders).
* Evaluates surface $Y$ using `findDryLandSurfaceY` (checking that footing is solid ground and non-water).
* If valid, this coordinate is recorded in the active mission state as `missionSiteAnchor`.

### 6.3. Bounded Site Footprints
To prevent chunk generation stalls:
* Small sites (`Forward Outpost`): $12 \times 12$ blocks.
* Medium sites (`Strongpoint`, `Artillery`, `Officer Compound`): $20 \times 20$ blocks.
* Structure templates are built programmatically or from compact NBT jigs that place foundation blocks downwards to prevent floating structures on uneven terrain.

---

## 7. Site Cleanup & World Restoration Architecture

### 7.1. The `MissionBlockSnapshot` System
Temporary mission sites must not leave permanent scars or allow players to duplicate materials indefinitely:
1. When a temporary site generates, a `MissionSiteSnapshot` records:
   * Bounding box `[minPos, maxPos]`.
   * Map of `BlockPos -> BlockState` for all modified blocks (or a sparse differential map).
2. When the mission completes, fails, expires, or is cancelled:
   * The snapshot restores the original blocks (or safely removes placed structure blocks, restoring air/terrain).
   * Placed objective props (beacons, crates, artillery guns) despawn.
   * Leftover mobs are processed by `MissionEntityTracker` (staggered poof despawn).

---

## 8. State Persistence & Server Authority

### 8.1. Moving Active Missions to Persistent Storage
Currently, `ActiveCampaignMissionManager` stores active progress only in RAM. This must be moved into persistent NBT:
* Add `CompoundTag active_campaign_missions` inside `RegionData` NBT.
* Each active subregion progress serializes:
  * `missionInstanceId` (UUID)
  * `missionType` (String)
  * `objectiveType` (String)
  * `targetFaction` (int)
  * `progressValue` / `requiredValue` (int)
  * `missionSiteAnchor` (optional BlockPos)
  * `phaseIndex` (int for multi-phase missions)
  * `completed` (boolean)
  * `trackedEntityUuids` (UUID list)
  * `trackedBlockPositions` (BlockPos list)

On server reboot or level load, `RegionData` rehydrates `ActiveCampaignMissionManager`, preventing lost progress or desynced sieges.

---

## 9. Network Payload & Client HUD Modernization

### 9.1. Generic Objective HUD Payload
Replace `currentKills / requiredKills` in `ActiveMissionHudPayload`:
```java
public record ActiveMissionHudPayload(
        boolean hasActiveMission,
        int regionX, int regionZ, int subX, int subZ,
        String missionName,
        String objectiveDescription, // e.g. "Demolish Fortified Core", "Survive Assault"
        int currentProgress,
        int targetProgress,
        String progressDisplayString, // e.g. "1/3", "ALIVE", "01:45", "75%"
        long remainingTicks,
        int factionId,
        boolean isDefense
) implements CustomPacketPayload
```
This cleanly decouples the client rendering overlay from kill counts, allowing any objective type to display its exact status.

---

## 10. Integration with Capture & Domino Mechanics

1. **Subregion Completion:**
   * Regardless of whether the objective was `DESTROY_STRUCTURE`, `ELIMINATE_LEADER`, or `SURVIVE_ASSAULT`, when the objective criteria are satisfied, the mission signals `complete()`.
   * Calls `RegionData.claimSubRegion(level, rx, rz, sx, sz, Faction.HUMANITY, 100.0F)`.
2. **Sortie & Campaign Synchronization:**
   * As established in the recent fixes: Domino collapse is gated until `activeRemainingMask == 0` (all launched missions in the sortie are finished).
   * Once all launched missions are complete:
     * If all mandatory base missions are cleared AND `matchingCount >= dominoThreshold`: the remaining sectors collapse and the region is fully conquered.
     * If base missions remain or threshold is not met: the sortie concludes cleanly, securing the conquered sectors without prematurely ending the war.

---

## 11. Iterative Implementation Plan

To ensure continuous build stability and prevent regression, implementation will proceed across **6 focused iterations**:

### Iteration 1: Data Model & Objective Abstraction
* Create `ObjectiveType` enum.
* Expand `MissionType` with the 18 redesigned Pillager missions (4 Easy, 4 Hard, 6 Mega, 4 Defense).
* Expand `SubRegionMission` to include `ObjectiveType`.
* Refactor `ActiveCampaignMissionManager.ActiveSubRegionProgress` to support generic progress, target tracking, and persistence in `RegionData`.
* Update `ActiveMissionHudPayload` and overlay for flexible text formatting.
* Update `PillagerMissionGenerator` definitions to map to new missions.

### Iteration 2: Dynamic & Cheap Missions (Easy Tier)
* Implement `Forward Patrol` (`ELIMINATE_TARGETS` patrol squad).
* Implement `Supply Convoy` (`INTERCEPT` moving mule/cart).
* Implement `Forward Outpost` (`DESTROY_STRUCTURE` with small temporary watchpost).
* Implement `Scout Network` (`DESTROY_OBJECTIVES` with 2–3 lookout nests).
* Implement cheap bounded site search and `MissionSiteSnapshot` for Outpost cleanup.

### Iteration 3: Fortified & Spaced Missions (Hard Tier)
* Implement `Strongpoint Assault` ($20 \times 20$ redoubt with breakable core).
* Implement `Officer Hunt` (Fortified compound with unique named Officer entity).
* Implement `Artillery Battery` (2 gun pits with breakable artillery blocks).
* Implement `Cut the Supply Line` (3 distributed logistical crates/wagons).
* Add block damage/break listener `MissionBlockBreakHandler` to register damage to mission props.

### Iteration 4: Mega-Base Integration (Persistent Tier)
* Connect `Command Bunker` (`ELIMINATE_LEADER` in Citadel Keep).
* Connect `Break the War Room` (`DESTROY_OBJECTIVES` in HQ interior).
* Connect `Munitions Depot` (`DESTROY_OBJECTIVES` in magazine).
* Connect `Break the Gates` (`BREACH` fortified gate blocks).
* Connect `Silence the Guns` & `Sever Communications`.
* Utilize existing persistent structure anchor without spawning duplicate buildings.

### Iteration 5: Defense & Multi-Phase Missions (Defense Tier)
* Implement `Hold the Line` (`SURVIVE_ASSAULT` timed survival).
* Implement `Protect the Strongpoint` (`PROTECT_OBJECTIVE` with structure durability).
* Implement `Emergency Evacuation` (`ESCORT` fleeing allied transport).
* Implement `Counterattack` (`MULTI_PHASE`: Defense phase $\rightarrow$ Officer assassination phase).

### Iteration 6: System Integration & Polish
* Full end-to-end testing of launch, progression, HUD display, cancellation, server restart persistence, and domino capture resolution.
* Audit performance and ensure zero tick spikes during site activation and restoration.
* Update `WARFRONT_OVERVIEW.md`.
