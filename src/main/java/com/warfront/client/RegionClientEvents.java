package com.warfront.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.warfront.Warfront;
import com.warfront.network.RequestRegionMapPayload;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@Mod(value = Warfront.MOD_ID, dist = Dist.CLIENT)
public final class RegionClientEvents {
    private static final KeyMapping OPEN_REGION_SCREEN = new KeyMapping(
            "key.warfront.open_region_screen",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.warfront");

    public RegionClientEvents(IEventBus modEventBus) {
        modEventBus.addListener(this::registerKeyMappings);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_REGION_SCREEN);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_REGION_SCREEN.consumeClick()) {
            PacketDistributor.sendToServer(new RequestRegionMapPayload());
        }
    }
}
