package com.warfront.claim;

import com.warfront.block.WarfrontBlocks;
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

        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(event.getPos());
        regions.claim(region.x(), region.z(), Faction.HUMANITY, level.getRandom().nextFloat(), level.getRandom().nextFloat());
    }
}
