package com.warfront.spawn;

import com.warfront.mission.MissionType;
import com.warfront.region.Faction;
import com.warfront.region.terrain.TerrainType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative tactical doctrine engine that determines enemy squad composition,
 * spawn weights, and mission objective quotas based on physical terrain and elevation data.
 *
 * Implements:
 * - Terrain-specific combat role weight modulation (Option 1A)
 * - Continuous altitude scaling for RANGED vs FIGHTER roles (Option 2B)
 * - Terrain-modulated mission quotas (e.g. reduced Kill Count in jungle/swamp/mountains) (Option 3A)
 * - Scout reconnaissance cadence and spyglass range adaptations (Option 4A)
 */
public final class TerrainSpawnDoctrine {

    private TerrainSpawnDoctrine() {
    }

    /**
     * Builds the tactical role pool for patrol squads belonging to the given faction,
     * resistance tier, subregion terrain, and calibrated elevation.
     */
    public static List<Object> buildPatrolRolePool(
            Faction faction,
            EnemyResistanceTier tier,
            TerrainType primary,
            TerrainType secondary,
            int elevation
    ) {
        List<Object> pool = new ArrayList<>();
        if (faction == Faction.PILLAGER_CONQUERORS) {
            Map<PillagerEnemyRole, Integer> weights = calculatePillagerRoleWeights(tier, primary, secondary, elevation);
            for (Map.Entry<PillagerEnemyRole, Integer> entry : weights.entrySet()) {
                PillagerEnemyRole role = entry.getKey();
                int weight = entry.getValue();
                for (int w = 0; w < weight; w++) {
                    pool.add(role);
                }
            }
        } else if (faction == Faction.ZOMBIE_HORDE) {
            for (ZombieEnemyRole role : ZombieEnemyRole.values()) {
                if (role.isAvailableAt(tier)) {
                    for (int w = 0; w < role.weight(); w++) {
                        pool.add(role);
                    }
                }
            }
        }
        return pool;
    }

    /**
     * Computes integer composition weights for Pillager roaming roles,
     * factoring in primary/secondary terrain and elevation modifiers.
     */
    public static Map<PillagerEnemyRole, Integer> calculatePillagerRoleWeights(
            EnemyResistanceTier tier,
            TerrainType primary,
            TerrainType secondary,
            int elevation
    ) {
        Map<PillagerEnemyRole, Integer> result = new EnumMap<>(PillagerEnemyRole.class);
        if (primary == null) primary = TerrainType.PLAINS;

        for (PillagerEnemyRole role : PillagerEnemyRole.values()) {
            if (role == PillagerEnemyRole.SCOUT || role.isBaseDefenseOnly()) {
                continue;
            }
            if (!role.isAvailableForRoaming(tier)) {
                continue;
            }

            double baseWeight = role.weight();

            // 1. Terrain Modifier (70% primary + 30% secondary if present)
            double primaryMod = getTerrainRoleMultiplier(role, primary);
            double terrainMod;
            if (secondary != null && secondary != primary) {
                double secondaryMod = getTerrainRoleMultiplier(role, secondary);
                terrainMod = (primaryMod * 0.70) + (secondaryMod * 0.30);
            } else {
                terrainMod = primaryMod;
            }

            // 2. Elevation Modifier
            double elevationMod = getElevationRoleMultiplier(role, elevation);

            int finalWeight = Math.max(1, (int) Math.round(baseWeight * terrainMod * elevationMod));
            result.put(role, finalWeight);
        }

        return result;
    }

