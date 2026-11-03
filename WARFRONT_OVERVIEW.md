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
3. **Strict Tier Preservation Over Destructive Vetoes:**
   * Faction clusters never disappear due to terrain or rivers.
   * Base placement strictly attempts the designated tier (Mega Base, Headquarters, or Outpost). If terrain cannot fit a structure, ownership cleanly remains with `BaseType.NONE` (territory flag) without cascading down to a lower base tier.
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
8. **Dual-Language Localization Invariant (English & Turkish):**
   * All user-facing strings (blocks, items, entities, screens, HUDs, commands, missions, factions, chat feedback) MUST use `Component.translatable(...)` keys. Direct string literals in user-facing contexts are prohibited.
   * `en_us.json` and `tr_tr.json` maintain strict 1:1 key parity. All new features or content must simultaneously register corresponding translations in both language files.

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
2. **Pillager Scout (`warfront:pillager_scout`):** Autonomous reconnaissance skirmisher. Spawns independently (1 per subregion, 5-minute cooldown in Pillager territory). Conducts swift patrols and horizon spyglass scans (150° arc, 30m range). Sighting a player sounds the Goat Horn ("Seek") to dispatch multi-squad reinforcements (staged across 10–40s), then draws daggers to engage in rapid 2-hit combat with evasive retreat leaps to stall the player until reinforcements arrive. Attacking the Scout during its scan interrupts the alarm.
3. **Pillager Marksman (`warfront:pillager_marksman`):** Long-range sniper crossbowman with 3-stage combat state machine (Aim $\rightarrow$ Shoot $\rightarrow$ Reload) and tactical distancing AI.
4. **Pillager Commander (`warfront:pillager_commander`):** Tactical backline commander wielding a saber and standard. Features a 12-second hit-timeout sheathing cycle (retaining target aggro while falling back behind frontline troops) and a 15-second tactical ability cycle: Flag Rally (20s horn buff: expands player melee engagement slots to 4 with coordinated pincer flanking, enforces ranged friendly fire hold-fire for Marksmen, and triggers Cannoneer artillery splash discipline) and Sword Raise (10s buff: Speed I and weak Regeneration I for allied troops). Includes clean post-buff expiry demoting excess melee combatants back to reserve.
5. **Pillager Armored Elite (`warfront:pillager_armored_elite`):** Heavy frontline juggernaut with plate armor, high knockback resistance, and steady advance.
6. **Pillager Cannoneer (`warfront:pillager_cannoneer`):** Base-only parapet artillery sentry holding a handheld heavy bombard. Features a 25-tick fuse sizzle telegraph and fires ballistic cannonballs with $2.0\text{m}$ concussive blast damage and ladder-sweeping knockback (`Level.ExplosionInteraction.NONE`, guaranteed non-destructive to fortress stone) followed by a 70-tick powder ramming reload.
7. **Pillager Rapidbow (`warfront:pillager_rapidbow`):** Base-only corridor suppression emplacement (Tachanka-style). Commits to deploying an anchored heavy iron tripod, locking horizontal traverse yaw to $\pm 40^\circ$ with a capped $3.5^\circ/\text{tick}$ turn rate (outrunnable) and firing a 10-bolt whistling suppression volley (1 bolt/6 ticks over 3.0s) from its rotary drum. Features absolute knockback/push resistance while deployed and $+2.0\text{m}$ forward projectile spawning. Point-blank rushing ($< 3.0\text{m}$) at volley completion, target death, or flanking behind traverse arc triggers emergency tripod pack-up and an 8-block tactical spacing sprint retreat.
8. **Pillager Ironclad (`warfront:pillager_ironclad`):** Towering $1.2\text{m} \times 3.0\text{m}$ (3-block tall) armored living bulwark. Features 100% frontal damage/projectile immunity via a monolithic riveted iron slab wall shield, zero handheld weapons, slow $3.5^\circ/\text{tick}$ turn rate, concussive shield bashes ($3.5\text{m}$ knockback + Slowness/Nausea), bodyguard line-of-fire intercept AI (stepping directly between players and allied gunners), and enraged speed upon being isolated.
* **Artillery & Siege Weapon Invariant:** Mobile catapult mob entities are deprecated/scrapped. Large siege engines and artillery are strictly represented as 1-block functional machine blocks (e.g. Heavy Mortar) rather than oversized mob entities.

### 3.3. Zombie Horde Ecosystem & Spawning Architecture (Horde Shooter Model)
* **Asymmetrical Faction Cadence (Helldivers 2 Bug Breach Model):**
  - Unlike Pillagers (tension builds low → high over time), Zombie territory features an inverted cadence: high tension upon arrival, gradually suppressed through active combat.
* **The Horde Breach Cycle:**
  - **First Arrival Emergence Surge:** Entering an unvisited `DORMANT_INFESTATION` subregion triggers an immediate surface emergence of 6–10 zombies playing the GeckoLib crawl-out animation with soil/biomass particles and audio. Mid-air entity pop-in is strictly forbidden.
  - **Dynamic Accelerating Cooldown ($T$):** Cooldown scales inversely with living population. High population triggers rapid reinforcements (5–10s); thinning the horde slows the timer down (35–45s). Spawning freezes completely when reaching the subregion cap ($C_{\text{total}} = 15 + \lfloor R \times 0.55 \rfloor$).
  - **Breach Victory & 3-Minute Grace Period:** Purging the subregion (`activeHordeCount == 0`) immediately triggers a victory cue and grants a flat 180-second (3:00) complete spawn grace period for exploration, looting, and tactical mission prep.
  - **Breach Resurgence Warning:** At $T - 5\text{s}$ of the grace period, subterranean rumbling and sludge bubbling warn players of an impending resurgence; at $T = 0$, a new emergence surge erupts to prevent infinite 1-kill peace farming.
* **Anti-Stuck & Anti-Cheese Burrow Lifecycle:**
  - Zombies stagnant for $> 7\text{s}$ with an active target (net movement $< 1.5\text{m}$) play a 30-tick burrow-down digging animation, discard, and re-emerge at a valid surface location within 15–25m of the player.
  - Taking damage (fire, magma, traps) pauses and resets the burrow timer, fully preserving player-built trap/kill chutes.
* **Continuous Subregion Density Scaling ($64 \times 64$):**
  - **Total Mob Cap ($C_{\text{total}}$):** Scales continuously with Resistance ($0 \le R \le 100$): $C_{\text{total}} = 15 + \lfloor R \times 0.55 \rfloor$ ($15$ at $0\text{ R}$ up to $70$ at $100\text{ R}$).
  - **Shared Elite Budget ($C_{\text{elite}}$):** Scales continuously with Resistance: $C_{\text{elite}} = \lfloor R \times 0.12 \rfloor$ ($0$ at $0\text{ R}$ up to $12$ at $100\text{ R}$).
  - **Fodder Population:** Fills all remaining subregion capacity ($C_{\text{total}} - N_{\text{current\_elites}}$).
* **Dedicated Custom Entity Taxonomy:**
  - Dedicated entity classes for Fodder and specialized Elites: **Hunter** (fast lunging stalker), **Bile Spitter** (ranged area denial caustic clouds), **Hivemind Screamer** (horde buff and macro alert howling), and **Tank/Brute** (heavy knockback-resistant vanguard).
* **Dual-Tier Spatial Leashing:**
  - **Idle / Ambient Roaming:** Strict $64 \times 64$ subregion tether. Random wander targets are selected strictly within the subregion bounding box.
  - **Active Combat Pursuit:** Expands to a soft $128 \times 128$ macro-region boundary. Pursuing zombies can chase targets across internal subregions, but break pursuit and return if the target crosses outside the regional borders.
* **Swarm Agitation Cascade:**
  - Ambient zombies idle or wander sluggishly until triggered. Weapon fire, sprint proximity ($\le 12\text{m}$), or injury broadcasts an alert groan rallying zombies within $24\text{m}$.
  - Screamer Elites elevate this agitation radius to $48\text{m}$, coordinating subregion-wide swarms.
* **Strict Performance & Tick Invariants ($< 0.005\text{ms}$ / tick):**
  - Population tracking is strictly event-driven ($O(1)$ integer counter on spawn/death), never polling world bounding boxes.
  - Stagnation detection relies on positional delta arithmetic (`distanceToSqr < 0.25`), strictly avoiding A* pathfinding queries.
  - Checks are interleaved at 1 Hz via entity ID modulo (`(id + gameTime) % 20 == 0`), capping active evaluations to 5 entities per tick at 100 mobs.
  - Spawns strictly validate against surface motion-blocking heightmaps, eliminating subterranean cave entrapment.

