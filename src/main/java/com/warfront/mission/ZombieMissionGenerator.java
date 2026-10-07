package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Faction-specific mission generator for the Zombie Horde.
 *
 * Implements:
 * - 4 Easy missions (Resistance < 50%)
 * - 4 Hard missions (Resistance >= 50%)
 * - Deterministic rolls tied to world seed and reinforcement salt
 * - Mandatory base missions for Outpost, Medium Base (HQ), and Mega Base
 * - Always preserves signature base mission on the anchor subregion
 */
public final class ZombieMissionGenerator implements FactionMissionGenerator {

    private static final List<MissionDefinition> EASY_DEFINITIONS = new ArrayList<>();
    private static final List<MissionDefinition> HARD_DEFINITIONS = new ArrayList<>();

    // Base mission definitions
    private static final MissionDefinition OUTPOST_BASE_DEF;
    private static final MissionDefinition HQ_COMMANDER_DEF;
    private static final MissionDefinition HQ_INTEL_DEF;
    private static final MissionDefinition HQ_SUPPLY_DEF;

    private static final MissionDefinition MEGA_COMMAND_DEF;
    private static final MissionDefinition MEGA_POWER_DEF;
    private static final MissionDefinition MEGA_MUNITIONS_DEF;
    private static final MissionDefinition MEGA_GATE_DEF;
    private static final MissionDefinition MEGA_AIR_DEF;
    private static final MissionDefinition MEGA_COMMS_DEF;

    private static final MissionDefinition FALLBACK_EASY_DEF;
    private static final MissionDefinition FALLBACK_HARD_DEF;

    static {
        // ==========================================
        // 4 Easy Named Missions (Resistance < 50%)
        // ==========================================
        MissionDefinition patrolSweep = new MissionDefinition(
                "zombie_patrol_sweep",
                MissionType.FORWARD_PATROL,
                Faction.ZOMBIE_HORDE,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                5, 8,
                0.05f,
                List.of("FODDER", "BASIC"),
                "Patrol Sweep",
                "Cull wandering zombie roamers infesting the perimeter sectors."
        );
        EASY_DEFINITIONS.add(patrolSweep);
        FALLBACK_EASY_DEF = patrolSweep;

        MissionDefinition scoutIntercept = new MissionDefinition(
                "zombie_scout_interception",
                MissionType.SUPPLY_CONVOY,
                Faction.ZOMBIE_HORDE,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                4, 6,
                0.05f,
                List.of("FAST_CHASER"),
                "Scout Interception",
                "Intercept agile sprinting chasers leading the horde advance."
        );
        EASY_DEFINITIONS.add(scoutIntercept);

        MissionDefinition borderSkirmish = new MissionDefinition(
                "zombie_border_skirmish",
                MissionType.FORWARD_OUTPOST,
                Faction.ZOMBIE_HORDE,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                6, 9,
                0.06f,
                List.of("FODDER", "FAST_CHASER"),
                "Border Skirmish",
                "Repel encroaching zombie swarm skirmishers probing humanity borders."
        );
        EASY_DEFINITIONS.add(borderSkirmish);

        MissionDefinition supplyRaid = new MissionDefinition(
                "zombie_supply_raid",
                MissionType.SCOUT_NETWORK,
                Faction.ZOMBIE_HORDE,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                5, 8,
                0.05f,
                List.of("FODDER", "BASIC"),
                "Supply Raid",
                "Reclaim overrun frontline supply depots from necrotic infestation."
        );
        EASY_DEFINITIONS.add(supplyRaid);

        // ==========================================
        // 4 Hard Named Missions (Resistance >= 50%)
        // ==========================================
        MissionDefinition heavySiege = new MissionDefinition(
                "zombie_heavy_siege",
                MissionType.STRONGPOINT_ASSAULT,
                Faction.ZOMBIE_HORDE,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                10, 15,
                0.08f,
                List.of("TANK", "FODDER"),
                "Heavy Siege",
                "Break through a dense, fortified zombie horde siege frontline."
        );
        HARD_DEFINITIONS.add(heavySiege);
        FALLBACK_HARD_DEF = heavySiege;

        MissionDefinition championHunt = new MissionDefinition(
                "zombie_champion_hunt",
                MissionType.OFFICER_HUNT,
                Faction.ZOMBIE_HORDE,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                6, 10,
                0.06f,
                List.of("TANK", "MUTANT"),
                "Champion Hunt",
                "Hunt down and destroy colossal mutant zombie brutes leading the swarm."
        );
        HARD_DEFINITIONS.add(championHunt);

        MissionDefinition sporeBattery = new MissionDefinition(
                "zombie_spore_battery",
                MissionType.ARTILLERY_BATTERY,
                Faction.ZOMBIE_HORDE,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                10, 16,
                0.08f,
                List.of("TANK", "SPEWER", "FODDER"),
                "Spore Battery",
                "Storm a necrotic spore catapult emplacement launching caustic blight shells."
        );
        HARD_DEFINITIONS.add(sporeBattery);

        MissionDefinition attritionStand = new MissionDefinition(
                "zombie_attrition_stand",
                MissionType.CUT_THE_SUPPLY_LINE,
                Faction.ZOMBIE_HORDE,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                12, 20,
                0.10f,
                List.of("TANK", "FAST_CHASER", "FODDER"),
                "Attrition Stand",
                "Weather a relentless, endless wave of ravenous horde reinforcements."
        );
        HARD_DEFINITIONS.add(attritionStand);

        // ==========================================
        // Mandatory Base Missions
        // ==========================================
        // Outpost (1 variant)
        OUTPOST_BASE_DEF = new MissionDefinition(
                "zombie_outpost_destroy",
                MissionType.OUTPOST_DESTROY_BUILDING,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.OUTPOST),
                0.0f, 100.0f,
                100,
                Map.of(),
                6, 10,
                0.05f,
                List.of("TANK", "FODDER"),
                "Demolish Outpost",
                "Infiltrate and collapse the fortified necrotic blight outpost."
        );