    private static double getTerrainRoleMultiplier(PillagerEnemyRole role, TerrainType terrain) {
        if (terrain == null) return 1.0;
        return switch (terrain) {
            case JUNGLE -> switch (role) {
                case FIGHTER -> 2.0;
                case RANGED -> 0.4;
                case ARMORED_ELITE -> 1.5;
                case COMMANDER -> 0.75;
                default -> 1.0;
            };
            case SWAMP -> switch (role) {
                case FIGHTER -> 1.9;
                case RANGED -> 0.5;
                case ARMORED_ELITE -> 1.3;
                case COMMANDER -> 0.75;
                default -> 1.0;
            };
            case PLAINS -> switch (role) {
                case FIGHTER -> 1.0;
                case RANGED -> 1.2;
                case ARMORED_ELITE -> 1.0;
                case COMMANDER -> 1.5;
                default -> 1.0;
            };
            case SAVANNA -> switch (role) {
                case FIGHTER -> 0.9;
                case RANGED -> 1.3;
                case ARMORED_ELITE -> 1.0;
                case COMMANDER -> 1.25;
                default -> 1.0;
            };
            case DESERT -> switch (role) {
                case FIGHTER -> 1.0;
                case RANGED -> 1.15;
                case ARMORED_ELITE -> 1.1;
                case COMMANDER -> 1.0;
                default -> 1.0;
            };
            case HILLS -> switch (role) {
                case FIGHTER -> 0.7;
                case RANGED -> 1.6;
                case ARMORED_ELITE -> 1.1;
                case COMMANDER -> 1.0;
                default -> 1.0;
            };
            case MOUNTAIN -> switch (role) {
                case FIGHTER -> 0.5;
                case RANGED -> 2.0;
                case ARMORED_ELITE -> 1.2;
                case COMMANDER -> 0.75;
                default -> 1.0;
            };
            case BADLANDS -> switch (role) {
                case FIGHTER -> 0.75;
                case RANGED -> 1.5;
                case ARMORED_ELITE -> 1.7;
                case COMMANDER -> 1.0;
                default -> 1.0;
            };
            case FOREST -> switch (role) {
                case FIGHTER -> 1.25;
                case RANGED -> 0.9;
                case ARMORED_ELITE -> 1.0;
                case COMMANDER -> 1.0;
                default -> 1.0;
            };
            case TAIGA -> switch (role) {
                case FIGHTER -> 1.2;
                case RANGED -> 1.1;
                case ARMORED_ELITE -> 1.2;
                case COMMANDER -> 1.0;
                default -> 1.0;
            };
            case TUNDRA -> switch (role) {
                case FIGHTER -> 0.7;
                case RANGED -> 1.3;
                case ARMORED_ELITE -> 1.8;
                case COMMANDER -> 1.0;
                default -> 1.0;
            };
            case RIVER -> switch (role) {
                case FIGHTER -> 1.5;
                case RANGED -> 1.0;
                case ARMORED_ELITE -> 1.0;
                case COMMANDER -> 1.25;
                default -> 1.0;
            };
            case OCEAN -> switch (role) {
                case FIGHTER -> 1.2;
                case RANGED -> 1.2;
                case ARMORED_ELITE -> 1.0;
                case COMMANDER -> 1.0;
                default -> 1.0;
            };
        };
    }

    private static double getElevationRoleMultiplier(PillagerEnemyRole role, int elevation) {
        if (role == PillagerEnemyRole.RANGED) {
            if (elevation < 64) return 0.70;
            if (elevation < 80) return 1.00;
            if (elevation < 105) return 1.20;
            if (elevation < 135) return 1.50;
            if (elevation < 170) return 1.80;
            return 2.20;
        }
        if (role == PillagerEnemyRole.FIGHTER) {
            if (elevation < 64) return 1.35;
            if (elevation < 80) return 1.00;
            if (elevation < 105) return 0.90;
            if (elevation < 135) return 0.75;
            if (elevation < 170) return 0.60;
            return 0.50;
        }
        return 1.00;
    }

