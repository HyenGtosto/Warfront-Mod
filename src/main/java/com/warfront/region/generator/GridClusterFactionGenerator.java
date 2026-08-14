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

    public static int[] getCanonicalCellCenter(long worldSeed, int cellX, int cellZ, int separation, long seedSalt) {
        long cellSeed = hashCell(worldSeed, cellX, cellZ, seedSalt);
        int margin = 4;
        int range = Math.max(1, separation - 2 * margin);
        int cx = cellX * separation + margin + (int) (Math.abs(cellSeed ^ 0x5DEECE66DL) % range);
        int cz = cellZ * separation + margin + (int) (Math.abs((cellSeed >> 16) ^ 0x5DEECE66DL) % range);
        return new int[] { cx, cz };
    }

    public Optional<RegionData.RegionState> generateRawRegionState(ServerLevel level, long worldSeed, int regionX, int regionZ) {
        return generateRegionInternal(level, worldSeed, regionX, regionZ, false);
    }

    public Optional<RegionData.RegionState> generateRegion(ServerLevel level, long worldSeed, int regionX, int regionZ) {
        return generateRegionInternal(level, worldSeed, regionX, regionZ, true);
    }

    private Optional<RegionData.RegionState> generateRegionInternal(ServerLevel level, long worldSeed, int regionX,
            int regionZ, boolean calculateStrength) {
        int separation = config.separation();
        int baseCellX = Math.floorDiv(regionX, separation);
        int baseCellZ = Math.floorDiv(regionZ, separation);

        // Check the current cell and neighboring cells in case a cluster spills over cell boundaries
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cellX = baseCellX + dx;
                int cellZ = baseCellZ + dz;

                // Symmetric 50/50 Faction Selection per Cell
                long factionSeed = hashCell(worldSeed, cellX, cellZ, 3003L);
                Faction cellFaction = ((Math.abs(factionSeed ^ 0x77777777L) % 100) < 50) ? Faction.ZOMBIE_HORDE : Faction.PILLAGER_CONQUERORS;
                if (cellFaction != this.faction) {
                    continue; // Cell assigned to rival faction in 50/50 symmetric split -> yield cell
                }

                long cellSeed = hashCell(worldSeed, cellX, cellZ, config.seedSalt());
                int[] center = getCanonicalCellCenter(worldSeed, cellX, cellZ, separation, config.seedSalt());
                int centerX = center[0];
                int centerZ = center[1];

                int spawnDistance = Math.abs(centerX) + Math.abs(centerZ);
                if (spawnDistance < config.minDistanceFromSpawn()) {
                    continue; // Skip clusters that generate too close to world spawn (0,0)
                }

                // Mega variant roll from config
                int megaThreshold = (int) (com.warfront.config.WarfrontConfig.MEGA_BASE_CHANCE.get() * 100.0D);
                boolean isMegaVariant = (Math.abs(cellSeed ^ 0x9E3779B9L) % 100) < megaThreshold;

                // Strict Restraints: Require at least a valid Big Base domain. Single-region / 1-tile bases are completely scrapped!
                if (isMegaVariant) {
                    if (!isValidMegaBaseLocation(level, centerX, centerZ)) {
                        isMegaVariant = false;
                        if (!isValidBigBaseLocation(level, centerX, centerZ)) {
                            continue; // Location too cramped for any large base -> cancel cluster completely!
                        }
                    }
                } else {
                    if (!isValidBigBaseLocation(level, centerX, centerZ)) {
                        continue; // Location too cramped for Big Base -> cancel cluster completely!
                    }
                }

                int manhattanDistance = Math.abs(regionX - centerX) + Math.abs(regionZ - centerZ);
                int relX = regionX - centerX;
                int relZ = regionZ - centerZ;

                // Prevent cluster fusion: If another neighboring cell center is closer to (regionX, regionZ), yield to that cell!
                if (isCloserToAnotherCell(worldSeed, separation, regionX, regionZ, cellX, cellZ, manhattanDistance)) {
                    continue;
                }

                long clusterId = net.minecraft.world.level.ChunkPos.asLong(cellX, cellZ);

                if (isMegaVariant) {
                    // Mega Variant: 1 Mega Core (0,0) + 4 Inner Walled Headquarters (MD = 1 cardinals) + 4 Outer Perimeter Outposts (MD = 3 cardinals) + 35% Organic Fringe
                    if (manhattanDistance <= 2) {
                        BaseType baseType = BaseType.NONE;

                        if (manhattanDistance == 0) {
                            baseType = BaseType.MEGA_BASE;
                        } else if (manhattanDistance == 1 && (relX == 0 || relZ == 0)) {
                            // 4 Inner Walled Headquarters at MD = 1 cardinal positions
                            baseType = BaseType.HEADQUARTERS;
                        }

                        if (!calculateStrength) {
                            return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, baseType, clusterId));
                        }
                        com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                                com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, clusterId, worldSeed);
                        return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), baseType, clusterId));
                    } else if (manhattanDistance == 3) {
                        boolean isCardinalTip = (relX == 0 || relZ == 0);
                        if (isCardinalTip) {
                            // Guaranteed 4 perimeter Outposts at cardinal tips (MD = 3)
                            if (!calculateStrength) {
                                return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.OUTPOST, clusterId));
                            }
                            com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                                    com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.OUTPOST, clusterId, worldSeed);
                            return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.OUTPOST, clusterId));
                        } else {
                            // 8 outer fringe corners at MD = 3: 35% chance to claim as plain territory (BaseType.NONE)
                            long fringeHash = hashCell(worldSeed, regionX, regionZ, 8888L);
                            if ((Math.abs(fringeHash) % 100) < 35) {
                                if (!calculateStrength) {
                                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, clusterId));
                                }
                                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, clusterId, worldSeed);
                                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, clusterId));
                            }
                        }
                    }
                } else {
                    // Big Standard Base: Core 13-region domain (MD <= 2 with 4 cardinal outposts at MD = 2) + 35% Organic Fringe at MD = 3 edge spots
                    if (manhattanDistance == 0) {
                        if (!calculateStrength) {
                            return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.HEADQUARTERS, clusterId));
                        }
                        com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                                com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.HEADQUARTERS, clusterId, worldSeed);
                        return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.HEADQUARTERS, clusterId));
                    } else if (manhattanDistance == 1) {
                        if (!calculateStrength) {
                            return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, clusterId));
                        }
                        com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                                com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, clusterId, worldSeed);
                        return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, clusterId));
                    } else if (manhattanDistance == 2) {
                        boolean isCardinalTip = (relX == 0 || relZ == 0);
                        BaseType baseType = isCardinalTip ? BaseType.OUTPOST : BaseType.NONE;
                        if (!calculateStrength) {
                            return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, baseType, clusterId));
                        }
                        com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                                com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, clusterId, worldSeed);
                        return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), baseType, clusterId));
                    } else if (manhattanDistance == 3 && relX != 0 && relZ != 0) {
                        long fringeHash = hashCell(worldSeed, regionX, regionZ, 8888L);
                        if ((Math.abs(fringeHash) % 100) < 35) {
                            if (!calculateStrength) {
                                return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, clusterId));
                            }
                            com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                                    com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, clusterId, worldSeed);
                            return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, clusterId));
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
                int[] otherCenter = getCanonicalCellCenter(worldSeed, otherCellX, otherCellZ, separation, config.seedSalt());
                int otherCenterX = otherCenter[0];
                int otherCenterZ = otherCenter[1];

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
