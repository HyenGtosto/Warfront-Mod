package com.warfront.spawn;

/**
 * Centralized Resistance difficulty tiers for out-of-war roaming enemy spawns.
 *
 * Resistance is a continuous 0–100 value. These thresholds determine which
 * enemy roles are unlocked and what encounter size range applies.
 *
 * Tier boundaries:
 * MINIMAL : 0–19
 * LOW : 20–39
 * MODERATE : 40–59
 * HIGH : 60–79
 * EXTREME : 80–100
 */
public enum EnemyResistanceTier {

    /** Resistance 0–19: fewest enemies, only basic roles. */
    MINIMAL(0.0f, 19.9f, 2, 4),

    /** Resistance 20–39: slightly more enemies, first specialist unlocks. */
    LOW(20.0f, 39.9f, 4, 6),

    /** Resistance 40–59: moderate encounter size, ranged units available. */
    MODERATE(40.0f, 59.9f, 6, 8),

    /**
     * Resistance 60–79: larger encounters, tank/hivemind/elite/commander available.
     */
    HIGH(60.0f, 79.9f, 8, 10),

    /** Resistance 80–100: all mobile roles available, largest encounters. */
    EXTREME(80.0f, 100.0f, 10, 12);

    private final float minResistance;
    private final float maxResistance;
    /** Minimum number of enemies for this tier. */
    private final int minSpawn;
    /** Maximum number of enemies for this tier (inclusive). */
    private final int maxSpawn;

    EnemyResistanceTier(float minResistance, float maxResistance, int minSpawn, int maxSpawn) {
        this.minResistance = minResistance;
        this.maxResistance = maxResistance;
        this.minSpawn = minSpawn;
        this.maxSpawn = maxSpawn;
    }

    public float minResistance() {
        return minResistance;
    }

    public float maxResistance() {
        return maxResistance;
    }

    public int minSpawn() {
        return minSpawn;
    }

    public int maxSpawn() {
        return maxSpawn;
    }

    /**
     * Resolves the Resistance tier for a given resistance value.
     * Clamps the value to [0, 100] before checking.
     *
     * @param resistance the region Resistance value (0–100)
     * @return the corresponding EnemyResistanceTier
     */
    public static EnemyResistanceTier fromResistance(float resistance) {
        float clamped = Math.max(0.0f, Math.min(100.0f, resistance));
        if (clamped >= EXTREME.minResistance)
            return EXTREME;
        if (clamped >= HIGH.minResistance)
            return HIGH;
        if (clamped >= MODERATE.minResistance)
            return MODERATE;
        if (clamped >= LOW.minResistance)
            return LOW;
        return MINIMAL;
    }
}
