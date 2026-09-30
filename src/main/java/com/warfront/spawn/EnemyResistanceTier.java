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

    /** Resistance 0–24.9: fewest enemies, degraded / pacified fringe. */
    MINIMAL(0.0f, 24.9f, 4, 6),

    /** Resistance 25–44.9: standard non-base occupied territory. */
    LOW(25.0f, 44.9f, 6, 9),

    /** Resistance 45–64.9: outpost territory / fortified clusters. */
    MODERATE(45.0f, 64.9f, 8, 12),

    /** Resistance 65–84.9: headquarters / major military base. */
    HIGH(65.0f, 84.9f, 12, 16),

    /** Resistance 85–100: mega base stronghold, largest encounters. */
    EXTREME(85.0f, 100.0f, 16, 22);

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
