package com.warfront;

import com.mojang.logging.LogUtils;
import com.warfront.block.WarfrontBlocks;
import com.warfront.claim.ClaimCoreEvents;
import com.warfront.event.RegionTriggerEvents;
import com.warfront.map.BiomeMapColorReloadListener;
import com.warfront.network.WarfrontPayloads;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(Warfront.MOD_ID)
public final class Warfront {
    public static final String MOD_ID = "warfront";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Warfront(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
        modEventBus.addListener(WarfrontPayloads::register);
        modEventBus.addListener(WarfrontBlocks::addToCreativeTab);
        WarfrontBlocks.BLOCKS.register(modEventBus);
        WarfrontBlocks.ITEMS.register(modEventBus);
        com.warfront.block.entity.WarfrontBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        com.warfront.menu.WarfrontMenus.MENUS.register(modEventBus);
        com.warfront.entity.ModEntities.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(ClaimCoreEvents::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                com.warfront.faction.PlayerFactionData.get(player.serverLevel()).syncToPlayer(player);
                if (player.serverLevel().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                    com.warfront.region.RegionData.get(player.serverLevel()).updateOwnerLocation(player);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                com.warfront.map.MapViewType activeType = com.warfront.network.RequestRegionMapPayload.getActiveSession(player);
                if (activeType != null) {
                    com.warfront.network.RequestRegionMapPayload.sendSnapshot(player, activeType, false);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(BiomeMapColorReloadListener::register);
        NeoForge.EVENT_BUS.addListener(RegionTriggerEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(RegionTriggerEvents::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(RegionTriggerEvents::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(com.warfront.region.base.BasePlacementManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.region.base.PersistentBaseProtectionManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.event.MissionDeathEventHandler::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(com.warfront.event.MissionBlockEventHandler::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(com.warfront.event.MissionBlockEventHandler::onExplosionDetonate);
        NeoForge.EVENT_BUS.addListener(com.warfront.ai.AIAttackManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.mission.FieldBattleManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.mission.ActiveCampaignMissionManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.spawn.RoamingEntityTracker::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.spawn.MissionEntityTracker::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.mission.site.MissionSiteSnapshotManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.spawn.AttackRoamerManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.spawn.SubregionPatrolManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.region.base.hq.HQSquadPatrolManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.region.bridge.SubregionBridgeManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.entity.AlliedFactionHelper::onLivingChangeTarget);
        NeoForge.EVENT_BUS.addListener(com.warfront.entity.AlliedFactionHelper::onProjectileImpact);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Load event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel
                    && serverLevel.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                com.warfront.network.RequestRegionMapPayload.clearColorCache();
                com.warfront.region.generator.ProceduralRegionGenerator.getInstance().clearAllCaches();
                com.warfront.region.base.PersistentBaseProtectionManager.clearCache();
                com.warfront.region.base.hq.HQGatePortcullisController.cleanup();
                com.warfront.region.base.hq.HQSquadPatrolManager.cleanup();
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel
                    && serverLevel.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                com.warfront.network.RequestRegionMapPayload.clearColorCache();
                com.warfront.region.generator.ProceduralRegionGenerator.getInstance().clearAllCaches();
                com.warfront.region.base.PersistentBaseProtectionManager.clearCache();
                com.warfront.region.base.hq.HQGatePortcullisController.cleanup();
                com.warfront.region.base.hq.HQSquadPatrolManager.cleanup();
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event) -> {
            com.warfront.network.RequestRegionMapPayload.clearColorCache();
            com.warfront.region.generator.ProceduralRegionGenerator.getInstance().clearAllCaches();
            com.warfront.region.base.PersistentBaseProtectionManager.clearCache();
            com.warfront.region.base.hq.HQGatePortcullisController.cleanup();
            com.warfront.region.base.hq.HQSquadPatrolManager.cleanup();

            net.minecraft.server.level.ServerLevel overworld = event.getServer().overworld();
            if (overworld != null) {
                long startNs = System.nanoTime();
                com.warfront.region.RegionData regionData = com.warfront.region.RegionData.get(overworld);
                net.minecraft.world.level.ChunkPos ownerCenter = regionData.resolveOwnerCenterChunk(overworld);
                int centerRegionX = Math.floorDiv(ownerCenter.x, 8);
                int centerRegionZ = Math.floorDiv(ownerCenter.z, 8);
                int regionRadius = 12; // 24x24 regions (192x192 chunks) centered on world owner's last location

                com.warfront.region.generator.ProceduralRegionGenerator generator =
                        com.warfront.region.generator.ProceduralRegionGenerator.getInstance();

                // Pass 1: Pre-bake raw procedural region states, terrain classification, and chunk biome map colors
                for (int rx = centerRegionX - regionRadius; rx <= centerRegionX + regionRadius; rx++) {
                    for (int rz = centerRegionZ - regionRadius; rz <= centerRegionZ + regionRadius; rz++) {
                        regionData.getRawOrSavedRegionState(rx, rz);
                        com.warfront.region.terrain.TerrainClassifier.getRegionTerrainName(overworld, rx, rz);
                        int baseCx = rx * 8;
                        int baseCz = rz * 8;
                        for (int dx = 0; dx < 8; dx++) {
                            for (int dz = 0; dz < 8; dz++) {
                                com.warfront.network.RequestRegionMapPayload.getOrComputeBiomeColor(overworld, baseCx + dx, baseCz + dz);
                            }
                        }
                    }
                }

                // Pass 2: Pre-bake full region states (including regional strength & bordering boosts)
                for (int rx = centerRegionX - regionRadius; rx <= centerRegionX + regionRadius; rx++) {
                    for (int rz = centerRegionZ - regionRadius; rz <= centerRegionZ + regionRadius; rz++) {
                        regionData.regionAt(rx, rz);
                    }
                }

                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                LOGGER.info("[Warfront] Pre-baked 24x24 region theatre (192x192 chunks) around owner chunk ({}, {}) in {} ms.",
                        ownerCenter.x, ownerCenter.z, elapsedMs);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent event) -> {
            com.warfront.ai.AIAttackManager.setTurboMode(null, false);
            com.warfront.network.RequestRegionMapPayload.clearColorCache();
            com.warfront.region.generator.ProceduralRegionGenerator.getInstance().clearAllCaches();
            com.warfront.region.base.PersistentBaseProtectionManager.clearCache();
        });
    }
}
