package com.warfront.client.map;

import com.warfront.map.MapViewType;
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
    private final List<RegionMapPayload.RegionMarkerData> markers = new ArrayList<>();
    private final List<RegionMapPayload.SiegeArrowData> siegeArrows = new ArrayList<>();

    private SelectedRegion selectedRegion;
    private final boolean[] subRegionMissionToggled = new boolean[4];
    private final Set<Long> activatedRegions = new HashSet<>();

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
        for (RegionMapPayload.ChunkData chunk : payload.chunks()) {
            chunks.put(ChunkPos.asLong(chunk.chunkX(), chunk.chunkZ()), chunk);
        }
        if (payload.markers() != null) {
            markers.clear();
            markers.addAll(payload.markers());
        }
        if (payload.siegeArrows() != null) {
            siegeArrows.clear();
            siegeArrows.addAll(payload.siegeArrows());
        }
        if (payload.logMessages() != null) {
            logMessages.clear();
            logMessages.addAll(payload.logMessages());
        }

        boolean shouldRefreshDetails = false;
        if (selectedRegion != null && selectedRegion.underSiege()) {
            int rcx = selectedRegion.regionX() * 8;
            int rcz = selectedRegion.regionZ() * 8;
            boolean foundAny = false;
            boolean regionStillSieged = false;
            outer:
            for (int cx = 0; cx < 8; cx++) {
                for (int cz = 0; cz < 8; cz++) {
                    RegionMapPayload.ChunkData c = chunks.get(ChunkPos.asLong(rcx + cx, rcz + cz));
                    if (c != null) {
                        foundAny = true;
                        regionStillSieged = c.underSiege();
                        break outer;
                    }
                }
            }
            if (foundAny && !regionStillSieged) {
                shouldRefreshDetails = true;
            }
        }

        return shouldRefreshDetails;
    }

    public void selectRegion(RegionDetailsPayload payload) {
        boolean prevWasSieged = selectedRegion != null
                && selectedRegion.regionX() == payload.regionX()
                && selectedRegion.regionZ() == payload.regionZ()
                && selectedRegion.underSiege();

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
                payload.conqueredMask());

        long regKey = ChunkPos.asLong(payload.regionX(), payload.regionZ());

        if (payload.underSiege() && payload.existingSiegeMask() != 0) {
            activatedRegions.add(regKey);
            for (int i = 0; i < 4; i++) {
                int sx = i % 2;
                int sz = i / 2;
                int bit = sz * 2 + sx;
                boolean isConquered = (payload.conqueredMask() & (1 << bit)) != 0;
                if (!isConquered) {
                    boolean wasActive = (payload.existingSiegeMask() & (1 << bit)) != 0;
                    subRegionMissionToggled[i] = wasActive;
                } else {
                    subRegionMissionToggled[i] = false;
                }
            }
        } else if (!payload.underSiege() && prevWasSieged) {
            activatedRegions.remove(regKey);
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
}
