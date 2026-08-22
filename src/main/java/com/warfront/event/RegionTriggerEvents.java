package com.warfront.event;

import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.SubRegionPos;
import com.warfront.spawn.ExplorationSpawnManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RegionTriggerEvents {
    // Tracks last known chunk position per player to detect chunk entry
    private static final Map<UUID, Long> LAST_PLAYER_CHUNK = new HashMap<>();

    private RegionTriggerEvents() {
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        ChunkPos currentChunk = player.chunkPosition();
        long currentChunkLong = currentChunk.toLong();
        UUID playerUUID = player.getUUID();
        Long previousChunkLong = LAST_PLAYER_CHUNK.put(playerUUID, currentChunkLong);

        // Only trigger when the player actually enters a NEW chunk
        if (previousChunkLong != null && previousChunkLong.equals(currentChunkLong)) {
            return;
        }

        int chunkX = currentChunk.x;
        int chunkZ = currentChunk.z;
        SubRegionPos subPos = SubRegionPos.fromChunk(chunkX, chunkZ);

        RegionData regions = RegionData.get(level);
        // Exploration rule: Unlocks a 3x3 region square around player upon visiting
        regions.unlock3x3Around(subPos.regionX(), subPos.regionZ());

        // Delegate out-of-war exploration spawning to ExplorationSpawnManager.
        // The manager scans nearby regions around the player, checks per-region
        // cooldowns, and selects distributed spawn positions within each eligible
        // enemy-owned region. Spawn logic and position selection do not live here.
        ExplorationSpawnManager.evaluateNearbyRegions(player, level);
    }
}
