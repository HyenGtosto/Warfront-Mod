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
 * Faction-specific mission generator for Pillager Conquerors.
 *
 * Implements:
 * - 4 Easy missions (Resistance < 50%)
 * - 4 Hard missions (Resistance >= 50%)
 * - Mandatory base missions for Outpost (1 variant), Headquarters (3 variants), and Mega Base (6 variants)
 * - 4 Defense missions
 * - Deterministic rolls tied to world seed and reinforcement salt
 * - Preserves the signature base mission on the anchor subregion
 */
public final class PillagerMissionGenerator implements FactionMissionGenerator {

    private static final List<MissionDefinition> EASY_DEFINITIONS = new ArrayList<>();
    private static final List<MissionDefinition> HARD_DEFINITIONS = new ArrayList<>();
    private static final List<MissionDefinition> DEFENSE_DEFINITIONS = new ArrayList<>();

    // Base mission definitions
    private static final MissionDefinition OUTPOST_BASE_DEF;
    private static final MissionDefinition HQ_COMMANDER_DEF;
    private static final MissionDefinition HQ_INTEL_DEF;
    private static final MissionDefinition HQ_SUPPLY_DEF;

    static MissionDefinition getHQDefinition(MissionType type) {
        return switch (type) {
            case BASE_KILL_COMMANDER -> HQ_COMMANDER_DEF;
            case BASE_DESTROY_INTEL -> HQ_INTEL_DEF;
            case BASE_DESTROY_SUPPLIES -> HQ_SUPPLY_DEF;
            default -> HQ_INTEL_DEF;
        };
    }

    private static final MissionDefinition MEGA_COMMAND_DEF;
    private static final MissionDefinition MEGA_WAR_ROOM_DEF;
    private static final MissionDefinition MEGA_MUNITIONS_DEF;
    private static final MissionDefinition MEGA_GATES_DEF;
    private static final MissionDefinition MEGA_GUNS_DEF;
    private static final MissionDefinition MEGA_COMMS_DEF;

    private static final MissionDefinition FALLBACK_EASY_DEF;
    private static final MissionDefinition FALLBACK_HARD_DEF;
    private static final MissionDefinition DEFEAT_INVADING_FORCES_DEF;
    private static final MissionDefinition HOLD_THE_CHOKEPOINT_DEF;

