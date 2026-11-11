package com.warfront.mission;

/**
 * Defines the category of player mission a subregion operation represents.
 *
 * Encompasses:
 * - 4 Easy missions (Resistance < 50%)
 * - 4 Hard missions (Resistance >= 50%)
 * - 1 Outpost Base mission (occupies 1 subregion)
 * - 3 Medium Base / HQ missions (occupies 2 subregions)
 * - 6 Mega-Base missions (occupies 4 subregions)
 * - 4 Defense missions (defending allied / contested territory)
 */
public enum MissionType {
    /** Fallback kill count test mission. */
    KILL_COUNT(ObjectiveType.ELIMINATE_TARGETS),

    // ==========================================
    // 4 Easy Mission Types (Resistance < 50%)
    // ==========================================
    /** Forward Patrol: Disrupt a light reconnaissance patrol operating near the frontline. */
    FORWARD_PATROL(ObjectiveType.ELIMINATE_TARGETS),

    /** Supply Convoy: Intercept logistical supply pack moving between hostile positions. */
    SUPPLY_CONVOY(ObjectiveType.INTERCEPT),

    /** Forward Outpost: Demolish an entrenched forward scouting watchpost. */
    FORWARD_OUTPOST(ObjectiveType.DESTROY_STRUCTURE),

    /** Scout Network: Neutralize multiple forward observation posts before intelligence reaches high command. */
    SCOUT_NETWORK(ObjectiveType.DESTROY_OBJECTIVES),

    // ==========================================
    // Biome-Specific Easy Mission Types (< 50% Resistance)
    // ==========================================
    /** Summit Observation Post: Highlands observation platform with scout tables. */
    MOUNTAIN_LOOKOUT_POST(ObjectiveType.DESTROY_OBJECTIVES),

    /** High Pass Barricade: Mountain pass fortified roadblock. */
    MOUNTAIN_PASS_BARRICADE(ObjectiveType.DESTROY_STRUCTURE),

    /** Logging Sawmill: Forest timber clear-cutting sawbenches. */
    FOREST_LOGGING_DEPOT(ObjectiveType.DESTROY_OBJECTIVES),

    /** Woodland Stash: Hidden canopy hunting supply stash. */
    FOREST_HUNTING_STASH(ObjectiveType.DESTROY_OBJECTIVES),

    /** Stranded Supply Barge: Swamp channel grounded flatboat. */
    SWAMP_STRANDED_BARGE(ObjectiveType.DESTROY_OBJECTIVES),

    /** Marshland Fuel Still: Swamp peat boiling still cauldrons. */
    SWAMP_FUEL_STILL(ObjectiveType.DESTROY_OBJECTIVES),

    /** Sand Road Checkpoint: Desert road sandstone barrier gate. */
    DESERT_ROAD_CHECKPOINT(ObjectiveType.DESTROY_STRUCTURE),

    /** Desert Cistern Depot: Arid cistern and terracotta water caches. */
    DESERT_CISTERN_DEPOT(ObjectiveType.DESTROY_OBJECTIVES),

    /** Forward Scout Paddock: Plains scout staging paddock and logistics carts. */
    PLAINS_SCOUT_PADDOCK(ObjectiveType.DESTROY_OBJECTIVES),

    /** Frontier Signal Mast: Grassland boundary signal pole. */
    PLAINS_SIGNAL_MAST(ObjectiveType.DESTROY_STRUCTURE),

    /** Icefield Supply Cache: Snow-covered arctic supply depot. */
    TUNDRA_SUPPLY_CACHE(ObjectiveType.DESTROY_OBJECTIVES),

    /** Frozen Watchpost: Cold-weather fortified lookout tower. */
    TUNDRA_FROZEN_WATCHPOST(ObjectiveType.DESTROY_STRUCTURE),

    // ==========================================
    // 4 Hard Mission Types (Resistance >= 50%)
    // ==========================================
    /** Strongpoint Assault: Breach and neutralize a heavily fortified pillager redoubt. */
    STRONGPOINT_ASSAULT(ObjectiveType.DESTROY_STRUCTURE),

    /** Officer Hunt: Infiltrate a defended field headquarters and assassinate a high-ranking Officer. */
    OFFICER_HUNT(ObjectiveType.ELIMINATE_LEADER),

    /** Artillery Battery: Sabotage heavy field mortar/catapult emplacements shelling the frontline. */
    ARTILLERY_BATTERY(ObjectiveType.DESTROY_OBJECTIVES),

    /** Cut the Supply Line: Sever a distributed logistics route by striking multiple supply targets. */
    CUT_THE_SUPPLY_LINE(ObjectiveType.MULTI_OBJECTIVE),

