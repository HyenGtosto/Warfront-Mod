package com.warfront.ai.strategy;

import com.warfront.region.Faction;
import com.warfront.region.RegionData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Zombie Faction Attack Strategy: 3-Tier Priority & Forward Horde Push.
 *
 * Tier Structure:
 * - Tier 1: Direct attacks targeting HUMANITY-owned regions/sub-regions (Exclusive Priority).
 * - Tier 2: Player-directed forward push (dist <= 8 regions AND vector dot > 0).
 */
public class ZombieAttackStrategy implements FactionAttackStrategy {

    private static RegionData.RegionState getRawOrSavedRegionState(AttackContext context, int rx, int rz) {
        RegionData.RegionState state = context.regions().getSavedRegionState(rx, rz);
        if (state != null) {
            return state;
        }
        net.minecraft.server.level.ServerLevel level = context.level();
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

        // 1. Filter out candidates from stranded/cut-off components disconnected from a valid Zombie HQ
        List<AttackCandidate> connectedCandidates = new ArrayList<>();
        for (AttackCandidate candidate : validCandidates) {
            Optional<HQPos> connectedHQ = StrategicConnectivityHelper.findConnectedHQ(
                    regions, candidate.sourceRegionX(), candidate.sourceRegionZ(), Faction.ZOMBIE_HORDE, context.evaluationCache());
            if (connectedHQ.isPresent()) {
                connectedCandidates.add(candidate);
            }
        }

        if (connectedCandidates.isEmpty()) {
            return Optional.empty();
        }

        // 0. Retaliation Bias for connected candidates
        List<ScoredCandidate> retaliationCandidates = new ArrayList<>();
        for (AttackCandidate candidate : connectedCandidates) {
            int retWeight = regions.getZombieRetaliation(candidate.targetRegionX(), candidate.targetRegionZ());
            if (retWeight > 0) {
                retaliationCandidates.add(new ScoredCandidate(candidate, 50.0D * retWeight));
            }
        }
        if (!retaliationCandidates.isEmpty()) {
            AttackCandidate picked = selectWeightedRandom(retaliationCandidates, context);
            long cid = getRawOrSavedRegionState(context, picked.sourceRegionX(), picked.sourceRegionZ()).clusterId();
            return Optional.of(picked.toTarget(cid));
        }

        // Partition candidates into 3 priority tiers
        List<ScoredCandidate> tier1Direct = new ArrayList<>();
        List<ScoredCandidate> tier2PushForward = new ArrayList<>();
        List<ScoredCandidate> tier3Normal = new ArrayList<>();

        for (AttackCandidate c : connectedCandidates) {
            Optional<HQPos> hqOpt = StrategicConnectivityHelper.findConnectedHQ(
                    regions, c.sourceRegionX(), c.sourceRegionZ(), Faction.ZOMBIE_HORDE, context.evaluationCache());
            HQPos hq = hqOpt.get(); // Guaranteed present by filtering above

            HQPos humanityTarget = StrategicConnectivityHelper.findClosestHumanityRegion(c.sourceRegionX(), c.sourceRegionZ(), regions);

            boolean isDirectHumanityAttack = getRawOrSavedRegionState(context, c.targetRegionX(), c.targetRegionZ()).owner() == Faction.HUMANITY;

            double distToHumanityTarget = humanityTarget != null ? Math.hypot(c.targetRegionX() - humanityTarget.x(), c.targetRegionZ() - humanityTarget.z()) : Double.MAX_VALUE;

            double toHumanityX = humanityTarget != null ? humanityTarget.x() - c.sourceRegionX() : 0.0D;
            double toHumanityZ = humanityTarget != null ? humanityTarget.z() - c.sourceRegionZ() : 0.0D;
            double toHumanityLen = Math.hypot(toHumanityX, toHumanityZ);

            double dirX = c.targetRegionX() - c.sourceRegionX();
            double dirZ = c.targetRegionZ() - c.sourceRegionZ();

            double dot = (toHumanityLen > 0.0001D) ? (dirX * toHumanityX + dirZ * toHumanityZ) / toHumanityLen : 0.0D;
            double score = (dot * 10.0D) + Math.max(0.0D, 30.0D - distToHumanityTarget);

            if (isDirectHumanityAttack) {
                // Tier 1: Direct attack on HUMANITY territory
                tier1Direct.add(new ScoredCandidate(c, Math.max(1.0D, score + 100.0D)));
            } else if (humanityTarget != null && distToHumanityTarget <= 3.0D && dot > 0.0D) {
                // Tier 2: Direct push toward claimed Humanity territory (ONLY when distToHumanityTarget <= 3.0D)
                tier2PushForward.add(new ScoredCandidate(c, Math.max(1.0D, score)));
            } else {
                // Tier 3: Normal spiral expansion / enemy-vs-enemy warfare
                double dx = c.targetRegionX() - hq.x();
                double dz = c.targetRegionZ() - hq.z();
                double dist = Math.hypot(dx, dz);
                double angle = Math.atan2(dz, dx);
                if (angle < 0) angle += 2 * Math.PI;

                double targetRadius = 1.0D + 0.5D * (angle / (2 * Math.PI));
                double diff = Math.abs(dist - targetRadius);
                double spiralScore = Math.max(0.5D, 20.0D - diff);
                tier3Normal.add(new ScoredCandidate(c, spiralScore));
            }
        }

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
        com.warfront.Warfront.LOGGER.info("[PERF AI] Zombie attack scoring completed in {} ms ({} candidates evaluated, 0 strength calculations)",
                elapsedMs, connectedCandidates.size());

        // Strict Tiered Selection: Higher tiers execute exclusively!
        if (!tier1Direct.isEmpty()) {
            AttackCandidate picked = selectWeightedRandom(tier1Direct, context);
            long cid = getRawOrSavedRegionState(context, picked.sourceRegionX(), picked.sourceRegionZ()).clusterId();
            return Optional.of(picked.toTarget(cid));
        }

        if (!tier2PushForward.isEmpty()) {
            AttackCandidate picked = selectWeightedRandom(tier2PushForward, context);
            long cid = getRawOrSavedRegionState(context, picked.sourceRegionX(), picked.sourceRegionZ()).clusterId();
            return Optional.of(picked.toTarget(cid));
        }

        if (!tier3Normal.isEmpty()) {
            AttackCandidate picked = selectWeightedRandom(tier3Normal, context);
            long cid = getRawOrSavedRegionState(context, picked.sourceRegionX(), picked.sourceRegionZ()).clusterId();
            return Optional.of(picked.toTarget(cid));
        }

        return Optional.empty();
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
