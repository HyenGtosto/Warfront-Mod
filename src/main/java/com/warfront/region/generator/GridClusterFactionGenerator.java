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
        // Option 2A: Bipartite Checkerboard Parity ensures strict 50/50 balance and immediate rival borders
        int parity = Math.floorMod(cellX + cellZ, 2);
        long worldToggle = (worldSeed ^ 0x5DEECE66DL) & 1L;
        return ((parity ^ worldToggle) == 0) ? Faction.PILLAGER_CONQUERORS : Faction.ZOMBIE_HORDE;
    }

    public static long getFactionSalt(Faction f) {
        return (f == Faction.PILLAGER_CONQUERORS) ? 1001L : 2002L;
    }

    public int getSeparation() {
        return config.separation();
    }

    public static int[] getCanonicalCellCenter(long worldSeed, int cellX, int cellZ, int separation, long seedSalt) {
        long cellSeed = hashCell(worldSeed, cellX, cellZ, seedSalt);
        int margin = Math.max(4, separation / 4);
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

    public record CellClusterInfo(int cx, int cz, int clusterSize, Faction faction) {}
    private final java.util.Map<Long, CellClusterInfo> cellClusterCache = new java.util.concurrent.ConcurrentHashMap<>();

    public CellClusterInfo getOptimalCellCluster(ServerLevel level, long worldSeed, int cellX, int cellZ, int separation) {
        Faction cellFaction = getCellFaction(worldSeed, cellX, cellZ);
        long salt = getFactionSalt(cellFaction);
        int[] fallbackCenter = getCanonicalCellCenter(worldSeed, cellX, cellZ, separation, salt);

        if (level == null) {
            long cellSeed = hashCell(worldSeed, cellX, cellZ, salt);
            int megaThreshold = 15; // 15% Mega Base rate
            int clusterRoll = (int) (Math.abs(cellSeed ^ 0x9E3779B9L) % 100);
            int size = (clusterRoll < megaThreshold) ? 3 : ((clusterRoll < 75) ? 2 : 1);
            return new CellClusterInfo(fallbackCenter[0], fallbackCenter[1], size, cellFaction);
        }

        long cacheKey = net.minecraft.world.level.ChunkPos.asLong(cellX, cellZ);
        CellClusterInfo cached = cellClusterCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        long cellSeed = hashCell(worldSeed, cellX, cellZ, salt);
        int megaThreshold = 15; // 15% Mega Base rate
        int clusterRoll = (int) (Math.abs(cellSeed ^ 0x9E3779B9L) % 100);

        int rolledTier;
        if (clusterRoll < megaThreshold) {
            rolledTier = 3; // Mega Base (15%)
        } else if (clusterRoll < 75) {
            rolledTier = 2; // Big Base (60%)
        } else {
            rolledTier = 1; // Small Base (25%)
        }

        int margin = Math.max(4, separation / 4);
        int minCoord = margin;
        int maxCoord = separation - 1 - margin;
        int range = Math.max(1, maxCoord - minCoord);

        // Option 1A-1: Candidate set including canonical center + 3x3 distributed interior grid
        java.util.List<int[]> candidates = new java.util.ArrayList<>();
        candidates.add(fallbackCenter);

        for (int gx = 0; gx <= 2; gx++) {
            for (int gz = 0; gz <= 2; gz++) {
                int candCX = cellX * separation + minCoord + (gx * range) / 2;
                int candCZ = cellZ * separation + minCoord + (gz * range) / 2;
                if (candCX != fallbackCenter[0] || candCZ != fallbackCenter[1]) {
                    candidates.add(new int[] { candCX, candCZ });
                }
            }
        }

        // Filter by minDistanceFromSpawn
        java.util.List<int[]> validSpawnCandidates = new java.util.ArrayList<>();
        for (int[] c : candidates) {
            if (Math.abs(c[0]) + Math.abs(c[1]) >= config.minDistanceFromSpawn()) {
                validSpawnCandidates.add(c);
            }
        }

        // Upfront Rival Clearance: Filter candidates that maintain Chebyshev distance >= 8 to neighboring rival cell canonical centers
        java.util.List<int[]> clearedCandidates = new java.util.ArrayList<>();
        for (int[] c : validSpawnCandidates) {
            boolean tooClose = false;
            for (int cdx = -1; cdx <= 1; cdx++) {
                for (int cdz = -1; cdz <= 1; cdz++) {
                    if (cdx == 0 && cdz == 0) continue;
                    int nCellX = cellX + cdx;
                    int nCellZ = cellZ + cdz;
                    Faction nFaction = getCellFaction(worldSeed, nCellX, nCellZ);
                    if (nFaction != cellFaction && nFaction != Faction.UNCLAIMED) {
                        long nSalt = getFactionSalt(nFaction);
                        int[] nCenter = getCanonicalCellCenter(worldSeed, nCellX, nCellZ, separation, nSalt);
                        int chebyshev = Math.max(Math.abs(c[0] - nCenter[0]), Math.abs(c[1] - nCenter[1]));
                        if (chebyshev < 8) {
                            tooClose = true;
                            break;
                        }
                    }
                }
                if (tooClose) break;
            }
            if (!tooClose) {
                clearedCandidates.add(c);
            }
        }

        if (!clearedCandidates.isEmpty()) {
            validSpawnCandidates = clearedCandidates;
        }

        if (validSpawnCandidates.isEmpty()) {
            CellClusterInfo info = new CellClusterInfo(fallbackCenter[0], fallbackCenter[1], 0, cellFaction);
            cellClusterCache.put(cacheKey, info);
            return info;
        }

        // Helper to score candidate center region for dryness and flatness in O(1)
        java.util.function.ToIntFunction<int[]> scoreCandidate = c -> {
            int cx = c[0];
            int cz = c[1];
            int aquaticChunks = 0;
            int startBlockX = cx * RegionData.REGION_SIZE_BLOCKS;
            int startBlockZ = cz * RegionData.REGION_SIZE_BLOCKS;

            for (int chunkX = 2; chunkX <= 5; chunkX++) {
                for (int chunkZ = 2; chunkZ <= 5; chunkZ++) {
                    int sampleX = startBlockX + (chunkX * 16) + 8;
                    int sampleZ = startBlockZ + (chunkZ * 16) + 8;
                    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = ProceduralRegionGenerator.sampleBiomeFast(level, sampleX, 64, sampleZ);
                    if (ProceduralRegionGenerator.isOceanBiome(biome) || ProceduralRegionGenerator.isRiverBiome(biome) || ProceduralRegionGenerator.isBeachBiome(biome)) {
                        aquaticChunks++;
                    }
                }
            }

            int centerBlockX = startBlockX + 64;
            int centerBlockZ = startBlockZ + 64;
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> centerBiome = ProceduralRegionGenerator.sampleBiomeFast(level, centerBlockX, 64, centerBlockZ);
            com.warfront.region.terrain.TerrainType t = com.warfront.region.terrain.TerrainType.fromBiome(centerBiome);
            int terrainBonus = switch (t) {
                case PLAINS, SAVANNA, DESERT -> 20;
                case HILLS, BADLANDS -> 5;
                case SWAMP, JUNGLE -> -10;
                case MOUNTAIN, TUNDRA -> -30;
                default -> 0;
            };

            // Fast O(1) regional dryness scoring in MD <= 2 (pure multi-noise, zero 3D noise router getBaseHeight calls)
            int dryNeighbors = 0;
            int waterNeighbors = 0;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (Math.abs(dx) + Math.abs(dz) <= 2) {
                        if (ProceduralRegionGenerator.getInstance().biomeAvailableForBase(level, cx + dx, cz + dz)) {
                            dryNeighbors++;
                        } else {
                            waterNeighbors++;
                        }
                    }
                }
            }
            int neighborScore = (dryNeighbors * 10) - (waterNeighbors * 100);

            return - (aquaticChunks * 50) + terrainBonus + neighborScore;
        };

        int finalSize = 0;
        int[] chosenCenter = validSpawnCandidates.get(0);

        // Candidate center selection relying purely on strict O(1) footprint validation
        if (rolledTier == 3) {
            int bestScore = Integer.MIN_VALUE;
            int[] bestMega = null;
            for (int[] c : validSpawnCandidates) {
                if (isValidMegaBaseLocation(level, c[0], c[1])) {
                    int sc = scoreCandidate.applyAsInt(c);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestMega = c;
                    }
                }
            }
            if (bestMega != null) {
                chosenCenter = bestMega;
                finalSize = 3;
            } else {
                finalSize = 0; // Unviable ocean/island cell becomes wilderness; never downgrades to HQ
            }
        } else if (rolledTier == 2) {
            int bestScore = Integer.MIN_VALUE;
            int[] bestBig = null;
            for (int[] c : validSpawnCandidates) {
                if (isValidBigBaseLocation(level, c[0], c[1])) {
                    int sc = scoreCandidate.applyAsInt(c);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestBig = c;
                    }
                }
            }
            if (bestBig != null) {
                chosenCenter = bestBig;
                finalSize = 2;
            } else {
                finalSize = 0;
            }
        } else if (rolledTier == 1) {
            int bestScore = Integer.MIN_VALUE;
            int[] bestSmall = null;
            for (int[] c : validSpawnCandidates) {
                if (isValidSmallBaseLocation(level, c[0], c[1])) {
                    int sc = scoreCandidate.applyAsInt(c);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestSmall = c;
                    }
                }
            }
            if (bestSmall != null) {
                chosenCenter = bestSmall;
                finalSize = 1;
            } else {
                finalSize = 0;
            }
        }

        CellClusterInfo info = new CellClusterInfo(chosenCenter[0], chosenCenter[1], finalSize, cellFaction);
        cellClusterCache.put(cacheKey, info);
        return info;
    }

    private Optional<RegionData.RegionState> generateRegionInternal(ServerLevel level, long worldSeed, int regionX,
            int regionZ, boolean calculateStrength) {
        int separation = getSeparation();
        int baseCellX = Math.floorDiv(regionX, separation);
        int baseCellZ = Math.floorDiv(regionZ, separation);

        int bufferDistance = 3;

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

                CellClusterInfo clusterInfo = getOptimalCellCluster(level, worldSeed, cellX, cellZ, separation);
                if (clusterInfo.clusterSize() <= 0) {
                    continue;
                }

                int cx = clusterInfo.cx();
                int cz = clusterInfo.cz();
                Faction cellFaction = clusterInfo.faction();
                int clusterSize = clusterInfo.clusterSize();

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

        // Check if too close to any rival faction cluster (Option 1: Strict >= 2 region DMZ buffer using Chebyshev metric)
        int dmzBuffer = Math.max(2, bufferDistance);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nCellX = baseCellX + dx;
                int nCellZ = baseCellZ + dz;
                CellClusterInfo nInfo = getOptimalCellCluster(level, worldSeed, nCellX, nCellZ, separation);
                if (nInfo != null && nInfo.clusterSize() > 0 && nInfo.faction() != this.faction && nInfo.faction() != Faction.UNCLAIMED) {
                    int rivalRadius = Math.max(2, nInfo.clusterSize());
                    int chebyshevToRival = Math.max(Math.abs(regionX - nInfo.cx()), Math.abs(regionZ - nInfo.cz()));
                    if (chebyshevToRival <= rivalRadius + dmzBuffer) {
                        return Optional.empty(); // Enforce strict >= 2 region neutral DMZ buffer!
                    }
                }
            }
        }

        int relX = regionX - bestCenterX;
        int relZ = regionZ - bestCenterZ;

        ProceduralRegionGenerator regionGen = ProceduralRegionGenerator.getInstance();

        if (bestClusterSize == 3) {
            if (bestDistance <= 2) {
                if (bestDistance == 0) {
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.MEGA_BASE, bestClusterId, null));
                    }
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.MEGA_BASE);
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, res.baseType(), bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), res.baseType(), bestClusterId, res.anchor()));
                } else if (bestDistance == 1 && (relX == 0 || relZ == 0)) {
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.HEADQUARTERS, bestClusterId, null));
                    }
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.HEADQUARTERS);
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, res.baseType(), bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), res.baseType(), bestClusterId, res.anchor()));
                }
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, bestClusterId, null));
                }
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, bestClusterId, null));
            } else if (bestDistance == 3) {
                boolean isCardinalTip = (relX == 0 || relZ == 0);
                if (isCardinalTip) {
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.OUTPOST, bestClusterId, null));
                    }
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.OUTPOST);
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, res.baseType(), bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), res.baseType(), bestClusterId, res.anchor()));
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
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.HEADQUARTERS, bestClusterId, null));
                }
                ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.HEADQUARTERS);
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, res.baseType(), bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), res.baseType(), bestClusterId, res.anchor()));
            } else if (bestDistance == 1) {
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, bestClusterId, null));
                }
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, bestClusterId, null));
            } else if (bestDistance == 2) {
                boolean isCardinalTip = (relX == 0 || relZ == 0);
                if (isCardinalTip) {
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.OUTPOST, bestClusterId, null));
                    }
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.OUTPOST);
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, res.baseType(), bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), res.baseType(), bestClusterId, res.anchor()));
                } else {
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, bestClusterId, null));
                    }
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, bestClusterId, null));
                }
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
            // Small Base (Size 1): Core Outpost at center (bestDistance == 0) + surrounding territory (bestDistance <= 2)
            if (bestDistance <= 2) {
                if (bestDistance == 0) {
                    if (!calculateStrength) {
                        return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.OUTPOST, bestClusterId, null));
                    }
                    ProceduralRegionGenerator.BasePlacementResult res = regionGen.resolveBasePlacement(level, worldSeed, regionX, regionZ, BaseType.OUTPOST);
                    com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                            com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, res.baseType(), bestClusterId, worldSeed);
                    return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), res.baseType(), bestClusterId, res.anchor()));
                }
                if (!calculateStrength) {
                    return Optional.of(new RegionData.RegionState(faction, 0.0F, 0.0F, BaseType.NONE, bestClusterId, null));
                }
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, BaseType.NONE, bestClusterId, worldSeed);
                return Optional.of(new RegionData.RegionState(faction, strength.stability(), strength.resistance(), BaseType.NONE, bestClusterId, null));
            }
        }

        return Optional.empty();
    }

    private final java.util.Map<Long, Boolean> smallBaseValidityCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, Boolean> bigBaseValidityCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, Boolean> megaBaseValidityCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int[][] CARDINAL_OFFSETS = new int[][] {
            { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 }
    };

    public void clearValidityCaches() {
        cellClusterCache.clear();
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

        // 2. Count valid land regions in MD <= 2 domain (at least 3 out of 13)
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

        boolean result = landCount >= 3;
        smallBaseValidityCache.put(key, result);
        return result;
    }

    public static boolean isStrictlyValidMegaBaseFootprint(ServerLevel level, int regionX, int regionZ) {
        if (level == null) return true;
        int startBlockX = regionX * RegionData.REGION_SIZE_BLOCKS;
        int startBlockZ = regionZ * RegionData.REGION_SIZE_BLOCKS;

        // 1. Center check: must be dry land (veto ocean, river, beach)
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> centerBiome =
                ProceduralRegionGenerator.sampleBiomeFast(level, startBlockX + 64, 64, startBlockZ + 64);
        if (ProceduralRegionGenerator.isOceanBiome(centerBiome) ||
                ProceduralRegionGenerator.isRiverBiome(centerBiome) ||
                ProceduralRegionGenerator.isBeachBiome(centerBiome)) {
            return false;
        }

        // 2. Footprint 4 Corners Check (Mega Base footprint: [14..114] in X and Z)
        int minX = startBlockX + 14;
        int maxX = startBlockX + 114;
        int minZ = startBlockZ + 14;
        int maxZ = startBlockZ + 114;

        int[][] corners = new int[][] {
                { minX, minZ }, { maxX, minZ }, { minX, maxZ }, { maxX, maxZ }
        };
        int cornerWater = 0;
        for (int[] c : corners) {
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b =
                    ProceduralRegionGenerator.sampleBiomeFast(level, c[0], 64, c[1]);
            if (ProceduralRegionGenerator.isOceanBiome(b) || ProceduralRegionGenerator.isBeachBiome(b)) {
                return false; // Zero ocean or beach at corners
            }
            if (ProceduralRegionGenerator.isRiverBiome(b)) {
                cornerWater++;
            }
        }
        if (cornerWater >= 2) {
            return false; // At most 1 corner river (narrow river bend); >= 2 corners river/water vetoes
        }

        // 3. Footprint 4 Edge Midpoints Check
        int[][] edgeMidpoints = new int[][] {
                { startBlockX + 64, minZ },
                { startBlockX + 64, maxZ },
                { minX, startBlockZ + 64 },
                { maxX, startBlockZ + 64 }
        };
        for (int[] m : edgeMidpoints) {
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b =
                    ProceduralRegionGenerator.sampleBiomeFast(level, m[0], 64, m[1]);
            if (ProceduralRegionGenerator.isOceanBiome(b) || ProceduralRegionGenerator.isBeachBiome(b)) {
                return false;
            }
        }

        // 4. Interior grid sampling: 16 points across the 100x100 footprint (step 25 blocks)
        int waterSamples = cornerWater;
        int totalSamples = 4;
        for (int ox = 14; ox <= 114; ox += 25) {
            for (int oz = 14; oz <= 114; oz += 25) {
                totalSamples++;
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b =
                        ProceduralRegionGenerator.sampleBiomeFast(level, startBlockX + ox, 64, startBlockZ + oz);
                if (ProceduralRegionGenerator.isOceanBiome(b)) {
                    return false; // Zero ocean inside footprint
                }
                if (ProceduralRegionGenerator.isRiverBiome(b) || ProceduralRegionGenerator.isBeachBiome(b)) {
                    waterSamples++;
                }
            }
        }

        // Water ratio must be <= 12.5% (max 2 river samples across footprint)
        return ((double) waterSamples / totalSamples) <= 0.125;
    }

    private boolean isValidMegaBaseLocation(ServerLevel level, int centerX, int centerZ) {
        if (level == null)
            return true;

        long key = net.minecraft.world.level.ChunkPos.asLong(centerX, centerZ);
        Boolean cached = megaBaseValidityCache.get(key);
        if (cached != null)
            return cached;

        // 1. Strict O(1) multi-noise footprint validation for the 100x100 Mega Base
        if (!isStrictlyValidMegaBaseFootprint(level, centerX, centerZ)) {
            megaBaseValidityCache.put(key, false);
            return false;
        }

        ProceduralRegionGenerator gen = ProceduralRegionGenerator.getInstance();

        // 2. Non-Expendable Core Invariant: ALL 4 cardinal inner regions (MD = 1) MUST be valid dry land!
        // For Mega Base clusters, cardinal MD = 1 regions host Headquarters; they cannot be in water.
        for (int[] off : CARDINAL_OFFSETS) {
            if (!gen.biomeAvailableForBase(level, centerX + off[0], centerZ + off[1])) {
                megaBaseValidityCache.put(key, false);
                return false;
            }
        }

        // 3. Count valid land regions in MD <= 3 domain (at least 14 out of 25)
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

        boolean result = landCount >= 14;
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

        // Center block must not be aquatic
        int startBlockX = centerX * RegionData.REGION_SIZE_BLOCKS;
        int startBlockZ = centerZ * RegionData.REGION_SIZE_BLOCKS;
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> centerBiome =
                ProceduralRegionGenerator.sampleBiomeFast(level, startBlockX + 64, 64, startBlockZ + 64);
        if (ProceduralRegionGenerator.isOceanBiome(centerBiome) ||
                ProceduralRegionGenerator.isRiverBiome(centerBiome) ||
                ProceduralRegionGenerator.isBeachBiome(centerBiome)) {
            bigBaseValidityCache.put(key, false);
            return false;
        }

        // 2. Non-Expendable Core Invariant: ALL 4 cardinal inner regions (MD = 1) MUST be valid dry land!
        for (int[] off : CARDINAL_OFFSETS) {
            if (!gen.biomeAvailableForBase(level, centerX + off[0], centerZ + off[1])) {
                bigBaseValidityCache.put(key, false);
                return false;
            }
        }

        // 3. Count valid land regions in MD <= 2 domain (at least 7 out of 13)
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
