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
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, com.warfront.config.WarfrontConfig.SPEC, "warfront-common.toml");
        modEventBus.addListener(WarfrontPayloads::register);
        modEventBus.addListener(WarfrontBlocks::addToCreativeTab);
        WarfrontBlocks.BLOCKS.register(modEventBus);
        WarfrontBlocks.ITEMS.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(ClaimCoreEvents::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(BiomeMapColorReloadListener::register);
        NeoForge.EVENT_BUS.addListener(RegionTriggerEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(com.warfront.ai.AIAttackManager::onServerTick);
    }
}

