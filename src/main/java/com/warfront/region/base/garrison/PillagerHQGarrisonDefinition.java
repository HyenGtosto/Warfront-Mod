package com.warfront.region.base.garrison;

import com.warfront.ai.goal.MissionPostGuardGoal;
import com.warfront.entity.*;
import com.warfront.entity.ai.goal.HQStablesScoutGuardGoal;
import com.warfront.entity.ai.goal.HQWallIroncladPatrolGoal;
import com.warfront.entity.ai.goal.HQWallSentryGuardGoal;
import com.warfront.region.RegionData;
import com.warfront.region.base.PillagerHeadquartersGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.EventHooks;

import javax.annotation.Nullable;

/**
 * Concrete garrison definition for the modular Pillager Headquarters ($52 \times 93$ shell).
 * Governs 67 persistent tracked slots partitioned across two operational layers:
 * <ul>
 *   <li>Slots 0–55: Fortress Shell Wall & Tower Sentries (56 entities)
 *       - 4 Corner Towers (28 entities: 4 loot warriors, 16 outlook rapidbows, 8 ballista warriors)
 *       - Long Side Walls (22 entities: 10 Cannoneers, 10 Marksmen/Riflemen with P(R) upgrade, 2 patrolling Ironclads)
 *       - Entrance Walls (6 entities: 4 cannoneers, 2 gatehouse elites)</li>
 *   <li>Slots 56–66: Wing A Commander's Keep (11 entities)
 *       - Commander, Watchtower Cannoneer, Entrance Ironclad Bulwark, Office Elite,
 *         First Floor Warrior, 2 First Floor Marksmen, 2 Map Rapidbows, 2 Stables Scouts</li>
 * </ul>
 */
public final class PillagerHQGarrisonDefinition implements BaseGarrisonDefinition {

    public static final PillagerHQGarrisonDefinition INSTANCE = new PillagerHQGarrisonDefinition();
    public static final int TOTAL_SLOTS = 67;

    public static final Vec3i SHELL_SIZE = new Vec3i(52, 17, 93);
    public static final Vec3i KEEP_SIZE = new Vec3i(36, 18, 38);

    private PillagerHQGarrisonDefinition() {
    }

    @Override
    public int getSlotCount() {
        return TOTAL_SLOTS;
    }

