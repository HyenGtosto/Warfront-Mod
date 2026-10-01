package com.warfront.event;

import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.network.ActiveMissionHudPayload;
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

    // Tracks if player currently has the active mission HUD open
    private static final Map<UUID, Boolean> PLAYER_IN_ACTIVE_MISSION = new HashMap<>();

    private RegionTriggerEvents() {
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }

        UUID playerUUID = player.getUUID();
        ChunkPos currentChunk = player.chunkPosition();
        long currentChunkLong = currentChunk.toLong();
        Long previousChunkLong = LAST_PLAYER_CHUNK.put(playerUUID, currentChunkLong);

        int chunkX = currentChunk.x;
        int chunkZ = currentChunk.z;
        SubRegionPos subPos = SubRegionPos.fromChunk(chunkX, chunkZ);
        int rx = subPos.regionX();
        int rz = subPos.regionZ();
        int sx = subPos.subX();
        int sz = subPos.subZ();

        // 1. Chunk entry trigger for unlocking map regions
        if (previousChunkLong == null || !previousChunkLong.equals(currentChunkLong)) {
            RegionData regions = RegionData.get(level);
            regions.unlock3x3Around(rx, rz);
        }

        // 2. Periodic in-war mission sync & spawning (every 20 ticks / 1 second)
        if (player.tickCount % 20 == 0) {
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress =
                    ActiveCampaignMissionManager.getActiveProgress(rx, rz, sx, sz);

            if (progress != null && !progress.isCompleted()) {
                // Handle in-world reinforcement spawning
                ActiveCampaignMissionManager.onPlayerInSubregion(level, rx, rz, sx, sz, player);

                // Send HUD synchronization packet
                RegionData regions = RegionData.get(level);
                RegionData.SiegeCampaign siege = regions.getSiege(rx, rz);
                long remainingTicks = 0L;
                if (siege != null) {
                    long elapsed = level.getGameTime() - siege.startTick();
                    remainingTicks = Math.max(0L, siege.durationTicks() - elapsed);
                }
                boolean isDefense = (siege != null && siege.attacker() != Faction.HUMANITY);

                player.connection.send(new ActiveMissionHudPayload(
                        true,
                        rx, rz, sx, sz,
                        progress.displayName(),
                        progress.currentKills(),
                        progress.requiredKills(),
                        remainingTicks,
                        progress.targetFaction().id(),
                        isDefense
                ));
                PLAYER_IN_ACTIVE_MISSION.put(playerUUID, true);
            } else {
                // If player was previously inside an active mission, hide the HUD
                if (Boolean.TRUE.equals(PLAYER_IN_ACTIVE_MISSION.put(playerUUID, false))) {
                    player.connection.send(new ActiveMissionHudPayload(
                            false,
                            0, 0, 0, 0,
                            "",
                            0, 0, 0L, 0, false
                    ));
                }
            }
        }
    }

    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            if (projectile.getOwner() instanceof net.minecraft.world.entity.player.Player player) {
                java.util.List<com.warfront.entity.PillagerWarriorEntity> warriors = event.getLevel().getEntitiesOfClass(
                        com.warfront.entity.PillagerWarriorEntity.class,
                        projectile.getBoundingBox().inflate(24.0D),
                        w -> w.isAlive()
                );
                for (com.warfront.entity.PillagerWarriorEntity warrior : warriors) {
                    warrior.raiseShield(80);
                    if (warrior.getTarget() == null) {
                        warrior.setTarget(player);
                    }
                    warrior.getLookControl().setLookAt(player, 60.0F, 60.0F);
                }
            }
        }
    }
}