    /** War Horn Relay / Signal Tower: Scale a fortified mast and sabotage the signal fuse before response battalions arrive. */
    WAR_HORN_RELAY(ObjectiveType.MULTI_PHASE),

    // ==========================================
    // Biome-Specific Hard Mission Types (>= 50% Resistance)
    // ==========================================
    /** Summit Signal Brazier: Mountain pinnacle beacon with timed flameout alarm. */
    MOUNTAIN_BEACON_BASTION(ObjectiveType.MULTI_PHASE),

    /** Mountain Ridge Battery: High-altitude mortar pit shelling valleys. */
    MOUNTAIN_RIDGE_BATTERY(ObjectiveType.DESTROY_OBJECTIVES),

    /** Canopy Sniping Blinds: Woodland camp with elevated tree blinds and snipers. */
    FOREST_CANOPY_BLINDS(ObjectiveType.DESTROY_STRUCTURE),

    /** Forest Command Camp: Entrenched forest camp and commander. */
    FOREST_COMMAND_CAMP(ObjectiveType.ELIMINATE_LEADER),

    /** Mangrove Stilt Redoubt: Elevated swamp stilt outpost over murky water. */
    SWAMP_STILT_REDOUBT(ObjectiveType.DESTROY_STRUCTURE),

    /** Swamp Ironclad Mud Pit: Sunken mud hollow held by ironclads and gunners. */
    SWAMP_IRONCLAD_HOLD(ObjectiveType.ELIMINATE_TARGETS),

    /** Mesa Ballista Battery: High mesa redoubt with heavy rapidbow ballistas. */
    BADLANDS_MESA_BATTERY(ObjectiveType.DESTROY_OBJECTIVES),

    /** Canyon Dugout Strongpoint: Desert canyon sandstone bunker and commander. */
    DESERT_CANYON_STRONGPOINT(ObjectiveType.ELIMINATE_LEADER),

    /** Trenched Field Redoubt: Plains zig-zag trench system with mortar dugout. */
    PLAINS_TRENCH_REDOUBT(ObjectiveType.DESTROY_STRUCTURE),

    /** Forward Field Command: Fortified plains command encampment and officer. */
    PLAINS_FIELD_COMMAND(ObjectiveType.ELIMINATE_LEADER),

    /** Frostbite Mortar Pit: Arctic snow revetment mortar pit. */
    TUNDRA_MORTAR_PIT(ObjectiveType.DESTROY_OBJECTIVES),

    /** Arctic Garrison Redoubt: Glacial log blockhouse and arctic commander. */
    TUNDRA_GARRISON_REDOUBT(ObjectiveType.ELIMINATE_LEADER),

    // ==========================================
    // Outpost Base Mission (1 variant: 1 subregion)
    // ==========================================
    /** Demolish Outpost: Infiltrate and demolish the physical outpost watchtower fortification. */
    OUTPOST_DESTROY_BUILDING(ObjectiveType.DESTROY_STRUCTURE),

    // ==========================================
    // Medium Base / Headquarters (3 variants: 2 subregions)
    // ==========================================
    /** Eliminate Commander: Assassinate the high-ranking commanding officer of the garrison. */
    BASE_KILL_COMMANDER(ObjectiveType.ELIMINATE_LEADER),

    /** Destroy Command Center: Infiltrate communication center and destroy enemy intelligence relay. */
    BASE_DESTROY_INTEL(ObjectiveType.DESTROY_STRUCTURE),

    /** Destroy Storage Depot: Infiltrate supply depot and obliterate frontline supply caches. */
    BASE_DESTROY_SUPPLIES(ObjectiveType.DESTROY_OBJECTIVES),

    // ==========================================
    // Mega Base (6 variants: 4 subregions)
    // ==========================================
    /** Command Bunker: Infiltrate citadel inner sanctum and eliminate High Command (Anchor). */
    COMMAND_BUNKER(ObjectiveType.ELIMINATE_LEADER),

    /** Break the War Room: Obliterate central tactical war room and strategic map charts. */
    BREAK_THE_WAR_ROOM(ObjectiveType.DESTROY_OBJECTIVES),

    /** Munitions Depot: Infiltrate and detonate primary explosive stockpiles fueling the war effort. */
    MUNITIONS_DEPOT(ObjectiveType.DESTROY_OBJECTIVES),

    /** Break the Gates: Shatter heavy reinforced citadel blast gates to allow friendly breach. */
    BREAK_THE_GATES(ObjectiveType.BREACH),

    /** Silence the Guns: Disable fortress heavy artillery and anti-air batteries protecting air corridors. */
    SILENCE_THE_GUNS(ObjectiveType.DESTROY_OBJECTIVES),

