package com.warfront.spawn;

/**
 * Pillager Conqueror enemy roles used for encounter composition.
 *
 * Pillagers have no FODDER role — their base unit is RANGED (vanilla Pillager).
 * The CATAPULT role is a base-defense role and MUST NOT appear in roaming encounters.
 *
 * Roles are ordered from common to rare.
 * Each role carries a minimum Resistance tier at which it becomes available
 * and a relative weight used for encounter composition within the tier.
 *
 * Current vanilla mapping:
 *   RANGED        → Minecraft Pillager
 *   FIGHTER       → Minecraft Vindicator
 *   SCOUT         → Minecraft Pillager  (placeholder)
 *   ARMORED_ELITE → Minecraft Pillager  (placeholder)
 *   COMMANDER     → Minecraft Pillager  (placeholder)
 *   CATAPULT      → no roaming spawn (excluded from EnemyEncounterSpawner)
 *
 * When custom entities are implemented, only EnemyEntityResolver needs updating.
 */
public enum PillagerEnemyRole {

    /**
     * Standard ranged attacker. Common at all tiers.
     * Unlock tier: MINIMAL
     * Weight: high
     */
    RANGED(EnemyResistanceTier.MINIMAL, 50),

    /**
     * Melee fighter (Vindicator-style). Common once unlocked.
     * Unlock tier: LOW
     * Weight: high
     */
    FIGHTER(EnemyResistanceTier.LOW, 40),

    /**
     * Fast-moving scout unit. Uncommon.
     * Unlock tier: MODERATE
     * Weight: moderate-low
     */
    SCOUT(EnemyResistanceTier.MODERATE, 18),

    /**
     * Heavily armored elite unit. Rare specialist.
     * Unlock tier: HIGH
     * Weight: low
     */
    ARMORED_ELITE(EnemyResistanceTier.HIGH, 10),

    /**
     * Commander unit that buffs nearby Pillagers. Rare specialist.
     * Unlock tier: HIGH
     * Weight: low
     */
    COMMANDER(EnemyResistanceTier.HIGH, 8),

    /**
     * Base-defense siege unit.
     * MUST NOT be produced by roaming encounter logic.
     * Handled exclusively by active-war/base-defense systems.
     * Weight: 0 (excluded from pool).
     */
    CATAPULT(EnemyResistanceTier.EXTREME, 0);

    /** The minimum tier required for this role to appear in encounter pools. */
    private final EnemyResistanceTier minimumTier;

    /**
     * Relative composition weight. Higher weight = more likely to be selected.
     * CATAPULT has weight 0 to guarantee exclusion from roaming pools.
     */
    private final int weight;

    PillagerEnemyRole(EnemyResistanceTier minimumTier, int weight) {
        this.minimumTier = minimumTier;
        this.weight = weight;
    }

    public EnemyResistanceTier minimumTier() {
        return minimumTier;
    }

    public int weight() {
        return weight;
    }

    /**
     * Returns true if this role is available for roaming encounters.
     * CATAPULT always returns false regardless of tier.
     */
    public boolean isAvailableForRoaming(EnemyResistanceTier tier) {
        if (this == CATAPULT) return false;
        return tier.ordinal() >= minimumTier.ordinal();
    }
}
