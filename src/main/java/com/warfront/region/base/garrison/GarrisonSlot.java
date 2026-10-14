package com.warfront.region.base.garrison;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Represents a single tracked entity slot within a persistent base garrison.
 */
public final class GarrisonSlot {

    private final int slotIndex;
    private final String roleId;
    @Nullable
    private UUID entityUuid;
    private long respawnGameTime; // -1 if alive or during active mission; gameTime + delay if dead

    public GarrisonSlot(int slotIndex, String roleId, @Nullable UUID entityUuid, long respawnGameTime) {
        this.slotIndex = slotIndex;
        this.roleId = roleId;
        this.entityUuid = entityUuid;
        this.respawnGameTime = respawnGameTime;
    }

    public int slotIndex() {
        return slotIndex;
    }

    public String roleId() {
        return roleId;
    }

    @Nullable
    public UUID getEntityUuid() {
        return entityUuid;
    }

    public void setEntityUuid(@Nullable UUID entityUuid) {
        this.entityUuid = entityUuid;
    }

    public long getRespawnGameTime() {
        return respawnGameTime;
    }

    public void setRespawnGameTime(long respawnGameTime) {
        this.respawnGameTime = respawnGameTime;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("slotIndex", slotIndex);
        tag.putString("roleId", roleId);
        if (entityUuid != null) {
            tag.putUUID("uuid", entityUuid);
        }
        tag.putLong("respawnTime", respawnGameTime);
        return tag;
    }

    public static GarrisonSlot fromNbt(CompoundTag tag) {
        int slotIndex = tag.getInt("slotIndex");
        String roleId = tag.getString("roleId");
        UUID entityUuid = tag.hasUUID("uuid") ? tag.getUUID("uuid") : null;
        long respawnTime = tag.getLong("respawnTime");
        return new GarrisonSlot(slotIndex, roleId, entityUuid, respawnTime);
    }
}
