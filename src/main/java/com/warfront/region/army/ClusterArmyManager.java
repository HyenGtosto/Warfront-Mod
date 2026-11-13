package com.warfront.region.army;

import com.warfront.Warfront;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.generator.ProceduralRegionGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

/**
 * Manages the lifecycle, HQ-exclusive initialization, interval movement,
 * pocket isolation, and decapitation of Cluster Mobile Field Armies.
 */
public final class ClusterArmyManager {
    private static final int[][] CARDINAL_OFFSETS = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
    private static final int MAX_BFS_DEPTH_REGIONS = 48;

    /**
     * All 6 possible combinations of choosing 2 HQs out of the 4 cardinal HQs
     * surrounding a procedural Mega Base.
     * Cardinal indices: 0 = North (0, -1), 1 = South (0, 1), 2 = West (-1, 0), 3 = East (1, 0).
     */
    private static final int[][] MEGA_BASE_HQ_PAIRS = new int[][] {
            { 0, 1 }, // North & South
            { 0, 2 }, // North & West
            { 0, 3 }, // North & East
            { 1, 2 }, // South & West
            { 1, 3 }, // South & East
            { 2, 3 }  // West & East
    };

    private ClusterArmyManager() {
    }

    /**
     * Checks if the specified region is a BaseType.HEADQUARTERS belonging to an AI faction
     * and, if not yet initialized, spawns a linked mobile army on a connected BaseType.NONE
     * region within the same cluster.
     * Strictly linked ONLY to HEADQUARTERS (never OUTPOST, never MEGA_BASE).
     */
    public static boolean ensureHqArmyInitialized(ServerLevel level, RegionData regions, int hqRX, int hqRZ, RegionData.RegionState hqState) {
        if (hqState == null || hqState.owner() == null || !hqState.owner().isAI()) {
            return false;
        }
        // Strictly linked ONLY to HEADQUARTERS (not OUTPOST, not MEGA_BASE)
        if (hqState.baseType() != BaseType.HEADQUARTERS) {
            return false;
        }

        long hqKey = ChunkPos.asLong(hqRX, hqRZ);
        if (regions.isHqArmyInitialized(hqKey)) {
            return false;
        }

        Faction faction = hqState.owner();

        // Mega Base cluster army balance: only 2 deterministically selected HQs out of the 4 cardinal
        // procedural HQs field an army. Upgraded outposts and Big Base HQs are never adjacent to a Mega Base.
        if (!shouldHqFieldArmy(level, hqRX, hqRZ, faction)) {
            regions.markHqArmyInitialized(hqKey);
            Warfront.LOGGER.info("[Warfront Army] HQ at ({}, {}) in Mega Base cluster was not chosen to field an army (2-army cap). Skipping.",
                    hqRX, hqRZ);
            return false;
        }

        long clusterId = hqState.clusterId();
        if (clusterId == 0L) {
            clusterId = regions.findAdjacentClusterId(hqRX, hqRZ, faction);
            if (clusterId == 0L) {
                clusterId = hqKey;
            }
        }

        // Find all cardinal-connected regions in the same cluster starting from the HQ
        List<Long> validSpawnCandidates = findConnectedNoneRegionsFromHQ(level, regions, hqRX, hqRZ, faction, clusterId);
        if (validSpawnCandidates.isEmpty()) {
            // Mark initialized only once we actually evaluate; if no NONE region exists in the cluster, mark so we don't spam BFS
            regions.markHqArmyInitialized(hqKey);
            return false;
        }

        // Deterministically pick initial housing region and army name from world seed + HQ coordinates
        long seed = (level != null) ? level.getSeed() : 0L;
        long mix = splitMix64(seed ^ (hqRX * 73856093L) ^ (hqRZ * 19349663L) ^ 0xA4319F5E3C2B1A0DL);

        // Prefer frontline candidates (adjacent to non-friendly territory) for initial placement
        List<Long> frontlineCandidates = new ArrayList<>();
        for (long candKey : validSpawnCandidates) {
            int cx = ChunkPos.getX(candKey);
            int cz = ChunkPos.getZ(candKey);
            if (isFrontlineRegion(level, regions, cx, cz, faction)) {
                frontlineCandidates.add(candKey);
            }
        }

        List<Long> pool = frontlineCandidates.isEmpty() ? validSpawnCandidates : frontlineCandidates;
        int chosenIdx = Math.floorMod((int) mix, pool.size());
        long chosenRegionKey = pool.get(chosenIdx);
        int spawnRX = ChunkPos.getX(chosenRegionKey);
        int spawnRZ = ChunkPos.getZ(chosenRegionKey);

        // Cluster-scoped unique nomenclature: prevent duplicate army names within the same cluster
        Set<Integer> usedZombieStrains = new HashSet<>();
        Set<Integer> usedPillagerOrdinals = new HashSet<>();
        Set<Integer> usedPillagerEpithets = new HashSet<>();
        for (ClusterArmy existingArmy : regions.getAllClusterArmies()) {
            if (existingArmy.clusterId() == clusterId && existingArmy.faction() == faction) {
                if (faction == Faction.ZOMBIE_HORDE) {
                    usedZombieStrains.add(Math.floorMod(existingArmy.epithetIndex(), ClusterArmy.ZOMBIE_STRAIN_COUNT));
                } else {
                    usedPillagerOrdinals.add(existingArmy.ordinalIndex());
                    usedPillagerEpithets.add(existingArmy.epithetIndex());
                }
            }
        }

        int ordinalIndex;
        int epithetIndex;

        if (faction == Faction.ZOMBIE_HORDE) {
            int baseStrain = Math.floorMod((int) (mix >>> 32), ClusterArmy.ZOMBIE_STRAIN_COUNT);
            int chosenStrain = baseStrain;
            for (int i = 0; i < ClusterArmy.ZOMBIE_STRAIN_COUNT; i++) {
                int candStrain = (baseStrain + i) % ClusterArmy.ZOMBIE_STRAIN_COUNT;
                if (!usedZombieStrains.contains(candStrain)) {
                    chosenStrain = candStrain;
                    break;
                }
            }
            ordinalIndex = 1;
            epithetIndex = chosenStrain;
        } else {
            int baseOrdinal = Math.floorMod((int) (mix >>> 16), ClusterArmy.PILLAGER_ORDINAL_COUNT) + 1;
            int totalEpithets = ClusterArmy.PILLAGER_EPITHET_COUNT * ClusterArmy.PILLAGER_UNIT_TYPE_COUNT;
            int baseEpithet = Math.floorMod((int) (mix >>> 32), totalEpithets);

            int chosenOrdinal = baseOrdinal;
            for (int i = 0; i < ClusterArmy.PILLAGER_ORDINAL_COUNT; i++) {
                int candOrdinal = ((baseOrdinal - 1 + i) % ClusterArmy.PILLAGER_ORDINAL_COUNT) + 1;
                if (!usedPillagerOrdinals.contains(candOrdinal)) {
                    chosenOrdinal = candOrdinal;
                    break;
                }
            }

            int chosenEpithet = baseEpithet;
            for (int i = 0; i < totalEpithets; i++) {
                int candEpithet = (baseEpithet + i) % totalEpithets;
                if (!usedPillagerEpithets.contains(candEpithet)) {
                    chosenEpithet = candEpithet;
                    break;
                }
            }

            ordinalIndex = chosenOrdinal;
            epithetIndex = chosenEpithet;
        }

        ClusterArmy army = new ClusterArmy(
                hqKey,
                clusterId,
                faction,
                spawnRX,
                spawnRZ,
                ordinalIndex,
                epithetIndex,
                false
        );

        regions.markHqArmyInitialized(hqKey);
        regions.putClusterArmy(army);
        ProceduralRegionGenerator.getInstance().invalidateFullStateCacheAround(spawnRX, spawnRZ);

        Warfront.LOGGER.info("[Warfront Army] Initialized {} '{}' at Region ({}, {}) linked to HQ ({}, {}) in cluster {}.",
                faction.commandName(), army.getDisplayName().getString(), spawnRX, spawnRZ, hqRX, hqRZ, clusterId);
        return true;
    }