### 3.4. Friendly Fire & Alliance Invariants
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

### 6.1. Base Tiers, Footprints & Faction Themes
* **Mega Base (Size 3):** $100 \times 100$ footprint ($7 \times 7$ chunks), 15% roll, requires MD $\le 3$ land domain.
  - *Pillager:* Imperial Fortress / Citadel.
  - *Zombie Horde:* **Heart of Infection** (primordial hive anchor).
* **Headquarters (Size 2):** $50 \times 90$ footprint ($4 \times 6$ chunks), 60% roll, requires MD $\le 2$ land domain.
  - *Pillager:* Military Headquarters.
* **Outpost (Size 1):** $32 \times 32$ footprint
  - *Pillager:* Guarded outpost building with supplies and commander.
* **None:** Territory claim without a physical fortress structure (Wilderness).

### 6.2. Placement & River Filtering Rules
* **Land Viability:** Inner $4 \times 4$ chunks sampled; rejected if aquatic ratio $> 50\%$.
* **Anchor Center:** Must be solid dry land (non-ocean, non-river).
* **Relative Water Minimization & Flatness Fitness:** Continuous multi-criteria scoring balances slope variance minimization against water presence penalty ($\text{Fitness} = - 40 \times N_{\text{water}} - 6 \times \Delta Y + \text{bonus}$), guaranteeing bases prefer flat plains/savannas over steep mountain slopes without binary river vetoes.
* **Multi-Candidate Cell Search:** Preserves 15% Mega Base probability by searching cell interior candidate centers (3×3 grid) for inland domains ($\ge 15$ land regions in $MD \le 3$) and minimal water before cascading.
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

### 7.4. Unified Regional Zombie Defense (Horde Incursion Battles)
Contrasting Pillager subregion tactical missions, defensive sieges against the Zombie Horde eliminate subregion partitioning, converting the entire $128 \times 128$ territory into a unified sandbox horde defense arena.
* **Strategic Border Incursion Vectors (Option 1A):** Swarms spawn along exterior perimeter borders that directly touch adjacent Zombie Horde-controlled regions (e.g., North and East vectors; 360° encirclement occurs strictly when completely surrounded by hostile territory).
* **Staged Incursion Surges & Breathers (Option 2B):** Assaults advance in 3–4 escalating wave surges with ~30-second intermission lulls between waves for repairs, ammo management, barricading, and tactical fortification.
* **Dual Defeat & Target Invariants (Fortified vs. Wilderness):**
  - **Fortified Regions (Claim Core / Base Present — Option 3A):** Horde forces march directly to breach and overrun the central Claim Core / Base Anchor. Players lose if the core structure is destroyed or held uncontested.
  - **Wilderness Regions (`BaseType.NONE` / Unclaimed Fringe):** No physical core exists. Zombies march inward from the borders individually/in loose columns, converging on defending players upon entering an engagement detection radius. The breach failure goal is disabled; survival and quota elimination against the active **war timer** governs victory.
* **Sandbox Freedom:** Mobs possess no complex military objectives or sabotage tasks; defensive depth relies entirely on player-constructed fortifications, chokepoints, kill zones, and ballistas.

### 7.5. Subregion Territory Defense Missions (Zero-Structure Defend Operations)
Defensive campaigns in player-held / Humanity territory provide tactical defense sorties without disturbing player builds.
* **Zero Structure Generation Invariant (Strict):** Defense missions strictly prohibit generating, pasting, or spawning physical block structures or NBT templates into player territory. Defending players utilize the existing terrain and their own player-built structures, barricades, and defenses.
* **Border Corridor Incursion Vectors:** Invading assault waves spawn strictly along the subregion perimeter border oriented toward the attacking source region (`SourcePos`), advancing inward toward the subregion center via directional pathfinding (`AdvanceToLocationGoal`).
* **Co-Fallback Mission Suite (50/50 Deterministic Roll):**
  - **`DEFEAT_INVADING_FORCES` (`ObjectiveType.ELIMINATE_TARGETS`):** Sequential assault waves advancing from the border perimeter into the subregion interior. Victory is achieved by neutralizing all invading waves.
  - **`HOLD_THE_CHOKEPOINT` (`ObjectiveType.SURVIVE_ASSAULT`):** Improvised perimeter defense (18m radius) around the subregion central waypoint. Features an abstract **Defense Integrity** meter (100%): decays (-1%/sec/mob) when unengaged hostiles enter the perimeter while uncontested; stabilizes and recovers (+1%/sec or +3%/kill) when players engage hostiles inside. Repelling 3 escalating assault waves while maintaining integrity > 0% secures victory.
* **Archaic Attack Value Purge:** All strategic sieges, duration calculations, and domino collapse thresholds are governed strictly by active attack `sources` (count and cardinal positions), encirclement flags, and target resistance; the obsolete integer `attack_value` has been purged across all data models and network codecs.

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
16. **Structure Generation in Defended Player Territory:**
    - *Problem:* Generating procedural base templates or temporary mission props into defending player subregions destroyed custom player structures and altered player-built terrain.
    - *Rule:* Defense missions must strictly observe the Zero Structure Generation invariant. Defenses rely entirely on player-constructed fortifications, existing terrain, and abstract integrity tracking.
17. **Archaic Attack Value Duplicate State:**
    - *Problem:* The legacy integer `attack_value` was redundant with siege source vectors and encirclement status, risking desynchronization between map arrows, siege durations, and domino thresholds.
    - *Rule:* Strategic defense duration and domino thresholds evaluate directly from active attacking `sources` and the `encircled` boolean flag; do not re-introduce arbitrary scalar attack power numbers.
18. **Multi-Controller Combat Stance Awareness & Dropped Bone Transforms:**
    - *Problem:* Splitting upper body stances and melee attacks across separate GeckoLib controllers, or leaving base movement controllers to play unconstrained `IDLE`, caused bone transforms to drop into neutral bind rest-poses when action animations finished. Furthermore, applying a non-zero cross-fade transition length between chained combo attacks that already share identical start/finish keyframes caused Euler angle interpolation to spin arms randomly through 360 degrees.
    - *Rule:* Manage all upper body combat behaviors within a single combat controller, and ensure base movement controllers yield completely (`PlayState.STOP`) during committed attacks. Combo attacks sharing exact followthrough-to-start keyframes must transition with zero cross-fade interpolation (`transitionLength(0)`), reserving smooth transitions strictly for entry/exit from passive stances.
19. **GeckoLib Action Controller Stagnation & Goal Tick Desynchronization:**
    - *Problem:* Non-looping GeckoLib action animations stop upon completion; returning `PlayState.CONTINUE` while idle left controllers stagnant, causing subsequent identical animations (e.g. scout spyglass scan) to fail to replay. Additionally, entity-level action decrementing running 1 tick ahead of goal selectors caused premature state clearing right before goal completion blocks executed.
    - *Rule:* Explicitly invoke `controller.forceAnimationReset()` on action state transitions and return `PlayState.STOP` during idle. Anchor multi-tick custom entity actions with tick safety buffers so AI goals authoritatively conclude before entity-level state timers lapse.
20. **Descending Height Cascades & Foundation Demolition Craters:**
    - *Problem:* Demolishing mission structures by setting all tracked positions to `AIR` excavated sub-surface foundations, creating deep craters. Subsequent surface raycasts anchored new retries inside the crater bottom, cascading the structure lower on each retry. Additionally, client bitwise-OR caching prevented the war map from clearing lost mission buttons.
    - *Rule:* Retain physical structures and anchors on campaign subregion failure/abandonment; repair and regarrison on mission relaunch without re-anchoring. When terminal demolition occurs, sub-surface foundation blocks must be backfilled to solid ground (`GRASS_BLOCK`/`DIRT`/`STONE`) flush with the surface—never hollowed to `AIR`. Client maps must authoritatively synchronize active siege masks from server snapshots.
