package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;

/**
 * Interface for faction-specific mission generation algorithms.
 *
 * Each faction (e.g. Zombie Horde, Pillager Conquerors) provides its own
 * generator implementation with distinct rules for objective difficulty,
 * enemy role composition, and subregion variations.
 */
public interface FactionMissionGenerator {

    /**
     * Generates 4 deterministic subregion missions for the given region parameters.
     *
     * @param regionX    region X coordinate
     * @param regionZ    region Z coordinate
     * @param faction    owning faction
     * @param baseType   base structure present in the region
     * @param resistance effective Resistance value (0-100)
     * @param stability  effective Stability value (0-100)
     * @return array of 4 {@link SubRegionMission} assignments, indexed by {@code subZ * 2 + subX}
     */
    SubRegionMission[] generateMissions(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability
    );
}
