package com.warfront.region.base.garrison;

import com.warfront.loot.OutpostLootManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.event.EventHooks;

import javax.annotation.Nullable;

/**
 * Fallback garrison definition for Zombie Horde occupied outposts.
 */
public final class ZombieOutpostGarrisonDefinition implements BaseGarrisonDefinition {

    public static final ZombieOutpostGarrisonDefinition INSTANCE = new ZombieOutpostGarrisonDefinition();

    private ZombieOutpostGarrisonDefinition() {
    }

    @Override
    public int getSlotCount() {
        return 4;
    }

    @Override
    public String getSlotRoleId(int slotIndex) {
        return switch (slotIndex) {
            case 0 -> "ZOMBIE_NW";
            case 1 -> "ZOMBIE_NE";
            case 2 -> "ZOMBIE_SW";
            case 3 -> "ZOMBIE_SE";
            default -> "ZOMBIE_" + slotIndex;
        };
    }

    @Override
    public BlockPos getSlotSpawnPos(BlockPos anchor, int slotIndex) {
        BlockPos origin = OutpostLootManager.getStructureOrigin(anchor);
        return switch (slotIndex) {
            case 0 -> origin.offset(6, 1, 6);
            case 1 -> origin.offset(22, 1, 6);
            case 2 -> origin.offset(6, 1, 21);
            case 3 -> origin.offset(22, 1, 21);
            default -> origin.offset(14, 1, 14);
        };
    }

    @Override
    @Nullable
    public Mob spawnSlot(ServerLevel level, BlockPos anchor, int slotIndex) {
        BlockPos pos = getSlotSpawnPos(anchor, slotIndex);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie != null) {
            zombie.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
            EventHooks.finalizeMobSpawn(zombie, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
            zombie.setPersistenceRequired();
            return zombie;
        }
        return null;
    }
}
