package com.warfront.spawn;

/**
 * Zombie Horde enemy roles used for encounter composition.
 *
 * Roles are ordered from least dangerous (common) to most dangerous (rare).
 * Each role carries a minimum Resistance tier at which it becomes available
 * and a relative weight used for encounter composition within the tier.
 *
 * Current vanilla mapping (all roles temporarily map to vanilla Zombie):
 *   FODDER, FAST_CHASER, RANGED, TANK, HIVEMIND_CONTROLLER → Minecraft Zombie
 *
 * When custom entities are implemented, only EnemyEntityResolver needs updating.
 */
public enum ZombieEnemyRole {

    /**
     * Basic zombie. Common at all tiers.
     * Unlock tier: MINIMAL
     * Weight: high
     */
    FODDER(EnemyResistanceTier.MINIMAL, 60),

    /**
     * Faster zombie that pursues at higher speed.
     * Unlock tier: LOW
     * Weight: moderate
     */
    FAST_CHASER(EnemyResistanceTier.LOW, 25),

    /**
     * Zombie capable of ranged attacks.
     * Unlock tier: MODERATE
     * Weight: moderate
     */
    RANGED(EnemyResistanceTier.MODERATE, 20),

    /**
     * Heavy zombie with high health.
     * Unlock tier: HIGH
     * Weight: low (rare specialist)
     */
    TANK(EnemyResistanceTier.HIGH, 10),

    /**
     * Hivemind controller that buffs nearby zombies.
     * Unlock tier: HIGH
     * Weight: very low (rare specialist)
     */
    HIVEMIND_CONTROLLER(EnemyResistanceTier.HIGH, 5);

    /** The minimum tier required for this role to be included in encounter pools. */
    private final EnemyResistanceTier minimumTier;

    /**
     * Relative composition weight. Higher weight = more likely to be selected
     * when this role is in the active pool. Weights are compared across currently
     * unlocked roles only.
     */
    private final int weight;

    ZombieEnemyRole(EnemyResistanceTier minimumTier, int weight) {
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
     * Returns true if this role is available at the given tier.
     * A role is available when the given tier's ordinal >= this role's minimum tier ordinal.
     */
    public boolean isAvailableAt(EnemyResistanceTier tier) {
        return tier.ordinal() >= minimumTier.ordinal();
    }
}
