package com.warfront.region.base.garrison;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * SavedData managing persistent base garrisons across the world.
 * Stored in data/warfront_base_garrisons.dat.
 */
public final class BaseGarrisonData extends SavedData {

    public static final String DATA_FILE_ID = "warfront_base_garrisons";

    private final Map<Long, BaseGarrisonState> garrisons = new HashMap<>();

    public static final Factory<BaseGarrisonData> FACTORY = new Factory<>(
            BaseGarrisonData::new,
            BaseGarrisonData::load
    );

    public static BaseGarrisonData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_FILE_ID);
    }

    public static BaseGarrisonData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_FILE_ID);
    }

    public BaseGarrisonData() {
    }

    @Nullable
    public BaseGarrisonState getGarrison(int rx, int rz) {
        return garrisons.get(ChunkPos.asLong(rx, rz));
    }

    public boolean hasGarrison(int rx, int rz) {
        return garrisons.containsKey(ChunkPos.asLong(rx, rz));
    }

    public void putGarrison(BaseGarrisonState state) {
        garrisons.put(ChunkPos.asLong(state.regionX(), state.regionZ()), state);
        setDirty();
    }

    public void removeGarrison(int rx, int rz) {
        if (garrisons.remove(ChunkPos.asLong(rx, rz)) != null) {
            setDirty();
        }
    }

    public Collection<BaseGarrisonState> getAllGarrisons() {
        return garrisons.values();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (BaseGarrisonState state : garrisons.values()) {
            list.add(state.toNbt());
        }
        tag.put("garrisons", list);
        return tag;
    }

    public static BaseGarrisonData load(CompoundTag tag, HolderLookup.Provider registries) {
        BaseGarrisonData data = new BaseGarrisonData();
        ListTag list = tag.getList("garrisons", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag sTag = list.getCompound(i);
            BaseGarrisonState state = BaseGarrisonState.fromNbt(sTag);
            data.garrisons.put(ChunkPos.asLong(state.regionX(), state.regionZ()), state);
        }
        return data;
    }
}
