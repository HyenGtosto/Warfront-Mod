package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;

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
     * Generates 4 deterministic subregion missions for a region using default parameters.
     */
    public static SubRegionMission[] generateForRegion(
            int regionX, int regionZ, Faction faction, BaseType baseType, float resistance, float stability) {
        return generateForRegion(regionX, regionZ, faction, baseType, resistance, stability, BiomeCategory.STANDARD, 0L, null);
    }

    /**
     * Generates 4 deterministic subregion missions with explicit biome classification.
     */
    public static SubRegionMission[] generateForRegion(
            int regionX, int regionZ, Faction faction, BaseType baseType, float resistance, float stability, BiomeCategory biomeCategory) {
        return generateForRegion(regionX, regionZ, faction, baseType, resistance, stability, biomeCategory, 0L, null);
    }

    /**
     * Generates 4 deterministic subregion missions with seed and base anchor coordinates.
     */
    public static SubRegionMission[] generateForRegion(
            int regionX, int regionZ, Faction faction, BaseType baseType, float resistance, float stability, long seed, BlockPos baseAnchor) {
        return generateForRegion(regionX, regionZ, faction, baseType, resistance, stability, BiomeCategory.STANDARD, seed, baseAnchor);
    }

    /**
     * Comprehensive entry point: Generates 4 deterministic subregion missions with biome, seed, and base anchor.
     */
    public static SubRegionMission[] generateForRegion(
            int regionX, int regionZ, Faction faction, BaseType baseType, float resistance, float stability,
            BiomeCategory biomeCategory, long seed, BlockPos baseAnchor) {
        return generateForRegion(regionX, regionZ, faction, baseType, resistance, stability, biomeCategory, seed, baseAnchor, false);
    }

    /**
     * Comprehensive entry point with defense flag: Generates 4 deterministic subregion missions
     * with biome, seed, base anchor, and defense state.
     */
    public static SubRegionMission[] generateForRegion(
            int regionX, int regionZ, Faction faction, BaseType baseType, float resistance, float stability,
            BiomeCategory biomeCategory, long seed, BlockPos baseAnchor, boolean isDefense) {
        return getGenerator(faction).generateMissionsWithSeedAndBase(
                regionX, regionZ, faction, baseType, resistance, stability, biomeCategory, seed, baseAnchor, isDefense);
    }

    /**
     * Generates 4 deterministic subregion missions with seed, base anchor, and defense flag.
     */
    public static SubRegionMission[] generateForRegion(
            int regionX, int regionZ, Faction faction, BaseType baseType, float resistance, float stability,
            long seed, BlockPos baseAnchor, boolean isDefense) {
        return generateForRegion(regionX, regionZ, faction, baseType, resistance, stability, BiomeCategory.STANDARD, seed, baseAnchor, isDefense);
    }

    /**
     * Calculates the 4-bit mask of subregions occupied by a physical base structure.
     * <ul>
     *   <li>OUTPOST: 1 subregion (the anchor subregion)</li>
     *   <li>HEADQUARTERS: 2 subregions (the subregions it shifted into)</li>
     *   <li>MEGA_BASE: all 4 subregions (mask = 0xF)</li>
     * </ul>
     */
    public static int getOccupiedBaseSubRegionsMask(int regionX, int regionZ, BaseType baseType, BlockPos baseAnchor) {
        if (baseType == null || baseType == BaseType.NONE || baseAnchor == null) {
            return 0;
        }

        int regMinX = regionX * 128;
        int regMinZ = regionZ * 128;
        int localX = Math.clamp(baseAnchor.getX() - regMinX, 0, 127);
        int localZ = Math.clamp(baseAnchor.getZ() - regMinZ, 0, 127);

        int anchorSubX = Math.clamp(localX / 64, 0, 1);
        int anchorSubZ = Math.clamp(localZ / 64, 0, 1);
        int anchorBit = anchorSubZ * 2 + anchorSubX;

        return switch (baseType) {
            case OUTPOST -> (1 << anchorBit);
            case HEADQUARTERS -> {
                // HQ occupies 2 subregions along its shift axis
                if (Math.abs(localX - 64) > Math.abs(localZ - 64)) {
                    if (localX >= 64) {
                        // EAST: Subregions (1,0) and (1,1) -> bits 1 and 3
                        yield (1 << 1) | (1 << 3);
                    } else {
                        // WEST: Subregions (0,0) and (0,1) -> bits 0 and 2
                        yield (1 << 0) | (1 << 2);
                    }
                } else {
                    if (localZ < 64) {
                        // NORTH: Subregions (0,0) and (1,0) -> bits 0 and 1
                        yield (1 << 0) | (1 << 1);
                    } else {
                        // SOUTH: Subregions (0,1) and (1,1) -> bits 2 and 3
                        yield (1 << 2) | (1 << 3);
                    }
                }
            }
            case MEGA_BASE -> 0xF;
            default -> 0;
        };
    }

    /**
     * Resolves the subregion bit (0..3) housing the base anchor.
     */
    public static int getAnchorSubRegionBit(int regionX, int regionZ, BlockPos baseAnchor) {
        if (baseAnchor == null) return 0;
        int regMinX = regionX * 128;
        int regMinZ = regionZ * 128;
        int localX = Math.clamp(baseAnchor.getX() - regMinX, 0, 127);
        int localZ = Math.clamp(baseAnchor.getZ() - regMinZ, 0, 127);
        int anchorSubX = Math.clamp(localX / 64, 0, 1);
        int anchorSubZ = Math.clamp(localZ / 64, 0, 1);
        return anchorSubZ * 2 + anchorSubX;
    }

    /**
     * Authoritative additive coordinate mixer for base seed calculation.
     * Prevents coordinate-axis collapse at (0, 0) and eliminates partial-victory reroll vulnerability.
     */
    public static long calculateBaseSeed(long worldSeed, int regionX, int regionZ, BlockPos baseAnchor) {
        long h = worldSeed + (long) regionX * 341873128712L + (long) regionZ * 132897987541L + 0xA0761D6478BD642FL;
        if (baseAnchor != null) {
            h += (long) baseAnchor.getX() * 83492791L + (long) baseAnchor.getZ() * 4393139L;
        }
        return WeightedMissionSelector.mix64(h);
    }

    /**
     * Shared architectural contract defining the two wings of a Headquarters (Medium Base).
     * Maps physical subregions to deterministic 2-of-3 strategic base mission types.
     */
    public record HQWingContract(
            int wing0SubX, int wing0SubZ, int wing0Bit, MissionType wing0Type,
            int wing1SubX, int wing1SubZ, int wing1Bit, MissionType wing1Type
    ) {
        public MissionType getTypeForBit(int subRegionBit) {
            if (subRegionBit == wing0Bit) return wing0Type;
            if (subRegionBit == wing1Bit) return wing1Type;
            return null;
        }

        public MissionDefinition getDefinitionForBit(Faction faction, int subRegionBit) {
            MissionType type = getTypeForBit(subRegionBit);
            if (type == null) return null;
            return getDefinition(faction, type);
        }

        public MissionDefinition getDefinitionWing0(Faction faction) {
            return getDefinition(faction, wing0Type);
        }

        public MissionDefinition getDefinitionWing1(Faction faction) {
            return getDefinition(faction, wing1Type);
        }

        public static MissionDefinition getDefinition(Faction faction, MissionType type) {
            if (faction == Faction.ZOMBIE_HORDE) {
                return ZombieMissionGenerator.getHQDefinition(type);
            }
            return PillagerMissionGenerator.getHQDefinition(type);
        }
    }

    /**
     * Resolves the authoritative 2-wing contract for a Headquarters base.
     * Strictly verifies that the occupied subregion mask contains exactly 2 bits.
     */
    public static HQWingContract getHQWingContract(long worldSeed, int regionX, int regionZ, BlockPos baseAnchor) {
        int occupiedMask = getOccupiedBaseSubRegionsMask(regionX, regionZ, BaseType.HEADQUARTERS, baseAnchor);
        if (Integer.bitCount(occupiedMask) != 2) {
            throw new IllegalStateException(String.format(
                    "[Warfront] Headquarters at (%d, %d) with anchor %s has invalid occupied mask %d (expected exactly 2 bits)!",
                    regionX, regionZ, baseAnchor, occupiedMask
            ));
        }

        int bit0 = Integer.numberOfTrailingZeros(occupiedMask);
        int bit1 = Integer.numberOfTrailingZeros(occupiedMask & ~(1 << bit0));

        int w0SubX = bit0 % 2;
        int w0SubZ = bit0 / 2;
        int w1SubX = bit1 % 2;
        int w1SubZ = bit1 / 2;

        long baseSeed = calculateBaseSeed(worldSeed, regionX, regionZ, baseAnchor);
        long hqRollSeed = WeightedMissionSelector.mix64(baseSeed ^ 0x9E3779B9L);
        int skipIdx = (int) Math.floorMod(hqRollSeed, 3); // Deterministically omit 1 of the 3 candidates

        MissionType[] candidates = new MissionType[] {
                MissionType.BASE_KILL_COMMANDER,
                MissionType.BASE_DESTROY_INTEL,
                MissionType.BASE_DESTROY_SUPPLIES
        };

        MissionType wing0Type = candidates[(skipIdx + 1) % 3];
        MissionType wing1Type = candidates[(skipIdx + 2) % 3];

        return new HQWingContract(
                w0SubX, w0SubZ, bit0, wing0Type,
                w1SubX, w1SubZ, bit1, wing1Type
        );
    }
}