    static {
        // ==========================================
        // 4 Easy Named Missions (Resistance < 50%)
        // ==========================================
        MissionDefinition killCount = new MissionDefinition(
                "pillager_kill_count",
                MissionType.KILL_COUNT,
                ObjectiveType.ELIMINATE_TARGETS,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                40, 50,
                0.0f,
                List.of("WARRIOR", "MARKSMAN"),
                "Eliminate Hostiles",
                "Thin out hostile patrol squads operating in this sector."
        );
        EASY_DEFINITIONS.add(killCount);
        FALLBACK_EASY_DEF = killCount;

        MissionDefinition supplyConvoy = new MissionDefinition(
                "pillager_supply_convoy",
                MissionType.SUPPLY_CONVOY,
                ObjectiveType.INTERCEPT,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                2, 2,
                0.04f,
                List.of("WARRIOR", "MARKSMAN"),
                "Supply Convoy",
                "Intercept and destroy both supply convoy wagons moving through this sector."
        );
        EASY_DEFINITIONS.add(supplyConvoy);

        MissionDefinition forwardOutpost = new MissionDefinition(
                "pillager_forward_outpost",
                MissionType.FORWARD_OUTPOST,
                ObjectiveType.DESTROY_STRUCTURE,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                4, 6,
                0.05f,
                List.of("WARRIOR", "MARKSMAN"),
                "Forward Outpost",
                "Demolish an entrenched forward scouting watchpost."
        );
        EASY_DEFINITIONS.add(forwardOutpost);

        MissionDefinition scoutNetwork = new MissionDefinition(
                "pillager_scout_network",
                MissionType.SCOUT_NETWORK,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                0.0f, 49.999f,
                100,
                Map.of(),
                3, 4,
                0.03f,
                List.of("MARKSMAN", "SCOUT"),
                "Scout Network",
                "Neutralize multiple forward observation posts before intelligence reaches high command."
        );
        EASY_DEFINITIONS.add(scoutNetwork);

        // ==========================================
        // 4 Hard Named Missions (Resistance >= 50%)
        // ==========================================
        MissionDefinition strongpointAssault = new MissionDefinition(
                "pillager_strongpoint_assault",
                MissionType.STRONGPOINT_ASSAULT,
                ObjectiveType.DESTROY_STRUCTURE,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                8, 14,
                0.06f,
                List.of("WARRIOR", "ARMORED_ELITE"),
                "Strongpoint Assault",
                "Breach and neutralize a heavily fortified pillager redoubt."
        );
        HARD_DEFINITIONS.add(strongpointAssault);
        FALLBACK_HARD_DEF = strongpointAssault;

        MissionDefinition officerHunt = new MissionDefinition(
                "pillager_officer_hunt",
                MissionType.OFFICER_HUNT,
                ObjectiveType.ELIMINATE_LEADER,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                1, 2,
                0.02f,
                List.of("COMMANDER", "ARMORED_ELITE"),
                "Commander Hunt",
                "Infiltrate the command encampment and eliminate the Field Commander."
        );
        HARD_DEFINITIONS.add(officerHunt);

        MissionDefinition artilleryBattery = new MissionDefinition(
                "pillager_artillery_battery",
                MissionType.ARTILLERY_BATTERY,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                2, 2,
                0.02f,
                List.of("MARKSMAN", "ARMORED_ELITE"),
                "Mortar Emplacement",
                "Destroy the heavy mortar positions shelling the sector."
        );
        HARD_DEFINITIONS.add(artilleryBattery);

        MissionDefinition warHornRelay = new MissionDefinition(
                "pillager_war_horn_relay",
                MissionType.WAR_HORN_RELAY,
                ObjectiveType.MULTI_PHASE,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                50.0f, 100.0f,
                100,
                Map.of(),
                1, 1,
                0.02f,
                List.of("WARRIOR", "MARKSMAN", "ARMORED_ELITE"),
                "Warhorn Tower",
                "Scale the broadcast tower and sabotage the summit signal fuse before reinforcements arrive."
        );
        HARD_DEFINITIONS.add(warHornRelay);

        // ==========================================
        // Mandatory Base Missions
        // ==========================================
        // Outpost (1 variant)
        OUTPOST_BASE_DEF = new MissionDefinition(
                "pillager_outpost_destroy",
                MissionType.OUTPOST_DESTROY_BUILDING,
                ObjectiveType.DESTROY_STRUCTURE,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.OUTPOST),
                0.0f, 100.0f,
                100,
                Map.of(),
                6, 8,
                0.05f,
                List.of("WARRIOR", "MARKSMAN", "COMMANDER"),
                "Demolish Outpost",
                "Infiltrate and demolish the fortified outpost watchtower fortification."
        );

