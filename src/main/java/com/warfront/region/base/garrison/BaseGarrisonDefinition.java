package com.warfront.region.base.garrison;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import javax.annotation.Nullable;

/**
 * Contract for tier-specific base garrison compositions.
 * Defines slot counts, target roles, spawn positions, and mob creation logic.
 */
public interface BaseGarrisonDefinition {

    /**
     * Total number of tracked slots in this garrison configuration.
     */
    int getSlotCount();

    /**
     * Human-readable unique role ID for this slot.
     */
    String getSlotRoleId(int slotIndex);

    /**
     * Default spawn / guard location for this slot relative to the base anchor.
     */
    BlockPos getSlotSpawnPos(BlockPos anchor, int slotIndex);

    /**
     * Instantiates and prepares the entity for this slot (without adding to level).
     */
    @Nullable
    Mob spawnSlot(ServerLevel level, BlockPos anchor, int slotIndex);
}
