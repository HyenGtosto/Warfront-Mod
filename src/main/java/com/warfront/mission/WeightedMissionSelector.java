package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared generic deterministic mission selection and descriptor generation engine.
 *
 * Implements eligibility filtering, biome-weighted selection, and deterministic
 * count and role determination without runtime side effects.
 */
public final class WeightedMissionSelector {

    private WeightedMissionSelector() {
    }

    /**
     * Generates a deterministic {@link SubRegionMission} from a candidate pool of definitions.
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
            long seed
    ) {
        float clampedRes = Math.clamp(resistance, 0.0f, 100.0f);

        // 1. Filter candidate definitions by BaseType and Resistance eligibility
        List<MissionDefinition> eligible = new ArrayList<>();
        for (MissionDefinition def : candidatePool) {
            if (def.isEligible(baseType, clampedRes)) {
                eligible.add(def);
            }
        }

        if (eligible.isEmpty()) {
            eligible.add(fallbackDefinition);
        }

        // 2. Calculate cumulative weights including biome modifiers
        double totalWeight = 0.0;
        double[] cumulativeWeights = new double[eligible.size()];
        for (int i = 0; i < eligible.size(); i++) {
            totalWeight += eligible.get(i).calculateWeight(biomeCategory);
            cumulativeWeights[i] = totalWeight;
        }

        // 3. Deterministic weighted roll
        double roll = (Math.abs(seed ^ 0x5DEECE66DL) % 10000) / 10000.0 * totalWeight;
        MissionDefinition selected = eligible.get(0);
        for (int i = 0; i < eligible.size(); i++) {
            if (roll <= cumulativeWeights[i]) {
                selected = eligible.get(i);
                break;
            }
        }

        // 4. Deterministically calculate target count based on resistance scaling and range
        int countSpan = Math.max(0, selected.baseCountMax() - selected.baseCountMin());
        int baseCountOffset = (countSpan > 0) ? (int) (Math.abs(seed >> 3) % (countSpan + 1)) : 0;
        int resBonus = (int) (clampedRes * selected.resistanceCountMultiplier());
        int subVariation = (int) (Math.abs(seed >> 6) % 3); // 0, 1, or 2

        int finalTargetCount = Math.max(1, selected.baseCountMin() + baseCountOffset + resBonus + subVariation);

        // 5. Deterministically pick target role
        String selectedRole = "BASIC";
        if (!selected.targetRoles().isEmpty()) {
            int roleIdx = (int) (Math.abs(seed >> 10) % selected.targetRoles().size());
            selectedRole = selected.targetRoles().get(roleIdx);
        }

        return new SubRegionMission(
                selected.type(),
                faction,
                subX,
                subZ,
                finalTargetCount,
                selectedRole,
                selected.displayName(),
                selected.description(),
                baseType,
                biomeCategory,
                seed
        );
    }
}
