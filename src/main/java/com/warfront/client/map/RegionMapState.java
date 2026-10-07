package com.warfront.client.map;

import com.warfront.map.MapViewType;
import com.warfront.mission.MissionProfile;
import com.warfront.mission.SubRegionMission;
import com.warfront.network.RegionDetailsPayload;
import com.warfront.network.RegionMapPayload;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;

public final class RegionMapState {

    public enum ActiveTab {
        MAP_VIEW,
        INTELLIGENCE_LOGS
    }

    public enum TickAction {
        NONE,
        REFRESH_ALL,
        SYNC_DETAILS
    }

    private ActiveTab activeTab = ActiveTab.MAP_VIEW;
    private int logScrollOffset = 0;
    private final List<String> logMessages = new ArrayList<>();

    private final int playerChunkX;
    private final int playerChunkZ;
    private final int originChunkX;
    private final int originChunkZ;
    private final MapViewType viewType;

    private final Map<Long, RegionMapPayload.ChunkData> chunks = new HashMap<>();
    private final Set<Long> visitedRegions = new HashSet<>();
    private final Map<Long, BaseType> regionBases = new HashMap<>();
    private final List<RegionMapPayload.RegionMarkerData> markers = new ArrayList<>();
    private final List<RegionMapPayload.SiegeArrowData> siegeArrows = new ArrayList<>();
    private final List<RegionMapPayload.ActiveWarData> activeWars = new ArrayList<>();

    private SelectedRegion selectedRegion;
    private final boolean[] subRegionMissionToggled = new boolean[4];
    private final Set<Long> activatedRegions = new HashSet<>();

    /**
     * Client-side mission cache: regionKey → 4 SubRegionMissions.
     * Populated only when the player clicks LAUNCH ATTACK on a region.
     * Generation is deterministic so caching avoids redundant recalculation.
     */
    private final Map<Long, SubRegionMission[]> missionCache = new HashMap<>();

    /**
     * True after the player clicks CONFIRM CAMPAIGN successfully.
     * Used by the renderer to show green borders on subregion buttons.
     * Reset when a different region is selected.
     */
    private boolean campaignConfirmed = false;

    private long lastTickTimeMs = 0L;
    private int syncTimerTicks = 0;

    public RegionMapState(RegionMapPayload payload) {
        this.playerChunkX = payload.centerChunkX();
        this.playerChunkZ = payload.centerChunkZ();
        this.viewType = payload.viewType();

        int diameter = viewType.chunkDiameter();
        this.originChunkX = playerChunkX - diameter / 2;
        this.originChunkZ = playerChunkZ - diameter / 2;

        updateMapData(payload);
    }

    public MapViewType getViewType() {
        return viewType;
    }

    public boolean isCommandTerminalMap() {
        return viewType == MapViewType.COMMAND;
    }

    public boolean isDebugMap() {
        return viewType == MapViewType.DEBUG;
    }

    public boolean updateMapData(RegionMapPayload payload) {
        this.chunks.clear();
        this.visitedRegions.clear();
        this.regionBases.clear();

        for (RegionMapPayload.ChunkData chunk : payload.chunks()) {
            chunks.put(ChunkPos.asLong(chunk.chunkX(), chunk.chunkZ()), chunk);
            int rx = Math.floorDiv(chunk.chunkX(), 8);
            int rz = Math.floorDiv(chunk.chunkZ(), 8);
            long regKey = ChunkPos.asLong(rx, rz);
            if (chunk.isVisited()) {
                visitedRegions.add(regKey);
            }
            if (chunk.factionId() != Faction.UNCLAIMED.id() && chunk.isVisited()) {
                regionBases.putIfAbsent(regKey, BaseType.NONE);
            }
        }
        if (payload.markers() != null) {
            markers.clear();
            markers.addAll(payload.markers());
            for (RegionMapPayload.RegionMarkerData marker : payload.markers()) {
                long regKey = ChunkPos.asLong(marker.regionX(), marker.regionZ());
                regionBases.put(regKey, BaseType.byId(marker.baseTypeId()));
            }
        }
        if (payload.siegeArrows() != null) {
            siegeArrows.clear();
            siegeArrows.addAll(payload.siegeArrows());
        }
        if (payload.activeWars() != null) {
            activeWars.clear();
            activeWars.addAll(payload.activeWars());
        }
        if (payload.logMessages() != null) {
            logMessages.clear();
            logMessages.addAll(payload.logMessages());
        }

        boolean shouldRefreshDetails = false;
        if (selectedRegion != null && selectedRegion.underSiege()) {
            boolean regionStillSieged = isRegionAtWar(selectedRegion.regionX(), selectedRegion.regionZ());
            if (!regionStillSieged) {
                shouldRefreshDetails = true;
            }
        }

        return shouldRefreshDetails;
    }

