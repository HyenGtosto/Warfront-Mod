package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.map.BiomeMapColors;
import com.warfront.map.MapViewType;
import com.warfront.region.RegionData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestRegionMapPayload(MapViewType viewType) implements CustomPacketPayload {
    private static final Map<UUID, MapViewType> ACTIVE_MAP_SESSIONS = new ConcurrentHashMap<>();

    public RequestRegionMapPayload() {
        this(MapViewType.COMMAND);
    }

    public static final Type<RequestRegionMapPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "request_region_map"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRegionMapPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, p -> p.viewType().id(),
            id -> new RequestRegionMapPayload(MapViewType.byId(id)));

    public static void setActiveSession(ServerPlayer player, MapViewType viewType) {
        if (player != null && viewType != null) {
            ACTIVE_MAP_SESSIONS.put(player.getUUID(), viewType);
        }
    }

    public static void clearActiveSession(ServerPlayer player) {
        if (player != null) {
            ACTIVE_MAP_SESSIONS.remove(player.getUUID());
        }
    }

    public static MapViewType getActiveSession(ServerPlayer player) {
        if (player == null) return null;
        return ACTIVE_MAP_SESSIONS.get(player.getUUID());
    }

    public static void handle(RequestRegionMapPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            sendSnapshot(player, payload.viewType(), true);
        }
    }

    public static void sendSnapshot(ServerPlayer player, MapViewType viewType, boolean isExplicitRequest) {
        setActiveSession(player, viewType);
        if (player.serverLevel().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            ServerLevel overworld = player.server.overworld();
            List<String> logMessages = (overworld != null)
                    ? RegionData.get(overworld).getWarfrontLogs()
                    : java.util.Collections.emptyList();

            PacketDistributor.sendToPlayer(player, new RegionMapPayload(
                    player.chunkPosition().x, player.chunkPosition().z,
                    java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(),
                    java.util.Collections.emptyList(),
                    logMessages,
                    isExplicitRequest,
                    viewType,
                    false
            ));
            return;
        }
        buildAndSendSnapshot(player, viewType, isExplicitRequest);
    }

    private static final Map<Long, Integer> CHUNK_BIOME_COLOR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static long cachedWorldSeed = Long.MIN_VALUE;

    public static void clearColorCache() {
        CHUNK_BIOME_COLOR_CACHE.clear();
        cachedWorldSeed = Long.MIN_VALUE;
    }

    public static int getOrComputeBiomeColor(ServerLevel level, int chunkX, int chunkZ) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return 0xFF000000;
        }

        long currentSeed = level.getSeed();
        if (cachedWorldSeed != currentSeed) {
            cachedWorldSeed = currentSeed;
            CHUNK_BIOME_COLOR_CACHE.clear();
        }

        long key = net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
        Integer cached = CHUNK_BIOME_COLOR_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int surfaceY;
        if (level.hasChunk(chunkX, chunkZ)) {
            surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, chunkX * 16 + 8, chunkZ * 16 + 8);
        } else {
            surfaceY = com.warfront.region.terrain.BiomeElevation.getEstimatedSurfaceHeight(level, chunkX, chunkZ);
        }
        int sampleY = Math.clamp(surfaceY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int quartX = (chunkX << 2) + 2;
        int quartZ = (chunkZ << 2) + 2;
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biomeHolder = level.getNoiseBiome(quartX, sampleY >> 2, quartZ);

        // Cave-Bypass: Prevent subterranean cave biomes (dripstone, lush, deep dark) from leaking onto surface map
        if (com.warfront.region.terrain.BiomeElevation.isCaveBiome(biomeHolder)) {
            int upwardY = Math.max(sampleY + 32, 120);
            while (upwardY < level.getMaxBuildHeight() - 16) {
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> candidate = level.getNoiseBiome(quartX, upwardY >> 2, quartZ);
                if (!com.warfront.region.terrain.BiomeElevation.isCaveBiome(candidate)) {
                    biomeHolder = candidate;
                    break;
                }
                upwardY += 32;
            }
        }

        int color = com.warfront.map.BiomeMapColors.colorFor(biomeHolder) & 0x00FFFFFF;
        int elevation = com.warfront.region.terrain.BiomeElevation.getChunkElevation(level, chunkX, chunkZ, biomeHolder, surfaceY);
        int clampedElevation = Math.clamp(elevation, 0, 255);
        int packed = (clampedElevation << 24) | color;

        CHUNK_BIOME_COLOR_CACHE.put(key, packed);
        return packed;
    }

    private static void buildAndSendSnapshot(ServerPlayer player, MapViewType viewType, boolean isExplicitRequest) {
        long startNano = System.nanoTime();
        ServerLevel level = player.serverLevel();
        RegionData regions = RegionData.get(level);

        int diameter = viewType.chunkDiameter();
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;
        int originChunkX = centerChunkX - diameter / 2;
        int originChunkZ = centerChunkZ - diameter / 2;

        List<RegionMapPayload.ChunkData> chunks = new ArrayList<>(diameter * diameter);
        List<RegionMapPayload.RegionMarkerData> markers = new ArrayList<>();

        int minRegionX = Math.floorDiv(originChunkX, 8);
        int maxRegionX = Math.floorDiv(originChunkX + diameter - 1, 8);
        int minRegionZ = Math.floorDiv(originChunkZ, 8);
        int maxRegionZ = Math.floorDiv(originChunkZ + diameter - 1, 8);

        int totalRegions = (maxRegionX - minRegionX + 1) * (maxRegionZ - minRegionZ + 1);
        int savedCount = 0;
        int proceduralCount = 0;

        Map<Long, CachedRegionData> regionCache = new HashMap<>(totalRegions);
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                RegionData.RegionState state = regions.getSavedRegionState(rx, rz);
                if (state != null) {
                    savedCount++;
                } else {
                    proceduralCount++;
                    state = com.warfront.region.generator.ProceduralRegionGenerator.getInstance().generateRawRegionState(level, level.getSeed(), rx, rz);
                }

                // Ensure any discovered HEADQUARTERS in the map view has initialized its linked cluster army
                if (state.baseType() == com.warfront.region.BaseType.HEADQUARTERS && state.owner() != null && state.owner().isAI()) {
                    com.warfront.region.army.ClusterArmyManager.ensureHqArmyInitialized(level, regions, rx, rz, state);
                }

                // Fog-of-war rule: DEBUG view is always visited; COMMAND view checks persistent visited state; SCOUT view is local
                boolean isVisited = !viewType.hasFogOfWar() || regions.isRegionVisitedForPlayer(level, player.getUUID(), rx, rz);

                RegionData.SubRegionState s00 = regions.subRegionAt(rx, rz, 0, 0);
                RegionData.SubRegionState s10 = regions.subRegionAt(rx, rz, 1, 0);
                RegionData.SubRegionState s01 = regions.subRegionAt(rx, rz, 0, 1);
                RegionData.SubRegionState s11 = regions.subRegionAt(rx, rz, 1, 1);

                regionCache.put(net.minecraft.world.level.ChunkPos.asLong(rx, rz),
                        new CachedRegionData(isVisited, state.baseType(), state.owner(), s00, s10, s01, s11));
            }
        }

        // Second pass for markers (after all HQs in view have initialized their armies)
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                CachedRegionData cached = regionCache.get(net.minecraft.world.level.ChunkPos.asLong(rx, rz));
                if (cached != null && cached.isVisited()) {
                    boolean hasArmy = regions.hasArmyAt(rx, rz);
                    if (cached.baseType() != com.warfront.region.BaseType.NONE || hasArmy) {
                        markers.add(new RegionMapPayload.RegionMarkerData(rx, rz, cached.owner().id(), cached.baseType().id(), hasArmy));
                    }
                }
            }
        }

        for (int chunkZ = originChunkZ; chunkZ < originChunkZ + diameter; chunkZ++) {
            int rz = Math.floorDiv(chunkZ, 8);
            int subZ = Math.floorMod(chunkZ, 8) >= 4 ? 1 : 0;

            for (int chunkX = originChunkX; chunkX < originChunkX + diameter; chunkX++) {
                int rx = Math.floorDiv(chunkX, 8);
                int subX = Math.floorMod(chunkX, 8) >= 4 ? 1 : 0;

                CachedRegionData cache = regionCache.get(net.minecraft.world.level.ChunkPos.asLong(rx, rz));
                int biomeColor = cache.isVisited() ? getOrComputeBiomeColor(level, chunkX, chunkZ) : 0;
                RegionData.SubRegionState subRegion = cache.getSubRegion(subX, subZ);
                boolean isSieged = com.warfront.mission.ActiveCampaignMissionManager.hasActiveMission(rx, rz, subX, subZ);

                chunks.add(new RegionMapPayload.ChunkData(chunkX, chunkZ, biomeColor, subRegion.owner().id(), isSieged, cache.isVisited(), subRegion.clusterId()));
            }
        }

        List<RegionMapPayload.SiegeArrowData> siegeArrows = new ArrayList<>();
        List<RegionMapPayload.ActiveWarData> activeWars = new ArrayList<>();
        for (RegionData.SiegeCampaign campaign : regions.getActiveSieges().values()) {
            int trx = campaign.targetRegionX();
            int trz = campaign.targetRegionZ();

            boolean warPermitted = !viewType.hasFogOfWar() || regions.isRegionVisitedForPlayer(level, player.getUUID(), trx, trz);
            if (warPermitted) {
                RegionData.Region targetReg = regions.regionAt(trx, trz);
                boolean isDefense = (campaign.attacker() != com.warfront.region.Faction.HUMANITY)
                        && (targetReg.owner() == com.warfront.region.Faction.HUMANITY || targetReg.owner() == com.warfront.region.Faction.UNCLAIMED);
                activeWars.add(new RegionMapPayload.ActiveWarData(trx, trz, campaign.attacker().id(), isDefense));
            }

            for (RegionData.SourcePos src : campaign.sources()) {
                // Fog-of-war rule: Siege arrows ONLY included if DEBUG view OR if source or target region is VISITED by player (or player attack)
                boolean arrowPermitted = !viewType.hasFogOfWar() || regions.isRegionVisitedForPlayer(level, player.getUUID(), src.x(), src.z()) || regions.isRegionVisitedForPlayer(level, player.getUUID(), trx, trz)
                        || (campaign.attacker() == com.warfront.region.Faction.HUMANITY);
                if (arrowPermitted) {
                    siegeArrows.add(new RegionMapPayload.SiegeArrowData(
                            src.x(), src.z(),
                            trx, trz,
                            campaign.encircled()
                    ));
                }
            }
        }

        List<String> logMessages = regions.getWarfrontLogs();

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
        Warfront.LOGGER.info("Map Snapshot ({}) built in {} ms: {} total regions ({} saved, {} procedural), {} chunks",
                viewType.name(), elapsedMs, totalRegions, savedCount, proceduralCount, diameter * diameter);

        PacketDistributor.sendToPlayer(player, new RegionMapPayload(centerChunkX, centerChunkZ, chunks, markers, siegeArrows, activeWars, logMessages, isExplicitRequest, viewType));
    }

    private record CachedRegionData(boolean isVisited, com.warfront.region.BaseType baseType, com.warfront.region.Faction owner,
            RegionData.SubRegionState s00, RegionData.SubRegionState s10,
            RegionData.SubRegionState s01, RegionData.SubRegionState s11) {
        public RegionData.SubRegionState getSubRegion(int subX, int subZ) {
            if (subX == 0 && subZ == 0) return s00;
            if (subX == 1 && subZ == 0) return s10;
            if (subX == 0 && subZ == 1) return s01;
            return s11;
        }
    }

    /**
     * Broadcasts live updates when strategic events occur (attack starts, ends, timeouts).
     * Sends snapshot tailored strictly to each active player's current MapViewType.
     */
    public static void notifyActiveMapTerminals(ServerLevel level) {
        if (level == null || level.getServer() == null) return;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            MapViewType activeType = getActiveSession(player);
            if (activeType != null) {
                sendSnapshot(player, activeType, false);
            }
        }
    }

    @Override
    public Type<RequestRegionMapPayload> type() {
        return TYPE;
    }
}