21. **Premature Base Demolition, Floor Slab Omission & Patrol Leash Air Teleport Loops:**
    - *Problem:* Filtering base demolition by $Y \ge \text{anchor}.getY()$ omitted the $Y = \text{anchor}.getY() - 1$ template layer, leaving exposed cobblestone while hollowing the ground floor to air. Unconditional marksman leashing caused infinite mid-air teleport loops when platforms were destroyed, and surviving garrison mobs persisted post-victory. Outposts were demolished immediately upon conquest without gating for unlooted chests, while decorative barrels blocked camp decay.
    - *Rule:* Demolition must include all template footprint and foundation blocks down to $Y = \text{anchor}.getY() - 1$ and deeper, backfilling topsoil flush to natural ground (`GRASS_BLOCK`) and foundation columns to `DIRT`/`STONE`. Height-leashed patrol goals must abort when motion-blocking support underneath vanishes. Base conquest must immediately disperse surviving defenders, gate post-war demolition via `PendingSalvageManager` until all prize chests are harvested, and restrict salvage tracking strictly to chests and machinery.
22. **Blind Volumetric Snapshot Capture & Phantom Air Demolition:**
    - *Problem:* Scanning an entire 3D structure volume ($27 \times 13 \times 31$) before template placement recorded thousands of empty sky coordinates as `AIR`. During terminal demolition, `ActiveDemolitionTask` checked `targetState.isAir()` instead of verifying if a physical world block was actively being removed (`!current.isAir() && targetState.isAir()`), causing minutes of phantom POOF particles and pop audio in empty air.
    - *Rule:* Structure templates must record pre-existing terrain only if non-air (`!existing.isAir()`) and assign air revert targets strictly to physical placed structure blocks (`!state.isAir()`). Demolition tasks must skip identical block states immediately and play particle/sound feedback only when solid blocks are actively destroyed into air.
23. **Base Subregion Salt Rerolling, Coordinate Hash Edge Cases & Base Tile Collisions:**
    - *Problem:* During partial victory expiration, reinforcement salt modified the regional mission seed, causing base subregions to reroll their objectives. Multiplicative coordinate hashing collapsed to zero along coordinate axes, and flat interior slicing left higher mountain terrain obstructing fortress interiors. Temporary mission structures could also overlap physical base footprints.
    - *Rule:* Headquarters and base subregions strictly evaluate missions via an additive SplitMix64 coordinate mixer and an explicit 2-wing contract (`Integer.bitCount == 2`), ensuring immutable wing assignments across rerolls. Headquarters placement permits steep mountain slopes (downgrades strictly on deep ocean or $>15\%$ water ratio), utilizing dynamic column heightmap excavation to open sky and stepped foundations down to solid ground. Temporary mission anchors reject candidates overlapping base footprints.
24. **Cross-Dimension Cache Poisoning & Region Data Leakage:**
    - *Problem:* Visiting the Nether or The End evaluated non-Overworld biomes into shared-seed chunk biome color caches and procedural generator caches. Calling `RegionData.get` or ticking across all server levels created detached data storages or swapped internal level references to non-Overworld levels, while Nether movement unlocked Overworld Fog of War coordinates.
    - *Rule:* `RegionData` and territory operations strictly bind to the Overworld (`level.getServer().overworld()`). Moving or acting in other dimensions is strictly prohibited from writing territory state, unlocking visited regions, or querying procedural caches. Map requests outside the Overworld bypass biome/chunk sampling and display a centered "Cannot Get Intel" status.
25. **Minecraft Goal Selector 10 TPS Throttling on Custom Combat Timers:**
    - *Problem:* Minecraft's vanilla `Mob.serverAiStep()` skips goal execution on odd game ticks (`this.tickCount % 2 != 0`) unless a goal explicitly overrides `requiresUpdateEveryTick()` to return `true`. AI goals tracking multi-tick animations via internal tick counters (`bashTicks++`) executed at 10 TPS instead of 20 TPS, doubling their elapsed real time (e.g., a 30-tick bash took 60 world ticks / 3.0s instead of 1.5s) and causing damage checks to land seconds after the client animation had already finished.
    - *Rule:* Any custom entity goal that orchestrates multi-tick attack timings, hit frames, or animation synchronization MUST override `public boolean requiresUpdateEveryTick() { return true; }` to maintain strict 1:1 parity with server game ticks and client GeckoLib animations.
26. **Goal Interruption on Target Death Aborting Transitional Cleanup:**
    - *Problem:* Minecraft's vanilla `Goal.canContinueToUse()` delegates by default to `canUse()` (`target != null && target.isAlive()`). When an active target died while the Rapidbow was deployed, the `GoalSelector` immediately terminated the emplacement goal mid-tick and called `stop()`, bypassing the transitional cleanup state (`STATE_UNDEPLOYING`) and causing the deployed tripod model to vanish instantly without playing the pack-up animation.
    - *Rule:* Any custom entity goal with transitional recovery/exit states (e.g. packing up emplacements, holstering, recovering) must override `canContinueToUse()` to return `true` while transitional states or deployed stances are active (`this.mob.isDeployed()`), allowing the goal tick loop to search for alternate targets or conclude cleanup animations before releasing goal execution.
27. **Custom MoveControl Jump Omission & Step Height Parity:**
    - *Problem:* Custom `MoveControl` implementations written for decoupled strafing or clamped angular turn rates completely bypassed vanilla's jump checks (`dy > maxUpStep() || horizontalCollision`), causing heavyweight mobs (like Pillager Ironclad) to get permanently stuck on 1-block steps or terrain inclines. Furthermore, setting 2D bodyguard intercept waypoints without surface height sampling placed destination targets underground on slopes.
    - *Rule:* Any custom `MoveControl` must replicate vanilla's elevation difference and horizontal obstacle collision checks to invoke `mob.getJumpControl().jump()` and sustain forward/strafe momentum during `Operation.JUMPING`. Large heavyweight mobs ($>1\text{m}$ width, $\ge 2.5\text{m}$ height) must register `.add(Attributes.STEP_HEIGHT, 1.0625D)` matching vanilla Iron Golems and Ravagers. AI waypoint generation across uneven terrain must dynamically sample true ground surface height (`findGroundSurfaceY`) rather than hardcoding destination $Y$ to the origin mob's elevation.
28. **Wilderness Incursion Ownership & Client Optimistic State Drift:**
    - *Problem:* AI sieges expanding into unowned wilderness (`Faction.UNCLAIMED`) were naively classified by the client map as generic player defenses, permitting players to trigger campaign confirmations without checking ownership. The server rejected these attempts with an erroneous multi-faction PvP lock (`'already in an ongoing war'`), while the client retained the staged siege overlay until refreshed. Additionally, defending wilderness lacked a reward mechanism.
    - *Rule:* Incursions into wilderness (`Faction.UNCLAIMED`) can be defended by bordering player factions via commitment (`defenderPlayerFactionId`), generating wilderness defense missions. Successfully securing the threshold grants region ownership to the defending player faction at baseline 20/20 stats (`BaseType.NONE`); failure cedes the territory to the attacking AI. Client maps must strictly validate faction reachability before enabling defense actions, and dynamic map textures must reflect authoritative server state.
29. **Premature Base Repair Hijack & Missing Placement Gates:**
    - *Problem:* Running structural integrity self-repair on unplaced or chunk-deferred bases interpreted raw natural terrain as missing blueprint blocks, placing initial blocks and prematurely marking the base as placed. This permanently aborted atomic NBT template generation and forced bases to build block-by-block upwards.
    - *Rule:* Base repair and garrison initialization must strictly gate on `basePlaced == true`. Self-repair must never mark unplaced bases as placed; structure templates must place atomically via `BasePlacementManager` when all footprint chunks are loaded.
30. **Keyword Precedence in Biome Classification & Non-Blocking Map Elevation:**
    - *Problem:* In vanilla biome registries, composite names overlap general keywords (e.g. `cherry_grove` contains `grove`). Checking general keywords first misclassified temperate flowering hills as alpine mountains. Furthermore, evaluating 3D noise routers (`getBaseHeight`) during macro map synchronization freezes the server thread.
    - *Rule:* Biome keyword matchers must strictly order specific compound names (`cherry_grove`, `savanna_plateau`, `windswept_*`) ahead of generic root keywords (`grove`, `savanna`, `mountain`). Macro-level elevation mapping must strictly use $O(1)$ calibrated biome elevations and multi-noise climate parameters, with elevation packed into the high byte of the existing 32-bit chunk color payload to eliminate bandwidth overhead.