    /**
     * Checks if this HQ is one of the 4 procedural HQs surrounding a Mega Base,
     * and if so, whether it is one of the 2 deterministically selected to field an army.
     *
     * HQs upgraded from Outposts are at distance 3 (or in non-mega clusters) and are never
     * cardinally adjacent to a Mega Base, so they return true.
     * Big Base HQs have no Mega Base in their cluster, so they return true.
     */
    public static boolean shouldHqFieldArmy(ServerLevel level, int hqRX, int hqRZ, Faction faction) {
        if (faction == null || !faction.isAI()) {
            return false;
        }
        long seed = (level != null) ? level.getSeed() : 0L;
        ProceduralRegionGenerator generator = ProceduralRegionGenerator.getInstance();

        int megaX = Integer.MIN_VALUE;
        int megaZ = Integer.MIN_VALUE;

        for (int[] offset : CARDINAL_OFFSETS) {
            int nx = hqRX + offset[0];
            int nz = hqRZ + offset[1];
            RegionData.RegionState rawState = generator.generateRawRegionState(level, seed, nx, nz);
            if (rawState.owner() == faction && rawState.baseType() == BaseType.MEGA_BASE) {
                megaX = nx;
                megaZ = nz;
                break;
            }
        }

        // If no cardinal neighbor is procedurally a MEGA_BASE, this HQ is either in a Big Base
        // cluster (clusterSize == 2) or an Outpost upgraded to an HQ (bestDistance >= 3).
        if (megaX == Integer.MIN_VALUE) {
            return true;
        }

        // Deterministically select 2 out of the 4 surrounding HQs based on world seed + Mega Base coordinates
        long megaHash = splitMix64(seed ^ (megaX * 73856093L) ^ (megaZ * 19349663L) ^ 0x517CC1B727220A95L);
        int pairIndex = Math.floorMod((int) megaHash, 6);
        int[] chosenPair = MEGA_BASE_HQ_PAIRS[pairIndex];

        // Determine this HQ's index among the 4 cardinal directions relative to the Mega Base:
        // 0 = North (0, -1), 1 = South (0, 1), 2 = West (-1, 0), 3 = East (1, 0)
        int hqIndex;
        if (hqRX == megaX && hqRZ == megaZ - 1) {
            hqIndex = 0; // North
        } else if (hqRX == megaX && hqRZ == megaZ + 1) {
            hqIndex = 1; // South
        } else if (hqRX == megaX - 1 && hqRZ == megaZ) {
            hqIndex = 2; // West
        } else if (hqRX == megaX + 1 && hqRZ == megaZ) {
            hqIndex = 3; // East
        } else {
            return true;
        }

        return (hqIndex == chosenPair[0] || hqIndex == chosenPair[1]);
    }

