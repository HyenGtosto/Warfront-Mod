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
    /** Hold the Line: Defend a frontier forward position against sustained assault waves. */
    HOLD_THE_LINE(ObjectiveType.SURVIVE_ASSAULT),

    /** Protect the Strongpoint: Defend allied bunker, radar relay, or beacon from being demolished. */
    PROTECT_THE_STRONGPOINT(ObjectiveType.PROTECT_OBJECTIVE),

    /** Emergency Evacuation: Escort civilian/engineer evacuation transport safely across the warzone. */
    EMERGENCY_EVACUATION(ObjectiveType.ESCORT),

    /** Counterattack: Repel enemy breakthrough, then strike back and eliminate the attacking field commander. */
    COUNTERATTACK(ObjectiveType.MULTI_PHASE);

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
                 SEVER_COMMUNICATIONS -> true;
            default -> false;
        };
    }

    /**
     * Checks if this mission type is an easy tier mission (< 50% resistance).
     */
    public boolean isEasy() {
        return switch (this) {
            case FORWARD_PATROL, SUPPLY_CONVOY, FORWARD_OUTPOST, SCOUT_NETWORK -> true;
            default -> false;
        };
    }

    /**
     * Checks if this mission type is a hard tier mission (>= 50% resistance).
     */
    public boolean isHard() {
        return switch (this) {
            case STRONGPOINT_ASSAULT, OFFICER_HUNT, ARTILLERY_BATTERY, CUT_THE_SUPPLY_LINE -> true;
            default -> false;
        };
    }

    /**
     * Checks if this mission type is a defense tier mission.
     */
    public boolean isDefense() {
        return switch (this) {
            case HOLD_THE_LINE, PROTECT_THE_STRONGPOINT, EMERGENCY_EVACUATION, COUNTERATTACK -> true;
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