31. **Loaded-to-Unloaded Chunk Elevation Steps & Cave Biome Surface Leaks:**
    - *Problem:* Preserving loaded heightmaps (`WORLD_SURFACE`) while approximating unloaded chunks with climate models created a 30–40 block elevation cliff along the server chunk loading radius, causing NW hillshading to paint an artificial moving square border around the player and cutting off contour lines. Additionally, sampling biomes at low default heights ($Y \approx 68$) beneath mountains sampled underground cave biomes (`dripstone_caves`), rendering flat brown cave patches on surface snow.
    - *Rule:* The strategic map evaluates a unified, continuous multi-noise elevation model across all chunks uniformly, guaranteeing seamless 3D hillshading and continuous contour lines regardless of chunk loading states. Surface biome sampling must incorporate vertical cave-bypass logic, advancing upwards to genuine ground/open-sky levels whenever subterranean cave biomes are detected.
32. **Entity Timestamp vs. World GameTime Mismatch & Raycast Aim Interception:**
    - *Problem:* Comparing world `gameTime` (`level.getGameTime()`, tens of thousands of ticks) against entity-scoped hurt timestamps (`entity.getLastHurtByMobTimestamp()`, which stores entity `tickCount`, e.g. 200) resulted in elapsed tick calculations in the tens of thousands (`gameTime - ts >> 100`), completely disabling recently-damaged priority AI goals. Furthermore, evaluating enemy aim via pure angular dot product without orthogonal ray-to-cylinder intersection distance locked bodyguards in front of whichever ally was behind them when the player looked at the bodyguard.
    - *Rule:* Always evaluate entity-level damage timestamps against entity-level tick age (`entity.tickCount - entity.getLastHurtByMobTimestamp()`). Crosshair aim detection must evaluate orthogonal raycast distance ($d_\perp \le 1.8\text{m}$) along the forward view ray ($d_\parallel > 0.5\text{m}$) with hysteresis distance bonuses for currently guarded allies to prevent target lock-in and jitter. Bodyguard whitelists must recognize all faction ranged/officer archetypes (Rifleman, Marksman, Cannoneer, Rapidbow, Commander).
33. **Chambered Ammunition Preservation & NeoForge Fuel Burn Hooks:**
    - *Problem:* Calling protected or deprecated vanilla furnace burn-time helpers threw compilation exceptions under NeoForge 1.21.1. In player-placed machinery with loaded chambers, dropping loaded chambered items on block destruction risked item duplication or desync when blocks were broken mid-firing cycle.
    - *Rule:* Retrieve fuel burn durations via NeoForge's standardized ItemStack.getBurnTime(RecipeType.SMELTING). In machinery utilizing internal breech chambering (currentLoadedShell), decrement and void the ammunition item immediately upon chambering, and restrict drop-on-break handlers to the inventory slots so loaded ordnance is consumed rather than duplicated.
34. **VoxelShape Network Range Discard & Item Interaction Handlers:**
    - *Problem:* Setting a single-block custom container to a \times3$ footprint VoxelShape caused raycasts outside the center block ($>88\%$ of surface) to exceed vanilla Minecraft's 1.0-block center distance limit in ServerGamePacketListenerImpl.handleUseItemOn, silently dropping the packet without opening the container. Missing useItemOn also bypassed menu opening when right-clicking while holding items.
    - *Rule:* Single block interactables must stay within standard 1x1 block bounds (Shapes.block()) and implement both useItemOn (checking !player.isSecondaryUseActive()) and useWithoutItem.
35. **GeoEntityRenderer South-Facing Lock & Projectile Model Orientation:**
    - *Problem:* GeoEntityRenderer applies an internal 180° rotation (poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - rotationYaw))) in pplyRotations(...). When model bones independently apply 3D flight trajectory yaw, shells face backwards toward the firing origin. Calling 
enderBackground in custom screen 
ender() methods before super.render() also doubles the world dimming gradient.
    - *Rule:* Projectile renderers must override and suppress default pplyRotations when bone animations control yaw and elevation pitch. Container screens must never duplicate 
enderBackground calls, and slot background pixels must follow standard vanilla #8B8B8B interior fills.
36. **Player Fortification Placement Gating & Regional Fall Lifecycle Invariants:**
    - *Problem:* Unrestricted construction of player fortifications (such as Friendly Mortars and Friendly Ballistas) in Wilderness or foreign territory bypassed faction territorial progression and allowed unlimited suppression defense. Furthermore, when territories fell to AI or hostile factions, leaving active player machinery operating or dropping valuable container inventories resulted in mechanical exploitation.
    - *Rule:* Player fortification placement must strictly require Overworld, active faction membership, and ownership by the player's own faction (`region.playerFactionId().equals(pf.getId())`), enforced via hard hierarchical caps: maximum 3 of each fortification type per $128 \times 128$ macro region (up to 6 total heavy fortifications), and a strict cap of 2 of any combination per $64 \times 64$ subregion. When a region falls to an AI faction, structures are destroyed and container inventories are completely voided; when captured by another player faction or vacated to Wilderness, structures are deactivated (offline, zero fuel consumption, halted targeting) while preserving container contents for looting; active sieges (region under attack) leave structures completely operational and intact.
37. **Foliage Raycast Interception & Damage Immunity Frame Clipping:**
    - *Problem:* Calling ProjectileUtil.getHitResultOnMoveVector without filtering non-solid/passable blocks caused high-velocity projectiles (artillery shells, cluster bomblets) to collide with tall grass, flowers, ferns, and vines, detonating prematurely in mid-air. Furthermore, multiple submunitions impacting simultaneously were neutralized by vanilla Minecraft's 10-tick invulnerableTime window.
    - *Rule:* Projectile raycasts must explicitly check candidate BlockHitResults and bypass blocks where state.getCollisionShape().isEmpty(), !state.blocksMotion(), or state.canBeReplaced(), allowing full pass-through parity with vanilla arrows. Cluster submunitions must reset target.invulnerableTime = 0 prior to applying explosion damage so stacked explosions register properly.
38. **GeckoLib Chained Action Loop Modes & Controller Freeze:**
    - *Problem:* Setting animation loop mode to `hold_on_last_frame` in multi-stage chained actions (such as ballista shoot -> reload) forces GeckoLib's `AnimationController` into `State.PAUSED` and returns `shouldPlayAgain = true`. This prevents the animation queue from advancing to subsequent stages and permanently blocks future animation triggers (`tryTriggerAnimation` checks `state == STOPPED`).
    - *Rule:* Action-oriented sequence stages must strictly use `PLAY_ONCE` loop modes in both JSON/Blockbench and Java `RawAnimation` definitions (`.then("stage", Animation.LoopType.PLAY_ONCE)`). When action timers complete, controllers must call `state.getController().stop()` to guarantee state reset to `STOPPED`.
39. **Supersonic Projectile 3D Swept Hitboxes & Turret Pitch Elevation Gating:**
    - *Problem:* Using 1D mathematical ray-clipping (`targetBox.clip(startPos, endPos)`) for high-velocity projectiles ($7.0\text{ blocks/tick}$) causes near-misses on valid targets due to zero-thickness line intersection. Furthermore, artificial under-leading multipliers ($0.65\times$) cause shots to trail moving targets, aiming at mob feet clips terrain in downward shots, and clamping aim pitch to mechanical bounds without invalidating unreachable targets results in turrets locking onto out-of-reach enemies and firing dummy shots into terrain.
    - *Rule:* High-velocity projectiles must perform a 3D cylindrical sweep against inflated entity bounding boxes testing proximity to `closestPointOnSegment`. Turret aim must target elevated center-of-mass ($70\%$ entity height), apply unattenuated velocity lead, require exact slew alignment before firing, and strictly reject targets requiring elevation outside physical mechanical pitch limits (`[-25^\circ, +35^\circ]`) during target acquisition and aim computation.
40. **Dynamic Melee Slot Demotion & Commander Combat Timeout:**
    - *Problem:* Expanding melee combat slots from 2 to 4 during tactical rally buffs caused mob dogpiling when the buff expired if excess attackers weren't actively demoted. Furthermore, mobs engaged in melee could get trapped in permanent drawn-weapon states when targets kited, preventing tactical ability execution.
    - *Rule:* Always implement active demotion upon rally buff expiry in `MeleeEngagementManager`—prune excess slots back to base capacity (demoting Warriors before Elites, and farthest mobs first) and transition demoted mobs back to the 5–6m reserve perimeter. Field commanders must feature a hit-timeout (12s) to sheathe weapons and resume backline tactical ability cycles while preserving target aggro.
