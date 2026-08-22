package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;

import java.util.EnumMap;
import java.util.Map;

/**
 * Entry point for subregion mission profile generation.
 *
 * Dispatches mission generation requests to faction-specific {@link FactionMissionGenerator}
 * implementations based on the region owner faction.
 */
public final class MissionProfile {

    private static final Map<Faction, FactionMissionGenerator> GENERATORS = new EnumMap<>(Faction.class);
    private static final FactionMissionGenerator DEFAULT_GENERATOR = new DefaultMissionGenerator();

    static {
        GENERATORS.put(Faction.ZOMBIE_HORDE, new ZombieMissionGenerator());
        GENERATORS.put(Faction.PILLAGER_CONQUERORS, new PillagerMissionGenerator());
    }

    private MissionProfile() {
    }

    /**
     * Resolves the appropriate {@link FactionMissionGenerator} for the given faction.
     */
    public static FactionMissionGenerator getGenerator(Faction faction) {
        if (faction == null) {
            return DEFAULT_GENERATOR;
        }
        return GENERATORS.getOrDefault(faction, DEFAULT_GENERATOR);
    }

    /**
     * Generates 4 deterministic subregion missions for a region using the appropriate
     * faction-specific generator.
     *
     * @param regionX    region X coordinate
     * @param regionZ    region Z coordinate
     * @param faction    the faction owning the region
     * @param baseType   the base structure present in the region
     * @param resistance effective Resistance value (0–100)
     * @param stability  effective Stability value (0–100)
     * @return array of 4 missions indexed by {@code i = subZ * 2 + subX}
     */
    public static SubRegionMission[] generateForRegion(
            int regionX, int regionZ, Faction faction, BaseType baseType, float resistance, float stability) {

        return getGenerator(faction).generateMissions(regionX, regionZ, faction, baseType, resistance, stability);
    }
}
