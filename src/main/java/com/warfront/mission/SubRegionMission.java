package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;

/**
 * Immutable descriptor for a single subregion's strategic mission assignment.
 *
 * Stores structured generation metadata required for UI display and future
 * server-side mission execution:
 * <ul>
 *   <li>{@code type}: category of mission objective (e.g. KILL_COUNT)</li>
 *   <li>{@code targetFaction}: enemy faction to be engaged</li>
 *   <li>{@code subX}, {@code subZ}: subregion index coordinates (0 or 1)</li>
 *   <li>{@code targetCount}: number of targets/kills required</li>
 *   <li>{@code targetRoleName}: structured string identifier of primary target role (e.g. "COMMANDER", "FIGHTER")</li>
 *   <li>{@code displayName}: human-readable mission name for UI (e.g. "Eliminate Captain")</li>
 *   <li>{@code description}: contextual mission briefing flavor description</li>
 *   <li>{@code sourceBaseType}: base structure present in the region</li>
 *   <li>{@code biomeCategory}: biome classification variant for future mob spawning</li>
 *   <li>{@code seed}: deterministic generation seed</li>
 * </ul>
 */
public record SubRegionMission(
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
        this(type, targetFaction, subX, subZ, killTarget, targetRoleName, displayName, "", BaseType.NONE, BiomeCategory.STANDARD, 0L);
    }

    /**
     * Backward-compatibility helper for UI button label.
     */
    public String displayLabel() {
        return displayName + " [" + targetCount + "]";
    }

    /**
     * Backward-compatibility helper for required kill count.
     */
    public int killTarget() {
        return targetCount;
    }
}