    @Override
    public String getSlotRoleId(int slotIndex) {
        return switch (slotIndex) {
            // --- NW Tower (Slots 0-6) ---
            case 0 -> "WALL_NW_TOWER_LOOT_WARRIOR";
            case 1 -> "WALL_NW_TOWER_OUTLOOK_N1";
            case 2 -> "WALL_NW_TOWER_OUTLOOK_N2";
            case 3 -> "WALL_NW_TOWER_OUTLOOK_W1";
            case 4 -> "WALL_NW_TOWER_OUTLOOK_W2";
            case 5 -> "WALL_NW_TOWER_BALLISTA_W1";
            case 6 -> "WALL_NW_TOWER_BALLISTA_W2";

            // --- NE Tower (Slots 7-13) ---
            case 7 -> "WALL_NE_TOWER_LOOT_WARRIOR";
            case 8 -> "WALL_NE_TOWER_OUTLOOK_N1";
            case 9 -> "WALL_NE_TOWER_OUTLOOK_N2";
            case 10 -> "WALL_NE_TOWER_OUTLOOK_E1";
            case 11 -> "WALL_NE_TOWER_OUTLOOK_E2";
            case 12 -> "WALL_NE_TOWER_BALLISTA_W1";
            case 13 -> "WALL_NE_TOWER_BALLISTA_W2";

            // --- SW Tower (Slots 14-20) ---
            case 14 -> "WALL_SW_TOWER_LOOT_WARRIOR";
            case 15 -> "WALL_SW_TOWER_OUTLOOK_S1";
            case 16 -> "WALL_SW_TOWER_OUTLOOK_S2";
            case 17 -> "WALL_SW_TOWER_OUTLOOK_W1";
            case 18 -> "WALL_SW_TOWER_OUTLOOK_W2";
            case 19 -> "WALL_SW_TOWER_BALLISTA_W1";
            case 20 -> "WALL_SW_TOWER_BALLISTA_W2";

            // --- SE Tower (Slots 21-27) ---
            case 21 -> "WALL_SE_TOWER_LOOT_WARRIOR";
            case 22 -> "WALL_SE_TOWER_OUTLOOK_S1";
            case 23 -> "WALL_SE_TOWER_OUTLOOK_S2";
            case 24 -> "WALL_SE_TOWER_OUTLOOK_E1";
            case 25 -> "WALL_SE_TOWER_OUTLOOK_E2";
            case 26 -> "WALL_SE_TOWER_BALLISTA_W1";
            case 27 -> "WALL_SE_TOWER_BALLISTA_W2";

            // --- West Long Side Wall (Slots 28-38) ---
            case 28 -> "WALL_WEST_SENTRY_1";
            case 29 -> "WALL_WEST_SENTRY_2";
            case 30 -> "WALL_WEST_SENTRY_3";
            case 31 -> "WALL_WEST_SENTRY_4";
            case 32 -> "WALL_WEST_SENTRY_5";
            case 33 -> "WALL_WEST_SENTRY_6";
            case 34 -> "WALL_WEST_SENTRY_7";
            case 35 -> "WALL_WEST_SENTRY_8";
            case 36 -> "WALL_WEST_SENTRY_9";
            case 37 -> "WALL_WEST_SENTRY_10";
            case 38 -> "WALL_WEST_IRONCLAD_PATROL";

            // --- East Long Side Wall (Slots 39-49) ---
            case 39 -> "WALL_EAST_SENTRY_1";
            case 40 -> "WALL_EAST_SENTRY_2";
            case 41 -> "WALL_EAST_SENTRY_3";
            case 42 -> "WALL_EAST_SENTRY_4";
            case 43 -> "WALL_EAST_SENTRY_5";
            case 44 -> "WALL_EAST_SENTRY_6";
            case 45 -> "WALL_EAST_SENTRY_7";
            case 46 -> "WALL_EAST_SENTRY_8";
            case 47 -> "WALL_EAST_SENTRY_9";
            case 48 -> "WALL_EAST_SENTRY_10";
            case 49 -> "WALL_EAST_IRONCLAD_PATROL";

            // --- Entrance Walls (Slots 50-55) ---
            case 50 -> "WALL_NORTH_CANNONEER_WEST";
            case 51 -> "WALL_NORTH_GATE_ELITE";
            case 52 -> "WALL_NORTH_CANNONEER_EAST";
            case 53 -> "WALL_SOUTH_CANNONEER_WEST";
            case 54 -> "WALL_SOUTH_GATE_ELITE";
            case 55 -> "WALL_SOUTH_CANNONEER_EAST";

            // --- Wing A: Commander's Keep (Slots 56-66) ---
            case 56 -> "WING_A_COMMANDER";
            case 57 -> "WING_A_TOWER_CANNONEER";
            case 58 -> "WING_A_IRONCLAD";
            case 59 -> "WING_A_OFFICE_ELITE";
            case 60 -> "WING_A_FLOOR1_WARRIOR";
            case 61 -> "WING_A_FLOOR1_MARKSMAN_1";
            case 62 -> "WING_A_FLOOR1_MARKSMAN_2";
            case 63 -> "WING_A_MAP_RAPIDBOW_1";
            case 64 -> "WING_A_MAP_RAPIDBOW_2";
            case 65 -> "WING_A_STABLES_SCOUT_1";
            case 66 -> "WING_A_STABLES_SCOUT_2";

            default -> "UNKNOWN_" + slotIndex;
        };
    }