    /** Sever Communications: Destroy long-range transmitter array linking base to high command. */
    SEVER_COMMUNICATIONS(ObjectiveType.DESTROY_OBJECTIVES),

    // ==========================================
    // Defense Missions (Defending Allied / Contested Territory)
    // ==========================================
    /** Defeat Invading Forces: Neutralize invading enemy assault waves advancing from contested borders. */
    DEFEAT_INVADING_FORCES(ObjectiveType.ELIMINATE_TARGETS),

    /** Hold the Chokepoint: Hold ground at an improvised perimeter against invading waves using terrain and player defenses. */
    HOLD_THE_CHOKEPOINT(ObjectiveType.SURVIVE_ASSAULT),

    // ==========================================
    // Mobile Field Army Battle Mission (Whole Region)
    // ==========================================
    /** Mobile Field Army Battle: Unified region-wide field battle against a stationed mobile army / horde vanguard. */
    FIELD_BATTLE(ObjectiveType.ELIMINATE_TARGETS);

    private final ObjectiveType defaultObjectiveType;

    MissionType(ObjectiveType defaultObjectiveType) {
        this.defaultObjectiveType = defaultObjectiveType;
    }

    /**
     * Returns the primary objective type that governs this mission category.
     */
    public ObjectiveType defaultObjectiveType() {
        return defaultObjectiveType;
    }

    /**
     * Checks if this mission type represents a mandatory physical base mission.
     */
    public boolean isBaseMission() {
        return switch (this) {
            case OUTPOST_DESTROY_BUILDING,
                 BASE_KILL_COMMANDER,
                 BASE_DESTROY_INTEL,
                 BASE_DESTROY_SUPPLIES,
                 COMMAND_BUNKER,
                 BREAK_THE_WAR_ROOM,
                 MUNITIONS_DEPOT,
                 BREAK_THE_GATES,
                 SILENCE_THE_GUNS,
                 SEVER_COMMUNICATIONS,
                 FIELD_BATTLE -> true;
            default -> false;
        };
    }

    /**
     * Checks if this mission is an army unified field battle.
     */
    public boolean isFieldBattle() {
        return this == FIELD_BATTLE;
    }

    /**
     * Checks if this mission type is an easy tier mission (< 50% resistance).
     */
    public boolean isEasy() {
        return switch (this) {
            case FORWARD_PATROL, SUPPLY_CONVOY, FORWARD_OUTPOST, SCOUT_NETWORK,
                 MOUNTAIN_LOOKOUT_POST, MOUNTAIN_PASS_BARRICADE,
                 FOREST_LOGGING_DEPOT, FOREST_HUNTING_STASH,
                 SWAMP_STRANDED_BARGE, SWAMP_FUEL_STILL,
                 DESERT_ROAD_CHECKPOINT, DESERT_CISTERN_DEPOT,
                 PLAINS_SCOUT_PADDOCK, PLAINS_SIGNAL_MAST,
                 TUNDRA_SUPPLY_CACHE, TUNDRA_FROZEN_WATCHPOST -> true;
            default -> false;
        };
    }

    /**
     * Checks if this mission type is a hard tier mission (>= 50% resistance).
     */
    public boolean isHard() {
        return switch (this) {
            case STRONGPOINT_ASSAULT, OFFICER_HUNT, ARTILLERY_BATTERY, CUT_THE_SUPPLY_LINE, WAR_HORN_RELAY,
                 MOUNTAIN_BEACON_BASTION, MOUNTAIN_RIDGE_BATTERY,
                 FOREST_CANOPY_BLINDS, FOREST_COMMAND_CAMP,
                 SWAMP_STILT_REDOUBT, SWAMP_IRONCLAD_HOLD,
                 BADLANDS_MESA_BATTERY, DESERT_CANYON_STRONGPOINT,
                 PLAINS_TRENCH_REDOUBT, PLAINS_FIELD_COMMAND,
                 TUNDRA_MORTAR_PIT, TUNDRA_GARRISON_REDOUBT -> true;
            default -> false;
        };
    }

    /**
     * Checks if this mission type is a defense tier mission.
     */
    public boolean isDefense() {
        return switch (this) {
            case DEFEAT_INVADING_FORCES, HOLD_THE_CHOKEPOINT -> true;
            default -> false;
        };
    }

    /**
     * Checks if this mission belongs to the Mega Base citadel complex.
     */
    public boolean isMegaBase() {
        return switch (this) {
            case COMMAND_BUNKER, BREAK_THE_WAR_ROOM, MUNITIONS_DEPOT,
                 BREAK_THE_GATES, SILENCE_THE_GUNS, SEVER_COMMUNICATIONS -> true;
            default -> false;
        };
    }
}