    public void selectRegion(RegionDetailsPayload payload) {
        boolean isSameRegion = selectedRegion != null
                && selectedRegion.regionX() == payload.regionX()
                && selectedRegion.regionZ() == payload.regionZ();

        long regKey = ChunkPos.asLong(payload.regionX(), payload.regionZ());

        if (!isSameRegion) {
            // Reset toggles and staging only when switching to a completely different region
            for (int i = 0; i < 4; i++) {
                subRegionMissionToggled[i] = false;
            }
            campaignConfirmed = false;
        }

        selectedRegion = new SelectedRegion(
                payload.regionX(),
                payload.regionZ(),
                payload.subX(),
                payload.subZ(),
                Faction.byId(payload.factionId()),
                payload.stability(),
                payload.resistance(),
                BaseType.byId(payload.baseTypeId()),
                payload.underSiege(),
                payload.isVisited(),
                payload.remainingSiegeTicks(),
                payload.dominoThreshold(),
                payload.reachableMask(),
                payload.regionReachable(),
                payload.existingSiegeMask(),
                payload.conqueredMask(),
                Faction.byId(payload.attackerFactionId()),
                payload.isAwaitingReinforcements(),
                payload.reinforcementRemainingTicks(),
                payload.isEncircled(),
                payload.baseAnchor(),
                payload.missionSeed());

        if (payload.underSiege()) {
            activatedRegions.add(regKey);
            if (payload.existingSiegeMask() != 0) {
                setConfirmedSubMask(payload.regionX(), payload.regionZ(), payload.existingSiegeMask());
            }
        } else if (!isSameRegion) {
            activatedRegions.remove(regKey);
            clearConfirmedSubMask(payload.regionX(), payload.regionZ());
        }
    }

    public TickAction tickTimer() {
        long currentTimeMs = System.currentTimeMillis();
        if (lastTickTimeMs == 0L) {
            lastTickTimeMs = currentTimeMs;
        }

        if (currentTimeMs - lastTickTimeMs >= 1000L) {
            long secondsElapsed = (currentTimeMs - lastTickTimeMs) / 1000L;
            lastTickTimeMs = currentTimeMs;

            if (selectedRegion != null && selectedRegion.remainingSiegeTicks() > 0) {
                long newTicks = Math.max(0L, selectedRegion.remainingSiegeTicks() - (secondsElapsed * 20L));
                selectedRegion = selectedRegion.withRemainingSiegeTicks(newTicks);
                if (newTicks == 0L) {
                    return TickAction.REFRESH_ALL;
                }
            }

            if (selectedRegion != null && selectedRegion.isAwaitingReinforcements() && !selectedRegion.isEncircled() && selectedRegion.reinforcementRemainingTicks() > 0) {
                long newReinfTicks = Math.max(0L, selectedRegion.reinforcementRemainingTicks() - (secondsElapsed * 20L));
                selectedRegion = selectedRegion.withReinforcementRemainingTicks(newReinfTicks);
                if (newReinfTicks == 0L) {
                    return TickAction.REFRESH_ALL;
                }
            }

            syncTimerTicks++;
            if (syncTimerTicks >= 4 && selectedRegion != null && (!viewType.hasFogOfWar() || selectedRegion.isVisited())) {
                syncTimerTicks = 0;
                return TickAction.SYNC_DETAILS;
            }
        }

        return TickAction.NONE;
    }

    public int mapChunkDiameter() {
        return viewType.chunkDiameter();
    }

    public ActiveTab getActiveTab() {
        return activeTab;
    }

    public void setActiveTab(ActiveTab activeTab) {
        this.activeTab = activeTab;
    }

    public int getLogScrollOffset() {
        return logScrollOffset;
    }

    public void setLogScrollOffset(int logScrollOffset) {
        this.logScrollOffset = logScrollOffset;
    }

    public List<String> getLogMessages() {
        return Collections.unmodifiableList(logMessages);
    }

    public int getPlayerChunkX() {
        return playerChunkX;
    }

    public int getPlayerChunkZ() {
        return playerChunkZ;
    }

    public int getOriginChunkX() {
        return originChunkX;
    }

    public int getOriginChunkZ() {
        return originChunkZ;
    }

    public Map<Long, RegionMapPayload.ChunkData> getChunks() {
        return chunks;
    }

    public List<RegionMapPayload.RegionMarkerData> getMarkers() {
        return markers;
    }

    public List<RegionMapPayload.SiegeArrowData> getSiegeArrows() {
        return siegeArrows;
    }

    public List<RegionMapPayload.ActiveWarData> getActiveWars() {
        return Collections.unmodifiableList(activeWars);
    }

    public boolean isRegionVisible(int regionX, int regionZ) {
        if (isDebugMap() || !viewType.hasFogOfWar()) {
            return true;
        }
        return visitedRegions.contains(ChunkPos.asLong(regionX, regionZ));
    }

    public BaseType getRegionBase(int regionX, int regionZ) {
        return regionBases.get(ChunkPos.asLong(regionX, regionZ));
    }

