package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Faction-specific mission generator for Pillager Conquerors.
 */
public final class PillagerMissionGenerator implements FactionMissionGenerator {

    private static final List<MissionDefinition> DEFINITIONS = new ArrayList<>();
    private static final MissionDefinition FALLBACK_DEFINITION;

    static {
        FALLBACK_DEFINITION = new MissionDefinition(
                "pillager_kill_count",
                MissionType.KILL_COUNT,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                0.0f, 100.0f,
                100,
                Map.of(),
                4, 6,
                0.05f,
                List.of("RANGED", "FIGHTER"),
                "Eliminate Pillagers",
                "Engage and eliminate occupying Pillager forces in the subregion."
        );
        DEFINITIONS.add(FALLBACK_DEFINITION);
    }

    @Override
    public SubRegionMission[] generateMissions(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability
    ) {
        return generateMissionsWithBiome(regionX, regionZ, faction, baseType, resistance, stability, BiomeCategory.STANDARD);
    }

    @Override
    public SubRegionMission[] generateMissionsWithBiome(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability,
            BiomeCategory biomeCategory
    ) {
        SubRegionMission[] missions = new SubRegionMission[4];

        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;

            long subSeed = (regionX * 73856093L) ^ (regionZ * 19349663L)
                    ^ (subX * 83492791L) ^ (subZ * 4393139L)
                    ^ faction.id() ^ ((long) baseType.id() << 32);

            missions[i] = WeightedMissionSelector.selectAndGenerate(
                    DEFINITIONS,
                    FALLBACK_DEFINITION,
                    regionX, regionZ,
                    subX, subZ,
                    faction,
                    baseType,
                    resistance,
                    biomeCategory,
                    subSeed
            );
        }

        return missions;
    }
}