    /**
     * Evaluates and moves all active Cluster Mobile Field Armies one step along their
     * contiguous friendly cluster territory.
     * Respects siege locks, base-tile avoidance (armies can only rest on BaseType.NONE),
     * and pocket immobilization when severed from friendly territory.
     */
    public static boolean tickClusterArmies(ServerLevel level, RegionData regions, Random random) {
        List<ClusterArmy> armies = new ArrayList<>(regions.getAllClusterArmies());
        if (armies.isEmpty()) {
            return false;
        }

        boolean anyMoved = false;
        for (ClusterArmy army : armies) {
            int curX = army.currentRegionX();
            int curZ = army.currentRegionZ();
            long curKey = army.currentRegionKey();

            // Verify current region is still owned by the army's faction and is BaseType.NONE
            RegionData.RegionState curState = regions.getRawOrSavedRegionState(curX, curZ);
            if (curState.owner() != army.faction()) {
                // Region was lost -> army destroyed
                regions.removeClusterArmy(army.parentHqKey());
                ProceduralRegionGenerator.getInstance().invalidateFullStateCacheAround(curX, curZ);
                anyMoved = true;
                continue;
            }

            // Check if parent HQ was destroyed or captured
            int hqX = army.parentHqX();
            int hqZ = army.parentHqZ();
            RegionData.RegionState hqState = regions.getRawOrSavedRegionState(hqX, hqZ);
            boolean hqLost = (hqState.owner() != army.faction() || hqState.baseType() != BaseType.HEADQUARTERS);
            if (hqLost && !army.hqDestroyed()) {
                army = army.withHqDestroyed(true);
                regions.putClusterArmy(army);
                ProceduralRegionGenerator.getInstance().invalidateFullStateCacheAround(curX, curZ);
                anyMoved = true;
            }

            // Mega Base 2-army cap enforcement: prune excess armies spawned under previous logic
            if (!shouldHqFieldArmy(level, hqX, hqZ, army.faction())) {
                regions.removeClusterArmy(army.parentHqKey());
                ProceduralRegionGenerator.getInstance().invalidateFullStateCacheAround(curX, curZ);
                anyMoved = true;
                Warfront.LOGGER.info("[Warfront Army] Pruned excess Army '{}' from unselected Mega Base HQ ({}, {}).",
                        army.getDisplayName().getString(), hqX, hqZ);
                continue;
            }

            // If current region somehow upgraded to a base (e.g. Outpost/HQ), immediately relocate to a connected NONE region if possible
            boolean mustEvacuateBase = (curState.baseType() != BaseType.NONE);

            // Siege Lock: Do not move if the army's current region is under siege or actively staging an attack
            if (!mustEvacuateBase && isRegionLockedBySiege(regions, curX, curZ)) {
                continue;
            }

            // Determine valid adjacent (or base-bridged) BaseType.NONE steps within the connected cluster component
            List<Long> stepCandidates = findValidNextStepRegions(level, regions, army);
            if (stepCandidates.isEmpty()) {
                // Pocketed / immobilized: no valid connected BaseType.NONE region to move into
                continue;
            }

            // Score candidates: strongly prefer frontline regions (bordering Humanity or non-friendly territory)
            List<Long> weightedPool = new ArrayList<>();
            for (long candKey : stepCandidates) {
                int nx = ChunkPos.getX(candKey);
                int nz = ChunkPos.getZ(candKey);
                int weight = 1;
                if ( bordersHumanity(level, regions, nx, nz)) {
                    weight = 6;
                } else if (isFrontlineRegion(level, regions, nx, nz, army.faction())) {
                    weight = 3;
                }
                for (int w = 0; w < weight; w++) {
                    weightedPool.add(candKey);
                }
            }

            long nextKey = weightedPool.get(random.nextInt(weightedPool.size()));
            int nextX = ChunkPos.getX(nextKey);
            int nextZ = ChunkPos.getZ(nextKey);

            if (nextX != curX || nextZ != curZ) {
                ClusterArmy movedArmy = army.withPosition(nextX, nextZ);
                regions.putClusterArmy(movedArmy);
                ProceduralRegionGenerator.getInstance().invalidateFullStateCacheAround(curX, curZ);
                ProceduralRegionGenerator.getInstance().invalidateFullStateCacheAround(nextX, nextZ);
                anyMoved = true;
            }
        }

        return anyMoved;
    }

