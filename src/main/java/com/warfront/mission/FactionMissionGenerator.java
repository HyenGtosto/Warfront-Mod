package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;

/**
 * Interface for faction-specific mission generation algorithms.
 *
 * Each faction (e.g. Zombie Horde, Pillager Conquerors) provides its own
 * generator implementation with distinct rules for objective difficulty,
 * enemy role composition, base type eligibility, and biome affinities.
 */
public interface FactionMissionGenerator {

    /**
     * Legacy entry point: Generates 4 deterministic subregion missions.
     */
    SubRegionMission[] generateMissions(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability
    );

    /**
     * Generates 4 deterministic subregion missions including biome categorization.
     */
    default SubRegionMission[] generateMissionsWithBiome(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability,
            BiomeCategory biomeCategory
    ) {
        return generateMissions(regionX, regionZ, faction, baseType, resistance, stability);
    }

    /**
     * Primary modern entry point: Generates 4 deterministic subregion missions
     * incorporating world seed/salt and base anchor coordinates for mandatory base missions.
     *
     * @param regionX       region X coordinate
     * @param regionZ       region Z coordinate
     * @param faction       owning faction
     * @param baseType      base structure present in the region
     * @param resistance    effective Resistance value (0-100)
     * @param stability     effective Stability value (0-100)
     * @param biomeCategory biome category
     * @param seed          authoritative deterministic seed (worldSeed ^ salt)
     * @param baseAnchor    authoritative base anchor block coordinate (if any)
     * @return array of 4 {@link SubRegionMission} assignments, indexed by {@code subZ * 2 + subX}
     */
    default SubRegionMission[] generateMissionsWithSeedAndBase(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability,
            BiomeCategory biomeCategory,
            long seed,
            BlockPos baseAnchor
    ) {
        return generateMissionsWithBiome(regionX, regionZ, faction, baseType, resistance, stability, biomeCategory);
    }
}
