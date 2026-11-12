package com.warfront.spawn;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tracks upgraded unit variants within a squad during assembly.
 *
 * Upgrade slots are unit-type (base role) specific and NOT squad-shared:
 * an upgraded RANGED unit (Marksman -> Rifleman) consumes a RANGED upgrade slot,
 * leaving FIGHTER upgrade slots fully available for upcoming Warrior upgrades.
 */
public class SquadUpgradeTracker {

    /**
     * Default maximum upgraded units allowed per unit role for small/medium squads (<= 10 members).
     */
    public static final int DEFAULT_MAX_UPGRADES_PER_ROLE_SMALL = 2;

    /**
     * Default maximum upgraded units allowed per unit role for large squads (> 10 members).
     */
    public static final int DEFAULT_MAX_UPGRADES_PER_ROLE_LARGE = 3;

    private final int defaultMaxUpgradesPerRole;
    private final Map<PillagerEnemyRole, Integer> upgradedCounts = new EnumMap<>(PillagerEnemyRole.class);
    private final Map<PillagerEnemyRole, Integer> roleOverrides = new EnumMap<>(PillagerEnemyRole.class);

    public SquadUpgradeTracker(int maxUpgradesPerRole) {
        this.defaultMaxUpgradesPerRole = Math.max(0, maxUpgradesPerRole);
    }

    /**
     * Creates a tracker with tier-scaled upgrade caps based on squad size:
     * max 2 per role for squads <= 10, max 3 per role for squads > 10.
     */
    public static SquadUpgradeTracker forSquadSize(int squadSize) {
        return new SquadUpgradeTracker(squadSize > 10 ? DEFAULT_MAX_UPGRADES_PER_ROLE_LARGE : DEFAULT_MAX_UPGRADES_PER_ROLE_SMALL);
    }

    /**
     * Checks if another unit of the given base role can be upgraded.
     *
     * @param baseRole the base role being evaluated (e.g. RANGED or FIGHTER)
     * @return true if the unit role has remaining upgrade slots
     */
    public boolean canUpgrade(PillagerEnemyRole baseRole) {
        if (baseRole == null) return false;
        int max = getMaxUpgrades(baseRole);
        return upgradedCounts.getOrDefault(baseRole, 0) < max;
    }

    /**
     * Records an upgrade for the specified base role.
     *
     * @param baseRole the base role that was upgraded
     */
    public void recordUpgrade(PillagerEnemyRole baseRole) {
        if (baseRole != null) {
            upgradedCounts.put(baseRole, upgradedCounts.getOrDefault(baseRole, 0) + 1);
        }
    }

    /**
     * Returns the number of upgrades already consumed for the given role.
     */
    public int getUpgrades(PillagerEnemyRole baseRole) {
        return upgradedCounts.getOrDefault(baseRole, 0);
    }

    /**
     * Returns the maximum upgrades allowed for the given role.
     */
    public int getMaxUpgrades(PillagerEnemyRole baseRole) {
        return roleOverrides.getOrDefault(baseRole, defaultMaxUpgradesPerRole);
    }

    /**
     * Sets a specific upgrade cap override for a role.
     */
    public void setMaxUpgrades(PillagerEnemyRole baseRole, int max) {
        if (baseRole != null) {
            roleOverrides.put(baseRole, Math.max(0, max));
        }
    }

    public int getDefaultMaxUpgradesPerRole() {
        return defaultMaxUpgradesPerRole;
    }
}
