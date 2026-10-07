package com.warfront.mission;

/**
 * Categorizes the gameplay objective mechanics governing a Warfront mission.
 */
public enum ObjectiveType {
    /** Single mob hunt — specific target mob slain. */
    ELIMINATE_TARGET,

    /** Fortified boss hunt — named Commander/Officer dead. */
    ELIMINATE_LEADER,

    /** Distributed squad hunt — multiple tracked mobs dead. */
    ELIMINATE_TARGETS,

    /** Single structure demolition — core block/structure broken. */
    DESTROY_STRUCTURE,

    /** Distributed assets demolition — multiple props/blocks broken. */
    DESTROY_OBJECTIVES,

    /** Moving group interception — waypoint convoy halted/slain. */
    INTERCEPT,

    /** Mixed operational tasks — props broken + escort dead. */
    MULTI_OBJECTIVE,

    /** Timed survival defense — survive hostile assault waves for duration. */
    SURVIVE_ASSAULT,

    /** Asset defense — objective block/entity protected until assault ceases. */
    PROTECT_OBJECTIVE,

    /** Ally protection — allied entity safely reaches extraction target. */
    ESCORT,

    /** Barrier breach — gate/barrier structure broken. */
    BREACH,

    /** Sequential multi-stage operation — phase 1 done -> phase 2 done. */
    MULTI_PHASE;

    /**
     * Checks if this objective type represents a defensive operation.
     */
    public boolean isDefense() {
        return this == SURVIVE_ASSAULT || this == PROTECT_OBJECTIVE || this == ESCORT;
    }

    /**
     * Checks if this objective type involves destroying physical structures or props.
     */
    public boolean isStructureOrProp() {
        return this == DESTROY_STRUCTURE || this == DESTROY_OBJECTIVES || this == BREACH;
    }
}
