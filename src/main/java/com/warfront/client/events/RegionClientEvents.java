package com.warfront.client.events;

import com.warfront.Warfront;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = Warfront.MOD_ID, dist = Dist.CLIENT)
public final class RegionClientEvents {
    public RegionClientEvents(IEventBus modEventBus) {
    }
}
