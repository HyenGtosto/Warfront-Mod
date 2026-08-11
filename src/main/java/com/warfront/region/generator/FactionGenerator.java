package com.warfront.region.generator;

import com.warfront.region.Faction;
import com.warfront.region.RegionData;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

public interface FactionGenerator {
    Faction getFaction();

    FactionGeneratorConfig getConfig();

    /**
     * Evaluates whether this faction controls the region at (regionX, regionZ).
     *
     * @param worldSeed The world seed for deterministic calculation
     * @param regionX   Region X coordinate
     * @param regionZ   Region Z coordinate
     * @return Optional containing the RegionState if claimed by this faction, empty otherwise.
     */
    Optional<RegionData.RegionState> generateRegion(long worldSeed, int regionX, int regionZ);

    default Optional<RegionData.RegionState> generateRegion(ServerLevel level, long worldSeed, int regionX, int regionZ) {
        return generateRegion(worldSeed, regionX, regionZ);
    }
}
