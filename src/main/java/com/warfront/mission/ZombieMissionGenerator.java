package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.spawn.EnemyResistanceTier;
import com.warfront.spawn.ZombieEnemyRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Faction-specific mission generator for the Zombie Horde.
 *
 * Zombie Horde mission design:
 * <ul>
 *   <li>BaseType (Hives): OUTPOST (+3 kills), HEADQUARTERS (+7 kills), MEGA_BASE (+12 kills).</li>
 *   <li>Resistance: Scales base kill target count (4 + resistance / 15) and unlocks higher zombie roles.</li>
 *   <li>Stability: Lower stability creates horde pressure and density (+ (100 - stability) / 20 kills).</li>
 *   <li>Subregion: Deterministic seed-based variation per subregion without arbitrary index bias.</li>
 * </ul>
 */
public final class ZombieMissionGenerator implements FactionMissionGenerator {

    @Override
    public SubRegionMission[] generateMissions(
            int regionX, int regionZ,
            Faction faction,
            BaseType baseType,
            float resistance,
            float stability
    ) {
        float clampedRes = Math.clamp(resistance, 0.0f, 100.0f);
        float clampedStab = Math.clamp(stability, 0.0f, 100.0f);

        EnemyResistanceTier tier = EnemyResistanceTier.fromResistance(clampedRes);

        // Base kill target from Resistance
        int baseKills = 4 + (int) (clampedRes / 15.0f);

        // BaseType scaling (Hive levels)
        int baseTypeBonus = switch (baseType) {
            case OUTPOST -> 3;       // Minor Hive
            case HEADQUARTERS -> 7;  // Major Hive
            case MEGA_BASE -> 12;    // Heart of Infection
            default -> 0;
        };

        // Stability pressure bonus (unstable territory requires clearing more zombies)
        int stabBonus = (int) ((100.0f - clampedStab) / 20.0f);

        SubRegionMission[] missions = new SubRegionMission[4];

        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;

            // Seeded subregion hash — deterministic variation per subregion
            long subSeed = (regionX * 31213L) ^ (regionZ * 65537L) ^ (subX * 104729L) ^ (subZ * 224737L) ^ faction.id();
            int subVariation = (int) (Math.abs(subSeed % 3L)); // 0, 1, or 2

            int totalKillTarget = baseKills + baseTypeBonus + stabBonus + subVariation;

            // Pick available zombie role for subregion based on tier & subSeed
            ZombieEnemyRole role = selectZombieRole(tier, subSeed);

            missions[i] = new SubRegionMission(
                    MissionType.KILL_COUNT,
                    Faction.ZOMBIE_HORDE,
                    subX, subZ,
                    totalKillTarget,
                    role.name(),
                    "Kill Count"
            );
        }

        return missions;
    }

    private ZombieEnemyRole selectZombieRole(EnemyResistanceTier tier, long seed) {
        List<ZombieEnemyRole> available = new ArrayList<>();
        for (ZombieEnemyRole role : ZombieEnemyRole.values()) {
            if (role.isAvailableAt(tier)) {
                available.add(role);
            }
        }

        if (available.isEmpty()) {
            return ZombieEnemyRole.FODDER;
        }

        int index = (int) (Math.abs(seed >> 8) % available.size());
        return available.get(index);
    }
}