    /**
     * Checks whether a region is currently under siege or serving as an attacking source vector in an active siege.
     */
    public static boolean isRegionLockedBySiege(RegionData regions, int rx, int rz) {
        long key = ChunkPos.asLong(rx, rz);
        if (regions.getActiveSieges().containsKey(key)) {
            return true;
        }
        for (RegionData.SiegeCampaign campaign : regions.getActiveSieges().values()) {
            if (campaign.sources() != null) {
                for (RegionData.SourcePos src : campaign.sources()) {
                    if (src.x() == rx && src.z() == rz) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Finds valid next-step BaseType.NONE regions for an army.
     * An army can step directly to a 4-cardinal neighbor (dist 1) if it is BaseType.NONE,
     * OR traverse across an adjacent friendly base tile (dist 2 where intermediate tile is a friendly base)
     * so armies are never trapped behind HQ/Outpost/Mega Base walls inside their own cluster.
     * If the parent HQ is intact, the destination must remain connected to the parent HQ.
     */
    private static List<Long> findValidNextStepRegions(ServerLevel level, RegionData regions, ClusterArmy army) {
        int curX = army.currentRegionX();
        int curZ = army.currentRegionZ();
        Faction faction = army.faction();
        long clusterId = army.clusterId();

        // First, compute the connected component of same-faction, same-cluster regions from current position
        Set<Long> connectedComponent = getConnectedClusterComponent(level, regions, curX, curZ, faction, clusterId);

        // If parent HQ is still alive, verify whether the army's pocket is connected to its parent HQ
        if (!army.hqDestroyed() && !connectedComponent.contains(army.parentHqKey())) {
            // Severed from parent HQ! Check if the pocket has more than 1 region; if isolated in a single-region pocket, immobilized.
            if (connectedComponent.size() <= 1) {
                return List.of();
            }
        }

        Set<Long> candidateKeys = new HashSet<>();

        for (int[] off1 : CARDINAL_OFFSETS) {
            int n1x = curX + off1[0];
            int n1z = curZ + off1[1];
            long n1Key = ChunkPos.asLong(n1x, n1z);

            if (!connectedComponent.contains(n1Key)) {
                continue;
            }

            RegionData.RegionState n1State = regions.getRawOrSavedRegionState(n1x, n1z);
            if (n1State.owner() != faction) {
                continue;
            }

            if (n1State.baseType() == BaseType.NONE) {
                if (!regions.hasArmyAt(n1x, n1z) && !regions.getActiveSieges().containsKey(n1Key)) {
                    candidateKeys.add(n1Key);
                }
            } else {
                // Intermediate tile is a friendly base (e.g., HQ, Outpost, Mega Base); allow stepping through it to an adjacent NONE tile
                for (int[] off2 : CARDINAL_OFFSETS) {
                    int n2x = n1x + off2[0];
                    int n2z = n1z + off2[1];
                    if (n2x == curX && n2z == curZ) continue;
                    long n2Key = ChunkPos.asLong(n2x, n2z);
                    if (!connectedComponent.contains(n2Key)) continue;

                    RegionData.RegionState n2State = regions.getRawOrSavedRegionState(n2x, n2z);
                    if (n2State.owner() == faction
                            && n2State.baseType() == BaseType.NONE
                            && !regions.hasArmyAt(n2x, n2z)
                            && !regions.getActiveSieges().containsKey(n2Key)) {
                        candidateKeys.add(n2Key);
                    }
                }
            }
        }

        return new ArrayList<>(candidateKeys);
    }

    private static List<Long> findConnectedNoneRegionsFromHQ(ServerLevel level, RegionData regions, int hqRX, int hqRZ, Faction faction, long clusterId) {
        Set<Long> component = getConnectedClusterComponent(level, regions, hqRX, hqRZ, faction, clusterId);
        List<Long> noneRegions = new ArrayList<>();
        for (long key : component) {
            int rx = ChunkPos.getX(key);
            int rz = ChunkPos.getZ(key);
            if (rx == hqRX && rz == hqRZ) continue;
            RegionData.RegionState state = regions.getRawOrSavedRegionState(rx, rz);
            if (state.owner() == faction
                    && state.baseType() == BaseType.NONE
                    && !regions.hasArmyAt(rx, rz)
                    && !regions.getActiveSieges().containsKey(key)) {
                noneRegions.add(key);
            }
        }
        return noneRegions;
    }

    private static Set<Long> getConnectedClusterComponent(ServerLevel level, RegionData regions, int startRX, int startRZ, Faction faction, long clusterId) {
        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        long startKey = ChunkPos.asLong(startRX, startRZ);
        queue.add(startKey);
        visited.add(startKey);

        while (!queue.isEmpty() && visited.size() < MAX_BFS_DEPTH_REGIONS) {
            long curKey = queue.poll();
            int cx = ChunkPos.getX(curKey);
            int cz = ChunkPos.getZ(curKey);

            for (int[] off : CARDINAL_OFFSETS) {
                int nx = cx + off[0];
                int nz = cz + off[1];
                long nKey = ChunkPos.asLong(nx, nz);
                if (!visited.contains(nKey)) {
                    RegionData.RegionState nState = regions.getRawOrSavedRegionState(nx, nz);
                    if (nState.owner() == faction && (clusterId == 0L || nState.clusterId() == clusterId)) {
                        visited.add(nKey);
                        queue.add(nKey);
                    }
                }
            }
        }
        return visited;
    }

    private static boolean isFrontlineRegion(ServerLevel level, RegionData regions, int rx, int rz, Faction faction) {
        for (int[] off : CARDINAL_OFFSETS) {
            int nx = rx + off[0];
            int nz = rz + off[1];
            RegionData.RegionState neighbor = regions.getRawOrSavedRegionState(nx, nz);
            if (neighbor.owner() != faction) {
                return true;
            }
        }
        return false;
    }

    private static boolean bordersHumanity(ServerLevel level, RegionData regions, int rx, int rz) {
        for (int[] off : CARDINAL_OFFSETS) {
            int nx = rx + off[0];
            int nz = rz + off[1];
            RegionData.RegionState neighbor = regions.getRawOrSavedRegionState(nx, nz);
            if (neighbor.owner() == Faction.HUMANITY) {
                return true;
            }
        }
        return false;
    }

    private static long splitMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
