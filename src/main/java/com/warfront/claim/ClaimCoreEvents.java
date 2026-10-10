package com.warfront.claim;

import com.warfront.block.WarfrontBlocks;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.BlockEvent;

public final class ClaimCoreEvents {
    private ClaimCoreEvents() {
    }

    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!event.getPlacedBlock().is(WarfrontBlocks.CLAIM_CORE.get())
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        java.util.UUID playerFactionId = null;
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.warfront.faction.PlayerFaction pf = com.warfront.faction.PlayerFactionData.get(level).getFactionByPlayer(player.getUUID());
            if (pf == null) {
                event.setCanceled(true);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Warfront] You cannot claim territory without being in a faction!"));
                return;
            }
            playerFactionId = pf.getId();
        }

        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(event.getPos());
        regions.claim(level, region.x(), region.z(), Faction.HUMANITY, 20.0F, 20.0F, BaseType.NONE, playerFactionId);
    }
}
