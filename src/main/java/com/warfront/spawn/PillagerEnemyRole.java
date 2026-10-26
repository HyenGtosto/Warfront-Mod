package com.warfront.spawn;

/**
 * Pillager Conqueror enemy roles used for encounter composition.
 *
 * Pillagers have no FODDER role — their base unit is RANGED (vanilla Pillager).
 * Base-defense roles (CANNONEER, RAPIDBOW, IRONCLAD) MUST NOT appear in roaming encounters.
 *
 * Roles are ordered from common to rare.
 * Each role carries a minimum Resistance tier at which it becomes available
 * and a relative weight used for encounter composition within the tier.
 *
 * Entity mapping:
 *   RANGED        → Pillager Marksman
 *   FIGHTER       → Pillager Warrior
 *   SCOUT         → Pillager Scout
 *   ARMORED_ELITE → Pillager Elite
 *   COMMANDER     → Pillager Commander
 *   CANNONEER     → Pillager Cannoneer (base defense only)
 *   RAPIDBOW      → Pillager Rapidbow  (base defense only)
 *   IRONCLAD      → Pillager Ironclad  (base defense only)
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
     * Base-defense wall sentry with handheld explosive cannon.
     * Weight: 0 (strictly base-defense only).
     */
    CANNONEER(EnemyResistanceTier.HIGH, 0),

    /**
     * Base-defense courtyard chokepoint suppression emplacement.
     * Weight: 0 (strictly base-defense only).
     */
    RAPIDBOW(EnemyResistanceTier.HIGH, 0),

    /**
     * Base-defense 3-block tall towering iron shield meatwall bodyguard.
     * Weight: 0 (strictly base-defense only).
     */
    IRONCLAD(EnemyResistanceTier.EXTREME, 0);

    /** The minimum tier required for this role to appear in encounter pools. */
    private final EnemyResistanceTier minimumTier;

    /**
     * Relative composition weight. Higher weight = more likely to be selected.
     * Base-defense roles have weight 0 to guarantee exclusion from roaming pools.
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
     * Returns true if this role is exclusively for stationary/fortress base defense.
     */
    public boolean isBaseDefenseOnly() {
        return this == CANNONEER || this == RAPIDBOW || this == IRONCLAD;
    }

    /**
     * Returns true if this role is available for roaming encounters.
     * Base-defense roles always return false regardless of tier.
     */
    public boolean isAvailableForRoaming(EnemyResistanceTier tier) {
        if (isBaseDefenseOnly()) return false;
        return tier.ordinal() >= minimumTier.ordinal();
    }
}
