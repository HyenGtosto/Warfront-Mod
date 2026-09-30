package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;

/**
 * Fallback mission generator for unassigned or neutral factions.
 */
public final class DefaultMissionGenerator implements FactionMissionGenerator {

    @Override
    public SubRegionMission[] generateMissions(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability
    ) {
        SubRegionMission[] missions = new SubRegionMission[4];
        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;
            long subSeed = (regionX * 31213L) ^ (regionZ * 65537L) ^ (subX * 104729L) ^ (subZ * 224737L);
            missions[i] = new SubRegionMission(
                    MissionType.KILL_COUNT,
                    faction,
                    subX,
                    subZ,
                    5,
                    "BASIC",
                    "Kill Count",
                    "Engage and eliminate occupying hostile units.",
                    baseType,
                    BiomeCategory.STANDARD,
                    subSeed
            );
        }
        return missions;
    }
}
