package com.warfront.region.generator;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.core.BlockPos;
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

    public static Faction getCellFaction(long worldSeed, int cellX, int cellZ) {
        long factionSeed = hashCell(worldSeed, cellX, cellZ, 3003L);
        return ((Math.abs(factionSeed ^ 0x77777777L) % 100) < 50) ? Faction.ZOMBIE_HORDE : Faction.PILLAGER_CONQUERORS;
    }

    public static long getFactionSalt(Faction f) {
        return (f == Faction.PILLAGER_CONQUERORS) ? 1001L : 2002L;
    }

    public int getSeparation() {
        try {
            if (this.faction == Faction.PILLAGER_CONQUERORS && com.warfront.config.WarfrontConfig.PILLAGER_SEPARATION != null) {
                return com.warfront.config.WarfrontConfig.PILLAGER_SEPARATION.get();
            }
            if (this.faction == Faction.ZOMBIE_HORDE && com.warfront.config.WarfrontConfig.ZOMBIE_SEPARATION != null) {
                return com.warfront.config.WarfrontConfig.ZOMBIE_SEPARATION.get();
            }
        } catch (Exception ignored) {
        }
        return config.separation();
    }

    public static int[] getCanonicalCellCenter(long worldSeed, int cellX, int cellZ, int separation, long seedSalt) {
        long cellSeed = hashCell(worldSeed, cellX, cellZ, seedSalt);
        int margin = Math.min(3, Math.max(1, separation / 4));
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
        int separation = getSeparation();
        int baseCellX = Math.floorDiv(regionX, separation);
        int baseCellZ = Math.floorDiv(regionZ, separation);

        int bufferDistance = 1;
        try {
            if (com.warfront.config.WarfrontConfig.FACTION_BUFFER_DISTANCE != null) {
                bufferDistance = com.warfront.config.WarfrontConfig.FACTION_BUFFER_DISTANCE.get();
            }
        } catch (Exception ignored) {
        }

        // Check if this region is land. If it's ocean, it cannot be claimed by any faction!
        if (level != null && !ProceduralRegionGenerator.getInstance().biomeAvailableForBase(level, regionX, regionZ)) {
            return Optional.empty();
        }

        // Search neighboring cells for the closest valid base center
        int bestDistance = Integer.MAX_VALUE;
        Faction bestFaction = null;
        int bestCenterX = 0;
        int bestCenterZ = 0;
        long bestClusterId = 0L;
        int bestClusterSize = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cellX = baseCellX + dx;
                int cellZ = baseCellZ + dz;

                Faction cellFaction = getCellFaction(worldSeed, cellX, cellZ);
                long salt = getFactionSalt(cellFaction);
                int[] center = getCanonicalCellCenter(worldSeed, cellX, cellZ, separation, salt);
                int cx = center[0];
                int cz = center[1];

                int spawnDistance = Math.abs(cx) + Math.abs(cz);
                if (spawnDistance < config.minDistanceFromSpawn()) {
                    continue;
                }

                long cellSeed = hashCell(worldSeed, cellX, cellZ, salt);
                int megaThreshold = (int) (com.warfront.config.WarfrontConfig.MEGA_BASE_CHANCE.get() * 100.0D);
                int clusterRoll = (int) (Math.abs(cellSeed ^ 0x9E3779B9L) % 100);

                int clusterSize;
                if (clusterRoll < megaThreshold) {
                    clusterSize = 3; // Mega Base (15%)
                } else if (clusterRoll < 75) {
                    clusterSize = 2; // Big Base (60%: 15% to 75%)
                } else {
                    clusterSize = 1; // Small Base (25%: 75% to 100%)
                }

                if (clusterSize == 3) {
                    if (!isValidMegaBaseLocation(level, cx, cz)) {
                        clusterSize = 2;
                    }
                }
                if (clusterSize == 2) {
                    if (!isValidBigBaseLocation(level, cx, cz)) {
                        clusterSize = 1;
                    }
                }
                if (clusterSize == 1) {
                    if (!isValidSmallBaseLocation(level, cx, cz)) {
                        continue;
                    }
                }

                int dist = Math.abs(regionX - cx) + Math.abs(regionZ - cz);
                if (dist < bestDistance) {
                    bestDistance = dist;
                    bestFaction = cellFaction;
                    bestCenterX = cx;
                    bestCenterZ = cz;
                    bestClusterId = net.minecraft.world.level.ChunkPos.asLong(cellX, cellZ);
                    bestClusterSize = clusterSize;
                }
            }
        }

        if (bestFaction != this.faction || bestDistance > 3) {
            return Optional.empty(); // Not claimed by this faction, or too far from any base
        }

        // Check if too close to any rival faction cluster
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nCellX = baseCellX + dx;
                int nCellZ = baseCellZ + dz;
                Faction nFaction = getCellFaction(worldSeed, nCellX, nCellZ);
                if (nFaction != this.faction && nFaction != Faction.UNCLAIMED) {
                    long nSalt = getFactionSalt(nFaction);
                    int[] nCenter = getCanonicalCellCenter(worldSeed, nCellX, nCellZ, separation, nSalt);
                    int distToRival = Math.abs(regionX - nCenter[0]) + Math.abs(regionZ - nCenter[1]);
                    if (distToRival <= 2 + bufferDistance) {
                        return Optional.empty(); // Enforce buffer zone between factions!
                    }
                }
            }
        }

        int relX = regionX - bestCenterX;
        int relZ = regionZ - bestCenterZ;

        ProceduralRegionGenerator regionGen = ProceduralRegionGenerator.getInstance();

        if (bestClusterSize == 3) {
            if (bestDistance <= 2) {
                BaseType baseType = BaseType.NONE;
                BlockPos baseAnchor = null;
                if (bestDistance == 0) {
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.MEGA_BASE);
                    baseType = res.baseType();
                    baseAnchor = res.anchor();
                } else if (bestDistance == 1 && (relX == 0 || relZ == 0)) {
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.HEADQUARTERS);
                    baseType = res.baseType();
                    baseAnchor = res.anchor();
                }
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, baseType, bestClusterId, baseAnchor));
                }
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), baseType, bestClusterId, baseAnchor));
            } else if (bestDistance == 3) {
                boolean isCardinalTip = (relX == 0 || relZ == 0);
                if (isCardinalTip) {
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.OUTPOST);
                    BaseType baseType = res.baseType();
                    BlockPos baseAnchor = res.anchor();
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, baseType, bestClusterId, baseAnchor));
                    }
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), baseType, bestClusterId, baseAnchor));
                } else {
                    long fringeHash = hashCell(worldSeed, regionX, regionZ, 8888L);
                    if ((Math.abs(fringeHash) % 100) < 35) {
                        if (!calculateStrength) {
                            return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, bestClusterId, null));
                        }
                        com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                                com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, bestClusterId, worldSeed);
                        return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, bestClusterId, null));
                    }
                }
            }
        } else if (bestClusterSize == 2) {
            if (bestDistance == 0) {
                ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.HEADQUARTERS);
                BaseType baseType = res.baseType();
                BlockPos baseAnchor = res.anchor();
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, baseType, bestClusterId, baseAnchor));
                }
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), baseType, bestClusterId, baseAnchor));
            } else if (bestDistance == 1) {
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, bestClusterId, null));
                }
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, bestClusterId, null));
            } else if (bestDistance == 2) {
                boolean isCardinalTip = (relX == 0 || relZ == 0);
                BaseType baseType = BaseType.NONE;
                BlockPos baseAnchor = null;
                if (isCardinalTip) {
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.OUTPOST);
                    baseType = res.baseType();
                    baseAnchor = res.anchor();
                }
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, baseType, bestClusterId, baseAnchor));
                }
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), baseType, bestClusterId, baseAnchor));
            } else if (bestDistance == 3 && relX != 0 && relZ != 0) {
                long fringeHash = hashCell(worldSeed, regionX, regionZ, 8888L);
                if ((Math.abs(fringeHash) % 100) < 35) {
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, bestClusterId, null));
                    }
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, bestClusterId, null));
                }
            }
        } else if (bestClusterSize == 1) {
            // Small Base (Size 1): Core 5-region plus-shape (MD <= 1) + 60% corner outposts at (|relX| == 1 && |relZ| == 1)
            if (bestDistance <= 1) {
                BaseType baseType = BaseType.NONE;
                BlockPos baseAnchor = null;
                if (bestDistance == 0) {
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.HEADQUARTERS);
                    baseType = res.baseType();
                    baseAnchor = res.anchor();
                }
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, baseType, bestClusterId, baseAnchor));
                }
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), baseType, bestClusterId, baseAnchor));
            } else if (Math.abs(relX) == 1 && Math.abs(relZ) == 1) {
                long cornerHash = hashCell(worldSeed, regionX, regionZ, 7777L);
                if ((Math.abs(cornerHash) % 100) < 60) {
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.OUTPOST);
                    BaseType baseType = res.baseType();
                    BlockPos baseAnchor = res.anchor();
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, baseType, bestClusterId, baseAnchor));
                    }
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), baseType, bestClusterId, baseAnchor));
                }
            }
        }

        return Optional.empty();
    }

    private final java.util.Map<Long, Boolean> smallBaseValidityCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, Boolean> bigBaseValidityCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, Boolean> megaBaseValidityCache = new java.util.concurrent.ConcurrentHashMap<>();

    public void clearValidityCaches() {
        smallBaseValidityCache.clear();
        bigBaseValidityCache.clear();
        megaBaseValidityCache.clear();
    }

    private boolean isValidSmallBaseLocation(ServerLevel level, int centerX, int centerZ) {
        if (level == null)
            return true;

        long key = net.minecraft.world.level.ChunkPos.asLong(centerX, centerZ);
        Boolean cached = smallBaseValidityCache.get(key);
        if (cached != null)
            return cached;

        ProceduralRegionGenerator gen = ProceduralRegionGenerator.getInstance();
        // 1. Center must be valid land
        if (!gen.biomeAvailableForBase(level, centerX, centerZ)) {
            smallBaseValidityCache.put(key, false);
            return false;
        }

        smallBaseValidityCache.put(key, true);
        return true;
    }

    private boolean isValidMegaBaseLocation(ServerLevel level, int centerX, int centerZ) {
        if (level == null)
            return true;

        long key = net.minecraft.world.level.ChunkPos.asLong(centerX, centerZ);
        Boolean cached = megaBaseValidityCache.get(key);
        if (cached != null)
            return cached;

        ProceduralRegionGenerator gen = ProceduralRegionGenerator.getInstance();

        // 1. Center must be valid land
        if (!gen.biomeAvailableForBase(level, centerX, centerZ)) {
            megaBaseValidityCache.put(key, false);
            return false;
        }

        // 2. Count valid land regions in MD <= 3 domain (at least 15 out of 25)
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

        boolean result = landCount >= 15;
        megaBaseValidityCache.put(key, result);
        return result;
    }

    private boolean isValidBigBaseLocation(ServerLevel level, int centerX, int centerZ) {
        if (level == null)
            return true;

        long key = net.minecraft.world.level.ChunkPos.asLong(centerX, centerZ);
        Boolean cached = bigBaseValidityCache.get(key);
        if (cached != null)
            return cached;

        ProceduralRegionGenerator gen = ProceduralRegionGenerator.getInstance();

        // 1. Center must be valid land
        if (!gen.biomeAvailableForBase(level, centerX, centerZ)) {
            bigBaseValidityCache.put(key, false);
            return false;
        }

        // 2. Count valid land regions in MD <= 2 domain (at least 7 out of 13)
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

        boolean result = landCount >= 7;
        bigBaseValidityCache.put(key, result);
        return result;
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