41. **Strict Lowercase Asset Resource Paths & Modpack Reload Collisions:**
    - *Problem:* Uppercase characters in asset paths (e.g. `HE_shell.geo.json`) violate Minecraft 1.21.1 `ResourceLocation` validation (`PathPackResources`). Modern multi-threaded pack loaders (ModernFix) index these segments, causing resource loading exceptions. Vanilla Minecraft's automatic recovery reload re-executes deferred setup queues, triggering duplicate key collisions in non-idempotent mods (e.g. TACZ synced data keys) and halting the client on a permanent black screen.
    - *Rule:* All asset resource files, directories, namespaces, and model paths MUST strictly be lowercase `[a-z0-9_.-]`. Keep asset naming clean and eliminate redundant duplicate exports.
42. **Convoy Wagon Constraints in Infantry Patrols & Fast-Flight Despawning:**
    - *Problem:* Validating infantry patrol borders with open-sky wagon clearance (`canSeeSky`, 3-block headroom, slope $\le 2$) rejected valid forested and rolling terrain, aborting squad spawns. Furthermore, vanilla's 128m despawn and fast player flight velocity ($>20\text{ m/s}$) purged non-persistent squads seconds after spawning before engagement.
    - *Rule:* Patrol squad generation must use dedicated infantry surface validation (permitting tree canopies and up to 3-block natural elevation stepping). Patrol squad members must set `mob.setPersistenceRequired()` to prevent vanilla 32m/128m despawn loops, delegating spatial lifecycles to relaxed roaming trackers (256m despawn, 128/160m AI activation hysteresis).
43. **Blind Single-Point Cell Centers & Slope-Blind Base Subregion Selection:**
    - *Problem:* Picking a single pseudo-random cell center blindly caused Mega Bases to cascade down to Big Bases whenever that point was near water or coastal fringes, destroying their 15% probability. Outposts and Headquarters selected the first subregion with dry land regardless of steepness, carving mountain cliffs and casting 20-block foundation pillars.
    - *Rule:* Cluster generation evaluates a 3×3 distributed candidate grid across the cell interior, anchoring Mega Bases at viable inland locations and preserving their 15% roll. Physical base anchors evaluate all subregions and configurations via continuous $O(1)$ placement fitness scoring ($\text{Fitness} = - 40 \times N_{\text{water}} - 6 \times \Delta Y + \text{bonus}$), prioritizing flat terrain over mountain slopes while minimizing water contact.
44. **BiomeElevation String Fallback on Unloaded Footprints & Bernoulli Faction Clustering:**
    - *Problem:* In unloaded chunks, elevation evaluation fell back to biome name string lookups (`BiomeElevation`). Because mountain cliffs and flat pine valleys shared identical biomes (`snowy_taiga`), $\Delta Y$ was evaluated as 0, blinding candidate scoring to mountain cliffs. Furthermore, per-cell independent pseudo-random coin flips caused runs of 6+ same-faction clusters in local terminal views.
    - *Rule:* Footprint candidate scoring queries true 3D noise surface height via `getSurfaceHeightAt` across 9 perimeter/center points, applying steep nonlinear penalties for $\Delta Y > 5$ so flat ground decisively beats mountain slopes. Faction assignment strictly uses bipartite checkerboard parity (`(cellX + cellZ) % 2`) XOR world seed parity, guaranteeing an interleaved 50/50 balance and immediate inter-faction frontlines within travel distance ($\le 3000$ blocks).
45. **Vanilla `NoAI` Flagging for Custom Combat Entities & Unchecked Cluster Patrol Flooding:**
    - *Problem:* Applying `mob.setNoAi(true)` to idle or distant entities completely breaks entity physics: in vanilla Minecraft, `LivingEntity.isPushable()` returns `false` when `isNoAi()` is true (making them 100% immune to player/mob collision displacement), and `aiStep()` wipes movement velocity (nullifying knockback). Additionally, saving unloaded entities while `NoAI: 1b` permanently freezes them as immovable statues. Furthermore, ambient patrol managers scanning with large 128m radii across multi-region clusters simultaneously spawned 150–250+ GeckoLib entities, overwhelming CPU tick and render threads down to 30 FPS.
    - *Rule:* NEVER call `mob.setNoAi(true)` on living combat entities—rely on Lithium and vanilla entity ticking optimizations, and use 2D horizontal distance ($dx^2 + dz^2$) for spatial lifecycles so flight altitude does not distort range. To preserve solid 60+ FPS in clusters while keeping full tactical squad sizes intact (up to 16 mobs), restrict player patrol spawn evaluation to a 64-block radius (128x128 footprint), limiting evaluation to 1–4 immediate subregions. If squads distance-despawn without entering combat, refund the subregion's spawn cooldown so fast-transit players return to active encounters rather than barren ghost towns.
46. **Biome-Only Unloaded Water Checks, Map Freeze from 3D Router Queries & Rival Border Clearance:**
    - *Problem:* In unloaded chunks, `isSurfaceWaterAt` checked only biome tags. Lakes, ponds, and river bends in `minecraft:plains` or `minecraft:forest` returned false, while `WORLD_SURFACE_WG` returned flat water surfaces ($\Delta Y = 0$). This fooled footprint scoring into rating open water bodies as ideal flat ground ($\text{fitness} = +20$), placing submerged bases on artificial landfills. Furthermore, evaluating 3D noise routers inside dense candidate footprint loops during raw region queries (`generateRawRegionState`) triggered over 150,000 synchronous evaluations across 576 map regions, locking the server tick thread on map open. Finally, measuring rival clearance from centers rather than outer territory borders cannibalized adjacent clusters down to 2–3 region shards.
    - *Rule:* Raw region queries (`generateRawRegionState`, strategic map rendering, AI attack evaluations) must NEVER invoke physical base anchor placement (`resolveBasePlacement`), determining base types deterministically in pure $O(1)$ arithmetic ($<2\text{ ms}$ for 576 regions). Physical anchor placement (`calculateStrength == true`) evaluates 3D noise router fluids (`WORLD_SURFACE_WG > OCEAN_FLOOR_WG`) strictly at the structure center and 4 corners (terminating immediately if center is submerged, or if $\ge 2$ corners are submerged), keeping dense interior sampling grids to $O(1)$ multi-noise biomes. Faction cluster buffer zones must measure clearance from the rival cluster's outer boundary ($\text{dist} - R_{\text{rival}}$) with a 1-region neutral DMZ, preserving healthy 10–25 region territories. Size 1 clusters must center strictly on `BaseType.OUTPOST` with territory up to $MD \le 2$.
47. **Shoreline Cluster Centering, Phantom Mega Bases & Strict O(1) Candidate Footprint Validation:**
    - *Problem:* Validating cluster centers with generic domain land counts ($\ge 10$ out of 25 regions) allowed clusters to anchor directly on lake shores where immediate cardinal neighbors ($MD = 1$) were water. Because $100 \times 100$ Mega Bases require huge footprints, shoreline water bled into the center, causing physical anchor placement to fail and revert to `BaseType.NONE` after raw map markers were already drawn (producing phantom icons with missing data). Conversely, pre-validating physical anchors via 3D noise router queries (`findPhysicalBaseAnchor`) inside candidate loops executed thousands of synchronous `getBaseHeight` computations per cell, triggering 40-second server thread watchdog crashes on map open and server ticks.
    - *Rule:* Candidate selection in `getOptimalCellCluster` must strictly rely on pure $O(1)$ multi-noise climate evaluations and NEVER invoke 3D noise router queries (`getBaseHeight`) or physical anchor placement. Mega Base candidate centers strictly require `isStrictlyValidMegaBaseFootprint` (verifying center, 4 corners, 4 edge midpoints, and 16 interior samples for zero ocean/beach and $\le 12.5\%$ river water) AND that all 4 cardinal inner regions at $MD = 1$ ($(0, \pm 1), (\pm 1, 0)$) are verified dry land. Mega Bases occupy $61\%$ of the region footprint ($[14..114]$) and strictly sit at the fixed canonical region center (`startBlockX + 64, startBlockZ + 64`) with zero arbitrary shifting and zero bandaid force-placement fallbacks. Outer fringe regions ($MD = 2, 3$) remain expendable.
