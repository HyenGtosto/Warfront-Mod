package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared generic deterministic mission selection and descriptor generation engine.
 *
 * Implements eligibility filtering, regional frequency cap (max 2 instances per mission type
 * in a region), repeat dampening, biome-weighted selection, and deterministic count and role
 * determination without runtime side effects.
 */
public final class WeightedMissionSelector {

    /** Maximum allowed instances of any single mission type within a single region. */
    public static final int MAX_MISSION_TYPE_INSTANCES_PER_REGION = 2;

    /** Weight multiplier applied to candidates that have already been picked once in the region. */
    public static final double REPEAT_DAMPENING_FACTOR = 0.45;

    private WeightedMissionSelector() {
    }

    /**
     * High-quality 64-bit mixer (SplitMix64) to eliminate bit correlation and modulo bias
     * across coordinate shifts and pseudo-random seeds.
     */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Backward-compatible overload without regional frequency tracking.
     */
    public static SubRegionMission selectAndGenerate(
            List<MissionDefinition> candidatePool,
            MissionDefinition fallbackDefinition,
            int regionX, int regionZ,
            int subX, int subZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            BiomeCategory biomeCategory,
            long seed
    ) {
        return selectAndGenerate(
                candidatePool,
                fallbackDefinition,
                regionX, regionZ,
                subX, subZ,
                faction,
                baseType,
                resistance,
                biomeCategory,
                seed,
                null
        );
    }

    /**
     * Generates a deterministic {@link SubRegionMission} from a candidate pool of definitions,
     * enforcing the regional frequency cap (max 2 of any type per region) and repeat dampening.
     *
     * @param candidatePool      faction-specific candidate definitions
     * @param fallbackDefinition safe fallback definition if no candidates match constraints
     * @param regionX            region X coordinate
     * @param regionZ            region Z coordinate
     * @param subX               subregion X index (0 or 1)
     * @param subZ               subregion Z index (0 or 1)
     * @param faction            target enemy faction
     * @param baseType           region base structure type
     * @param resistance         effective resistance value (0–100)
     * @param biomeCategory      region biome classification
     * @param seed               deterministic subregion seed
     * @param regionTypeCounts   tracking map of existing MissionType counts for this region, or null
     * @return immutable {@link SubRegionMission} descriptor
     */
    public static SubRegionMission selectAndGenerate(
            List<MissionDefinition> candidatePool,
            MissionDefinition fallbackDefinition,
            int regionX, int regionZ,
            int subX, int subZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            BiomeCategory biomeCategory,
            long seed,
            Map<MissionType, Integer> regionTypeCounts
    ) {
        float clampedRes = Math.clamp(resistance, 0.0f, 100.0f);

        // 1. Filter candidate definitions by BaseType, Resistance eligibility, and Regional Quota
        List<MissionDefinition> eligible = new ArrayList<>();
        for (MissionDefinition def : candidatePool) {
            if (def.isEligible(baseType, clampedRes)) {
                int count = (regionTypeCounts != null) ? regionTypeCounts.getOrDefault(def.type(), 0) : 0;
                if (count < MAX_MISSION_TYPE_INSTANCES_PER_REGION) {
                    eligible.add(def);
                }
            }
        }

        // If quota filtering left no candidates (e.g., custom pool with fewer than 2 types),
        // gracefully fall back to base eligibility without quota cap to prevent selection failure
        if (eligible.isEmpty()) {
            for (MissionDefinition def : candidatePool) {
                if (def.isEligible(baseType, clampedRes)) {
                    eligible.add(def);
                }
            }
        }

        if (eligible.isEmpty()) {
            eligible.add(fallbackDefinition);
        }

        // 2. Calculate cumulative weights including biome modifiers and repeat dampening
        double totalWeight = 0.0;
        double[] cumulativeWeights = new double[eligible.size()];
        for (int i = 0; i < eligible.size(); i++) {
            MissionDefinition def = eligible.get(i);
            double baseWeight = def.calculateWeight(biomeCategory);
            int count = (regionTypeCounts != null) ? regionTypeCounts.getOrDefault(def.type(), 0) : 0;
            double adjustedWeight = (count == 1) ? (baseWeight * REPEAT_DAMPENING_FACTOR) : baseWeight;
            totalWeight += Math.max(0.001, adjustedWeight);
            cumulativeWeights[i] = totalWeight;
        }

        // 3. High-entropy deterministic weighted roll
        long rollHash = mix64(seed ^ 0x5DEECE66DL);
        double roll = ((rollHash & 0x7FFFFFFFFFFFFFFFL) % 100000) / 100000.0 * totalWeight;
        MissionDefinition selected = eligible.get(0);
        for (int i = 0; i < eligible.size(); i++) {
            if (roll <= cumulativeWeights[i]) {
                selected = eligible.get(i);
                break;
            }
        }

        // Record selection in regional frequency map
        if (regionTypeCounts != null) {
            regionTypeCounts.merge(selected.type(), 1, Integer::sum);
        }

        // 4. Deterministically calculate target count based on resistance scaling and range
        int countSpan = Math.max(0, selected.baseCountMax() - selected.baseCountMin());
        long countHash = mix64(seed ^ 0x123456789L);
        int baseCountOffset = (countSpan > 0) ? (int) ((countHash & 0x7FFFFFFFFFFFFFFFL) % (countSpan + 1)) : 0;
        int resBonus = (int) (clampedRes * selected.resistanceCountMultiplier());
        long varHash = mix64(seed ^ 0x987654321L);
        int subVariation = (int) ((varHash & 0x7FFFFFFFFFFFFFFFL) % 3); // 0, 1, or 2

        int finalTargetCount;
        if (selected.type() == MissionType.KILL_COUNT) {
            int[] variations = {40, 45, 50};
            finalTargetCount = variations[(int) ((countHash & 0x7FFFFFFFFFFFFFFFL) % variations.length)];
        } else {
            finalTargetCount = Math.max(1, selected.baseCountMin() + baseCountOffset + resBonus + subVariation);
        }

        // 5. Deterministically pick target role
        String selectedRole = "BASIC";
        if (!selected.targetRoles().isEmpty()) {
            long roleHash = mix64(seed ^ 0xABCDEF012L);
            int roleIdx = (int) ((roleHash & 0x7FFFFFFFFFFFFFFFL) % selected.targetRoles().size());
            selectedRole = selected.targetRoles().get(roleIdx);
        }

        return new SubRegionMission(
                selected.type(),
                selected.objectiveType(),
                faction,
                subX,
                subZ,
                finalTargetCount,
                selectedRole,
                selected.displayName(),
                selected.description(),
                baseType,
                biomeCategory,
                seed,
                selected.type().isBaseMission()
        );
    }
}
