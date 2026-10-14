package com.warfront.region.base.garrison;

import com.warfront.entity.ModEntities;
import com.warfront.entity.PillagerCommanderEntity;
import com.warfront.entity.PillagerMarksmanEntity;
import com.warfront.entity.PillagerWarriorEntity;
import com.warfront.ai.goal.MissionPostGuardGoal;
import com.warfront.entity.ai.goal.OutpostMarksmanPatrolGoal;
import com.warfront.loot.OutpostLootManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.event.EventHooks;

import javax.annotation.Nullable;

/**
 * Concrete garrison definition for the 29x25x29 Pillager Outpost structure.
 * Features 17 tracked slots:
 * - 8 Tower Marksmen (NW, NE, SW, SE corner towers at Y=5 platform)
 * - 1 Main Gate Sentry (above entrance at Y=5)
 * - 4 Courtyard Warriors (independent rovers at Y=1)
 * - 1 Floor 1 Barracks Warrior (Y=1)
 * - 1 Floor 2 Commander (Y=5)
 * - 2 Floor 2 Bodyguard Warriors (Y=5 near stairs)
 */
public final class OutpostGarrisonDefinition implements BaseGarrisonDefinition {

    public static final OutpostGarrisonDefinition INSTANCE = new OutpostGarrisonDefinition();
    public static final int TOTAL_SLOTS = 17;

    private OutpostGarrisonDefinition() {
    }

    @Override
    public int getSlotCount() {
        return TOTAL_SLOTS;
    }

    @Override
    public String getSlotRoleId(int slotIndex) {
        return switch (slotIndex) {
            case 0 -> "NW_TOWER_NORTH";
            case 1 -> "NW_TOWER_WEST";
            case 2 -> "NE_TOWER_NORTH";
            case 3 -> "NE_TOWER_EAST";
            case 4 -> "SW_TOWER_WEST";
            case 5 -> "SW_TOWER_SOUTH";
            case 6 -> "SE_TOWER_EAST";
            case 7 -> "SE_TOWER_SOUTH";
            case 8 -> "GATE_SENTRY";
            case 9 -> "COURTYARD_NW";
            case 10 -> "COURTYARD_NE";
            case 11 -> "COURTYARD_SW";
            case 12 -> "COURTYARD_SE";
            case 13 -> "BARRACKS_WARRIOR";
            case 14 -> "COMMANDER";
            case 15 -> "COMMANDER_BODYGUARD_1";
            case 16 -> "COMMANDER_BODYGUARD_2";
            default -> "UNKNOWN_" + slotIndex;
        };
    }

    @Override
    public BlockPos getSlotSpawnPos(BlockPos anchor, int slotIndex) {
        BlockPos origin = OutpostLootManager.getStructureOrigin(anchor);
        return switch (slotIndex) {
            // Corner Towers (Y=8 platform relative to origin)
            case 0 -> origin.offset(4, 8, 2);   // NW Tower North
            case 1 -> origin.offset(2, 8, 4);   // NW Tower West
            case 2 -> origin.offset(24, 8, 2);  // NE Tower North
            case 3 -> origin.offset(26, 8, 4);  // NE Tower East
            case 4 -> origin.offset(2, 8, 25);  // SW Tower West
            case 5 -> origin.offset(4, 8, 27);  // SW Tower South
            case 6 -> origin.offset(26, 8, 25); // SE Tower East
            case 7 -> origin.offset(24, 8, 27); // SE Tower South

            // Main Gate Sentry (Y=5 relative to origin)
            case 8 -> origin.offset(14, 5, 24);

            // Courtyard (Y=1 relative to origin)
            case 9 -> origin.offset(14, 1, 8);   // Courtyard North
            case 10 -> origin.offset(8, 1, 14);  // Courtyard West
            case 11 -> origin.offset(14, 1, 20); // Courtyard South
            case 12 -> origin.offset(20, 1, 14); // Courtyard East

            // Floor 1 Barracks (Y=1 relative to origin)
            case 13 -> origin.offset(14, 1, 14);

            // Floor 2 Commander & Bodyguards (Y=5 relative to origin)
            case 14 -> origin.offset(10, 5, 12); // Commander
            case 15 -> origin.offset(14, 5, 10); // Commander Bodyguard 1
            case 16 -> origin.offset(14, 5, 14); // Commander Bodyguard 2

            default -> origin.offset(14, 1, 14);
        };
    }

    public static Direction getMarksmanFacing(int slotIndex) {
        return switch (slotIndex) {
            case 0, 2 -> Direction.NORTH;
            case 1, 4 -> Direction.WEST;
            case 3, 6 -> Direction.EAST;
            default -> Direction.SOUTH; // 5, 7, 8
        };
    }

