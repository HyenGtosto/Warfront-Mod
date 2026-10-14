package com.warfront.region.base.garrison;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores the full garrison state for a specific persistent base in a region.
 */
public final class BaseGarrisonState {

    private final int regionX;
    private final int regionZ;
    private final BaseType baseType;
    private final Faction faction;
    private final BlockPos baseAnchor;
    private final List<GarrisonSlot> slots;

    public BaseGarrisonState(int regionX, int regionZ, BaseType baseType, Faction faction, BlockPos baseAnchor) {
        this(regionX, regionZ, baseType, faction, baseAnchor, new ArrayList<>());
    }

    public BaseGarrisonState(int regionX, int regionZ, BaseType baseType, Faction faction, BlockPos baseAnchor, List<GarrisonSlot> slots) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.baseType = baseType;
        this.faction = faction;
        this.baseAnchor = baseAnchor;
        this.slots = slots;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public BaseType baseType() {
        return baseType;
    }

    public Faction faction() {
        return faction;
    }

    public BlockPos baseAnchor() {
        return baseAnchor;
    }

    public List<GarrisonSlot> getSlots() {
        return Collections.unmodifiableList(slots);
    }

    public void addSlot(GarrisonSlot slot) {
        slots.add(slot);
    }

    @Nullable
    public GarrisonSlot getSlot(int slotIndex) {
        if (slotIndex >= 0 && slotIndex < slots.size()) {
            return slots.get(slotIndex);
        }
        return null;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("rx", regionX);
        tag.putInt("rz", regionZ);
        tag.putString("baseType", baseType.name());
        tag.putInt("faction", faction.id());
        tag.putLong("anchor", baseAnchor.asLong());

        ListTag slotsTag = new ListTag();
        for (GarrisonSlot slot : slots) {
            slotsTag.add(slot.toNbt());
        }
        tag.put("slots", slotsTag);

        return tag;
    }

    public static BaseGarrisonState fromNbt(CompoundTag tag) {
        int rx = tag.getInt("rx");
        int rz = tag.getInt("rz");
        BaseType baseType = BaseType.valueOf(tag.getString("baseType"));
        Faction faction = Faction.byId(tag.getInt("faction"));
        BlockPos anchor = BlockPos.of(tag.getLong("anchor"));

        List<GarrisonSlot> slots = new ArrayList<>();
        ListTag slotsTag = tag.getList("slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < slotsTag.size(); i++) {
            slots.add(GarrisonSlot.fromNbt(slotsTag.getCompound(i)));
        }

        return new BaseGarrisonState(rx, rz, baseType, faction, anchor, slots);
    }
}