        // Medium Base / HQ (3 variants)
        HQ_COMMANDER_DEF = new MissionDefinition(
                "pillager_base_commander",
                MissionType.BASE_KILL_COMMANDER,
                ObjectiveType.ELIMINATE_LEADER,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.HEADQUARTERS),
                0.0f, 100.0f,
                100,
                Map.of(),
                1, 2,
                0.04f,
                List.of("COMMANDER", "ARMORED_ELITE"),
                "Eliminate Garrison Commander",
                "Assassinate the high-ranking commanding officer of the garrison."
        );

        HQ_INTEL_DEF = new MissionDefinition(
                "pillager_base_command_center",
                MissionType.BASE_DESTROY_INTEL,
                ObjectiveType.DESTROY_STRUCTURE,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.HEADQUARTERS),
                0.0f, 100.0f,
                100,
                Map.of(),
                1, 2,
                0.04f,
                List.of("SCOUT", "MARKSMAN", "WARRIOR"),
                "Destroy Command Center",
                "Infiltrate communication command center and destroy enemy intelligence relay."
        );

        HQ_SUPPLY_DEF = new MissionDefinition(
                "pillager_base_storage",
                MissionType.BASE_DESTROY_SUPPLIES,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.HEADQUARTERS),
                0.0f, 100.0f,
                100,
                Map.of(),
                3, 4,
                0.04f,
                List.of("WARRIOR", "ARMORED_ELITE"),
                "Destroy Storage Depot",
                "Infiltrate supply depot and obliterate frontline supply stockpiles."
        );

        // Mega Base (6 variants)
        MEGA_COMMAND_DEF = new MissionDefinition(
                "pillager_command_bunker",
                MissionType.COMMAND_BUNKER,
                ObjectiveType.ELIMINATE_LEADER,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                1, 2,
                0.05f,
                List.of("COMMANDER", "ARMORED_ELITE"),
                "Command Bunker",
                "Assault citadel inner sanctum and eliminate Pillager High Command."
        );

        MEGA_WAR_ROOM_DEF = new MissionDefinition(
                "pillager_break_the_war_room",
                MissionType.BREAK_THE_WAR_ROOM,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                4, 4,
                0.05f,
                List.of("WARRIOR", "ARMORED_ELITE"),
                "Break the War Room",
                "Obliterate central tactical war room and strategic map charts."
        );

        MEGA_MUNITIONS_DEF = new MissionDefinition(
                "pillager_munitions_depot",
                MissionType.MUNITIONS_DEPOT,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                4, 4,
                0.05f,
                List.of("WARRIOR", "MARKSMAN"),
                "Munitions Depot",
                "Detonate primary explosive stockpiles fueling the enemy war effort."
        );

        MEGA_GATES_DEF = new MissionDefinition(
                "pillager_break_the_gates",
                MissionType.BREAK_THE_GATES,
                ObjectiveType.BREACH,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                2, 4,
                0.05f,
                List.of("ARMORED_ELITE", "WARRIOR"),
                "Break the Gates",
                "Shatter heavy reinforced citadel blast gates to allow friendly breach."
        );

        MEGA_GUNS_DEF = new MissionDefinition(
                "pillager_silence_the_guns",
                MissionType.SILENCE_THE_GUNS,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                3, 3,
                0.05f,
                List.of("MARKSMAN", "ARMORED_ELITE"),
                "Silence the Guns",
                "Scale fortress bastions and disable 3 heavy wall-mounted artillery emplacements."
        );

        MEGA_COMMS_DEF = new MissionDefinition(
                "pillager_sever_communications",
                MissionType.SEVER_COMMUNICATIONS,
                ObjectiveType.DESTROY_OBJECTIVES,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.of(BaseType.MEGA_BASE),
                0.0f, 100.0f,
                100,
                Map.of(),
                3, 3,
                0.05f,
                List.of("SCOUT", "COMMANDER"),
                "Sever Communications",
                "Destroy long-range transmitter array linking base to high command."
        );

        // ==========================================
        // 2 Defense Missions (Co-Fallbacks)
        // ==========================================
        MissionDefinition defeatInvadingForces = new MissionDefinition(
                "pillager_defeat_invading_forces",
                MissionType.DEFEAT_INVADING_FORCES,
                ObjectiveType.ELIMINATE_TARGETS,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                0.0f, 100.0f,
                100,
                Map.of(),
                12, 16,
                0.05f,
                List.of("WARRIOR", "MARKSMAN", "ARMORED_ELITE"),
                "Defeat Invading Forces",
                "Repel incoming hostile assault waves advancing from the contested border."
        );
        DEFENSE_DEFINITIONS.add(defeatInvadingForces);
        DEFEAT_INVADING_FORCES_DEF = defeatInvadingForces;

        MissionDefinition holdTheChokepoint = new MissionDefinition(
                "pillager_hold_the_chokepoint",
                MissionType.HOLD_THE_CHOKEPOINT,
                ObjectiveType.SURVIVE_ASSAULT,
                Faction.PILLAGER_CONQUERORS,
                EnumSet.allOf(BaseType.class),
                0.0f, 100.0f,
                100,
                Map.of(),
                3, 3,
                0.05f,
                List.of("WARRIOR", "ARMORED_ELITE", "COMMANDER"),
                "Hold the Chokepoint",
                "Hold ground at the defense perimeter against invading waves using terrain and player fortifications."
        );
        DEFENSE_DEFINITIONS.add(holdTheChokepoint);
        HOLD_THE_CHOKEPOINT_DEF = holdTheChokepoint;
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
        return generateMissionsWithSeedAndBase(regionX, regionZ, faction, baseType, resistance, stability, biomeCategory, seed, baseAnchor, false);
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
            BlockPos baseAnchor,
            boolean isDefense
    ) {
        SubRegionMission[] missions = new SubRegionMission[4];
        float clampedRes = Math.clamp(resistance, 0.0f, 100.0f);

        // Derive authoritative deterministic region seed
        long effectiveSeed = seed != 0L ? seed : ((regionX * 73856093L) ^ (regionZ * 19349663L));

        // Track regional mission type occurrences (max 2 of any type per region)
        Map<MissionType, Integer> regionTypeCounts = new EnumMap<>(MissionType.class);

        // DEFENSE PROTOCOL: Zero structure generation, co-fallback between Defeat Invading Forces and Hold The Chokepoint
        if (isDefense) {
            for (int i = 0; i < 4; i++) {
                int subX = i % 2;
                int subZ = i / 2;
                long subSeed = effectiveSeed ^ (subX * 83492791L) ^ (subZ * 4393139L)
                        ^ faction.id() ^ 0xDEFE1234ABCDL;

                MissionDefinition def = ((subSeed & 1) == 0) ? DEFEAT_INVADING_FORCES_DEF : HOLD_THE_CHOKEPOINT_DEF;

                missions[i] = WeightedMissionSelector.selectAndGenerate(
                        List.of(def),
                        def,
                        regionX, regionZ,
                        subX, subZ,
                        faction,
                        BaseType.NONE,
                        clampedRes,
                        biomeCategory,
                        subSeed,
                        regionTypeCounts
                );
            }
            return missions;
        }

        // Determine occupied base subregions & anchor subregion
        int occupiedBaseMask = MissionProfile.getOccupiedBaseSubRegionsMask(regionX, regionZ, baseType, baseAnchor);
        int anchorSubBit = MissionProfile.getAnchorSubRegionBit(regionX, regionZ, baseAnchor);

        // Permanent unsalted base seed: Base subregions are strictly immune to partial victory rerolls!
        long baseSeed = MissionProfile.calculateBaseSeed(effectiveSeed, regionX, regionZ, baseAnchor);

        MissionProfile.HQWingContract hqContract = null;
        if (baseType == BaseType.HEADQUARTERS && Integer.bitCount(occupiedBaseMask) == 2) {
            hqContract = MissionProfile.getHQWingContract(effectiveSeed, regionX, regionZ, baseAnchor);
        }

        // Prepare Mega Base 3 secondary variants (if Mega Base)
        List<MissionDefinition> megaSecondaryDefs = null;
        if (baseType == BaseType.MEGA_BASE) {
            List<MissionDefinition> candidates = new ArrayList<>(List.of(
                    MEGA_WAR_ROOM_DEF, MEGA_MUNITIONS_DEF, MEGA_GATES_DEF, MEGA_GUNS_DEF, MEGA_COMMS_DEF
            ));
            // Deterministic shuffle of the 5 candidate variants using permanent base seed
            Random rng = new Random(WeightedMissionSelector.mix64(baseSeed ^ 0xCAFEBABE1234L));
            Collections.shuffle(candidates, rng);
            megaSecondaryDefs = candidates.subList(0, 3);
        }
        int megaSecondaryIdx = 0;

        // Pass 1: Resolve and assign mandatory base missions for occupied subregions using unsalted baseSeed
        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;
            int bit = subZ * 2 + subX;

            boolean isOccupiedByBase = (occupiedBaseMask & (1 << bit)) != 0;
            if (!isOccupiedByBase) {
                continue;
            }

            long subSeed = baseSeed ^ (subX * 83492791L) ^ (subZ * 4393139L)
                    ^ faction.id() ^ ((long) baseType.id() << 32);

            MissionDefinition baseDef;
            if (baseType == BaseType.HEADQUARTERS) {
                baseDef = (hqContract != null)
                        ? hqContract.getDefinitionForBit(faction, bit)
                        : HQ_INTEL_DEF;
                if (baseDef == null) {
                    baseDef = HQ_INTEL_DEF;
                }
            } else if (bit == anchorSubBit) {
                baseDef = switch (baseType) {
                    case OUTPOST -> OUTPOST_BASE_DEF;
                    case MEGA_BASE -> MEGA_COMMAND_DEF;
                    default -> FALLBACK_HARD_DEF;
                };
            } else if (baseType == BaseType.MEGA_BASE) {
                baseDef = (megaSecondaryDefs != null && megaSecondaryIdx < megaSecondaryDefs.size())
                        ? megaSecondaryDefs.get(megaSecondaryIdx++)
                        : MEGA_WAR_ROOM_DEF;
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

            long subSeed = effectiveSeed ^ (subX * 83492791L) ^ (subZ * 4393139L)
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
