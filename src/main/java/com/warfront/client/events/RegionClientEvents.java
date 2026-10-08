package com.warfront.client.events;

import com.warfront.Warfront;
import com.warfront.client.hud.ActiveMissionHudOverlay;
import com.warfront.client.renderer.PillagerArmoredEliteRenderer;
import com.warfront.client.renderer.PillagerCommanderRenderer;
import com.warfront.client.renderer.PillagerMarksmanRenderer;
import com.warfront.client.renderer.PillagerScoutRenderer;
import com.warfront.client.renderer.PillagerWarriorRenderer;
import com.warfront.client.renderer.SupplyWagonCartRenderer;
import com.warfront.client.renderer.SupplyWagonExtensionRenderer;
import com.warfront.entity.ModEntities;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@Mod(value = Warfront.MOD_ID, dist = Dist.CLIENT)
public final class RegionClientEvents {

    public RegionClientEvents(IEventBus modEventBus) {
        modEventBus.addListener(RegionClientEvents::registerGuiLayers);
        modEventBus.addListener(RegionClientEvents::registerEntityRenderers);
    }

    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(
                VanillaGuiLayers.CHAT,
                ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "active_mission_hud"),
                ActiveMissionHudOverlay::render
        );
    }

    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PILLAGER_ARMORED_ELITE.get(), PillagerArmoredEliteRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_WARRIOR.get(), PillagerWarriorRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_SCOUT.get(), PillagerScoutRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_MARKSMAN.get(), PillagerMarksmanRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_COMMANDER.get(), PillagerCommanderRenderer::new);
        event.registerEntityRenderer(ModEntities.SUPPLY_WAGON_CART.get(), SupplyWagonCartRenderer::new);
        event.registerEntityRenderer(ModEntities.SUPPLY_WAGON_EXTENSION.get(), SupplyWagonExtensionRenderer::new);
    }
}
