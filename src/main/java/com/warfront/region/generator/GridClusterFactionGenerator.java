package com.warfront.region.generator;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

public class GridClusterFactionGenerator implements FactionGenerator {
    private final Faction faction;
    private final FactionGeneratorConfig config;

    public GridClusterFactionGenerator(Faction faction, FactionGeneratorConfig config) {
        this.faction = faction;
        this.config = config;
    }

    @Override
    public Faction getFaction() {
        return faction;
    }

    @Override
    public FactionGeneratorConfig getConfig() {
        return config;
    }

    @Override
    public Optional<RegionData.RegionState> generateRegion(long worldSeed, int regionX, int regionZ) {
        return generateRegion(null, worldSeed, regionX, regionZ);
    }

    public Optional<RegionData.RegionState> generateRegion(ServerLevel level, long worldSeed, int regionX,
            int regionZ) {
        int separation = config.separation();
        int baseCellX = Math.floorDiv(regionX, separation);
        int baseCellZ = Math.floorDiv(regionZ, separation);

        // Check the current cell and neighboring cells in case a cluster spills over
        // cell boundaries
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cellX = baseCellX + dx;
                int cellZ = baseCellZ + dz;

                long cellSeed = hashCell(worldSeed, cellX, cellZ, config.seedSalt());
                int centerX = cellX * separation + (int) (Math.abs(cellSeed ^ 0x5DEECE66DL) % separation);
                int centerZ = cellZ * separation + (int) (Math.abs((cellSeed >> 16) ^ 0x5DEECE66DL) % separation);

                int spawnDistance = Math.abs(centerX) + Math.abs(centerZ);
                if (spawnDistance < config.minDistanceFromSpawn()) {
                    continue; // Skip clusters that generate too close to world spawn (0,0)
                }

                // Mega variant roll from config
                int megaThreshold = (int) (com.warfront.config.WarfrontConfig.MEGA_BASE_CHANCE.get() * 100.0D);
                boolean isMegaVariant = (Math.abs(cellSeed ^ 0x9E3779B9L) % 100) < megaThreshold;

                int sizeRange = config.maxClusterSize() - config.minClusterSize() + 1;
                int clusterSize = config.minClusterSize() + (int) (Math.abs(cellSeed >> 32) % sizeRange);

                // Strict Restraints: Reject candidate location if land domain is insufficient
                // or cardinal outposts land on water!
                if (isMegaVariant) {
                    if (!isValidMegaBaseLocation(level, centerX, centerZ)) {
                        // Fallback: If Mega Base cannot fit inland, attempt to downgrade to a Big Base
                        // or cancel
                        isMegaVariant = false;
                        if (!isValidBigBaseLocation(level, centerX, centerZ)) {
                            continue; // Coastal location too cramped for any large base -> cancel cluster!
                        }
                    }
                } else if (clusterSize >= 2) {
                    if (!isValidBigBaseLocation(level, centerX, centerZ)) {
                        clusterSize = 1; // Downgrade to Small Base if coastal land is too cramped for Big Base
                    }
                }

                int manhattanDistance = Math.abs(regionX - centerX) + Math.abs(regionZ - centerZ);
                int relX = regionX - centerX;
                int relZ = regionZ - centerZ;

                // Prevent cluster fusion: If another neighboring cell center is closer to
                // (regionX, regionZ), yield to that cell!
                if (isCloserToAnotherCell(worldSeed, separation, regionX, regionZ, cellX, cellZ, manhattanDistance)) {
                    continue;
                }

                if (isMegaVariant) {
                    // Mega Variant: 1 Mega Core (0,0) + 4 Inner Walled Headquarters (MD = 1
                    // cardinals) + 4 Outer Perimeter Outposts (MD = 3 cardinals) + 35% Organic
                    // Fringe
                    if (manhattanDistance <= 2) {
                        BaseType baseType = BaseType.NONE;

                        if (manhattanDistance == 0) {
                            baseType = BaseType.MEGA_BASE;
                        } else if (manhattanDistance == 1 && (relX == 0 || relZ == 0)) {
                            // 4 Inner Walled Headquarters at MD = 1 cardinal positions
                            baseType = BaseType.HEADQUARTERS;
                        }

                        float baseMetric = (baseType == BaseType.MEGA_BASE) ? 90.0F
                                : (baseType == BaseType.HEADQUARTERS) ? 70.0F : 30.0F;

                        return Optional.of(new RegionData.RegionState(faction, baseMetric, baseMetric, baseType));
                    } else if (manhattanDistance == 3) {
                        boolean isCardinalTip = (relX == 0 || relZ == 0);
                        if (isCardinalTip) {
                            // Guaranteed 4 perimeter Outposts at cardinal tips (MD = 3)
                            return Optional.of(new RegionData.RegionState(faction, 50.0F, 50.0F, BaseType.OUTPOST));
                        } else {
                            // 8 outer fringe corners at MD = 3: 35% chance to claim as plain territory
                            // (BaseType.NONE)
                            long fringeHash = hashCell(worldSeed, regionX, regionZ, 8888L);
                            if ((Math.abs(fringeHash) % 100) < 35) {
                                return Optional.of(new RegionData.RegionState(faction, 30.0F, 30.0F, BaseType.NONE));
                            }
                        }
                    }
                } else if (clusterSize >= 2) {
                    // Big Standard Base: Core 13-region domain (MD <= 2 with 4 cardinal outposts at
                    // MD = 2) + 35% Organic Fringe at MD = 3 edge spots (-2,1), (-2,-1), (-1,2),
                    // (-1,-2), etc.
                    if (manhattanDistance == 0) {
                        return Optional.of(new RegionData.RegionState(faction, 70.0F, 70.0F, BaseType.HEADQUARTERS));
                    } else if (manhattanDistance == 1) {
                        return Optional.of(new RegionData.RegionState(faction, 30.0F, 30.0F, BaseType.NONE));
                    } else if (manhattanDistance == 2) {
                        boolean isCardinalTip = (relX == 0 || relZ == 0);
                        BaseType baseType = isCardinalTip ? BaseType.OUTPOST : BaseType.NONE;
                        float baseMetric = isCardinalTip ? 50.0F : 30.0F;
                        return Optional.of(new RegionData.RegionState(faction, baseMetric, baseMetric, baseType));
                    } else if (manhattanDistance == 3 && relX != 0 && relZ != 0) {
                        // The 8 edge spots surrounding the 13-region diamond: (-2,1), (-2,-1), (-1,2),
                        // (-1,-2), etc.
                        long fringeHash = hashCell(worldSeed, regionX, regionZ, 8888L);
                        if ((Math.abs(fringeHash) % 100) < 35) {
                            return Optional.of(new RegionData.RegionState(faction, 30.0F, 30.0F, BaseType.NONE));
                        }
                    }
                } else {
                    // Small Standard Base: Core 5-region cross (MD <= 1) + 60% corner outposts at
                    // (|relX|==1 && |relZ|==1)
                    if (manhattanDistance <= 1) {
                        BaseType baseType = (manhattanDistance == 0) ? BaseType.HEADQUARTERS : BaseType.NONE;
                        float baseMetric = (baseType == BaseType.HEADQUARTERS) ? 70.0F : 30.0F;
                        return Optional.of(new RegionData.RegionState(faction, baseMetric, baseMetric, baseType));
                    } else if (Math.abs(relX) == 1 && Math.abs(relZ) == 1) {
                        // Diagonal corner at MD = 2: 60% chance to spawn an Outpost attached to the
                        // 5-region base
                        long cornerHash = hashCell(worldSeed, regionX, regionZ, 7777L);
                        if ((Math.abs(cornerHash) % 100) < 60) {
                            return Optional.of(new RegionData.RegionState(faction, 50.0F, 50.0F, BaseType.OUTPOST));
                        }
                    }
                }
            }
        }

        return Optional.empty();
    }

    private boolean isCloserToAnotherCell(long worldSeed, int separation, int targetRX, int targetRZ, int selfCellX,
            int selfCellZ, int selfDistance) {
        for (int cdx = -1; cdx <= 1; cdx++) {
            for (int cdz = -1; cdz <= 1; cdz++) {
                if (cdx == 0 && cdz == 0)
                    continue;

                int otherCellX = selfCellX + cdx;
                int otherCellZ = selfCellZ + cdz;
                long otherSeed = hashCell(worldSeed, otherCellX, otherCellZ, config.seedSalt());
                int otherCenterX = otherCellX * separation + (int) (Math.abs(otherSeed ^ 0x5DEECE66DL) % separation);
                int otherCenterZ = otherCellZ * separation
                        + (int) (Math.abs((otherSeed >> 16) ^ 0x5DEECE66DL) % separation);

                int otherDist = Math.abs(targetRX - otherCenterX) + Math.abs(targetRZ - otherCenterZ);
                if (otherDist < selfDistance) {
                    return true; // Another cell center is closer -> Hand over claim to that cell!
                }
            }
        }
        return false;
    }

    private boolean isValidMegaBaseLocation(ServerLevel level, int centerX, int centerZ) {
        if (level == null)
            return true;

        ProceduralRegionGenerator gen = ProceduralRegionGenerator.getInstance();

        // 1. Center must be valid land
        if (!gen.biomeAvailableForBase(level, centerX, centerZ))
            return false;

        // 2. All 4 cardinal outposts at MD = 3 MUST be valid land (no coastal exposed
        // command core!)
        if (!gen.biomeAvailableForBase(level, centerX, centerZ + 3))
            return false;
        if (!gen.biomeAvailableForBase(level, centerX, centerZ - 3))
            return false;
        if (!gen.biomeAvailableForBase(level, centerX + 3, centerZ))
            return false;
        if (!gen.biomeAvailableForBase(level, centerX - 3, centerZ))
            return false;

        // 3. Count valid land regions within MD <= 3 diamond (must have at least 18 out
        // of 25 land regions)
        int landCount = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 3) {
                    if (gen.biomeAvailableForBase(level, centerX + dx, centerZ + dz)) {
                        landCount++;
                    }
                }
            }
        }

        return landCount >= 18;
    }

    private boolean isValidBigBaseLocation(ServerLevel level, int centerX, int centerZ) {
        if (level == null)
            return true;

        ProceduralRegionGenerator gen = ProceduralRegionGenerator.getInstance();

        // 1. Center must be valid land
        if (!gen.biomeAvailableForBase(level, centerX, centerZ))
            return false;

        // 2. All 4 cardinal outposts at MD = 2 MUST be valid land
        if (!gen.biomeAvailableForBase(level, centerX, centerZ + 2))
            return false;
        if (!gen.biomeAvailableForBase(level, centerX, centerZ - 2))
            return false;
        if (!gen.biomeAvailableForBase(level, centerX + 2, centerZ))
            return false;
        if (!gen.biomeAvailableForBase(level, centerX - 2, centerZ))
            return false;

        // 3. Count valid land regions within MD <= 2 diamond (must have at least 10 out
        // of 13 land regions)
        int landCount = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 2) {
                    if (gen.biomeAvailableForBase(level, centerX + dx, centerZ + dz)) {
                        landCount++;
                    }
                }
            }
        }

        return landCount >= 10;
    }

    private static long hashCell(long worldSeed, int cellX, int cellZ, long salt) {
        long h = worldSeed ^ salt;
        h = h * 6364136223846793005L + cellX;
        h = h * 6364136223846793005L + cellZ;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }
}
