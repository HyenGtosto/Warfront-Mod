package com.warfront.mission;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.spawn.EnemyResistanceTier;
import com.warfront.spawn.PillagerEnemyRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Faction-specific mission generator for Pillager Conquerors.
 *
 * Pillager Conqueror mission design:
 * <ul>
 *   <li>BaseType (Bases): OUTPOST (+3 kills), HEADQUARTERS (+7 kills), MEGA_BASE (+12 kills).</li>
 *   <li>Resistance: Scales tactical force requirement (3 + resistance / 12) and unlocks military roles.</li>
 *   <li>Stability: Lower stability increases organized squad counter-operations (+ (100 - stability) / 25 kills).</li>
 *   <li>Subregion: Seeded subregion variation without arbitrary index bias.</li>
 *   <li>Role exclusion: CATAPULT is strictly excluded from roaming mission generation.</li>
 * </ul>
 */
public final class PillagerMissionGenerator implements FactionMissionGenerator {

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
        int baseKills = 3 + (int) (clampedRes / 12.0f);

        // BaseType scaling (Military bases)
        int baseTypeBonus = switch (baseType) {
            case OUTPOST -> 3;       // Pillager Outpost
            case HEADQUARTERS -> 7;  // Command Center
            case MEGA_BASE -> 12;    // Mega Command Center
            default -> 0;
        };

        // Stability pressure bonus (organized military response)
        int stabBonus = (int) ((100.0f - clampedStab) / 25.0f);

        SubRegionMission[] missions = new SubRegionMission[4];

        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;

            // Seeded subregion hash — deterministic variation per subregion
            long subSeed = (regionX * 73856093L) ^ (regionZ * 19349663L) ^ (subX * 83492791L) ^ (subZ * 4393139L) ^ faction.id();
            int subVariation = (int) (Math.abs(subSeed % 3L)); // 0, 1, or 2

            int totalKillTarget = baseKills + baseTypeBonus + stabBonus + subVariation;

            // Pick available pillager role for subregion (CATAPULT strictly excluded)
            PillagerEnemyRole role = selectPillagerRole(tier, subSeed);

            missions[i] = new SubRegionMission(
                    MissionType.KILL_COUNT,
                    Faction.PILLAGER_CONQUERORS,
                    subX, subZ,
                    totalKillTarget,
                    role.name(),
                    "Kill Count"
            );
        }

        return missions;
    }

    private PillagerEnemyRole selectPillagerRole(EnemyResistanceTier tier, long seed) {
        List<PillagerEnemyRole> available = new ArrayList<>();
        for (PillagerEnemyRole role : PillagerEnemyRole.values()) {
            // CATAPULT always returns false for isAvailableForRoaming
            if (role.isAvailableForRoaming(tier)) {
                available.add(role);
            }
        }

        if (available.isEmpty()) {
            return PillagerEnemyRole.RANGED;
        }

        int index = (int) (Math.abs(seed >> 8) % available.size());
        return available.get(index);
    }
}