48. **Candidate Margin Shrinkage, DMZ Buffer Subtraction & Diagonal Border Touching:**
    - *Problem:* Shrinking candidate margins to 3 in $16 \times 16$ cells allowed candidate centers in adjacent cells to generate at $X=12$ and $X=19$ (only 7 regions apart). Furthermore, subtracting 2 from `bufferDistance` in `generateRegionInternal` reduced the DMZ clearance to 1 region, and checking clearance via 1D Manhattan distance permitted diagonal territory tips ($dx = 1, dz = 1$) to touch corner-to-corner, causing opposing faction borders to collide with zero separation.
    - *Rule:* Candidate centers must maintain a strict safe interior margin ($margin = 4$ for separation 16, restricting coordinates to $[4..11]$), guaranteeing $\ge 9$ regions center-to-center distance between adjacent cells. Candidate selection filters out any candidate with Chebyshev distance $< 8$ to adjacent rival cell centers. Territory claim evaluation in `generateRegionInternal` enforces a strict Chebyshev DMZ buffer ($\max(|dx|, |dz|) \le R_{\text{rival}} + \max(2, \text{bufferDistance})$), guaranteeing at least 2 neutral unclaimed regions between opposing factions with zero edge-sharing and zero corner-touching.
49. **Candidate Pool Starvation Under Duplicate Mission Caps & Terrain Filtering:**
    - *Problem:* When terrain or biome requirements filtered out unsuitable generic missions (e.g. supply convoys requiring road clearance in rugged mountains), a sparse mission roster combined with a strict regional duplicate cap ($\le 2$ identical missions) caused the candidate selector to exhaust all valid options, forcing fallback repetition or generation failure.
    - *Rule:* Mission registries must supply ample native candidate variety ($\ge 2$ Easy and $\ge 2$ Hard missions per active tactical biome class alongside universal missions), and ensure informational biomes (River, Ocean) are excluded from hostile mission generation while candidate selectors retain unthrottled fallbacks.

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
- [x] **Deterministic Mission Generation & Progressive S-Curve Tiering:** SplitMix64 mixing, regional quota cap (max 2 per type), repeat dampening (0.45 factor), and deterministic per-subregion progressive S-curve tiering (cubic Hermite smoothstep across $20 \le R \le 80$, hitting 50% parity at $R=50$), replacing the binary 50% cliff with organic mixed easy/hard mission rosters.
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
- [x] **Pillager Elite Duelist 4-Stage Combo Chain:** Multi-stage greatsword combo chain (`attack_1` through `attack_4` finisher) with distance-based combo chaining ($\le 3.5\text{m}$), forward lunge impulses, bespoke per-hit directional frontal damage arcs ($110^\circ, 150^\circ, 80^\circ, 40^\circ$), finisher recovery freeze, and dynamic stance switching between greatsword and repeating crossbow.
- [x] **Pillager Elite Shield-Punish Kick Attack:** 30% replacement roll on any hit in the chain (Hit 1 through 4) when target is actively blocking with a shield; at $0.60\text{s}$ contact disables blocking shields (5.0s cooldown), deals 1.0 damage, and inflicts 4 blocks of knockback. Whiffing against lowered shields incurs an extended 1.75s vulnerability freeze.
- [x] **Pillager Scout Reconnaissance & Reinforcement Orchestration:** Autonomous 1-per-subregion spawning with 5-minute cooldown, 150-degree 30-block spyglass cone detection, interruptible scan counterplay, Goat Horn "Seek" alarm, and 3-scenario progressive squad reinforcements (0-1 squads: +10s / +40s; 2 squads: immediate + +30s; maxed: immediate + +30s) with homing pursuit and natural spawn suppression.
- [x] **Zero-Structure Territory Defense Missions:** Co-fallback defense suite (`DEFEAT_INVADING_FORCES` & `HOLD_THE_CHOKEPOINT`) with border corridor invasion vectors, abstract defense integrity decay/stabilization, and zero structure generation in player territory.
- [x] **Archaic Attack Value Purge:** Complete removal of obsolete `attack_value` across network payloads, siege records, domino calculations, and AI manager.
- [x] **Structure Preservation, Retry Repair & Crater-Free Demolition:** Preserves physical structure anchors and block footprints upon mission loss during active regional wars; repairs broken sabotage/salvage blocks and respawns defender garrisons on retry. Sub-surface foundation demolition backfills flush to natural solid ground, preventing crater formation and downward height cascades. Authoritative map button synchronization resolves client green border stagnation.
- [x] **Outpost Salvage Lifecycle, Floor Demolition & Anti-Loop Safeguards:** Integrated Outpost chests into `PendingSalvageManager`, deferring base demolition until all loot chests are harvested. Demolition cleanly replaces base footprints and foundation columns flush with natural topsoil (`GRASS_BLOCK`). Marksman patrol AI checks motion-blocking platform support before repositioning, preventing mid-air teleport loops on demolished watchtowers. Enlisted garrison defenders immediately disperse upon outpost conquest, and decorative prop barrels are excluded from salvage objectives.
- [x] **Volumetric Demolition Optimization & Phantom Air Suppression:** Suppressed blind volumetric air recording in temporary structure templates (`OfficerCamp`, `ArtilleryBattery`, `WarHornTower`). Site snapshots strictly record overwritten natural terrain and physical placed structure blocks. Demolition tasks instantly bypass identical blocks and trigger POOF feedback strictly on physical block demolition, eliminating phantom air popping.
- [x] **Headquarters Footprint, Mountain Lodging & Explicit Wing Invariant:** $50 \times 90$ rectangular base centered across 2 subregions with 7-block lateral margins (zero overflow into adjacent subregions), 5-block thick perimeter walls, seamless open central courtyard pathways, and an explicit 2-wing contract mapping subregions to deterministic 2-of-3 base missions across factions. Lodges cleanly into mountain slopes via dynamic column heightmap excavation to open sky and stepped foundation footings down to solid ground. Subregion-partitioned base self-repair ensures non-active base wings continue self-repairing during active combat missions. Base subregions are strictly insulated from reinforcement salt, guaranteeing mission persistence and preventing temporary structures on base tiles.
- [x] **Cross-Dimension Isolation & Map Intel Restriction:** Authoritative binding of `RegionData` and procedural generation to the Overworld (`level.getServer().overworld()`), dimension-guarded biome caches, and restricted map viewing outside the Overworld displaying centered 'Cannot Get Intel' status without data corruption or cross-dimension writes.
- [x] **Base-Only Defense Triad & Catapult Deprecation:** Mobile Catapult entity scrapped in favor of 1-block artillery machines. Added three base-only defense specialists: Pillager Cannoneer (parapet sentry with large-caliber handheld bombard firing concussive non-destructive cannonballs), Pillager Rapidbow (tripod-mounted repeating ballista with locked $\pm 40^\circ$ traverse, $3.5^\circ/\text{tick}$ turn rate, 10-bolt burst volley, absolute knockback resistance when deployed, $2.16\text{s}$ integer-snapped deploy/pack-up animations, and 8-block tactical spacing AI), and Pillager Ironclad (3-block tall meatwall with 100% frontal damage absorption, bodyguard line-of-fire intercept AI, and concussive shield bash).
- [x] **Pillager Cannoneer Chained Combat & Cannon Bomb Ballistics:** Two-handed culverin hold with updated two-handed locomotion; seamless chained combat sequence (30-tick Aim -> 17-tick Shoot -> 55-tick Reload -> 60-tick / 3.0s Idle Cooldown) with pose continuity; fires weighted ballistic `cannon_bomb` projectile on Tick 3 with upward launch arc, non-destructive explosion, 8.0 damage, and radial explosive knockback.
- [x] **Pillager Rifleman & High-Velocity Ballistics:** Bolt-action sniper armed with bolt action rifle and bayonet. Features internal 5-round magazine, bolt cycling between shots, single-stage tactical reload (incrementing bullets at ticks 20, 35, 50, 65, 80 with damage interruption), out-of-combat top-off, and close-quarters bayonet defense ($\le 2.5\text{m}$, 7.0 damage). Fires high-velocity ballistic rounds ($4.8\text{F}$ velocity) that bypass vanilla wooden shields, are blocked by upgraded redoubt shields, and deliver calibrated armor-piercing damage ($6.0\text{ HP}$ unarmored down to exactly $2.0\text{ HP}$ / 1.0 heart against Full Protection IV Netherite). Intercepts Pillager Marksman spawns in higher resistance territories with quadratic probability $P(R) = (R / 100)^2$.
- [x] **Contested Wilderness Defense & Incursion Commitment:** Bordering player factions can contest AI expansion into `Faction.UNCLAIMED` wilderness. Defending factions commit to the incursion (`defenderPlayerFactionId`); completing defense objectives claims the region for the player faction at 20/20 baseline stats (`BaseType.NONE`), while abandoning or failing allows the AI faction to conquer it. Corrected client defense classification and removed desynchronized optimistic overlay staging.
- [x] **Performance Optimization Pipeline (AI Goals, Patrol Lifecycles & Netcode):** Consolidated Pillager hostile targeting goals across custom entities using unified `Monster` filters. Throttled projectile reflection and bodyguard scans. Fixed patrol squad deletion on chunk unloading (`countLiving` checks chunk load state). Discarded distant non-persistent wandering mobs (>128m). Pre-allocated map payload chunk collections and proximity-filtered active mission HUD updates to 192m.
- [x] **Subregion Biome Detection & Compound Terrain Naming:** $O(1)$ Voronoi climate classifier sampling chunk centers without chunk generation. Aggregates $4 \times 4$ chunk subregion profiles and averages across macro regions to generate dual-element compound terrain descriptors (e.g. 'Rivery Plains', 'Mountainous Jungle', 'Wooded Swamp') when secondary terrain $\ge 20\%$. Integrated into `RegionDetailsPayload` and displayed in a dedicated left-panel 'TERRAIN' info box on the strategic map.
- [x] **Ballistic Friendly Fire, Scout Alarm Cooldown & Base Placement Isolation:** Enabled authentic projectile friendly fire across high-velocity rifle rounds and concussive cannon bombs while preserving Ironclad shield passthrough and suppressing inter-faction retaliatory civil wars. Widened rifleman line-of-fire corridor safety to hold shots when any ally is directly obstructed. Filtered Creative/Spectator players from scout targeting and reconnaissance spyglass scans, and enforced a 3-minute subregion alarm cooldown (`REINFORCEMENT_COOLDOWN_TICKS = 3600L`). Strictly gated base repair and garrisons to verified placed bases, preventing unplaced bases from being hijacked into block-by-block rebuilds.
- [x] **Topographic 3D Hillshading, Unified Climate Elevation Model & Clean Map Canvas:** Dedicated `HILLS` terrain classification separating elevated floral plateaus/hills (meadows, cherry groves, savanna plateaus, windswept hills at Y=95–125) from alpine mountain slopes and peaks. Unified $O(1)$ global climate elevation model (`BiomeElevation`) combining multi-noise continentalness, erosion, and weirdness (Peaks & Valleys) evaluated uniformly across all chunks, completely eliminating moving square chunk borders, transition halos, and boundary seams. Cave-bypass biome sampling prevents subterranean cave biomes (dripstone, lush, deep dark) from leaking onto surface maps. Dynamic NW cartographic hillshading in dynamic texture rendering provides crisp, uncluttered 3D physical elevation relief across the entire canvas without line clutter, alongside a consolidated 5-box left-panel strategic map UI displaying dual-metric territory stats ('Resistance : X', 'Stability : Y').
- [x] **Dual-Language Localization (EN-US & TR-TR):** Complete localization parity across the entire mod codebase. All hardcoded English strings in blocks, items, commands, mission handlers, faction panels, HUD overlays, strategic map screens, and renderers are decoupled into translatable components, backed by synchronized `en_us.json` and `tr_tr.json` language assets with 1:1 key parity.
- [x] **Ironclad Dynamic Bodyguard Prioritization & Crosshair Interception:** Restored dynamic bodyguard priority architecture across 3 tiers: Tier 1 emergency defense for allies damaged within 100 ticks (`tickCount - lastHurtTimestamp < 100` or `hurtTime > 0`), Tier 2 active aim interception via 3D orthogonal ray crosshair distance ($d_\perp \le 1.8\text{m}$ along forward look vector), and Tier 3 proximity guard with 3.0m hysteresis sticky bonus to eliminate retargeting jitter. Whitelisted all allied Pillager gunners and officers (Marksman, Rifleman, Cannoneer, Rapidbow, Commander, vanilla Pillager) with fallback to protect generic comrades before triggering solo Last Stand.
- [x] **Physical Player Fortifications (Friendly Mortar Support):** Crafted from salvaged machinery cores (`SALVAGED_MORTAR_CORE`), iron blocks, and redstone. Provides long-range tactical artillery support ($1.9\text{ m/s}$ muzzle velocity, $0.05$ gravity, $3 \times 3$ subregion coverage) with a custom 6-slot container UI (5 ammo + 1 fuel). Features conditional furnace burn (paused when unarmed), random chambering with breech UI feedback, and 4 specialized shell types: HE (heavy 2.8F crater + 11.0m lethal blast), Incendiary (napalm fire field + burn DoT), Airburst (25-block altitude fuse scattering 18 dedicated cluster bomblets via Mitchell's Best-Candidate blue-noise sampling across a 20-meter footprint with 4–6 tick rolling arrival jitter and zero entity knockback), and Smoke (Counter-Strike style high-pressure pop burst, dense zero-drift volumetric cloud, inside-smoke blindness, and event-driven target acquisition suppression via `LivingChangeTargetEvent`). Subject to regional fortification quotas (maximum 3 mortars per macro-region, maximum 2 heavy fortifications combined per subregion).
- [x] **Giant Ballista Heavy Siege Fortifications (Pillager & Player Variants):** 1-block automated heavy defense turret ($15^\circ/\text{s}$ traverse, 15-second cooldown) firing supersonic piercing bolts ($7.0\text{ blocks/tick}$ / $140\text{ m/s}$, 0.005 gravity, 30.0 piercing damage) with 3D swept collision ($0.55\text{m}$ cylindrical radius), near-miss sonic boom shockwave knockback ($2.0\text{m}$ perpendicular corridor), and full armor/invulnerability penetration, piercing through all entities in its path until impacting solid terrain. Features automated targeting across $3 \times 3$ subregions with foliage and smoke line-of-sight verification, strict physical pitch gating ($-25.0^\circ$ to $+35.0^\circ$), full velocity lead at $70\%$ entity center-of-mass, zero-tolerance alignment firing, single-axis pitch and yaw GeckoLib articulation, and full player territorial lifecycle support (crafted from heavy pivot bearings, iron, crossbow, redstone; fueled by coal furnace burn with conditional pausing and chambering; subject to 3 ballistas per region and 2 heavy fortifications combined per subregion).
- [x] **Terrain & Elevation Combat Doctrines & Mission Scaling:** Integrated subregion terrain classification and $O(1)$ climate elevation into squad role generation via `TerrainSpawnDoctrine`, modulating Pillager composition (melee vanguard in jungles/swamps, long-range snipers and elevated Marksmen on high hills and alpine peaks) while strictly preserving resistance-gated Rifleman conversion. Scaled mission weights and concrete objective quotas across biomes (reducing Kill Count quotas by ~35% in dense jungles, swamps, and mountain crags to preserve pacing; calibrating Scout Network nests and Artillery Battery pits). Enhanced autonomous Scout reconnaissance with 3-minute accelerated cooldown in rugged/dense terrain and an expanded 40m spyglass detection cone on elevated terrain, synchronized deterministically between client and server via packed 16-bit subregion terrain and elevation codes.
- [x] **Performance Optimization Stack Compatibility & Relaxed Patrol Lifecycles:** Fully validated compatibility against heavy performance modpacks (Sodium, Iris, Lithium, FerriteCore, ImmediatelyFast, EntityCulling, ModernFix, AllTheLeaks). Decoupled infantry patrol squad border validation from wagon highway constraints, allowing patrols to traverse tree canopies and natural stepping inclines. Tagged patrol squad entities with persistent state and relaxed roaming tracker culling to 256m with expanded 128m/160m AI activation hysteresis, enabling sustained entity encounters and seamless army testing under high-speed flight without premature despawns.
- [x] **Cluster Generation & Base Anchor Placement Rework:** Multi-candidate cell center search (3×3 grid) preserving 15% Mega Base occurrence and steering clusters away from river banks. Authoritative $100 \times 100$ Mega Base footprint centered in region across all 4 subregions. Continuous 9-point true 3D surface height evaluation with nonlinear slope penalties for Outposts (20-candidate subregion search) and Headquarters (4-cardinal orientation and 1D middle-line shift evaluation). Unloaded 3D noise router fluid detection (`WORLD_SURFACE_WG` vs `OCEAN_FLOOR_WG`), strict center water veto, and $\le 10\%$ submerged outpost ceiling, eliminating submerged bases. Complete removal of tier downgrade cascades in favor of strict tier preservation. Size 1 clusters center strictly on Outposts ($MD \le 2$ territory). Dynamic rival border buffer clearance enforcing 1-region neutral DMZ without cannibalizing cluster territories. Bipartite checkerboard faction parity guaranteeing 50/50 Pillager/Zombie distribution and immediate rival conflict borders across the world.
- [x] **Stateful Patrol Memory & Proximity Spawning Optimization:** Restored high framerate (60–120+ FPS) in Mega Base clusters by tuning `PLAYER_SCAN_RADIUS_BLOCKS` to 64 blocks ($128 \times 128$ footprint), scoping active spawns to 1–4 immediate subregions while preserving full tactical squad compositions (3 to 16 mobs per resistance tier). Added stateful transit memory to `SubregionPatrolManager` and `RoamingEntityTracker`: squads discarded by distance without entering combat automatically refund their subregion's cooldown, guaranteeing active hostile encounters upon return. Purged destructive `setNoAi(true)` calls, migrated tracking to 2D horizontal distance ($dx^2 + dz^2$), auto-healed legacy frozen entities, and eliminated the infinite 15-second hold-ground loop with robust interior waypoint fallback and perturbation.
- [x] **Biome-Specific Tactical Missions & Regional Salvage Matrix:** Expanded mission roster with 24 procedural biome missions (12 Easy / 12 Hard across 6 tactical biomes: Highlands, Woodlands, Wetlands, Arid, Grasslands, Glacial Arctic) resolving region mission repetition bottlenecks. Excludes River and Ocean from mission generation. Employs procedural stepped foundation building ($Y-1 \rightarrow Y-8$) with natural site clearing and native mob garrisons. Introduces a functional regional salvage matrix: Optical Rangefinder Lens, Cured Siege Timber, Giant Bolt Bundle (unpacks to 16 ballista bolts), Alchemical Sulfur Slag (high-efficiency 4x burn fuel), Frost-Tempered Heatsink, and rare lootable Cryo Artillery Shells (freezing flashburst), fueling player artillery, defense fortifications, and ammunition logistics.

---

## 10. Active Development & Forward Roadmap

### Active Focus (In Progress):
- [ ] **Headquarters & Mega Base NBT Blueprints:** Designing, building, and exporting native `.nbt` templates for Big Base (HQ, $50 \times 90$) and Mega Base ($100 \times 100$) tiers.
- [ ] **Cluster Mobile Field Armies & Maneuver Warfare:**
  - Capacity: 1 army per Medium Base (HQ) in the cluster; armies occupy only non-base regions.
  - Interval Movement: Relocates across contiguous friendly cardinal neighbors back to cluster base.
  - Encirclement & Pocketing: Severing friendly connections immobilizes the army in an isolated pocket.
  - Stat Impact: Raises region Resistance to 80–90 levels and multiplies attack value in offensive sieges.
  - Decapitation Rule: Capturing parent HQ cuts army stat bonus in half (40–45 Resistance).
  - Combat Encounter: Replaces 4 subregion missions with a single region-wide "Field Battle / Army Extermination" mission.
  - Faction Army Nomenclature:
    - **Pillager Conquerors (Imperial Legions):** Classical military legionary naming (`[Ordinal] [Epithet] Legion / Cohort`, e.g., *1st Iron Legion*, *3rd Vanguard Cohort*, *7th Ravager Legion*).
    - **Zombie Horde (Viral Strains):** Epidemiological biohazard classification (`Horde [Greek Letter]`, e.g., *Horde Alpha*, *Horde Delta*, *Horde Omicron*).
- [ ] **Mob Component Drops & Bulk Logistics Loot Tables:** Tiered military component drops for mobs (rivets, fletchings, prisms, ciphers) and bulk engineering logistics loot tables for Supply Wagons.

### Planned Milestones:
- [ ] **Physical Player Fortifications:** Salvaged installation cores (mortar cores, redoubt plates, war horn relays) used to construct watchtowers, mounted ballistas, and artillery to fortify player territory beyond 20/20.
- [ ] **Zombie Horde Infestation System & Custom Entities:** `ZombieInfestationManager` ambient saturation engine, Helldivers 2-style breach & grace cycle (3-minute purge grace, emergence surges, accelerating cooldown curve), zero-pathfinding burrow-relocation anti-stuck AI, continuous density scaling ($15 \rightarrow 70$), shared elite pool ($0 \rightarrow 12$), dual-tier spatial leashing, and custom GeckoLib entity classes (Fodder, Hunter, Spitter, Screamer, Tank).
- [ ] **Future Incursion Factions (Piglin Expedition, Ender Eclipse):** Unclaimed frontier portal incursions, mercenary bartering, and reality-bending endgame siege mechanics.
- [ ] **Kinetic Military Automation Hooks:** Optional mechanical automation interfaces for player fortifications (Create mod compatibility).
- [ ] **DLC 3: "Overworld Strikes Back" (Post-End Dimensional Warfare):**
  - **End-Tech Gate & Vanilla Preservation:** Dimension territory claiming is gated behind post-dragon End-tier tech (Planar Staging Cores); early-game visits retain 100% vanilla loot and mechanics without territory claiming.
  - **Total Hostile Ownership with Proximity Gradient:** Dimensions generate 100% enemy-owned (Piglin Dominion / Ender Hive), with Resistance/Stability scaling dynamically from frontier fringes (20–30) up to central Citadels (80–90+) via base aura boosting.
  - **Piglin Barter Rework & Territorial Spoils:** Replaces single-ingot bartering with military conquest: capturing Piglin sectors disarms high-volume Gilded Treasury Vaults, and captured command desks enable bulk gold requisitioning of all bartered goods.
  - **High-Stake, Low-Frequency Defense:** Infrequent, heavy counter-assaults designed to eliminate defense chore spam while challenging fully enchanted post-End gear.
  - **Overworld Incursion Decapitation:** Conquering dimension command nodes permanently suppresses and weakens dimensional raid pressure on the Overworld.
  - **Modular Logistics & Create Integration:** Multi-dimension data isolation supporting cross-portal logistics (e.g. Create train networks), sequential assembly core crafting, and heavy endgame material sinks.

---

## 11. Developer Rules for Future Iterations

1. **Always Check Stored State First:** Never re-derive or guess a base location or region owner if `RegionData` already has it stored.
2. **Never Re-Introduce Region-Wide Bans on Rivers:** Keep river checks localized to footprint viability and base-center aquatic ratio ($\le 50\%$).
3. **Keep `AlliedFactionHelper` Free of Recursion:** Never call `livingA.isAlliedTo(livingB)` inside `AlliedFactionHelper.isAllied`. Check tags, teams, squads, and IDs directly.
4. **Zero Improvised Behaviors:** Implement ONLY what the user explicitly directs and approves. Do not invent behaviors, UI layouts, or mechanics.
5. **Always Ask For Confirmation:** Create an implementation plan artifact for every task and wait for explicit user approval before modifying files.
6. **Preserve GeckoLib Performance:** Do not run client-side animation state logic on the server thread.
7. **Maintain Milestone Checklist & Invariants:** Keep this overview file clean. Update milestone checkmarks (`- [x]`) and record high-level architectural rules; do NOT append code changelogs, class walkthroughs, or bug post-mortems.
8. **Blockbench to GeckoLib Coordinate Parity Invariant:** Animations authored in Blockbench space (`.bbmodel`) have torso forward lean as $-X$, forward leg/arm swing as $+X$, trailing leg/knee flexion as $-X$, and forearm curls as $+X$. When exporting to GeckoLib standalone format (`.animation.json`), the official Blockbench export transform ($\text{geo.x} = -\text{bb.x}$, $\text{geo.y} = -\text{bb.y}$, $\text{geo.z} = \text{bb.z}$) must be applied to preserve exact visual and runtime parity.
9. **Enforce Dual-Language (EN/TR) Localization Parity:** Any newly introduced block, item, entity, UI widget, command feedback, or chat message MUST be defined with a translation key and added simultaneously to both `en_us.json` and `tr_tr.json`.