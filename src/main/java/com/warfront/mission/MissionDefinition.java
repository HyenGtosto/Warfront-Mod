package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.BiomeCategory;
import com.warfront.region.Faction;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates a candidate mission definition template with explicit eligibility rules,
 * biome weight adjustments, and resistance-based scaling rules.
 */
public record MissionDefinition(
        String id,
        MissionType type,
        Faction faction,
        Set<BaseType> allowedBaseTypes,
        float minResistance,
        float maxResistance,
        int baseWeight,
        Map<BiomeCategory, Double> biomeWeightMultipliers,
        int baseCountMin,
        int baseCountMax,
        float resistanceCountMultiplier,
        List<String> targetRoles,
        String displayName,
        String description
) {
    public MissionDefinition {
        allowedBaseTypes = Set.copyOf(allowedBaseTypes);
        biomeWeightMultipliers = biomeWeightMultipliers != null ? Map.copyOf(biomeWeightMultipliers) : Collections.emptyMap();
        targetRoles = List.copyOf(targetRoles);
    }

    /**
     * Checks if this mission definition is eligible for the specified region base type and resistance.
     *
     * @param baseType   base structure present in the region
     * @param resistance effective region resistance value (0–100)
     * @return true if eligible for generation
     */
    public boolean isEligible(BaseType baseType, float resistance) {
        if (!allowedBaseTypes.contains(baseType)) {
            return false;
        }
        return resistance >= minResistance && resistance <= maxResistance;
    }

    /**
     * Calculates the selection weight for this definition considering biome multipliers.
     *
     * @param biome the region's biome category
     * @return positive weight value
     */
    public double calculateWeight(BiomeCategory biome) {
        double multiplier = biomeWeightMultipliers.getOrDefault(biome, 1.0);
        return Math.max(0.1, baseWeight * multiplier);
    }
}