    public static BlockPos getLocalShellPos(int slotIndex) {
        return switch (slotIndex) {
            // NW Tower
            case 0 -> new BlockPos(4, 1, 4);    // Bottom Loot Room Warrior
            case 1 -> new BlockPos(3, 7, 1);    // Outlook North 1
            case 2 -> new BlockPos(5, 7, 1);    // Outlook North 2
            case 3 -> new BlockPos(1, 7, 3);    // Outlook West 1
            case 4 -> new BlockPos(1, 7, 5);    // Outlook West 2
            case 5 -> new BlockPos(2, 12, 4);   // Ballista Floor Warrior 1
            case 6 -> new BlockPos(6, 12, 4);   // Ballista Floor Warrior 2

            // NE Tower
            case 7 -> new BlockPos(47, 1, 4);   // Bottom Loot Room Warrior
            case 8 -> new BlockPos(46, 7, 1);   // Outlook North 1
            case 9 -> new BlockPos(48, 7, 1);   // Outlook North 2
            case 10 -> new BlockPos(49, 7, 3);  // Outlook East 1
            case 11 -> new BlockPos(49, 7, 5);  // Outlook East 2
            case 12 -> new BlockPos(45, 12, 4); // Ballista Floor Warrior 1
            case 13 -> new BlockPos(49, 12, 4); // Ballista Floor Warrior 2

            // SW Tower
            case 14 -> new BlockPos(4, 1, 88);   // Bottom Loot Room Warrior
            case 15 -> new BlockPos(3, 7, 91);   // Outlook South 1
            case 16 -> new BlockPos(5, 7, 91);   // Outlook South 2
            case 17 -> new BlockPos(1, 7, 87);   // Outlook West 1
            case 18 -> new BlockPos(1, 7, 89);   // Outlook West 2
            case 19 -> new BlockPos(2, 12, 88);  // Ballista Floor Warrior 1
            case 20 -> new BlockPos(6, 12, 88);  // Ballista Floor Warrior 2

            // SE Tower
            case 21 -> new BlockPos(47, 1, 88);  // Bottom Loot Room Warrior
            case 22 -> new BlockPos(46, 7, 91);  // Outlook South 1
            case 23 -> new BlockPos(49, 7, 91);  // Outlook South 2
            case 24 -> new BlockPos(49, 7, 87);  // Outlook East 1
            case 25 -> new BlockPos(49, 7, 89);  // Outlook East 2
            case 26 -> new BlockPos(45, 12, 88); // Ballista Floor Warrior 1
            case 27 -> new BlockPos(49, 12, 88); // Ballista Floor Warrior 2

            // West Long Side Wall (X=3, Y=7)
            case 28 -> new BlockPos(2, 7, 13);  // Cannoneer
            case 29 -> new BlockPos(2, 7, 18);  // Rapidbow
            case 30 -> new BlockPos(2, 7, 23);  // Cannoneer
            case 31 -> new BlockPos(2, 7, 28);  // Rapidbow
            case 32 -> new BlockPos(2, 7, 33);  // Cannoneer
            case 33 -> new BlockPos(2, 7, 58);  // Rapidbow
            case 34 -> new BlockPos(2, 7, 63);  // Cannoneer
            case 35 -> new BlockPos(2, 7, 68);  // Rapidbow
            case 36 -> new BlockPos(2, 7, 73);  // Cannoneer
            case 37 -> new BlockPos(2, 7, 78);  // Rapidbow
            case 38 -> new BlockPos(3, 7, 12);  // Ironclad Patrol Start

            // East Long Side Wall (X=48, Y=7)
            case 39 -> new BlockPos(49, 7, 13); // Cannoneer
            case 40 -> new BlockPos(49, 7, 18); // Rapidbow
            case 41 -> new BlockPos(49, 7, 23); // Cannoneer
            case 42 -> new BlockPos(49, 7, 28); // Rapidbow
            case 43 -> new BlockPos(49, 7, 33); // Cannoneer
            case 44 -> new BlockPos(49, 7, 58); // Rapidbow
            case 45 -> new BlockPos(49, 7, 63); // Cannoneer
            case 46 -> new BlockPos(49, 7, 68); // Rapidbow
            case 47 -> new BlockPos(49, 7, 73); // Cannoneer
            case 48 -> new BlockPos(49, 7, 78); // Rapidbow
            case 49 -> new BlockPos(49, 7, 12); // Ironclad Patrol Start

            // Entrance Walls (Y=7)
            case 50 -> new BlockPos(14, 7, 2);  // North Cannoneer West
            case 51 -> new BlockPos(26, 7, 4);  // North Gatehouse Elite
            case 52 -> new BlockPos(37, 7, 2);  // North Cannoneer East
            case 53 -> new BlockPos(14, 7, 90); // South Cannoneer West
            case 54 -> new BlockPos(26, 7, 88); // South Gatehouse Elite
            case 55 -> new BlockPos(37, 7, 90); // South Cannoneer East

            default -> BlockPos.ZERO;
        };
    }