        // Medium Base / HQ (3 variants)
        HQ_COMMANDER_DEF = new MissionDefinition(
                "zombie_base_commander",
                MissionType.BASE_KILL_COMMANDER,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.HEADQUARTERS),
                0.0f, 100.0f,
                100,
                Map.of(),
                8, 12,
                0.06f,
                List.of("HIVEMIND_CONTROLLER", "TANK"),
                "Eliminate Commander",
                "Assassinate the necrotic hivemind controller orchestrating the local swarm."
        );

        HQ_INTEL_DEF = new MissionDefinition(
                "zombie_base_intel",
                MissionType.BASE_DESTROY_INTEL,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.HEADQUARTERS),
                0.0f, 100.0f,
                100,
                Map.of(),
                6, 10,
                0.05f,
                List.of("FAST_CHASER", "SPEWER"),
                "Destroy Intel Network",
                "Sever the fungal spore sensory network coordinating swarm movements."
        );

        HQ_SUPPLY_DEF = new MissionDefinition(
                "zombie_base_supplies",
                MissionType.BASE_DESTROY_SUPPLIES,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.HEADQUARTERS),
                0.0f, 100.0f,
                100,
                Map.of(),
                6, 10,
                0.05f,
                List.of("TANK", "FODDER"),
                "Destroy Supply Cache",
                "Demolish the central biomass cache feeding undead regeneration."
        );

        // Mega Base (6 variants)
        MEGA_COMMAND_DEF = new MissionDefinition(
                "zombie_mega_command",
                MissionType.COMMAND_BUNKER,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                12, 18,
                0.09f,
                List.of("PRIME_HIVEMIND", "TANK"),
                "Eliminate High Command",
                "Destroy the apex prime hivemind heart inside the mega nest."
        );

        MEGA_POWER_DEF = new MissionDefinition(
                "zombie_mega_power",
                MissionType.BREAK_THE_WAR_ROOM,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                10, 15,
                0.07f,
                List.of("TANK", "SPEWER"),
                "Sabotage Power Grid",
                "Sever the bio-electric spore conduits sustaining hive metabolism."
        );

        MEGA_MUNITIONS_DEF = new MissionDefinition(
                "zombie_mega_munitions",
                MissionType.MUNITIONS_DEPOT,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                10, 15,
                0.07f,
                List.of("FODDER", "SPEWER"),
                "Destroy Munitions Depot",
                "Obliterate volatile acid bile reservoirs before they detonate."
        );

        MEGA_GATE_DEF = new MissionDefinition(
                "zombie_mega_gate",
                MissionType.BREAK_THE_GATES,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                12, 18,
                0.08f,
                List.of("TANK", "FODDER"),
                "Breach Citadel Gate",
                "Smash through calcified bone barricades guarding the central hive."
        );

        MEGA_AIR_DEF = new MissionDefinition(
                "zombie_mega_air",
                MissionType.SILENCE_THE_GUNS,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                10, 15,
                0.07f,
                List.of("SPEWER", "FAST_CHASER"),
                "Neutralize Anti-Air",
                "Neutralize massive biological acid mortar towers raining corrosive bile."
        );

        MEGA_COMMS_DEF = new MissionDefinition(
                "zombie_mega_comms",
                MissionType.SEVER_COMMUNICATIONS,
                Faction.ZOMBIE_HORDE,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                10, 15,
                0.07f,
                List.of("FAST_CHASER", "HIVEMIND_CONTROLLER"),
                "Sever Communications",
                "Shatter high-frequency telepathic resonance crystals to disrupt horde cohesion."
        );
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
        float clampedRes = Math.clamp(resistance, 0.0f, 100.0f);

        // Derive authoritative deterministic region seed
        long effectiveSeed = seed != 0L ? seed : ((regionX * 31213L) ^ (regionZ * 65537L));

        // Determine occupied base subregions & anchor subregion
        int occupiedBaseMask = MissionProfile.getOccupiedBaseSubRegionsMask(regionX, regionZ, baseType, baseAnchor);
        int anchorSubBit = MissionProfile.getAnchorSubRegionBit(regionX, regionZ, baseAnchor);

        // Track regional mission type occurrences (max 2 of any type per region)
        Map<MissionType, Integer> regionTypeCounts = new EnumMap<>(MissionType.class);

        // Prepare Medium Base secondary variant roll (if HQ)
        MissionDefinition hqSecondaryDef = null;
        if (baseType == BaseType.HEADQUARTERS) {
            long hqSeed = WeightedMissionSelector.mix64(effectiveSeed ^ 0x9E3779B9L);
            boolean pickIntel = ((hqSeed & 1) == 0);
            hqSecondaryDef = pickIntel ? HQ_INTEL_DEF : HQ_SUPPLY_DEF;
        }

        // Prepare Mega Base 3 secondary variants (if Mega Base)
        List<MissionDefinition> megaSecondaryDefs = null;
        if (baseType == BaseType.MEGA_BASE) {
            List<MissionDefinition> candidates = new ArrayList<>(List.of(
                    MEGA_POWER_DEF, MEGA_MUNITIONS_DEF, MEGA_GATE_DEF, MEGA_AIR_DEF, MEGA_COMMS_DEF
            ));
            Random rng = new Random(WeightedMissionSelector.mix64(effectiveSeed ^ 0xCAFEBABE1234L));
            Collections.shuffle(candidates, rng);
            megaSecondaryDefs = candidates.subList(0, 3);
        }
        int megaSecondaryIdx = 0;

        // Pass 1: Resolve and assign mandatory base missions for occupied subregions
        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;
            int bit = subZ * 2 + subX;

            boolean isOccupiedByBase = (occupiedBaseMask & (1 << bit)) != 0;
            if (!isOccupiedByBase) {
                continue;
            }

            long subSeed = effectiveSeed ^ (subX * 104729L) ^ (subZ * 224737L)
                    ^ faction.id() ^ ((long) baseType.id() << 32);

            MissionDefinition baseDef;
            if (bit == anchorSubBit) {
                baseDef = switch (baseType) {
                    case OUTPOST -> OUTPOST_BASE_DEF;
                    case HEADQUARTERS -> HQ_COMMANDER_DEF;
                    case MEGA_BASE -> MEGA_COMMAND_DEF;
                    default -> FALLBACK_HARD_DEF;
                };
            } else if (baseType == BaseType.HEADQUARTERS) {
                baseDef = hqSecondaryDef != null ? hqSecondaryDef : HQ_INTEL_DEF;
            } else if (baseType == BaseType.MEGA_BASE) {
                baseDef = (megaSecondaryDefs != null && megaSecondaryIdx < megaSecondaryDefs.size())
                        ? megaSecondaryDefs.get(megaSecondaryIdx++)
                        : MEGA_POWER_DEF;
            } else {
                baseDef = OUTPOST_BASE_DEF;
            }

            missions[i] = WeightedMissionSelector.selectAndGenerate(
                    List.of(baseDef),
                    baseDef,
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

        // Pass 2: Resolve and assign regular missions for unoccupied subregions
        List<MissionDefinition> candidatePool = (clampedRes < 50.0f) ? EASY_DEFINITIONS : HARD_DEFINITIONS;
        MissionDefinition fallbackDef = (clampedRes < 50.0f) ? FALLBACK_EASY_DEF : FALLBACK_HARD_DEF;

        for (int i = 0; i < 4; i++) {
            if (missions[i] != null) {
                continue;
            }

            int subX = i % 2;
            int subZ = i / 2;

            long subSeed = effectiveSeed ^ (subX * 104729L) ^ (subZ * 224737L)
                    ^ faction.id() ^ ((long) baseType.id() << 32);

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