    public BaseType getRegionBase(long regionKey) {
        return regionBases.get(regionKey);
    }

    public boolean isRegionAtWar(int regionX, int regionZ) {
        long key = ChunkPos.asLong(regionX, regionZ);
        if (activatedRegions.contains(key)) {
            return true;
        }
        for (RegionMapPayload.ActiveWarData war : activeWars) {
            if (war.regionX() == regionX && war.regionZ() == regionZ) {
                return true;
            }
        }
        if (selectedRegion != null && selectedRegion.regionX() == regionX && selectedRegion.regionZ() == regionZ) {
            return selectedRegion.underSiege();
        }
        return false;
    }

    public RegionMapPayload.ActiveWarData getActiveWar(int regionX, int regionZ) {
        for (RegionMapPayload.ActiveWarData war : activeWars) {
            if (war.regionX() == regionX && war.regionZ() == regionZ) {
                return war;
            }
        }
        return null;
    }

    public SelectedRegion getSelectedRegion() {
        return selectedRegion;
    }

    public void setSelectedRegion(SelectedRegion selectedRegion) {
        this.selectedRegion = selectedRegion;
    }

    public boolean[] getSubRegionMissionToggled() {
        return subRegionMissionToggled;
    }

    public boolean isSubRegionMissionToggled(int index) {
        return subRegionMissionToggled[index];
    }

    public void setSubRegionMissionToggled(int index, boolean value) {
        subRegionMissionToggled[index] = value;
    }

    public void toggleSubRegionMission(int index) {
        subRegionMissionToggled[index] = !subRegionMissionToggled[index];
    }

    public Set<Long> getActivatedRegions() {
        return activatedRegions;
    }

    // -----------------------------------------------------------------------
    // Mission cache
    // -----------------------------------------------------------------------

    /**
     * Returns the cached missions for the region, generating and caching them
     * on first call. Must only be called after the player has clicked LAUNCH
     * ATTACK (i.e. when selectedRegion is non-null).
     */
    public SubRegionMission[] getOrGenerateMissions(
            int regionX, int regionZ,
            com.warfront.region.Faction faction,
            com.warfront.region.BaseType baseType,
            float resistance, float stability) {
        return getOrGenerateMissions(regionX, regionZ, faction, baseType, resistance, stability, 0L, null);
    }

    /**
     * Returns the cached missions for the region, generating and caching them
     * on first call. Automatically invalidates the cache if the region's authoritative
     * seed has changed (e.g. following a post-war reinforcement state salt roll).
     */
    public SubRegionMission[] getOrGenerateMissions(
            int regionX, int regionZ,
            com.warfront.region.Faction faction,
            com.warfront.region.BaseType baseType,
            float resistance, float stability,
            long seed, net.minecraft.core.BlockPos baseAnchor) {
        long key = ChunkPos.asLong(regionX, regionZ);
        SubRegionMission[] cached = missionCache.get(key);
        if (cached != null && cached.length > 0 && seed != 0L && cached[0].seed() != seed) {
            missionCache.remove(key);
            cached = null;
        }
        if (cached != null) {
            return cached;
        }
        SubRegionMission[] missions = MissionProfile.generateForRegion(
                regionX, regionZ, faction, baseType, resistance, stability, seed, baseAnchor);
        missionCache.put(key, missions);
        return missions;
    }

    /**
     * Returns the cached missions for a region, or {@code null} if not yet generated.
     */
    public SubRegionMission[] getCachedMissions(int regionX, int regionZ) {
        return missionCache.get(ChunkPos.asLong(regionX, regionZ));
    }

    // -----------------------------------------------------------------------
    // Campaign confirmed state & subregion mission tracking
    // -----------------------------------------------------------------------

    private final Map<Long, Integer> confirmedMissionsMap = new HashMap<>();

    public int getConfirmedSubMask(int regionX, int regionZ) {
        return confirmedMissionsMap.getOrDefault(ChunkPos.asLong(regionX, regionZ), 0);
    }

    public void setConfirmedSubMask(int regionX, int regionZ, int mask) {
        long key = ChunkPos.asLong(regionX, regionZ);
        int existing = confirmedMissionsMap.getOrDefault(key, 0);
        confirmedMissionsMap.put(key, existing | mask);
    }

    public void clearConfirmedSubMask(int regionX, int regionZ) {
        confirmedMissionsMap.remove(ChunkPos.asLong(regionX, regionZ));
    }

    public boolean isSubRegionConfirmed(int regionX, int regionZ, int index) {
        int mask = getConfirmedSubMask(regionX, regionZ);
        int subX = index % 2;
        int subZ = index / 2;
        int bit = subZ * 2 + subX;
        return (mask & (1 << bit)) != 0;
    }

    public boolean isCampaignConfirmed() {
        return campaignConfirmed;
    }

    public void setCampaignConfirmed(boolean confirmed) {
        this.campaignConfirmed = confirmed;
    }
}