    public static Direction getLocalShellFacing(int slotIndex) {
        return switch (slotIndex) {
            // NW Tower
            case 0, 1, 2, 5, 6 -> Direction.NORTH;
            case 3, 4 -> Direction.WEST;

            // NE Tower
            case 7, 8, 9, 12, 13 -> Direction.NORTH;
            case 10, 11 -> Direction.EAST;

            // SW Tower
            case 14, 15, 16, 19, 20 -> Direction.SOUTH;
            case 17, 18 -> Direction.WEST;

            // SE Tower
            case 21, 22, 23, 26, 27 -> Direction.SOUTH;
            case 24, 25 -> Direction.EAST;

            // West Wall sentries & patrol
            case 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38 -> Direction.WEST;

            // East Wall sentries & patrol
            case 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49 -> Direction.EAST;

            // North Entrance
            case 50, 51, 52 -> Direction.NORTH;

            // South Entrance
            case 53, 54, 55 -> Direction.SOUTH;

            default -> Direction.SOUTH;
        };
    }

    public static BlockPos getLocalKeepPos(int slotIndex) {
        return switch (slotIndex) {
            case 56 -> new BlockPos(7, 9, 10);  // Commander (2nd floor office desk)
            case 57 -> new BlockPos(13, 9, 16); // Watchtower Balcony Cannoneer
            case 58 -> new BlockPos(14, 1, 15); // Entrance Archway Ironclad Bulwark
            case 59 -> new BlockPos(10, 9, 15); // 2nd Floor Office Elite Guard
            case 60 -> new BlockPos(8, 1, 16);  // First Floor Hall Center Warrior
            case 61 -> new BlockPos(11, 1, 13); // First Floor Hall West Marksman
            case 62 -> new BlockPos(11, 1, 19); // First Floor Hall East Marksman (near stairs)
            case 63 -> new BlockPos(28, 1, 30); // Outdoor Map Pavilion Rapidbow 1
            case 64 -> new BlockPos(30, 1, 30); // Outdoor Map Pavilion Rapidbow 2
            case 65 -> new BlockPos(29, 1, 6);  // Courtyard Stables Scout 1
            case 66 -> new BlockPos(29, 1, 10); // Courtyard Stables Scout 2
            default -> BlockPos.ZERO;
        };
    }

    public static Direction getLocalKeepFacing(int slotIndex) {
        return switch (slotIndex) {
            case 63, 64 -> Direction.NORTH;
            case 65, 66 -> Direction.WEST;
            default -> Direction.SOUTH;
        };
    }

