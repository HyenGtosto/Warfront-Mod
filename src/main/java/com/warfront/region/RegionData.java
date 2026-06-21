package com.warfront.region;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public final class RegionData extends SavedData {
    public static final int REGION_SIZE_BLOCKS = 128;
    private static final String DATA_FILE_ID = "warfront_regions_128";
    private static final String OWNERS_TAG = "owners";
    private static final String REGION_TAG = "region";
    private static final String FACTION_TAG = "faction";
    private static final Factory<RegionData> FACTORY = new Factory<>(RegionData::new, RegionData::load);

    private final Map<Long, Faction> owners = new HashMap<>();

    public static RegionData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_FILE_ID);
    }

    private static RegionData load(CompoundTag tag, HolderLookup.Provider registries) {
        RegionData data = new RegionData();
        ListTag owners = tag.getList(OWNERS_TAG, Tag.TAG_COMPOUND);

        for (int index = 0; index < owners.size(); index++) {
            CompoundTag ownerTag = owners.getCompound(index);
            Faction faction = Faction.byId(ownerTag.getInt(FACTION_TAG));
            if (faction != Faction.UNCLAIMED) {
                data.owners.put(ownerTag.getLong(REGION_TAG), faction);
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag ownersTag = new ListTag();

        for (Map.Entry<Long, Faction> entry : owners.entrySet()) {
            CompoundTag ownerTag = new CompoundTag();
            ownerTag.putLong(REGION_TAG, entry.getKey());
            ownerTag.putInt(FACTION_TAG, entry.getValue().id());
            ownersTag.add(ownerTag);
        }

        tag.put(OWNERS_TAG, ownersTag);
        return tag;
    }

    public Region regionAt(BlockPos position) {
        return regionAt(Math.floorDiv(position.getX(), REGION_SIZE_BLOCKS), Math.floorDiv(position.getZ(), REGION_SIZE_BLOCKS));
    }

    public Region regionAt(int regionX, int regionZ) {
        return new Region(regionX, regionZ, ownerAt(regionX, regionZ));
    }

    public void setOwner(int regionX, int regionZ, Faction faction) {
        long regionId = ChunkPos.asLong(regionX, regionZ);
        if (faction == Faction.UNCLAIMED) {
            owners.remove(regionId);
        } else {
            owners.put(regionId, faction);
        }
        setDirty();
    }

    private Faction ownerAt(int regionX, int regionZ) {
        return owners.getOrDefault(ChunkPos.asLong(regionX, regionZ), Faction.UNCLAIMED);
    }

    public record Region(int x, int z, Faction owner) {
    }
}
