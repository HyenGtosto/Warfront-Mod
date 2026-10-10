package com.warfront.faction;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

public record FactionMember(UUID uuid, String name, FactionRole role) {

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        tag.putString("name", name);
        tag.putString("role", role.name());
        return tag;
    }

    public static FactionMember fromNbt(CompoundTag tag) {
        UUID uuid = tag.getUUID("uuid");
        String name = tag.getString("name");
        FactionRole role;
        try {
            role = FactionRole.valueOf(tag.getString("role"));
        } catch (Exception e) {
            role = FactionRole.MEMBER;
        }
        return new FactionMember(uuid, name, role);
    }
}
