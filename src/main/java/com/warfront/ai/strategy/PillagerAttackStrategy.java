package com.warfront.ai.strategy;

import com.warfront.region.Faction;
import com.warfront.region.RegionData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pillager Faction Attack Strategy: 3-Tier Priority & 90° Front-Filling Geometry.
 *
 * Tier Structure:
 * - Tier 1: Direct attacks targeting HUMANITY-owned regions/sub-regions (Exclusive Priority).
 * - Tier 2: Player-directed 90° sector frontline expansion with gap-filling & corridor-tip prevention.
 * - Tier 3: Normal faction behavior (enemy-vs-enemy / wilderness expansion).
 */
public class PillagerAttackStrategy implements FactionAttackStrategy {

    private static RegionData.RegionState getRawOrSavedRegionState(AttackContext context, int rx, int rz) {
        RegionData.RegionState state = context.regions().getSavedRegionState(rx, rz);
        if (state != null) {
            return state;
        }
        net.minecraft.server.level.ServerLevel level = context.level();
        long seed = (level != null) ? level.getSeed() : 0L;
        return com.warfront.region.generator.ProceduralRegionGenerator.getInstance().generateRawRegionState(level, seed, rx, rz);
    }

    private static RegionData.RegionState getRawOrSavedRegionState(RegionData regions, int rx, int rz) {
        RegionData.RegionState state = regions.getSavedRegionState(rx, rz);
        if (state != null) {
            return state;
        }
        net.minecraft.server.level.ServerLevel level = regions.getLevel();
        long seed = (level != null) ? level.getSeed() : 0L;
        return com.warfront.region.generator.ProceduralRegionGenerator.getInstance().generateRawRegionState(level, seed, rx, rz);
    }

    @Override
    public Optional<AttackTarget> chooseAttackTarget(Faction faction, AttackContext context, List<AttackCandidate> validCandidates) {
        if (validCandidates == null || validCandidates.isEmpty()) {
            return Optional.empty();
        }

        long startNano = System.nanoTime();
        RegionData regions = context.regions();

        // 1. Filter out candidates from stranded/cut-off components (no connected HQ)
        List<AttackCandidate> connectedCandidates = new ArrayList<>();
        for (AttackCandidate candidate : validCandidates) {
            Optional<HQPos> connectedHQ = StrategicConnectivityHelper.findConnectedHQ(
                    regions, candidate.sourceRegionX(), candidate.sourceRegionZ(), Faction.PILLAGER_CONQUERORS, context.evaluationCache());
            if (connectedHQ.isPresent()) {
                connectedCandidates.add(candidate);
            }
        }

        if (connectedCandidates.isEmpty()) {
            return Optional.empty();
        }

        // Partition candidates into priority tiers
        List<ScoredCandidate> tier1Frontline = new ArrayList<>();
        List<ScoredCandidate> tier2Fallback = new ArrayList<>();

        for (AttackCandidate candidate : connectedCandidates) {
            Optional<HQPos> connectedHQ = StrategicConnectivityHelper.findConnectedHQ(
                    regions, candidate.sourceRegionX(), candidate.sourceRegionZ(), Faction.PILLAGER_CONQUERORS, context.evaluationCache());
            HQPos hq = connectedHQ.get();
            int originX = hq.x();
            int originZ = hq.z();

            // Find closest claimed HUMANITY region (returns null if no claimed Humanity regions exist)
            HQPos humanityTarget = StrategicConnectivityHelper.findClosestHumanityRegion(originX, originZ, regions);

            double px = humanityTarget != null ? humanityTarget.x() : originX;
            double pz = humanityTarget != null ? humanityTarget.z() : originZ + 1.0D;

            double mainVecX = px - originX;
            double mainVecZ = pz - originZ;
            double mainAngle = Math.atan2(mainVecZ, mainVecX);

            int targetX = candidate.targetRegionX();
            int targetZ = candidate.targetRegionZ();
            double tvX = targetX - originX;
            double tvZ = targetZ - originZ;
            double targetDist = Math.hypot(tvX, tvZ);
            double targetAngle = Math.atan2(tvZ, tvX);

            double angleDiff = Math.abs(normalizeAngle(targetAngle - mainAngle));
            double halfArc = Math.PI / 4.0D; // 45 degrees (90-degree total arc)

            boolean isDirectHumanityAttack = getRawOrSavedRegionState(context, targetX, targetZ).owner() == Faction.HUMANITY;
            boolean isInsideSector = angleDiff <= halfArc;

            int pillagerNeighbors = countPillagerNeighbors(context, targetX, targetZ);

            // 1. Sector Arc Score (Alignment with central sector axis)
            double arcScore;
            if (isInsideSector) {
                arcScore = 30.0D * (1.0D - (angleDiff / halfArc));
            } else {
                arcScore = Math.max(0.1D, 5.0D - 10.0D * (angleDiff - halfArc));
            }

            long clusterId = getRawOrSavedRegionState(context, candidate.sourceRegionX(), candidate.sourceRegionZ()).clusterId();

            // 2. Explicit Radial-Depth Front Completion Metric & Per-Cluster Sector Density Transition
            double avgClusterDist = calculateAverageClusterDistance(context, candidate.sourceRegionX(), candidate.sourceRegionZ(), originX, originZ, clusterId);
            double sectorDensity = calculateSectorDensity(context, originX, originZ, mainAngle, halfArc, avgClusterDist, clusterId);
            boolean isFrontEstablished = sectorDensity >= 0.65D;

            double depthDiff = targetDist - avgClusterDist;

            double frontCompletionBonus = 0.0D;
            double overExtensionPenalty = 0.0D;

            if (depthDiff <= 0.5D) {
                // Front under construction -> Strong gap-filling (+35). Front established -> Shift focus to attack (+10).
                frontCompletionBonus = isFrontEstablished ? 10.0D : 35.0D;
            } else {
                // Candidate pushes beyond current front radius -> PENALTY scaling with distance past front
                overExtensionPenalty = 15.0D * (depthDiff - 0.5D);
            }

            // 3. Multi-flank neighbor support bonus
            double neighborBonus = pillagerNeighbors * 10.0D;

            // 4. Distance to Humanity Region Target
            double distToHumanityTarget = Math.hypot(targetX - px, targetZ - pz);
            double humanityDistanceScore = Math.max(0.1D, 40.0D - distToHumanityTarget);

            // 5. Humanity Target Preference (Elevated to +50 when front is established!)
            double humanityBonus = isDirectHumanityAttack ? (isFrontEstablished ? 50.0D : 15.0D) : 0.0D;

            // 6. Corridor-Tip Penalty (Penalize single-tile spikes extending past cluster average)
            double corridorTipPenalty = (targetDist > avgClusterDist + 1.5D && pillagerNeighbors <= 1) ? 30.0D : 0.0D;

            double finalScore = Math.max(0.1D, arcScore + frontCompletionBonus + humanityDistanceScore + humanityBonus + neighborBonus - overExtensionPenalty - corridorTipPenalty);

            if (isInsideSector) {
                tier1Frontline.add(new ScoredCandidate(candidate, finalScore));
            } else {
                tier2Fallback.add(new ScoredCandidate(candidate, finalScore));
            }
        }

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
        com.warfront.Warfront.LOGGER.info("[PERF AI] Pillager attack scoring completed in {} ms ({} candidates evaluated, 0 strength calculations)",
                elapsedMs, connectedCandidates.size());

        // Tiered Selection: 90° sector candidates execute first!
        if (!tier1Frontline.isEmpty()) {
            AttackCandidate picked = selectWeightedRandom(tier1Frontline, context);
            long cid = getRawOrSavedRegionState(context, picked.sourceRegionX(), picked.sourceRegionZ()).clusterId();
            return Optional.of(picked.toTarget(cid));
        }

        if (!tier2Fallback.isEmpty()) {
            AttackCandidate picked = selectWeightedRandom(tier2Fallback, context);
            long cid = getRawOrSavedRegionState(context, picked.sourceRegionX(), picked.sourceRegionZ()).clusterId();
            return Optional.of(picked.toTarget(cid));
        }

        return Optional.empty();
    }

