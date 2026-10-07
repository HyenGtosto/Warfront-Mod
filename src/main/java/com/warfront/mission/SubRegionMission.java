package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;

/**
 * Immutable descriptor for a single subregion's strategic mission assignment.
 *
 * Stores structured generation metadata required for UI display and
 * server-side mission execution:
 * <ul>
 *   <li>{@code type}: category of mission (e.g. FORWARD_PATROL, COMMAND_BUNKER)</li>
 *   <li>{@code objectiveType}: gameplay mechanics category (e.g. DESTROY_STRUCTURE, ELIMINATE_LEADER)</li>
 *   <li>{@code targetFaction}: enemy faction to be engaged</li>
 *   <li>{@code subX}, {@code subZ}: subregion index coordinates (0 or 1)</li>
 *   <li>{@code targetCount}: number of targets/progress units required</li>
 *   <li>{@code targetRoleName}: structured string identifier of primary target role (e.g. "COMMANDER", "SCOUT")</li>
 *   <li>{@code displayName}: human-readable mission name for UI</li>
 *   <li>{@code description}: contextual mission briefing flavor description</li>
 *   <li>{@code sourceBaseType}: base structure present in the region</li>
 *   <li>{@code biomeCategory}: biome classification variant</li>
 *   <li>{@code seed}: deterministic generation seed</li>
 *   <li>{@code isBaseMission}: whether this mission is a mandatory base mission</li>
 * </ul>
 */
public record SubRegionMission(
        MissionType type,
        ObjectiveType objectiveType,
        Faction targetFaction,
        int subX,
        int subZ,
        int targetCount,
        String targetRoleName,
        String displayName,
        String description,
        BaseType sourceBaseType,
        BiomeCategory biomeCategory,
        long seed,
        boolean isBaseMission
) {
    /**
     * Constructor allowing omission of objectiveType (defaults to type's default objective type).
     */
    public SubRegionMission(
            MissionType type,
            Faction targetFaction,
            int subX,
            int subZ,
            int targetCount,
            String targetRoleName,
            String displayName,
            String description,
            BaseType sourceBaseType,
            BiomeCategory biomeCategory,
            long seed,
            boolean isBaseMission
    ) {
        this(
                type,
                type != null ? type.defaultObjectiveType() : ObjectiveType.ELIMINATE_TARGETS,
                targetFaction,
                subX,
                subZ,
                targetCount,
                targetRoleName,
                displayName,
                description,
                sourceBaseType,
                biomeCategory,
                seed,
                isBaseMission
        );
    }

    /**
     * Backward-compatibility constructor without isBaseMission flag.
     */
    public SubRegionMission(
            MissionType type,
            Faction targetFaction,
            int subX,
            int subZ,
            int targetCount,
            String targetRoleName,
            String displayName,
            String description,
            BaseType sourceBaseType,
            BiomeCategory biomeCategory,
            long seed
    ) {
        this(type, targetFaction, subX, subZ, targetCount, targetRoleName, displayName, description, sourceBaseType, biomeCategory, seed, type != null && type.isBaseMission());
    }

    /**
     * Backward-compatibility constructor for simple descriptors.
     */
    public SubRegionMission(
            MissionType type,
            Faction targetFaction,
            int subX,
            int subZ,
            int killTarget,
            String targetRoleName,
            String displayName
    ) {
        this(type, targetFaction, subX, subZ, killTarget, targetRoleName, displayName, "", BaseType.NONE, BiomeCategory.STANDARD, 0L, type != null && type.isBaseMission());
    }

    /**
     * Helper for UI button label.
     */
    public String displayLabel() {
        if (isBaseMission) {
            return "★ " + displayName + " [" + targetCount + "]";
        }
        return displayName + " [" + targetCount + "]";
    }

    /**
     * Backward-compatibility helper for required target count.
     */
    public int killTarget() {
        return targetCount;
    }
}
