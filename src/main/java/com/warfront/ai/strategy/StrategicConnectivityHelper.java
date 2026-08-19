package com.warfront.ai.strategy;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

public final class StrategicConnectivityHelper {
    private StrategicConnectivityHelper() {
    }

    private static RegionData.RegionState getRawOrSavedRegionState(RegionData regions, int rx, int rz) {
        RegionData.RegionState state = regions.getSavedRegionState(rx, rz);
        if (state != null) {
            return state;
        }
        ServerLevel level = regions.getLevel();
        long seed = (level != null) ? level.getSeed() : 0L;
        return com.warfront.region.generator.ProceduralRegionGenerator.getInstance().generateRawRegionState(level, seed, rx, rz);
    }

    /**
     * Performs a 4-cardinal BFS over contiguous same-faction territory to locate a connected HQ (HEADQUARTERS or MEGA_BASE).
     * Uses the per-evaluation cycle cache to store results for all regions in the visited component.
     */
    public static Optional<HQPos> findConnectedHQ(RegionData regions, int sourceRX, int sourceRZ, Faction faction, Map<Long, Optional<HQPos>> cache) {
        long startKey = ChunkPos.asLong(sourceRX, sourceRZ);
        if (cache != null && cache.containsKey(startKey)) {
            return cache.get(startKey);
        }

        RegionData.RegionState startReg = getRawOrSavedRegionState(regions, sourceRX, sourceRZ);
        long targetClusterId = startReg.clusterId();

        Queue<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<Long> componentRegionKeys = new ArrayList<>();

        queue.add(startKey);
        visited.add(startKey);

        Optional<HQPos> foundHQ = Optional.empty();
        int[][] cardinalOffsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };

        while (!queue.isEmpty()) {
            long currentKey = queue.poll();
            componentRegionKeys.add(currentKey);

            int rx = ChunkPos.getX(currentKey);
            int rz = ChunkPos.getZ(currentKey);
            RegionData.RegionState reg = getRawOrSavedRegionState(regions, rx, rz);

            if (reg.owner() == faction && (targetClusterId == 0L || reg.clusterId() == targetClusterId)) {
                if (foundHQ.isEmpty() && (reg.baseType() == BaseType.HEADQUARTERS || reg.baseType() == BaseType.MEGA_BASE)) {
                    foundHQ = Optional.of(new HQPos(rx, rz));
                }

                for (int[] offset : cardinalOffsets) {
                    int nrx = rx + offset[0];
                    int nrz = rz + offset[1];
                    long nkey = ChunkPos.asLong(nrx, nrz);

                    if (!visited.contains(nkey)) {
                        visited.add(nkey);
                        RegionData.RegionState nreg = getRawOrSavedRegionState(regions, nrx, nrz);
                        if (nreg.owner() == faction && (targetClusterId == 0L || nreg.clusterId() == targetClusterId)) {
                            queue.add(nkey);
                        }
                    }
                }
            }
        }

        if (foundHQ.isEmpty() && !componentRegionKeys.isEmpty()) {
            foundHQ = Optional.of(new HQPos(sourceRX, sourceRZ));
        }

        // Cache result for every region in this connected component
        if (cache != null) {
            for (long key : componentRegionKeys) {
                cache.put(key, foundHQ);
            }
        }

        return foundHQ;
    }

    /**
     * Finds the nearest claimed HUMANITY region coordinate relative to (originX, originZ).
     * Returns null if no claimed HUMANITY regions exist. Player entity coordinates are NEVER substituted.
     */
    public static HQPos findClosestHumanityRegion(int originX, int originZ, RegionData regions) {
        List<RegionData.Region> humanityRegions = regions.getRegionsOwnedBy(Faction.HUMANITY);

        if (!humanityRegions.isEmpty()) {
            HQPos closest = null;
            double minDistance = Double.MAX_VALUE;
            for (RegionData.Region hr : humanityRegions) {
                double dist = Math.hypot(hr.x() - originX, hr.z() - originZ);
                if (dist < minDistance) {
                    minDistance = dist;
                    closest = new HQPos(hr.x(), hr.z());
                }
            }
            return closest;
        }

        return null;
    }

    /**
     * Alias for backward compatibility that delegates strictly to findClosestHumanityRegion.
     */
    public static HQPos findClosestHumanityRegionOrPlayer(int originX, int originZ, RegionData regions, ServerLevel level) {
        return findClosestHumanityRegion(originX, originZ, regions);
    }
}
