package com.warfront.region.army;

import com.warfront.region.Faction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

/**
 * Represents a mobile Field Army (Pillager Imperial Legion or Zombie Horde Strain)
 * housed in a non-base (BaseType.NONE) region and linked strictly to a parent
 * BaseType.HEADQUARTERS within its cluster.
 */
public record ClusterArmy(
        long parentHqKey,
        long clusterId,
        Faction faction,
        int currentRegionX,
        int currentRegionZ,
        int ordinalIndex,
        int epithetIndex,
        boolean hqDestroyed
) {
    public static final int PILLAGER_ORDINAL_COUNT = 12;
    public static final int PILLAGER_EPITHET_COUNT = 8;
    public static final int PILLAGER_UNIT_TYPE_COUNT = 2;
    public static final int ZOMBIE_STRAIN_COUNT = 12;

    public long currentRegionKey() {
        return ChunkPos.asLong(currentRegionX, currentRegionZ);
    }

    public int parentHqX() {
        return ChunkPos.getX(parentHqKey);
    }

    public int parentHqZ() {
        return ChunkPos.getZ(parentHqKey);
    }

    public ClusterArmy withPosition(int newRegionX, int newRegionZ) {
        return new ClusterArmy(parentHqKey, clusterId, faction, newRegionX, newRegionZ, ordinalIndex, epithetIndex, hqDestroyed);
    }

    public ClusterArmy withHqDestroyed(boolean destroyed) {
        return new ClusterArmy(parentHqKey, clusterId, faction, currentRegionX, currentRegionZ, ordinalIndex, epithetIndex, destroyed);
    }

    public float getStatBoost() {
        return hqDestroyed ? 10.0F : 40.0F;
    }

    public Component getDisplayName() {
        return formatArmyDisplayName(faction, ordinalIndex, epithetIndex);
    }

    public static Component formatArmyDisplayName(Faction faction, int ordinalIndex, int epithetIndex) {
        if (faction == Faction.ZOMBIE_HORDE) {
            int strainIdx = Math.floorMod(epithetIndex, ZOMBIE_STRAIN_COUNT);
            Component strainComp = Component.translatable("army.warfront.zombie.strain." + strainIdx);
            return Component.translatable("army.warfront.zombie.format", strainComp);
        } else {
            int ordIdx = Math.floorMod(ordinalIndex - 1, PILLAGER_ORDINAL_COUNT) + 1;
            int epIdx = Math.floorMod(epithetIndex, PILLAGER_EPITHET_COUNT);
            int typeIdx = Math.floorMod(epithetIndex / PILLAGER_EPITHET_COUNT, PILLAGER_UNIT_TYPE_COUNT);
            Component ordComp = Component.translatable("army.warfront.pillager.ordinal." + ordIdx);
            Component epComp = Component.translatable("army.warfront.pillager.epithet." + epIdx);
            Component typeComp = Component.translatable("army.warfront.pillager.type." + typeIdx);
            return Component.translatable("army.warfront.pillager.format", ordComp, epComp, typeComp);
        }
    }

    public CompoundTag toCompoundTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("parent_hq_key", parentHqKey);
        tag.putLong("cluster_id", clusterId);
        tag.putInt("faction_id", faction.id());
        tag.putInt("cur_rx", currentRegionX);
        tag.putInt("cur_rz", currentRegionZ);
        tag.putInt("ordinal_idx", ordinalIndex);
        tag.putInt("epithet_idx", epithetIndex);
        tag.putBoolean("hq_destroyed", hqDestroyed);
        return tag;
    }

    public static ClusterArmy fromCompoundTag(CompoundTag tag) {
        long parentHqKey = tag.getLong("parent_hq_key");
        long clusterId = tag.getLong("cluster_id");
        Faction faction = Faction.byId(tag.getInt("faction_id"));
        int curRx = tag.getInt("cur_rx");
        int curRz = tag.getInt("cur_rz");
        int ordinalIdx = tag.getInt("ordinal_idx");
        int epithetIdx = tag.getInt("epithet_idx");
        boolean hqDestroyed = tag.getBoolean("hq_destroyed");
        return new ClusterArmy(parentHqKey, clusterId, faction, curRx, curRz, ordinalIdx, epithetIdx, hqDestroyed);
    }
}