    /**
     * Resolves the mission selection weight multiplier for the given mission type in the specified terrain and elevation.
     */
    public static double getMissionWeightMultiplier(MissionType type, TerrainType terrain, int elevation) {
        if (terrain == null) terrain = TerrainType.PLAINS;
        double terrainMult = 1.0;

        switch (type) {
            case SUPPLY_CONVOY -> {
                terrainMult = switch (terrain) {
                    case PLAINS -> 1.6;
                    case SAVANNA -> 1.5;
                    case DESERT -> 1.4;
                    case FOREST -> 1.1;
                    case HILLS -> 0.4;
                    case SWAMP -> 0.2;
                    case RIVER -> 0.3;
                    case MOUNTAIN -> 0.1;
                    case OCEAN -> 0.0;
                    default -> 0.8;
                };
                if (elevation >= 150) terrainMult = 0.0;
                else if (elevation >= 115) terrainMult *= 0.2;
            }
            case SCOUT_NETWORK -> {
                terrainMult = switch (terrain) {
                    case MOUNTAIN -> 3.0;
                    case HILLS -> 2.5;
                    case BADLANDS -> 2.2;
                    case FOREST, TAIGA -> 1.5;
                    case PLAINS -> 0.5;
                    case SWAMP -> 0.4;
                    default -> 1.0;
                };
                if (elevation >= 95) terrainMult *= 2.0;
            }
            case WAR_HORN_RELAY -> {
                terrainMult = switch (terrain) {
                    case MOUNTAIN -> 3.0;
                    case HILLS -> 2.5;
                    case BADLANDS -> 2.0;
                    case SWAMP -> 0.3;
                    case JUNGLE -> 0.4;
                    default -> 1.0;
                };
                if (elevation >= 115) terrainMult *= 2.5;
            }
            case ARTILLERY_BATTERY -> {
                terrainMult = switch (terrain) {
                    case HILLS, BADLANDS -> 1.8;
                    case PLAINS -> 1.5;
                    case SAVANNA -> 1.4;
                    case JUNGLE -> 0.2; // Canopy interferes with shells
                    case SWAMP -> 0.5;
                    default -> 1.0;
                };
                if (elevation >= 105) terrainMult *= 1.3;
            }
            case STRONGPOINT_ASSAULT -> {
                terrainMult = switch (terrain) {
                    case MOUNTAIN, BADLANDS -> 2.0;
                    case JUNGLE -> 1.6;
                    case FOREST -> 1.3;
                    case PLAINS -> 0.8;
                    default -> 1.0;
                };
                if (elevation >= 135) terrainMult *= 1.4;
            }
            case OFFICER_HUNT -> {
                terrainMult = switch (terrain) {
                    case PLAINS, SAVANNA -> 1.5;
                    case TAIGA, FOREST -> 1.4;
                    case MOUNTAIN -> 0.3;
                    case OCEAN -> 0.0;
                    default -> 1.0;
                };
                if (elevation >= 140) terrainMult *= 0.3;
            }
            case FORWARD_OUTPOST -> {
                terrainMult = switch (terrain) {
                    case RIVER -> 2.0;
                    case HILLS -> 1.8;
                    case PLAINS -> 1.2;
                    case OCEAN -> 0.2;
                    case SWAMP -> 0.5;
                    default -> 1.0;
                };
            }
            case KILL_COUNT -> {
                terrainMult = switch (terrain) {
                    case JUNGLE -> 1.6;
                    case SWAMP -> 1.5;
                    case TAIGA -> 1.2;
                    default -> 1.0;
                };
                if (elevation < 64) terrainMult *= 1.3;
            }

            // --- Biome-Specific Tactical Missions (Highlands, Woodlands, Wetlands, Arid, Plains, Arctic) ---
            case MOUNTAIN_LOOKOUT_POST, MOUNTAIN_PASS_BARRICADE, MOUNTAIN_BEACON_BASTION, MOUNTAIN_RIDGE_BATTERY -> {
                terrainMult = (terrain == TerrainType.MOUNTAIN || terrain == TerrainType.HILLS) ? 2.5 : 0.0;
                if (terrainMult > 0 && elevation >= 95) terrainMult *= 1.2;
            }
            case FOREST_LOGGING_DEPOT, FOREST_HUNTING_STASH, FOREST_CANOPY_BLINDS, FOREST_COMMAND_CAMP -> {
                terrainMult = (terrain == TerrainType.FOREST || terrain == TerrainType.TAIGA) ? 2.5 : 0.0;
            }
            case SWAMP_STRANDED_BARGE, SWAMP_FUEL_STILL, SWAMP_STILT_REDOUBT, SWAMP_IRONCLAD_HOLD -> {
                terrainMult = (terrain == TerrainType.SWAMP || terrain == TerrainType.JUNGLE) ? 2.5 : 0.0;
            }
            case DESERT_ROAD_CHECKPOINT, DESERT_CISTERN_DEPOT, BADLANDS_MESA_BATTERY, DESERT_CANYON_STRONGPOINT -> {
                terrainMult = (terrain == TerrainType.DESERT || terrain == TerrainType.BADLANDS) ? 2.5 : 0.0;
            }
            case PLAINS_SCOUT_PADDOCK, PLAINS_SIGNAL_MAST, PLAINS_TRENCH_REDOUBT, PLAINS_FIELD_COMMAND -> {
                terrainMult = (terrain == TerrainType.PLAINS || terrain == TerrainType.SAVANNA) ? 2.5 : 0.0;
            }
            case TUNDRA_SUPPLY_CACHE, TUNDRA_FROZEN_WATCHPOST, TUNDRA_MORTAR_PIT, TUNDRA_GARRISON_REDOUBT -> {
                terrainMult = (terrain == TerrainType.TUNDRA) ? 2.5 : 0.0;
            }
            default -> {
            }
        }

        return Math.max(0.001, terrainMult);
    }