    @Nullable
    public static BlockPos getMarksmanWallPatrolPos(BlockPos anchor, int slotIndex) {
        BlockPos origin = OutpostLootManager.getStructureOrigin(anchor);
        return switch (slotIndex) {
            case 0 -> origin.offset(10, 5, 4);  // NW North -> steps down stairs to North wall walkway (Z=4, Y=5)
            case 1 -> origin.offset(4, 5, 10);  // NW West  -> steps down stairs to West wall walkway (X=4, Y=5)
            case 2 -> origin.offset(18, 5, 4);  // NE North -> steps down stairs to North wall walkway (Z=4, Y=5)
            case 3 -> origin.offset(24, 5, 10); // NE East  -> steps down stairs to East wall walkway (X=24, Y=5)
            case 4 -> origin.offset(4, 5, 18);  // SW West  -> steps down stairs to West wall walkway (X=4, Y=5)
            case 6 -> origin.offset(24, 5, 18); // SE East  -> steps down stairs to East wall walkway (X=24, Y=5)
            default -> null; // 5, 7, 8 hold permanent posts facing South
        };
    }

    public static OutpostMarksmanPatrolGoal createMarksmanPatrolGoal(PillagerMarksmanEntity sniper, BlockPos anchor, int slotIndex) {
        BlockPos pos = INSTANCE.getSlotSpawnPos(anchor, slotIndex);
        Direction facing = getMarksmanFacing(slotIndex);
        BlockPos wallPatrol = getMarksmanWallPatrolPos(anchor, slotIndex);
        Direction wallFacing = facing;
        return new OutpostMarksmanPatrolGoal(sniper, pos, facing, wallPatrol, wallFacing);
    }

    @Override
    @Nullable
    public Mob spawnSlot(ServerLevel level, BlockPos anchor, int slotIndex) {
        BlockPos pos = getSlotSpawnPos(anchor, slotIndex);

        if (slotIndex >= 0 && slotIndex <= 8) {
            // Marksmen
            Direction facing = getMarksmanFacing(slotIndex);

            PillagerMarksmanEntity sniper = ModEntities.PILLAGER_MARKSMAN.get().create(level);
            if (sniper != null) {
                float yaw = facing.toYRot();
                sniper.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, yaw, 0.0F);
                sniper.setYRot(yaw);
                sniper.setYHeadRot(yaw);
                sniper.setYBodyRot(yaw);
                EventHooks.finalizeMobSpawn(sniper, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                sniper.goalSelector.addGoal(1, createMarksmanPatrolGoal(sniper, anchor, slotIndex));
                sniper.setPersistenceRequired();
                return sniper;
            }
        } else if (slotIndex >= 9 && slotIndex <= 12) {
            // Courtyard Roaming Warriors (Quadrants: North, West, South, East)
            PillagerWarriorEntity warrior = ModEntities.PILLAGER_WARRIOR.get().create(level);
            if (warrior != null) {
                float yaw = level.getRandom().nextFloat() * 360.0F;
                warrior.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, yaw, 0.0F);
                EventHooks.finalizeMobSpawn(warrior, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                // Leash courtyard warrior to their quadrant (radius 6.0m) preventing climbing towers or exiting gate
                warrior.goalSelector.addGoal(1, new MissionPostGuardGoal(warrior, pos, 6.0D, 1.0D));
                warrior.setPersistenceRequired();
                return warrior;
            }
        } else if (slotIndex == 13 || slotIndex == 15 || slotIndex == 16) {
            // Guarded Interior Warriors (Floor 1 Barracks + Floor 2 Commander Bodyguards)
            PillagerWarriorEntity warrior = ModEntities.PILLAGER_WARRIOR.get().create(level);
            if (warrior != null) {
                warrior.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
                EventHooks.finalizeMobSpawn(warrior, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                warrior.goalSelector.addGoal(1, new MissionPostGuardGoal(warrior, pos, 8.0D, 1.10D));
                warrior.setPersistenceRequired();
                return warrior;
            }
        } else if (slotIndex == 14) {
            // Commander beside War Desks (10, 5, 12)
            PillagerCommanderEntity commander = ModEntities.PILLAGER_COMMANDER.get().create(level);
            if (commander != null) {
                commander.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
                EventHooks.finalizeMobSpawn(commander, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
                commander.goalSelector.addGoal(1, new MissionPostGuardGoal(commander, pos, 10.0D, 1.15D));
                commander.setPersistenceRequired();
                return commander;
            }
        }

        return null;
    }
}