    private static double calculateSectorDensity(AttackContext context, int originX, int originZ, double mainAngle, double halfArc, double avgClusterDist, long clusterId) {
        int occupied = 0;
        int total = 0;
        int maxR = (int) Math.ceil(avgClusterDist) + 1;
        for (int dx = -maxR; dx <= maxR; dx++) {
            for (int dz = -maxR; dz <= maxR; dz++) {
                if (dx == 0 && dz == 0) continue;
                double dist = Math.hypot(dx, dz);
                if (dist <= avgClusterDist + 0.5D) {
                    double angle = Math.atan2(dz, dx);
                    double diff = Math.abs(normalizeAngle(angle - mainAngle));
                    if (diff <= halfArc) {
                        total++;
                        RegionData.RegionState reg = getRawOrSavedRegionState(context, originX + dx, originZ + dz);
                        if (reg.owner() == Faction.PILLAGER_CONQUERORS && (clusterId == 0L || reg.clusterId() == clusterId)) {
                            occupied++;
                        }
                    }
                }
            }
        }
        return total > 0 ? (double) occupied / total : 1.0D;
    }

    private static double calculateAverageClusterDistance(AttackContext context, int sourceRX, int sourceRZ, int originX, int originZ, long clusterId) {
        int radius = 5;
        double sumDist = 0.0D;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                RegionData.RegionState reg = getRawOrSavedRegionState(context, sourceRX + dx, sourceRZ + dz);
                if (reg.owner() == Faction.PILLAGER_CONQUERORS && (clusterId == 0L || reg.clusterId() == clusterId)) {
                    sumDist += Math.hypot((sourceRX + dx) - originX, (sourceRZ + dz) - originZ);
                    count++;
                }
            }
        }
        return count > 0 ? sumDist / count : Math.hypot(sourceRX - originX, sourceRZ - originZ);
    }

    private static int countPillagerNeighbors(AttackContext context, int rx, int rz) {
        int count = 0;
        int[][] offsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
        for (int[] off : offsets) {
            if (getRawOrSavedRegionState(context, rx + off[0], rz + off[1]).owner() == Faction.PILLAGER_CONQUERORS) {
                count++;
            }
        }
        return count;
    }

    private static double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    private record ScoredCandidate(AttackCandidate candidate, double score) {}

    private static AttackCandidate selectWeightedRandom(List<ScoredCandidate> scored, AttackContext context) {
        double totalWeight = 0.0D;
        for (ScoredCandidate sc : scored) {
            totalWeight += Math.max(0.1D, sc.score());
        }

        double roll = context.random().nextDouble() * totalWeight;
        double accumulated = 0.0D;

        for (ScoredCandidate sc : scored) {
            accumulated += Math.max(0.1D, sc.score());
            if (roll <= accumulated) {
                return sc.candidate();
            }
        }

        return scored.get(scored.size() - 1).candidate();
    }
}