    @Override
    public BlockPos getSlotSpawnPos(BlockPos anchor, int slotIndex) {
        boolean isEastWest = PillagerHeadquartersGenerator.isEastWest(anchor);
        BlockPos shellBoxMin = PillagerHeadquartersGenerator.getShellBoxMin(anchor);

        if (slotIndex >= 0 && slotIndex <= 55) {
            // Shell sentry slots
            Direction targetShellFacing = isEastWest ? Direction.SOUTH : Direction.WEST;
            Rotation shellRot = PillagerHeadquartersGenerator.getRotationBetween(
                    PillagerHeadquartersGenerator.SHELL_INITIAL_FACING, targetShellFacing);
            BlockPos shellOrigin = PillagerHeadquartersGenerator.getRotatedOrigin(shellBoxMin, SHELL_SIZE, shellRot);

            BlockPos localPos = getLocalShellPos(slotIndex);
            return shellOrigin.offset(StructureTemplate.transform(localPos, Mirror.NONE, shellRot, BlockPos.ZERO));
        } else if (slotIndex >= 56 && slotIndex <= 66) {
            // Wing A slots (Commander's Keep)
            Direction targetSlotAFacing = isEastWest ? Direction.SOUTH : Direction.EAST;
            Rotation slotARot = PillagerHeadquartersGenerator.getRotationBetween(
                    PillagerHeadquartersGenerator.KEEP_INITIAL_FACING, targetSlotAFacing);
            BlockPos slotABoxMin = PillagerHeadquartersGenerator.getSlotBoxMin(
                    shellBoxMin, isEastWest, true, KEEP_SIZE, slotARot);
            BlockPos slotAOrigin = PillagerHeadquartersGenerator.getRotatedOrigin(slotABoxMin, KEEP_SIZE, slotARot);

            BlockPos localPos = getLocalKeepPos(slotIndex);
            return slotAOrigin.offset(StructureTemplate.transform(localPos, Mirror.NONE, slotARot, BlockPos.ZERO));
        }

        return anchor;
    }

    public Direction getSlotFacing(BlockPos anchor, int slotIndex) {
        boolean isEastWest = PillagerHeadquartersGenerator.isEastWest(anchor);

        if (slotIndex >= 0 && slotIndex <= 55) {
            Direction targetShellFacing = isEastWest ? Direction.SOUTH : Direction.WEST;
            Rotation shellRot = PillagerHeadquartersGenerator.getRotationBetween(
                    PillagerHeadquartersGenerator.SHELL_INITIAL_FACING, targetShellFacing);
            return shellRot.rotate(getLocalShellFacing(slotIndex));
        } else if (slotIndex >= 56 && slotIndex <= 66) {
            Direction targetSlotAFacing = isEastWest ? Direction.SOUTH : Direction.EAST;
            Rotation slotARot = PillagerHeadquartersGenerator.getRotationBetween(
                    PillagerHeadquartersGenerator.KEEP_INITIAL_FACING, targetSlotAFacing);
            return slotARot.rotate(getLocalKeepFacing(slotIndex));
        }

        return Direction.SOUTH;
    }