    /**
     * Deterministically calculates the target progress count / quota for a mission,
     * applying terrain and elevation adaptations (e.g. reduced kill quota in dense or rugged terrain).
     */
    public static int calculateTargetCount(
            MissionType type,
            TerrainType terrain,
            int elevation,
            int baseMin,
            int baseMax,
            int resBonus,
            int subVariation,
            long countHash
    ) {
        if (terrain == null) terrain = TerrainType.PLAINS;

        if (type == MissionType.KILL_COUNT) {
            // Dense, swampy, or high mountainous terrain: hunting patrols is harder and slower,
            // so we scale down the kill quota to maintain pacing consistency.
            int[] variations;
            if (terrain == TerrainType.JUNGLE || terrain == TerrainType.SWAMP || terrain == TerrainType.MOUNTAIN || elevation >= 135) {
                variations = new int[]{25, 30, 35};
            } else if (terrain == TerrainType.FOREST || terrain == TerrainType.TAIGA || terrain == TerrainType.BADLANDS || terrain == TerrainType.HILLS || elevation >= 105) {
                variations = new int[]{35, 40, 45};
            } else {
                variations = new int[]{40, 45, 50};
            }
            return variations[(int) ((countHash & 0x7FFFFFFFFFFFFFFFL) % variations.length)];
        }

        if (type == MissionType.SCOUT_NETWORK) {
            // Mountainous or high terrain provides more ridge positions for nests
            if (terrain == TerrainType.MOUNTAIN || terrain == TerrainType.HILLS || terrain == TerrainType.BADLANDS || elevation >= 95) {
                return 3 + (int) ((countHash & 0x7FFFFFFFFFFFFFFFL) % 2); // 3 or 4 nests
            } else {
                return 2 + (int) ((countHash & 0x7FFFFFFFFFFFFFFFL) % 2); // 2 or 3 nests
            }
        }

        if (type == MissionType.ARTILLERY_BATTERY) {
            if (terrain == TerrainType.HILLS || terrain == TerrainType.BADLANDS || terrain == TerrainType.PLAINS || terrain == TerrainType.SAVANNA) {
                return 3;
            }
            return 2;
        }

        if (type == MissionType.STRONGPOINT_ASSAULT) {
            int span = Math.max(0, baseMax - baseMin);
            int baseOffset = (span > 0) ? (int) ((countHash & 0x7FFFFFFFFFFFFFFFL) % (span + 1)) : 0;
            int count = baseMin + baseOffset + resBonus + subVariation;
            if (terrain == TerrainType.MOUNTAIN || terrain == TerrainType.BADLANDS || terrain == TerrainType.JUNGLE) {
                count = Math.max(count, 10 + (int) ((countHash & 0x7FFFFFFFFFFFFFFFL) % 5)); // 10 to 14 defenders
            }
            return Math.max(1, count);
        }

        int span = Math.max(0, baseMax - baseMin);
        int baseOffset = (span > 0) ? (int) ((countHash & 0x7FFFFFFFFFFFFFFFL) % (span + 1)) : 0;
        return Math.max(1, baseMin + baseOffset + resBonus + subVariation);
    }

    /**
     * Checks if a subregion qualifies for accelerated Scout autonomous spawn cadence (3 min vs 5 min).
     */
    public static boolean isScoutAcceleratedTerrain(TerrainType terrain, int elevation) {
        if (terrain == null) return false;
        return terrain == TerrainType.JUNGLE
                || terrain == TerrainType.MOUNTAIN
                || terrain == TerrainType.HILLS
                || terrain == TerrainType.BADLANDS
                || elevation >= 105;
    }

    /**
     * Resolves the autonomous scout spyglass horizon scan range in blocks (40m in mountains/hills, 30m in lowlands).
     */
    public static double getScoutScanRange(TerrainType terrain, int elevation) {
        if (terrain == TerrainType.MOUNTAIN || terrain == TerrainType.HILLS || elevation >= 105) {
            return 40.0D;
        }
        return 30.0D;
    }
}
