package com.warfront.region;

import com.warfront.Warfront;
import com.warfront.region.generator.ProceduralRegionGenerator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public final class RegionData extends SavedData {
    public static final int REGION_SIZE_BLOCKS = 128;
    private static final String DATA_FILE_ID = "warfront_regions_128_v2";
    private static final String REGIONS_TAG = "regions";
    private static final String SUB_REGIONS_TAG = "sub_regions";
    private static final String SIEGES_TAG = "sieges";
    private static final String LOGS_TAG = "warfront_logs";
    private static final String REGION_TAG = "region";
    private static final String SUB_X_TAG = "sub_x";
    private static final String SUB_Z_TAG = "sub_z";
    private static final String FACTION_TAG = "faction";
    private static final String STABILITY_TAG = "stability";
    private static final String RESISTANCE_TAG = "resistance";
    private static final String BASE_TYPE_TAG = "base_type";
    private static final String SIEGE_TAG = "under_siege";
    private static final Factory<RegionData> FACTORY = new Factory<>(RegionData::new, RegionData::load);

    private final Map<Long, RegionState> regions = new HashMap<>();
    private final Map<Long, SubRegionState> subRegions = new HashMap<>();
    private final Map<Long, SiegeCampaign> activeSieges = new HashMap<>();
    private final Map<Long, Integer> zombieRetaliationWeights = new HashMap<>();
    private final List<String> warfrontLogs = new ArrayList<>();
    private final java.util.Set<Long> visitedRegions = new java.util.HashSet<>();
    private long worldSeed;
    private transient ServerLevel level;

    public static RegionData get(ServerLevel level) {
        RegionData data = level.getDataStorage().computeIfAbsent(FACTORY, DATA_FILE_ID);
        data.worldSeed = level.getSeed();
        data.level = level;

        // Load persisted war events from world log file
        List<String> diskLogs = WarfrontWorldLogger.readLogs(level);
        for (String diskLog : diskLogs) {
            if (!data.warfrontLogs.contains(diskLog)) {
                data.warfrontLogs.add(diskLog);
            }
        }
        while (data.warfrontLogs.size() > 50) {
            data.warfrontLogs.remove(0);
        }

        return data;
    }

    public ServerLevel getLevel() {
        return level;
    }

    private static RegionData load(CompoundTag tag, HolderLookup.Provider registries) {
        RegionData data = new RegionData();

        ListTag regionsTag = tag.getList(REGIONS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < regionsTag.size(); index++) {
            CompoundTag regionTag = regionsTag.getCompound(index);
            Faction faction = Faction.byId(regionTag.getInt(FACTION_TAG));
            BaseType baseType = BaseType.byId(regionTag.getInt(BASE_TYPE_TAG));
            long clusterId = regionTag.contains("cluster_id", Tag.TAG_LONG) ? regionTag.getLong("cluster_id") : 0L;
            if (faction != Faction.UNCLAIMED) {
                float rawStab = regionTag.getFloat(STABILITY_TAG);
                float rawRes = regionTag.getFloat(RESISTANCE_TAG);

                // Auto-migrate legacy 0.0-1.0 scale to 0.0-100.0 scale
                if (rawStab > 0.0F && rawStab <= 1.0F) rawStab *= 100.0F;
                if (rawRes > 0.0F && rawRes <= 1.0F) rawRes *= 100.0F;

                data.regions.put(regionTag.getLong(REGION_TAG), new RegionState(
                        faction,
                        Math.clamp(rawStab, 0.0F, 100.0F),
                        Math.clamp(rawRes, 0.0F, 100.0F),
                        baseType,
                        clusterId));
            }
        }

        ListTag subRegionsTag = tag.getList(SUB_REGIONS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < subRegionsTag.size(); index++) {
            CompoundTag subTag = subRegionsTag.getCompound(index);
            long regionId = subTag.getLong(REGION_TAG);
            int subX = subTag.getInt(SUB_X_TAG);
            int subZ = subTag.getInt(SUB_Z_TAG);
            Faction faction = Faction.byId(subTag.getInt(FACTION_TAG));
            float rawStab = subTag.getFloat(STABILITY_TAG);
            long clusterId = subTag.contains("cluster_id", Tag.TAG_LONG) ? subTag.getLong("cluster_id") : 0L;

            if (rawStab > 0.0F && rawStab <= 1.0F) rawStab *= 100.0F;

            float stability = Math.clamp(rawStab, 0.0F, 100.0F);
            boolean underSiege = subTag.getBoolean(SIEGE_TAG);

            long subKey = subRegionKeyFromRegionId(regionId, subX, subZ);
            data.subRegions.put(subKey, new SubRegionState(faction, stability, underSiege, clusterId));
        }

        ListTag siegesTag = tag.getList(SIEGES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < siegesTag.size(); index++) {
            CompoundTag sTag = siegesTag.getCompound(index);
            long targetId = sTag.getLong("target_id");
            Faction attacker = Faction.byId(sTag.getInt("attacker_id"));
            ListTag sourcesTag = sTag.getList("sources", Tag.TAG_COMPOUND);
            List<SourcePos> sources = new ArrayList<>();
            for (int sIndex = 0; sIndex < sourcesTag.size(); sIndex++) {
                CompoundTag srcTag = sourcesTag.getCompound(sIndex);
                sources.add(new SourcePos(srcTag.getInt("src_x"), srcTag.getInt("src_z")));
            }

            long durationTicks;
            if (sTag.contains("duration_ticks", Tag.TAG_LONG)) {
                durationTicks = sTag.getLong("duration_ticks");
            } else if (attacker == Faction.HUMANITY) {
                durationTicks = 4000L;
            } else {
                durationTicks = com.warfront.config.WarfrontConfig.SIEGE_RESOLUTION_DURATION_SECONDS.get() * 20L;
            }

            int activeSubRegionsMask = sTag.contains("active_sub_regions_mask", Tag.TAG_INT)
                    ? sTag.getInt("active_sub_regions_mask")
                    : 0xF;

            long attackerClusterId = sTag.contains("attacker_cluster_id", Tag.TAG_LONG)
                    ? sTag.getLong("attacker_cluster_id")
                    : 0L;

            data.activeSieges.put(targetId, new SiegeCampaign(
                    attacker,
                    sTag.getInt("tgt_rx"), sTag.getInt("tgt_rz"),
                    sources,
                    sTag.getInt("attack_val"),
                    sTag.getBoolean("encircled"),
                    sTag.getLong("start_tick"),
                    durationTicks,
                    activeSubRegionsMask,
                    attackerClusterId
            ));
        }

        ListTag logsTag = tag.getList(LOGS_TAG, Tag.TAG_STRING);
        for (int index = 0; index < logsTag.size(); index++) {
            data.warfrontLogs.add(logsTag.getString(index));
        }

        ListTag visitedTag = tag.getList("visited_regions", Tag.TAG_LONG);
        for (int index = 0; index < visitedTag.size(); index++) {
            if (visitedTag.get(index) instanceof net.minecraft.nbt.LongTag longTag) {
                data.visitedRegions.add(longTag.getAsLong());
            }
        }

        ListTag retTag = tag.getList("zombie_retaliation", Tag.TAG_COMPOUND);
        for (int index = 0; index < retTag.size(); index++) {
            CompoundTag rTag = retTag.getCompound(index);
            data.zombieRetaliationWeights.put(rTag.getLong("region_id"), rTag.getInt("weight"));
        }

        data.migrateLegacyClusterIds();

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag regionsTag = new ListTag();
        for (Map.Entry<Long, RegionState> entry : regions.entrySet()) {
            CompoundTag regionTag = new CompoundTag();
            regionTag.putLong(REGION_TAG, entry.getKey());
            regionTag.putInt(FACTION_TAG, entry.getValue().owner().id());
            regionTag.putFloat(STABILITY_TAG, entry.getValue().stability());
            regionTag.putFloat(RESISTANCE_TAG, entry.getValue().resistance());
            regionTag.putInt(BASE_TYPE_TAG, entry.getValue().baseType().id());
            regionTag.putLong("cluster_id", entry.getValue().clusterId());
            regionsTag.add(regionTag);
        }
        tag.put(REGIONS_TAG, regionsTag);

        ListTag subRegionsTag = new ListTag();
        for (Map.Entry<Long, SubRegionState> entry : subRegions.entrySet()) {
            CompoundTag subTag = new CompoundTag();
            long subKey = entry.getKey();
            long regionId = subKey >> 2;
            int subX = (int) (subKey & 1);
            int subZ = (int) ((subKey >> 1) & 1);

            subTag.putLong(REGION_TAG, regionId);
            subTag.putInt(SUB_X_TAG, subX);
            subTag.putInt(SUB_Z_TAG, subZ);
            subTag.putInt(FACTION_TAG, entry.getValue().owner().id());
            subTag.putFloat(STABILITY_TAG, entry.getValue().stability());
            subTag.putBoolean(SIEGE_TAG, entry.getValue().underSiege());
            subTag.putLong("cluster_id", entry.getValue().clusterId());
            subRegionsTag.add(subTag);
        }
        tag.put(SUB_REGIONS_TAG, subRegionsTag);

        ListTag siegesTag = new ListTag();
        for (Map.Entry<Long, SiegeCampaign> entry : activeSieges.entrySet()) {
            CompoundTag sTag = new CompoundTag();
            SiegeCampaign sc = entry.getValue();
            sTag.putLong("target_id", entry.getKey());
            sTag.putInt("attacker_id", sc.attacker().id());
            sTag.putInt("tgt_rx", sc.targetRegionX());
            sTag.putInt("tgt_rz", sc.targetRegionZ());
            sTag.putInt("attack_val", sc.attackValue());
            sTag.putBoolean("encircled", sc.encircled());
            sTag.putLong("start_tick", sc.startTick());
            sTag.putLong("duration_ticks", sc.durationTicks());
            sTag.putInt("active_sub_regions_mask", sc.activeSubRegionsMask());
            sTag.putLong("attacker_cluster_id", sc.attackerClusterId());

            ListTag sourcesTag = new ListTag();
            for (SourcePos src : sc.sources()) {
                CompoundTag srcTag = new CompoundTag();
                srcTag.putInt("src_x", src.x());
                srcTag.putInt("src_z", src.z());
                sourcesTag.add(srcTag);
            }
            sTag.put("sources", sourcesTag);

            siegesTag.add(sTag);
        }
        tag.put(SIEGES_TAG, siegesTag);

        ListTag logsTag = new ListTag();
        for (String logMsg : warfrontLogs) {
            logsTag.add(StringTag.valueOf(logMsg));
        }
        tag.put(LOGS_TAG, logsTag);

        ListTag visitedTag = new ListTag();
        for (long key : visitedRegions) {
            visitedTag.add(net.minecraft.nbt.LongTag.valueOf(key));
        }
        tag.put("visited_regions", visitedTag);

        ListTag retTag = new ListTag();
        for (Map.Entry<Long, Integer> entry : zombieRetaliationWeights.entrySet()) {
            CompoundTag rTag = new CompoundTag();
            rTag.putLong("region_id", entry.getKey());
            rTag.putInt("weight", entry.getValue());
            retTag.add(rTag);
        }
        tag.put("zombie_retaliation", retTag);

        return tag;
    }

    public boolean isRegionVisited(int regionX, int regionZ) {
        return visitedRegions.contains(ChunkPos.asLong(regionX, regionZ));
    }

    public void unlock3x3Around(int centerRegionX, int centerRegionZ) {
        boolean changed = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(centerRegionX + dx, centerRegionZ + dz);
                if (visitedRegions.add(key)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public void addLog(ServerLevel level, String message) {
        if (message != null && !message.isBlank()) {
            warfrontLogs.add(message);
            while (warfrontLogs.size() > 30) {
                warfrontLogs.remove(0);
            }
            if (level != null) {
                WarfrontWorldLogger.logEvent(level, message);
            }
            setDirty();
        }
    }

    public static void broadcastTitle(ServerLevel level, Component title, Component subtitle) {
        if (level == null || level.getServer() == null) return;
        net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket animPacket =
                new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5, 30, 5); // 30 ticks = 1.5 seconds
        net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket titlePacket =
                new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(title);
        net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket subtitlePacket =
                (subtitle != null) ? new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(subtitle) : null;

        for (net.minecraft.server.level.ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level) {
                player.connection.send(animPacket);
                player.connection.send(titlePacket);
                if (subtitlePacket != null) {
                    player.connection.send(subtitlePacket);
                }
            }
        }
    }

    public void addLog(String message) {
        addLog(null, message);
    }

    public List<String> getWarfrontLogs() {
        return new ArrayList<>(warfrontLogs);
    }

    public List<Region> getRegionsOwnedBy(Faction faction) {
        List<Region> list = new ArrayList<>();
        for (Map.Entry<Long, RegionState> entry : regions.entrySet()) {
            if (entry.getValue().owner() == faction) {
                long key = entry.getKey();
                int rx = ChunkPos.getX(key);
                int rz = ChunkPos.getZ(key);
                list.add(new Region(rx, rz, faction, entry.getValue().stability(), entry.getValue().resistance(), entry.getValue().baseType(), entry.getValue().clusterId()));
            }
        }
        return list;
    }

    public void addZombieRetaliation(int regionX, int regionZ, int boost) {
        long key = ChunkPos.asLong(regionX, regionZ);
        int current = zombieRetaliationWeights.getOrDefault(key, 0);
        zombieRetaliationWeights.put(key, Math.min(10, current + boost));
        setDirty();
    }

    public int getZombieRetaliation(int regionX, int regionZ) {
        return zombieRetaliationWeights.getOrDefault(ChunkPos.asLong(regionX, regionZ), 0);
    }

    public void decayZombieRetaliation() {
        if (zombieRetaliationWeights.isEmpty()) return;
        boolean changed = false;
        java.util.Iterator<Map.Entry<Long, Integer>> iterator = zombieRetaliationWeights.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Integer> entry = iterator.next();
            int val = entry.getValue() - 1;
            if (val <= 0) {
                iterator.remove();
            } else {
                entry.setValue(val);
            }
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public Map<Long, SiegeCampaign> getActiveSieges() {
        return activeSieges;
    }

    public SiegeCampaign getSiege(int regionX, int regionZ) {
        return activeSieges.get(ChunkPos.asLong(regionX, regionZ));
    }

    public Region regionAt(BlockPos position) {
        return regionAt(Math.floorDiv(position.getX(), REGION_SIZE_BLOCKS), Math.floorDiv(position.getZ(), REGION_SIZE_BLOCKS));
    }

    public RegionState getSavedRegionState(int regionX, int regionZ) {
        return regions.get(ChunkPos.asLong(regionX, regionZ));
    }

    public Region regionAt(int regionX, int regionZ) {
        RegionState state = regions.get(ChunkPos.asLong(regionX, regionZ));
        if (state == null) {
            state = ProceduralRegionGenerator.getInstance().generateRegion(this.level, worldSeed, regionX, regionZ);
        }
        return new Region(regionX, regionZ, state.owner(), state.stability(), state.resistance(), state.baseType(), state.clusterId());
    }

    public SubRegionState subRegionAt(BlockPos position) {
        int chunkX = Math.floorDiv(position.getX(), 16);
        int chunkZ = Math.floorDiv(position.getZ(), 16);
        SubRegionPos pos = SubRegionPos.fromChunk(chunkX, chunkZ);
        return subRegionAt(pos.regionX(), pos.regionZ(), pos.subX(), pos.subZ());
    }

    public SubRegionState subRegionAt(int regionX, int regionZ, int subX, int subZ) {
        long key = subRegionKey(regionX, regionZ, subX, subZ);
        SubRegionState saved = subRegions.get(key);
        if (saved != null) {
            return saved;
        }

        // Fallback to base region state without calculating strength
        RegionState state = getSavedRegionState(regionX, regionZ);
        if (state == null) {
            ServerLevel targetLevel = (this.level != null) ? this.level : null;
            long seed = (targetLevel != null) ? targetLevel.getSeed() : 0L;
            state = com.warfront.region.generator.ProceduralRegionGenerator.getInstance().generateRawRegionState(targetLevel, seed, regionX, regionZ);
        }
        return new SubRegionState(state.owner(), state.stability(), false, state.clusterId());
    }

    /**
     * Returns a 4-bit mask (bit = sz*2+sx) where each bit is set if the
     * sub-region is SECURED (owned by HUMANITY in enemy territory, or not under siege in friendly territory).
     */
    public int computeSecuredMask(int regionX, int regionZ) {
        int mask = 0;
        Region region = regionAt(regionX, regionZ);
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                int bit = sz * 2 + sx;
                SubRegionState subState = subRegionAt(regionX, regionZ, sx, sz);
                if (region.owner() == Faction.HUMANITY) {
                    if (!subState.underSiege()) {
                        mask |= (1 << bit);
                    }
                } else {
                    if (subState.owner() == Faction.HUMANITY) {
                        mask |= (1 << bit);
                    }
                }
            }
        }
        return mask;
    }

    public int computeConqueredMask(int regionX, int regionZ) {
        return computeSecuredMask(regionX, regionZ);
    }

    /**
     * A region is reachable (attackable/defendable by the player) if at least one sub-region
     * inside is already owned by HUMANITY, OR if it is a HUMANITY region under siege,
     * OR if at least one of its 4 cardinal neighboring regions (N/S/E/W) is owned by HUMANITY or UNCLAIMED.
     */
    public boolean isRegionReachable(int regionX, int regionZ) {
        Region region = regionAt(regionX, regionZ);
        if (region.owner() == Faction.HUMANITY) {
            return true;
        }
        if (computeSecuredMask(regionX, regionZ) != 0) {
            return true;
        }
        int[] dx = {0,  0, -1, 1};
        int[] dz = {-1, 1,  0, 0};
        for (int i = 0; i < 4; i++) {
            Faction neighbor = regionAt(regionX + dx[i], regionZ + dz[i]).owner();
            if (neighbor == Faction.HUMANITY || neighbor == Faction.UNCLAIMED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a 4-bit mask (bit = sz*2+sx) where each bit is set if the
     * corresponding sub-region of (regionX, regionZ) is reachable.
     *
     * For Defense (HUMANITY territory): all non-secured sub-regions are reachable without border checks.
     * For Attack (Enemy territory): sub-regions bordering friendly territory are reachable.
     */
    public int computeReachableMask(int regionX, int regionZ) {
        Region region = regionAt(regionX, regionZ);
        int securedMask = computeSecuredMask(regionX, regionZ);

        if (region.owner() == Faction.HUMANITY) {
            // Defense mission: all sub-regions under siege (not yet secured) are reachable at all times
            return 0xF & ~securedMask;
        }

        // Attack mission: Pre-fetch external and internal neighbor factions
        Faction west  = regionAt(regionX - 1, regionZ).owner();
        Faction east  = regionAt(regionX + 1, regionZ).owner();
        Faction north = regionAt(regionX, regionZ - 1).owner();
        Faction south = regionAt(regionX, regionZ + 1).owner();

        Faction s00 = subRegionAt(regionX, regionZ, 0, 0).owner();
        Faction s10 = subRegionAt(regionX, regionZ, 1, 0).owner();
        Faction s01 = subRegionAt(regionX, regionZ, 0, 1).owner();
        Faction s11 = subRegionAt(regionX, regionZ, 1, 1).owner();

        int mask = 0;

        // Sub(0,0) bit 0: external=west,north  internal=Sub(1,0),Sub(0,1)
        if ((securedMask & (1 << 0)) == 0 && isReachable(west, north, s10, s01)) mask |= (1 << 0);
        // Sub(1,0) bit 1: external=east,north  internal=Sub(0,0),Sub(1,1)
        if ((securedMask & (1 << 1)) == 0 && isReachable(east, north, s00, s11)) mask |= (1 << 1);
        // Sub(0,1) bit 2: external=west,south  internal=Sub(0,0),Sub(1,1)
        if ((securedMask & (1 << 2)) == 0 && isReachable(west, south, s00, s11)) mask |= (1 << 2);
        // Sub(1,1) bit 3: external=east,south  internal=Sub(1,0),Sub(0,1)
        if ((securedMask & (1 << 3)) == 0 && isReachable(east, south, s10, s01)) mask |= (1 << 3);

        return mask;
    }

    /** True if any of the four neighbor factions is HUMANITY or UNCLAIMED. */
    private static boolean isReachable(Faction a, Faction b, Faction c, Faction d) {
        return isFriendlyOrEmpty(a) || isFriendlyOrEmpty(b)
                || isFriendlyOrEmpty(c) || isFriendlyOrEmpty(d);
    }

    private static boolean isFriendlyOrEmpty(Faction f) {
        return f == Faction.HUMANITY || f == Faction.UNCLAIMED;
    }

    public void setSiege(int regionX, int regionZ, int subX, int subZ, boolean underSiege) {
        long key = subRegionKey(regionX, regionZ, subX, subZ);
        SubRegionState current = subRegionAt(regionX, regionZ, subX, subZ);
        subRegions.put(key, new SubRegionState(current.owner(), current.stability(), underSiege, current.clusterId()));
        setDirty();
    }

    public void setRegionSiege(int regionX, int regionZ, boolean underSiege) {
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                SubRegionState current = subRegionAt(regionX, regionZ, sx, sz);
                subRegions.put(subRegionKey(regionX, regionZ, sx, sz), new SubRegionState(current.owner(), current.stability(), underSiege, current.clusterId()));
            }
        }
        if (!underSiege) {
            activeSieges.remove(ChunkPos.asLong(regionX, regionZ));
        }
        setDirty();
    }

    public void setRegionSiegeWithCampaign(int targetX, int targetZ, SiegeCampaign campaign) {
        int mask = campaign.activeSubRegionsMask();
        RegionState targetRegion = getSavedRegionState(targetX, targetZ);
        if (targetRegion == null) {
            ServerLevel targetLevel = (this.level != null) ? this.level : null;
            long seed = (targetLevel != null) ? targetLevel.getSeed() : 0L;
            targetRegion = com.warfront.region.generator.ProceduralRegionGenerator.getInstance().generateRawRegionState(targetLevel, seed, targetX, targetZ);
        }
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                int bit = sz * 2 + sx;
                boolean isSubActive = (mask & (1 << bit)) != 0;
                SubRegionState current = subRegionAt(targetX, targetZ, sx, sz);
                // For Attack: never re-siege sub-regions already owned by HUMANITY
                // For Defense: set underSiege = true for active defense targets
                boolean siegeFlag;
                if (targetRegion.owner() == Faction.HUMANITY) {
                    siegeFlag = isSubActive || current.underSiege();
                } else {
                    siegeFlag = isSubActive && current.owner() != Faction.HUMANITY;
                }
                setSiege(targetX, targetZ, sx, sz, siegeFlag);
            }
        }
        activeSieges.put(ChunkPos.asLong(targetX, targetZ), campaign);
        setDirty();
    }

    public BaseType determineBaseTypeForConqueredRegion(int regionX, int regionZ, Faction faction) {
        return determineBaseTypeForConqueredRegion(this.level, regionX, regionZ, faction);
    }

    public BaseType determineBaseTypeForConqueredRegion(ServerLevel level, int regionX, int regionZ, Faction faction) {
        if (faction == Faction.UNCLAIMED) {
            return BaseType.NONE;
        }

        long startNano = System.nanoTime();
        ServerLevel targetLevel = (level != null) ? level : this.level;
        RegionState currentRegion = getRawOrSavedRegionStateForBaseDet(targetLevel, regionX, regionZ);
        long targetClusterId = currentRegion.clusterId();

        // Count nearby same-cluster regions owned by the conquering faction (MD <= 2)
        int sameClusterRegionCount = 0;
        boolean hasNearbyBaseInCluster = false;
        int radius = 2;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > radius) continue;

                int nx = regionX + dx;
                int nz = regionZ + dz;
                RegionState neighbor = getRawOrSavedRegionStateForBaseDet(targetLevel, nx, nz);

                if (neighbor.owner() == faction && (targetClusterId == 0L || neighbor.clusterId() == targetClusterId)) {
                    sameClusterRegionCount++;
                    if (dx != 0 || dz != 0) {
                        if (neighbor.baseType() == BaseType.OUTPOST || neighbor.baseType() == BaseType.HEADQUARTERS || neighbor.baseType() == BaseType.MEGA_BASE) {
                            hasNearbyBaseInCluster = true;
                        }
                    }
                }
            }
        }

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
        com.warfront.Warfront.LOGGER.info("[PERF AI] Conquered base determination for Region ({}, {}) completed in {} ms (13 neighbor raw states evaluated, 0 strength calculations)",
                regionX, regionZ, elapsedMs);

        // Qualifies for an OUTPOST if cluster has established territory (>= 3 regions) and no adjacent base in range
        if (sameClusterRegionCount >= 3 && !hasNearbyBaseInCluster) {
            return BaseType.OUTPOST;
        }

        return BaseType.NONE;
    }

    private RegionState getRawOrSavedRegionStateForBaseDet(ServerLevel level, int rx, int rz) {
        RegionState saved = getSavedRegionState(rx, rz);
        if (saved != null) return saved;
        ServerLevel targetLevel = (level != null) ? level : this.level;
        long seed = (targetLevel != null) ? targetLevel.getSeed() : 0L;
        return com.warfront.region.generator.ProceduralRegionGenerator.getInstance().generateRawRegionState(targetLevel, seed, rx, rz);
    }

    /**
     * Calculates the domino collapse threshold for a region based on its effective stability.
     *
     * Defense (HUMANITY owner): High stability = fortified defenses = easier to hold (1 sector needed).
     * Attack (Enemy owner): High stability = strong enemy defenses = harder to conquer (4 sectors needed).
     */
    public int calculateDominoThreshold(int regionX, int regionZ) {
        float effectiveStability = calculateEffectiveStability(regionX, regionZ);
        Region region = regionAt(regionX, regionZ);
        if (region.owner() == Faction.HUMANITY) {
            return (effectiveStability >= 70.0F) ? 1 :
                   (effectiveStability >= 35.0F) ? 2 :
                   (effectiveStability >= 15.0F) ? 3 : 4;
        } else {
            return (effectiveStability <= 35.0F) ? 1 :
                   (effectiveStability <= 70.0F) ? 2 :
                   (effectiveStability < 100.0F) ? 3 : 4;
        }
    }

    public void claimSubRegion(int regionX, int regionZ, int subX, int subZ, Faction faction, float stability) {
        claimSubRegion(null, regionX, regionZ, subX, subZ, faction, stability);
    }

    public void claimSubRegion(ServerLevel level, int regionX, int regionZ, int subX, int subZ, Faction faction, float stability) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        SiegeCampaign activeCampaign = activeSieges.get(regionKey);
        if (activeCampaign != null && activeCampaign.attacker() == faction) {
            int bit = subZ * 2 + subX;
            boolean isMissionActive = (activeCampaign.activeSubRegionsMask() & (1 << bit)) != 0;
            if (!isMissionActive) {
                return;
            }
        }

        long key = subRegionKey(regionX, regionZ, subX, subZ);
        SubRegionState prevSubState = subRegionAt(regionX, regionZ, subX, subZ);
        if (faction == Faction.HUMANITY && prevSubState.owner() == Faction.ZOMBIE_HORDE) {
            addZombieRetaliation(regionX, regionZ, 2);
        }
        long subClusterId = prevSubState.clusterId();
        if (subClusterId == 0L && faction.isAI()) {
            if (activeCampaign != null && activeCampaign.attackerClusterId() != 0L) {
                subClusterId = activeCampaign.attackerClusterId();
            } else {
                subClusterId = findAdjacentClusterId(regionX, regionZ, faction);
            }
        }
        subRegions.put(key, new SubRegionState(faction, stability, false, subClusterId));

        int dominoThreshold = calculateDominoThreshold(regionX, regionZ);
        Region targetRegion = regionAt(regionX, regionZ);

        if (targetRegion.owner() == Faction.HUMANITY && faction == Faction.HUMANITY && activeCampaign != null && activeCampaign.attacker() != Faction.HUMANITY) {
            // DEFENSE RESOLUTION: Count how many sub-regions in this region are SECURED (not under siege)
            int securedCount = 0;
            for (int sx = 0; sx <= 1; sx++) {
                for (int sz = 0; sz <= 1; sz++) {
                    if (!subRegionAt(regionX, regionZ, sx, sz).underSiege()) {
                        securedCount++;
                    }
                }
            }

            if (securedCount >= dominoThreshold) {
                // Defense threshold reached -> DEFENSE SUCCESSFUL! Clear siege campaign completely!
                setRegionSiege(regionX, regionZ, false);
                activeSieges.remove(regionKey);
                addLog(level, String.format("§aDefense successful: Region (%d, %d), siege cleared.", regionX, regionZ));
                broadcastTitle(level, Component.literal("§a§lDEFENSE SUCCESSFUL!"), Component.literal(String.format("§7Enemy attack repelled from Region (%d, %d)", regionX, regionZ)));
                Warfront.LOGGER.info("Defense successful: Region ({}, {}), siege cleared.", regionX, regionZ);
                if (level != null) {
                    com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);
                }
            }
        } else {
            // ATTACK RESOLUTION: Count sub-regions owned by attacking faction
            int matchingCount = 0;
            for (int sx = 0; sx <= 1; sx++) {
                for (int sz = 0; sz <= 1; sz++) {
                    if (subRegionAt(regionX, regionZ, sx, sz).owner() == faction) {
                        matchingCount++;
                    }
                }
            }

            if (matchingCount >= dominoThreshold) {
                // Threshold reached -> auto-collapse region to faction & FULL REGION CAPTURE!
                long clusterId = 0L;
                SiegeCampaign campaign = activeSieges.get(regionKey);
                if (campaign != null && campaign.attackerClusterId() != 0L) {
                    clusterId = campaign.attackerClusterId();
                } else if (faction.isAI()) {
                    clusterId = findAdjacentClusterId(regionX, regionZ, faction);
                    if (clusterId == 0L) {
                        clusterId = ChunkPos.asLong(regionX, regionZ);
                    }
                }
                for (int sx = 0; sx <= 1; sx++) {
                    for (int sz = 0; sz <= 1; sz++) {
                        subRegions.put(subRegionKey(regionX, regionZ, sx, sz), new SubRegionState(faction, 100.0F, false, clusterId));
                    }
                }
                BaseType baseType = determineBaseTypeForConqueredRegion(level, regionX, regionZ, faction);
                com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                        com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level, regionX, regionZ, faction, baseType, clusterId, worldSeed);
                setRegion(level, regionX, regionZ, faction, strength.stability(), strength.resistance(), baseType, clusterId);
                if (faction == Faction.HUMANITY) {
                    addLog(level, String.format("§aRegion conquered: Region (%d, %d).", regionX, regionZ));
                    broadcastTitle(level, Component.literal("§a§lATTACK SUCCESSFUL!"), Component.literal(String.format("§7Region (%d, %d) Conquered", regionX, regionZ)));
                }
                if (level != null) {
                    com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);
                }
            }
        }

        // If the region STILL has an active campaign, extend its duration by +60 seconds (+1200 ticks)
        if (activeSieges.containsKey(regionKey)) {
            SiegeCampaign currentCampaign = activeSieges.get(regionKey);
            activeSieges.put(regionKey, new SiegeCampaign(
                    currentCampaign.attacker(),
                    currentCampaign.targetRegionX(), currentCampaign.targetRegionZ(),
                    currentCampaign.sources(), currentCampaign.attackValue(), currentCampaign.encircled(),
                    currentCampaign.startTick(), currentCampaign.durationTicks() + 1200L,
                    currentCampaign.activeSubRegionsMask(),
                    currentCampaign.attackerClusterId()));
        }

        setDirty();
    }

    public void setOwner(int regionX, int regionZ, Faction faction) {
        long clusterId = 0L;
        if (faction.isAI()) {
            clusterId = findAdjacentClusterId(regionX, regionZ, faction);
            if (clusterId == 0L) {
                clusterId = ChunkPos.asLong(regionX, regionZ);
            }
        }
        com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(this.level, regionX, regionZ, faction, BaseType.NONE, clusterId, worldSeed);
        setRegion(null, regionX, regionZ, faction, strength.stability(), strength.resistance(), BaseType.NONE, clusterId);
    }

    public void claim(int regionX, int regionZ, Faction faction, float stability, float resistance) {
        claim(null, regionX, regionZ, faction, stability, resistance, BaseType.NONE);
    }

    public void claim(ServerLevel level, int regionX, int regionZ, Faction faction, float stability, float resistance, BaseType baseType) {
        long clusterId = 0L;
        long regionId = ChunkPos.asLong(regionX, regionZ);
        SiegeCampaign campaign = activeSieges.get(regionId);
        if (campaign != null && campaign.attackerClusterId() != 0L) {
            clusterId = campaign.attackerClusterId();
        } else if (faction.isAI()) {
            clusterId = findAdjacentClusterId(regionX, regionZ, faction);
            if (clusterId == 0L) {
                clusterId = ChunkPos.asLong(regionX, regionZ);
            }
        }
        if (stability <= 0.0F && resistance <= 0.0F) {
            com.warfront.region.strength.RegionalStrengthCalculator.RegionalStrength strength =
                    com.warfront.region.strength.RegionalStrengthCalculator.calculateInitialStrength(level != null ? level : this.level, regionX, regionZ, faction, baseType, clusterId, worldSeed);
            stability = strength.stability();
            resistance = strength.resistance();
        }
        setRegion(level, regionX, regionZ, faction, stability, resistance, baseType, clusterId);
    }

    public void setRegion(ServerLevel level, int regionX, int regionZ, Faction faction, float stability, float resistance, BaseType baseType, long clusterId) {
        long regionId = ChunkPos.asLong(regionX, regionZ);
        activeSieges.remove(regionId);
        regions.put(regionId, new RegionState(
                faction,
                Math.clamp(stability, 0.0F, 100.0F),
                Math.clamp(resistance, 0.0F, 100.0F),
                baseType,
                clusterId));
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                subRegions.put(subRegionKey(regionX, regionZ, sx, sz), new SubRegionState(faction, stability, false, clusterId));
            }
        }

        // Gambit Mechanic: Triggered ONLY on FULL REGION CAPTURE for Humanity!
        if (faction == Faction.HUMANITY && level != null) {
            checkAndExecuteGambit(level, regionX, regionZ);
        }

        setDirty();
    }

    public float calculateEffectiveResistance(int regionX, int regionZ) {
        Region region = regionAt(regionX, regionZ);
        return region.resistance();
    }

    public float calculateEffectiveStability(int regionX, int regionZ) {
        Region region = regionAt(regionX, regionZ);
        return region.stability();
    }

    public long findAdjacentClusterId(int regionX, int regionZ, Faction faction) {
        int[][] offsets = new int[][] {
            { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 },
            { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 }
        };
        for (int[] off : offsets) {
            Region reg = regionAt(regionX + off[0], regionZ + off[1]);
            if (reg.owner() == faction && reg.clusterId() != 0L) {
                return reg.clusterId();
            }
        }
        return 0L;
    }

    public Set<Long> getAllPillagerClusterIds() {
        return getAllClusterIds(null, Faction.PILLAGER_CONQUERORS);
    }

    public Set<Long> getAllPillagerClusterIds(ServerLevel level) {
        return getAllClusterIds(level, Faction.PILLAGER_CONQUERORS);
    }

    public Set<Long> getAllClusterIds(ServerLevel level, Faction faction) {
        Set<Long> set = new HashSet<>();
        // 1. Scan stored regions in HashMap
        for (RegionState state : regions.values()) {
            if (state.owner() == faction && state.clusterId() != 0L) {
                set.add(state.clusterId());
            }
        }

        // 2. Scan procedural regions around active players & claimed Humanity regions
        List<Region> humanityRegions = getRegionsOwnedBy(Faction.HUMANITY);
        List<ChunkPos> scanOrigins = new ArrayList<>();

        if (level != null && level.getServer() != null) {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (player.level() == level) {
                    scanOrigins.add(new ChunkPos(Math.floorDiv(player.chunkPosition().x, 8), Math.floorDiv(player.chunkPosition().z, 8)));
                }
            }
        }
        for (Region hr : humanityRegions) {
            scanOrigins.add(new ChunkPos(hr.x(), hr.z()));
        }

        int scanRadius = 15;
        for (ChunkPos origin : scanOrigins) {
            for (int rx = origin.x - scanRadius; rx <= origin.x + scanRadius; rx++) {
                for (int rz = origin.z - scanRadius; rz <= origin.z + scanRadius; rz++) {
                    Region reg = regionAt(rx, rz);
                    if (reg.owner() == faction && reg.clusterId() != 0L) {
                        set.add(reg.clusterId());
                    }
                }
            }
        }

        return set;
    }

    private void migrateLegacyClusterIds() {
        boolean changed = false;
        Set<Long> visited = new HashSet<>();

        for (Map.Entry<Long, RegionState> entry : regions.entrySet()) {
            long regionId = entry.getKey();
            RegionState state = entry.getValue();

            if (state.owner() == Faction.PILLAGER_CONQUERORS && state.clusterId() == 0L && !visited.contains(regionId)) {
                long newClusterId = regionId;
                Queue<Long> queue = new ArrayDeque<>();
                queue.add(regionId);
                visited.add(regionId);

                int[][] offsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
                while (!queue.isEmpty()) {
                    long currKey = queue.poll();
                    RegionState currState = regions.get(currKey);

                    if (currState != null && currState.owner() == Faction.PILLAGER_CONQUERORS) {
                        regions.put(currKey, new RegionState(currState.owner(), currState.stability(), currState.resistance(), currState.baseType(), newClusterId));
                        for (int sx = 0; sx <= 1; sx++) {
                            for (int sz = 0; sz <= 1; sz++) {
                                long subKey = subRegionKeyFromRegionId(currKey, sx, sz);
                                SubRegionState subState = subRegions.get(subKey);
                                if (subState != null) {
                                    subRegions.put(subKey, new SubRegionState(subState.owner(), subState.stability(), subState.underSiege(), newClusterId));
                                }
                            }
                        }

                        int rx = ChunkPos.getX(currKey);
                        int rz = ChunkPos.getZ(currKey);
                        for (int[] off : offsets) {
                            long nKey = ChunkPos.asLong(rx + off[0], rz + off[1]);
                            if (!visited.contains(nKey)) {
                                RegionState nState = regions.get(nKey);
                                if (nState != null && nState.owner() == Faction.PILLAGER_CONQUERORS && nState.clusterId() == 0L) {
                                    visited.add(nKey);
                                    queue.add(nKey);
                                }
                            }
                        }
                    }
                }
                changed = true;
            }
        }

        if (changed) {
            setDirty();
        }
    }

    public void checkAndExecuteGambit(ServerLevel level, int claimedRegionX, int claimedRegionZ) {
        List<Long> resolvedTargetIds = new ArrayList<>();

        for (Map.Entry<Long, SiegeCampaign> entry : activeSieges.entrySet()) {
            SiegeCampaign campaign = entry.getValue();
            if (campaign.attacker() != Faction.HUMANITY) {
                for (SourcePos src : campaign.sources()) {
                    if (src.x() == claimedRegionX && src.z() == claimedRegionZ) {
                        resolvedTargetIds.add(entry.getKey());
                        break;
                    }
                }
            }
        }

        if (!resolvedTargetIds.isEmpty()) {
            for (long targetId : resolvedTargetIds) {
                SiegeCampaign campaign = activeSieges.get(targetId);
                if (campaign != null) {
                    int targetX = campaign.targetRegionX();
                    int targetZ = campaign.targetRegionZ();

                    // Cancel the AI siege on target region & clear underSiege status -> Defense Auto-Won!
                    setRegionSiege(targetX, targetZ, false);

                    String logMsg = String.format("§aStaging region captured: Region (%d, %d). Siege defense cleared for Region (%d, %d).",
                            claimedRegionX, claimedRegionZ, targetX, targetZ);
                    addLog(logMsg);

                    com.warfront.Warfront.LOGGER.info("Staging region captured: Region ({}, {}). Siege defense cleared for Region ({}, {}).",
                            claimedRegionX, claimedRegionZ, targetX, targetZ);
                }
            }
            com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);
        }
    }

    private static long subRegionKey(int regionX, int regionZ, int subX, int subZ) {
        long regionId = ChunkPos.asLong(regionX, regionZ);
        return subRegionKeyFromRegionId(regionId, subX, subZ);
    }

    private static long subRegionKeyFromRegionId(long regionId, int subX, int subZ) {
        return (regionId << 2) | ((subZ & 1) << 1) | (subX & 1);
    }

    public record SourcePos(int x, int z) {
    }

    public record SiegeCampaign(
            Faction attacker,
            int targetRegionX, int targetRegionZ,
            List<SourcePos> sources,
            int attackValue,
            boolean encircled,
            long startTick,
            long durationTicks,
            int activeSubRegionsMask,
            long attackerClusterId
    ) {
        public SiegeCampaign(Faction attacker, int targetRegionX, int targetRegionZ, List<SourcePos> sources, int attackValue, boolean encircled, long startTick) {
            this(attacker, targetRegionX, targetRegionZ, sources, attackValue, encircled, startTick, 4000L, 0xF, 0L);
        }

        public SiegeCampaign(Faction attacker, int targetRegionX, int targetRegionZ, List<SourcePos> sources, int attackValue, boolean encircled, long startTick, long durationTicks) {
            this(attacker, targetRegionX, targetRegionZ, sources, attackValue, encircled, startTick, durationTicks, 0xF, 0L);
        }

        public SiegeCampaign(Faction attacker, int targetRegionX, int targetRegionZ, List<SourcePos> sources, int attackValue, boolean encircled, long startTick, long durationTicks, int activeSubRegionsMask) {
            this(attacker, targetRegionX, targetRegionZ, sources, attackValue, encircled, startTick, durationTicks, activeSubRegionsMask, 0L);
        }
    }

    public record RegionState(Faction owner, float stability, float resistance, BaseType baseType, long clusterId) {
    }

    public record SubRegionState(Faction owner, float stability, boolean underSiege, long clusterId) {
    }

    public record Region(int x, int z, Faction owner, float stability, float resistance, BaseType baseType, long clusterId) {
    }
}