    @Override
    @Nullable
    public Mob spawnSlot(ServerLevel level, BlockPos anchor, int slotIndex) {
        BlockPos pos = getSlotSpawnPos(anchor, slotIndex);
        Direction facing = getSlotFacing(anchor, slotIndex);
        float yaw = facing.toYRot();

        // 1. Patrolling Side-Wall Ironclads (Slots 38 & 49)
        if (slotIndex == 38 || slotIndex == 49) {
            PillagerIroncladEntity ironclad = ModEntities.PILLAGER_IRONCLAD.get().create(level);
            if (ironclad != null) {
                setupMobOrientation(ironclad, pos, yaw);
                EventHooks.finalizeMobSpawn(ironclad, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);

                // Compute patrol end stop at Z=80
                boolean isEastWest = PillagerHeadquartersGenerator.isEastWest(anchor);
                BlockPos shellBoxMin = PillagerHeadquartersGenerator.getShellBoxMin(anchor);
                Direction targetShellFacing = isEastWest ? Direction.SOUTH : Direction.WEST;
                Rotation shellRot = PillagerHeadquartersGenerator.getRotationBetween(
                        PillagerHeadquartersGenerator.SHELL_INITIAL_FACING, targetShellFacing);
                BlockPos shellOrigin = PillagerHeadquartersGenerator.getRotatedOrigin(shellBoxMin, SHELL_SIZE, shellRot);

                int lx = (slotIndex == 38) ? 3 : 48;
                BlockPos localEnd = new BlockPos(lx, 7, 80);
                BlockPos worldEnd = shellOrigin.offset(StructureTemplate.transform(localEnd, Mirror.NONE, shellRot, BlockPos.ZERO));

                ironclad.goalSelector.addGoal(1, new HQWallIroncladPatrolGoal(ironclad, pos, worldEnd, anchor));
                ironclad.setPersistenceRequired();
                return ironclad;
            }
        }

        // 2. Stationary Wall Cannoneers (Side Walls: 28, 30, 32, 34, 36, 39, 41, 43, 45, 47; Entrance Walls: 50, 52, 53, 55)
        if (isWallCannoneer(slotIndex)) {
            PillagerCannoneerEntity cannoneer = ModEntities.PILLAGER_CANNONEER.get().create(level);
            if (cannoneer != null) {
                setupMobOrientation(cannoneer, pos, yaw);
                EventHooks.finalizeMobSpawn(cannoneer, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                cannoneer.goalSelector.getAvailableGoals().removeIf(w -> w.getGoal() instanceof WaterAvoidingRandomStrollGoal);
                cannoneer.goalSelector.addGoal(1, new HQWallSentryGuardGoal(cannoneer, pos, facing, anchor));
                cannoneer.setPersistenceRequired();
                return cannoneer;
            }
        }

        // 3. Stationary Wall Marksmen / Riflemen (Side Walls: 29, 31, 33, 35, 37, 40, 42, 44, 46, 48)
        // Scaled with quadratic resistance upgrade probability P(R) = (R / 100)^2 to Pillager Rifleman
        if (isWallMarksman(slotIndex)) {
            RegionData regions = RegionData.get(level);
            int rx = Math.floorDiv(anchor.getX(), RegionData.REGION_SIZE_BLOCKS);
            int rz = Math.floorDiv(anchor.getZ(), RegionData.REGION_SIZE_BLOCKS);
            float resistance = regions.calculateEffectiveResistance(rx, rz);
            float clampedR = Math.clamp(resistance, 0.0F, 100.0F);
            float pRifleman = (clampedR / 100.0F) * (clampedR / 100.0F);
            boolean isRifleman = level.getRandom().nextFloat() < pRifleman;

            Mob sentry = isRifleman
                    ? ModEntities.PILLAGER_RIFLEMAN.get().create(level)
                    : ModEntities.PILLAGER_MARKSMAN.get().create(level);
            if (sentry != null) {
                setupMobOrientation(sentry, pos, yaw);
                EventHooks.finalizeMobSpawn(sentry, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                sentry.goalSelector.getAvailableGoals().removeIf(w -> w.getGoal() instanceof WaterAvoidingRandomStrollGoal);
                sentry.goalSelector.addGoal(1, new HQWallSentryGuardGoal(sentry, pos, facing, anchor));
                sentry.setPersistenceRequired();
                return sentry;
            }
        }

        // 4. Tower Outlook Rapidbows (Slots 1-4, 8-11, 15-18, 22-25)
        if (isTowerOutlookRapidbow(slotIndex)) {
            PillagerRapidbowEntity rapidbow = ModEntities.PILLAGER_RAPIDBOW.get().create(level);
            if (rapidbow != null) {
                setupMobOrientation(rapidbow, pos, yaw);
                EventHooks.finalizeMobSpawn(rapidbow, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                rapidbow.goalSelector.getAvailableGoals().removeIf(w -> w.getGoal() instanceof WaterAvoidingRandomStrollGoal);
                rapidbow.goalSelector.addGoal(1, new HQWallSentryGuardGoal(rapidbow, pos, facing, anchor));
                rapidbow.setPersistenceRequired();
                return rapidbow;
            }
        }

        // 5. Tower Loot & Ballista Warriors (Slots 0, 5, 6, 7, 12, 13, 14, 19, 20, 21, 26, 27)
        if (isTowerWarrior(slotIndex)) {
            PillagerWarriorEntity warrior = ModEntities.PILLAGER_WARRIOR.get().create(level);
            if (warrior != null) {
                setupMobOrientation(warrior, pos, yaw);
                EventHooks.finalizeMobSpawn(warrior, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                warrior.goalSelector.getAvailableGoals().removeIf(w -> w.getGoal() instanceof WaterAvoidingRandomStrollGoal);
                warrior.goalSelector.addGoal(1, new HQWallSentryGuardGoal(warrior, pos, facing, anchor));
                warrior.setPersistenceRequired();
                return warrior;
            }
        }

        // 6. Gatehouse Armored Elites (Slots 51 & 54)
        if (slotIndex == 51 || slotIndex == 54) {
            PillagerEliteEntity elite = ModEntities.PILLAGER_ELITE.get().create(level);
            if (elite != null) {
                setupMobOrientation(elite, pos, yaw);
                EventHooks.finalizeMobSpawn(elite, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                elite.goalSelector.getAvailableGoals().removeIf(w -> w.getGoal() instanceof WaterAvoidingRandomStrollGoal);
                elite.goalSelector.addGoal(1, new HQWallSentryGuardGoal(elite, pos, facing, anchor));
                elite.setPersistenceRequired();
                return elite;
            }
        }

        // 7. Wing A Commander's Keep Defenders (Slots 56-66)
        if (slotIndex == 56) {
            // Commander (2nd Floor Office Desk)
            PillagerCommanderEntity commander = ModEntities.PILLAGER_COMMANDER.get().create(level);
            if (commander != null) {
                setupMobOrientation(commander, pos, yaw);
                EventHooks.finalizeMobSpawn(commander, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                commander.goalSelector.addGoal(1, new MissionPostGuardGoal(commander, pos, 8.0D, 1.15D));
                commander.setPersistenceRequired();
                return commander;
            }
        } else if (slotIndex == 57) {
            // Watchtower Balcony Cannoneer
            PillagerCannoneerEntity cannoneer = ModEntities.PILLAGER_CANNONEER.get().create(level);
            if (cannoneer != null) {
                setupMobOrientation(cannoneer, pos, yaw);
                EventHooks.finalizeMobSpawn(cannoneer, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                cannoneer.goalSelector.addGoal(1, new MissionPostGuardGoal(cannoneer, pos, 4.0D, 1.0D));
                cannoneer.setPersistenceRequired();
                return cannoneer;
            }
        } else if (slotIndex == 58) {
            // Keep Entrance Archway Bulwark Ironclad
            PillagerIroncladEntity ironclad = ModEntities.PILLAGER_IRONCLAD.get().create(level);
            if (ironclad != null) {
                setupMobOrientation(ironclad, pos, yaw);
                EventHooks.finalizeMobSpawn(ironclad, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                ironclad.goalSelector.addGoal(1, new MissionPostGuardGoal(ironclad, pos, 6.0D, 1.0D));
                ironclad.setPersistenceRequired();
                return ironclad;
            }
        } else if (slotIndex == 59) {
            // 2nd Floor Office Threshold Elite Guard
            PillagerEliteEntity elite = ModEntities.PILLAGER_ELITE.get().create(level);
            if (elite != null) {
                setupMobOrientation(elite, pos, yaw);
                EventHooks.finalizeMobSpawn(elite, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                elite.goalSelector.addGoal(1, new MissionPostGuardGoal(elite, pos, 8.0D, 1.10D));
                elite.setPersistenceRequired();
                return elite;
            }
        } else if (slotIndex == 60) {
            // First Floor Hall Center Warrior
            PillagerWarriorEntity warrior = ModEntities.PILLAGER_WARRIOR.get().create(level);
            if (warrior != null) {
                setupMobOrientation(warrior, pos, yaw);
                EventHooks.finalizeMobSpawn(warrior, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                warrior.goalSelector.addGoal(1, new MissionPostGuardGoal(warrior, pos, 8.0D, 1.10D));
                warrior.setPersistenceRequired();
                return warrior;
            }
        } else if (slotIndex == 61 || slotIndex == 62) {
            // First Floor Hall Marksmen (West and East Crossfire)
            PillagerMarksmanEntity marksman = ModEntities.PILLAGER_MARKSMAN.get().create(level);
            if (marksman != null) {
                setupMobOrientation(marksman, pos, yaw);
                EventHooks.finalizeMobSpawn(marksman, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                marksman.goalSelector.addGoal(1, new MissionPostGuardGoal(marksman, pos, 8.0D, 1.10D));
                marksman.setPersistenceRequired();
                return marksman;
            }
        } else if (slotIndex == 63 || slotIndex == 64) {
            // Outdoor Map Pavilion Rapidbows
            PillagerRapidbowEntity rapidbow = ModEntities.PILLAGER_RAPIDBOW.get().create(level);
            if (rapidbow != null) {
                setupMobOrientation(rapidbow, pos, yaw);
                EventHooks.finalizeMobSpawn(rapidbow, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                rapidbow.goalSelector.addGoal(1, new MissionPostGuardGoal(rapidbow, pos, 5.0D, 1.0D));
                rapidbow.setPersistenceRequired();
                return rapidbow;
            }
        } else if (slotIndex == 65 || slotIndex == 66) {
            // Courtyard Stables Scouts (Stationary watch until player spotted)
            PillagerScoutEntity scout = ModEntities.PILLAGER_SCOUT.get().create(level);
            if (scout != null) {
                setupMobOrientation(scout, pos, yaw);
                EventHooks.finalizeMobSpawn(scout, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                scout.goalSelector.getAvailableGoals().removeIf(w ->
                        w.getGoal() instanceof com.warfront.ai.goal.ScoutReconGoal ||
                        w.getGoal() instanceof WaterAvoidingRandomStrollGoal);
                scout.goalSelector.addGoal(1, new HQStablesScoutGuardGoal(scout, pos, facing, 14.0D, 18.0D));
                scout.setPersistenceRequired();
                return scout;
            }
        }

        return null;
    }

    private static boolean isWallCannoneer(int s) {
        return s == 28 || s == 30 || s == 32 || s == 34 || s == 36
                || s == 39 || s == 41 || s == 43 || s == 45 || s == 47
                || s == 50 || s == 52 || s == 53 || s == 55;
    }

    private static boolean isWallMarksman(int s) {
        return s == 29 || s == 31 || s == 33 || s == 35 || s == 37
                || s == 40 || s == 42 || s == 44 || s == 46 || s == 48;
    }

    private static boolean isTowerOutlookRapidbow(int s) {
        return (s >= 1 && s <= 4) || (s >= 8 && s <= 11)
                || (s >= 15 && s <= 18) || (s >= 22 && s <= 25);
    }

    private static boolean isTowerWarrior(int s) {
        return s == 0 || s == 5 || s == 6 || s == 7 || s == 12 || s == 13
                || s == 14 || s == 19 || s == 20 || s == 21 || s == 26 || s == 27;
    }

    private void setupMobOrientation(Mob mob, BlockPos pos, float yaw) {
        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, yaw, 0.0F);
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
    }
}
