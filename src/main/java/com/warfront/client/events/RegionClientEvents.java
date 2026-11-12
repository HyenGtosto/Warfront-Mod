package com.warfront.client.events;

import com.warfront.Warfront;
import com.warfront.client.gui.FriendlyMortarScreen;
import com.warfront.client.hud.ActiveMissionHudOverlay;
import com.warfront.client.renderer.FriendlyArtilleryShellRenderer;
import com.warfront.client.renderer.FriendlyMortarBlockRenderer;
import com.warfront.client.renderer.PillagerCommanderRenderer;
import com.warfront.client.renderer.PillagerEliteRenderer;
import com.warfront.client.renderer.PillagerMarksmanRenderer;
import com.warfront.client.renderer.PillagerScoutRenderer;
import com.warfront.client.renderer.PillagerWarriorRenderer;
import com.warfront.client.renderer.SmokeScreenRenderer;
import com.warfront.client.renderer.SupplyWagonCartRenderer;
import com.warfront.client.renderer.SupplyWagonExtensionRenderer;
import com.warfront.client.renderer.WarfrontGeoEntityRenderer;
import com.warfront.entity.ModEntities;
import com.warfront.menu.WarfrontMenus;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Warfront.MOD_ID, dist = Dist.CLIENT)
public final class RegionClientEvents {

    public RegionClientEvents(IEventBus modEventBus) {
        modEventBus.addListener(RegionClientEvents::registerGuiLayers);
        modEventBus.addListener(RegionClientEvents::registerEntityRenderers);
        modEventBus.addListener(RegionClientEvents::registerMenuScreens);
        NeoForge.EVENT_BUS.addListener(RegionClientEvents::onClientLoggingOut);
    }

    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WarfrontGeoEntityRenderer.clearRenderCaches();
    }

    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(
                VanillaGuiLayers.CHAT,
                ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "active_mission_hud"),
                ActiveMissionHudOverlay::render
        );
    }

    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PILLAGER_ELITE.get(), PillagerEliteRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_WARRIOR.get(), PillagerWarriorRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_SCOUT.get(), PillagerScoutRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_RIFLEMAN.get(), com.warfront.client.renderer.PillagerRiflemanRenderer::new);
        event.registerEntityRenderer(ModEntities.RIFLE_BULLET.get(), com.warfront.client.renderer.RifleBulletRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_MARKSMAN.get(), PillagerMarksmanRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_COMMANDER.get(), PillagerCommanderRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_CANNONEER.get(), com.warfront.client.renderer.PillagerCannoneerRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_RAPIDBOW.get(), com.warfront.client.renderer.PillagerRapidbowRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLAGER_IRONCLAD.get(), com.warfront.client.renderer.PillagerIroncladRenderer::new);
        event.registerEntityRenderer(ModEntities.CANNON_BOMB.get(), com.warfront.client.renderer.CannonBombRenderer::new);
        event.registerEntityRenderer(ModEntities.SUPPLY_WAGON_CART.get(), SupplyWagonCartRenderer::new);
        event.registerEntityRenderer(ModEntities.SUPPLY_WAGON_EXTENSION.get(), SupplyWagonExtensionRenderer::new);
        event.registerEntityRenderer(ModEntities.ARTILLERY_SHELL.get(), com.warfront.client.renderer.ArtilleryShellRenderer::new);
        event.registerEntityRenderer(ModEntities.FRIENDLY_ARTILLERY_SHELL.get(), FriendlyArtilleryShellRenderer::new);
        event.registerEntityRenderer(ModEntities.FRIENDLY_CLUSTER_BOMBLET.get(), com.warfront.client.renderer.FriendlyClusterBombletRenderer::new);
        event.registerEntityRenderer(ModEntities.SMOKE_SCREEN.get(), SmokeScreenRenderer::new);
        event.registerEntityRenderer(ModEntities.BALLISTA_BOLT.get(), com.warfront.client.renderer.BallistaBoltRenderer::new);
        event.registerBlockEntityRenderer(com.warfront.block.entity.WarfrontBlockEntities.MORTAR.get(), context -> new com.warfront.client.renderer.MortarBlockRenderer());
        event.registerBlockEntityRenderer(com.warfront.block.entity.WarfrontBlockEntities.PILLAGER_BALLISTA.get(), context -> new com.warfront.client.renderer.PillagerBallistaBlockRenderer());
        event.registerBlockEntityRenderer(com.warfront.block.entity.WarfrontBlockEntities.AMMO_RACK.get(), context -> new com.warfront.client.renderer.AmmoRackBlockRenderer());
        event.registerBlockEntityRenderer(com.warfront.block.entity.WarfrontBlockEntities.FRIENDLY_MORTAR.get(), context -> new FriendlyMortarBlockRenderer());
        event.registerBlockEntityRenderer(com.warfront.block.entity.WarfrontBlockEntities.FRIENDLY_BALLISTA.get(), context -> new com.warfront.client.renderer.FriendlyBallistaBlockRenderer());
    }

    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(WarfrontMenus.FRIENDLY_MORTAR_MENU.get(), FriendlyMortarScreen::new);
        event.register(WarfrontMenus.FRIENDLY_BALLISTA_MENU.get(), com.warfront.client.gui.FriendlyBallistaScreen::new);
    }
}
