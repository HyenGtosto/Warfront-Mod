package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Fallback mission generator for unassigned or neutral factions.
 */
public final class DefaultMissionGenerator implements FactionMissionGenerator {

    private static final List<MissionDefinition> EASY_DEFINITIONS = new ArrayList<>();
    private static final List<MissionDefinition> HARD_DEFINITIONS = new ArrayList<>();
    private static final MissionDefinition FALLBACK_EASY_DEF;
    private static final MissionDefinition FALLBACK_HARD_DEF;

    static {
        MissionDefinition patrol = new MissionDefinition(
                "default_patrol",
                MissionType.FORWARD_PATROL,
                ObjectiveType.ELIMINATE_TARGETS,
                Faction.UNCLAIMED,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                5, 8,
                0.05f,
                List.of("BASIC"),
                "Forward Patrol",
                "Engage and eliminate occupying hostile patrols in this sector."
        );
        EASY_DEFINITIONS.add(patrol);
        FALLBACK_EASY_DEF = patrol;

        EASY_DEFINITIONS.add(new MissionDefinition(
                "default_convoy",
                MissionType.SUPPLY_CONVOY,
                ObjectiveType.INTERCEPT,
                Faction.UNCLAIMED,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                4, 6,
                0.05f,
                List.of("BASIC"),
                "Supply Convoy",
                "Intercept enemy logistics transport moving through this sector."
        ));

        EASY_DEFINITIONS.add(new MissionDefinition(
                "default_outpost",
                MissionType.FORWARD_OUTPOST,
                ObjectiveType.DESTROY_STRUCTURE,
                Faction.UNCLAIMED,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                6, 8,
                0.05f,
                List.of("BASIC"),
                "Forward Outpost",
                "Demolish a forward observation watchpoint in this sector."
        ));

        EASY_DEFINITIONS.add(new MissionDefinition(
                "default_network",
                MissionType.SCOUT_NETWORK,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.UNCLAIMED,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                3, 5,
                0.05f,
                List.of("BASIC"),
                "Scout Network",
                "Disrupt enemy communication and scout network nodes."
        ));

        MissionDefinition strongpoint = new MissionDefinition(
                "default_strongpoint",
                MissionType.STRONGPOINT_ASSAULT,
                ObjectiveType.DESTROY_STRUCTURE,
                Faction.UNCLAIMED,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                8, 12,
                0.08f,
                List.of("BASIC"),
                "Strongpoint Assault",
                "Breach and neutralize a heavily fortified enemy stronghold."
        );
        HARD_DEFINITIONS.add(strongpoint);
        FALLBACK_HARD_DEF = strongpoint;

        HARD_DEFINITIONS.add(new MissionDefinition(
                "default_officer",
                MissionType.OFFICER_HUNT,
                ObjectiveType.ELIMINATE_LEADER,
                Faction.UNCLAIMED,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                1, 2,
                0.04f,
                List.of("COMMANDER"),
                "Officer Hunt",
                "Infiltrate enemy headquarters and assassinate a commanding officer."
        ));

        HARD_DEFINITIONS.add(new MissionDefinition(
                "default_artillery",
                MissionType.ARTILLERY_BATTERY,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.UNCLAIMED,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                2, 2,
                0.03f,
                List.of("BASIC"),
                "Artillery Battery",
                "Sabotage enemy field artillery battery bombarding friendly positions."
        ));

        HARD_DEFINITIONS.add(new MissionDefinition(
                "default_supply_line",
                MissionType.CUT_THE_SUPPLY_LINE,
                ObjectiveType.MULTI_OBJECTIVE,
                Faction.UNCLAIMED,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                3, 3,
                0.03f,
                List.of("BASIC"),
                "Cut the Supply Line",
                "Sever vital frontline supply lines across this combat zone."
        ));
    }

    @Override
    public SubRegionMission[] generateMissions(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability
    ) {
        return generateMissionsWithSeedAndBase(regionX, regionZ, faction, baseType, resistance, stability, BiomeCategory.STANDARD, 0L, null);
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
        return generateMissionsWithSeedAndBase(regionX, regionZ, faction, baseType, resistance, stability, biomeCategory, 0L, null);
    }

    @Override
    public SubRegionMission[] generateMissionsWithSeedAndBase(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability,
            BiomeCategory biomeCategory,
            long seed,
            BlockPos baseAnchor
    ) {
        SubRegionMission[] missions = new SubRegionMission[4];
        long effectiveSeed = seed != 0L ? seed : ((regionX * 31213L) ^ (regionZ * 65537L));
        float clampedRes = Math.clamp(resistance, 0.0f, 100.0f);
        Map<MissionType, Integer> regionTypeCounts = new EnumMap<>(MissionType.class);

        List<MissionDefinition> candidatePool = (clampedRes < 50.0f) ? EASY_DEFINITIONS : HARD_DEFINITIONS;
        MissionDefinition fallbackDef = (clampedRes < 50.0f) ? FALLBACK_EASY_DEF : FALLBACK_HARD_DEF;

        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;
            long subSeed = effectiveSeed ^ (subX * 104729L) ^ (subZ * 224737L);

            missions[i] = WeightedMissionSelector.selectAndGenerate(
                    candidatePool,
                    fallbackDef,
                    regionX, regionZ,
                    subX, subZ,
                    faction,
                    baseType,
                    clampedRes,
                    biomeCategory,
                    subSeed,
                    regionTypeCounts
            );
        }
        return missions;
    }
}
