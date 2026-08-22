package com.warfront.mission;

import com.warfront.region.Faction;

/**
 * Immutable descriptor for a single subregion's strategic mission assignment.
 *
 * Stores structured information required for UI display and future server-side execution:
 * <ul>
 *   <li>{@code type}: category of mission objective (e.g. KILL_COUNT)</li>
 *   <li>{@code targetFaction}: enemy faction to be engaged</li>
 *   <li>{@code subX}, {@code subZ}: subregion index coordinates (0 or 1)</li>
 *   <li>{@code killTarget}: number of enemy kills required</li>
 *   <li>{@code targetRoleName}: structured string identifier of primary target role (e.g. "FODDER", "FIGHTER")</li>
 *   <li>{@code displayLabel}: human-readable name for UI rendering</li>
 * </ul>
 *
 * Produced deterministically by {@link FactionMissionGenerator} implementations.
 */
public record SubRegionMission(
        MissionType type,
        Faction targetFaction,
        int subX,
        int subZ,
        int killTarget,
        String targetRoleName,
        String displayLabel
) {}
