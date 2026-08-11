package com.warfront.event;

import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.SubRegionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RegionTriggerEvents {
    // Tracks last known chunk position per player to detect chunk entry
    private static final Map<UUID, Long> LAST_PLAYER_CHUNK = new HashMap<>();

    // Cooldown per chunk position (in game ticks) to prevent repeated spawn spamming
    private static final Map<Long, Long> CHUNK_SPAWN_COOLDOWN = new HashMap<>();

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

        RegionData.SubRegionState subState = regions.subRegionAt(subPos.regionX(), subPos.regionZ(), subPos.subX(), subPos.subZ());

        // Auto-conquest test feature: Secure sub-region on entry when under siege
        if (subState.underSiege()) {
            regions.claimSubRegion(level, subPos.regionX(), subPos.regionZ(), subPos.subX(), subPos.subZ(), Faction.HUMANITY, 100.0F);
            regions.addLog(level, String.format("§a[Warfront] Sub-region (%d, %d) in Region (%d, %d) SECURED!",
                    subPos.subX(), subPos.subZ(), subPos.regionX(), subPos.regionZ()));
            return;
        }

        long currentGameTime = level.getGameTime();
        long cooldownTicks = com.warfront.config.WarfrontConfig.CHUNK_TRIGGER_COOLDOWN_SECONDS.get() * 20L;
        // Check if this chunk is on cooldown
        Long lastTriggeredTime = CHUNK_SPAWN_COOLDOWN.get(currentChunkLong);
        if (lastTriggeredTime != null && (currentGameTime - lastTriggeredTime) < cooldownTicks) {
            return;
        }

        // Trigger open-world patrol spawns when entering a chunk inside hostile territory
        if (subState.owner() == Faction.ZOMBIE_HORDE || subState.owner() == Faction.PILLAGER_CONQUERORS) {
            float effectiveResistance = regions.calculateEffectiveResistance(subPos.regionX(), subPos.regionZ());
            int spawnCount = Math.clamp((int) (effectiveResistance / 10.0F), 3, 12);

            spawnHostilePatrol(level, chunkX, chunkZ, subPos.regionX(), subPos.regionZ(), subState.owner(), spawnCount, effectiveResistance);
            CHUNK_SPAWN_COOLDOWN.put(currentChunkLong, currentGameTime);
        }
    }

    /**
     * Spawns open-world patrol squads when roaming through hostile territory.
     * Mob count and equipment scale dynamically with effective Resistance.
     */
    private static void spawnHostilePatrol(ServerLevel level, int playerChunkX, int playerChunkZ, int regionX, int regionZ, Faction faction, int mobCount, float resistance) {
        int startChunkX = regionX * 8;
        int startChunkZ = regionZ * 8;

        double centerChunkX = startChunkX + 3.5D;
        double centerChunkZ = startChunkZ + 3.5D;

        double dx = centerChunkX - playerChunkX;
        double dz = centerChunkZ - playerChunkZ;

        int targetChunkX = playerChunkX;
        int targetChunkZ = playerChunkZ;

        // Step 1 chunk towards the region center
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            targetChunkX += (dx > 0) ? 1 : -1;
        } else if (dz != 0) {
            targetChunkZ += (dz > 0) ? 1 : -1;
        }

        for (int i = 0; i < mobCount; i++) {
            int spawnX = targetChunkX * 16 + 4 + level.getRandom().nextInt(8);
            int spawnZ = targetChunkZ * 16 + 4 + level.getRandom().nextInt(8);
            int spawnY = level.getHeight(Heightmap.Types.WORLD_SURFACE, spawnX, spawnZ);

            if (faction == Faction.ZOMBIE_HORDE) {
                Zombie zombie = EntityType.ZOMBIE.create(level);
                if (zombie != null) {
                    zombie.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                    zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);
                    if (resistance >= 70.0F) {
                        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                    }
                    level.addFreshEntity(zombie);
                }
            } else if (faction == Faction.PILLAGER_CONQUERORS) {
                Pillager pillager = EntityType.PILLAGER.create(level);
                if (pillager != null) {
                    pillager.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                    pillager.finalizeSpawn(level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);
                    level.addFreshEntity(pillager);
                }
            }
        }
    }
}
