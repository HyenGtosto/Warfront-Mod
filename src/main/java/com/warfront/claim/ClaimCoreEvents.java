package com.warfront.claim;

import com.warfront.block.WarfrontBlocks;
import com.warfront.faction.PlayerFaction;
import com.warfront.faction.PlayerFactionData;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.SubRegionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.UUID;

public final class ClaimCoreEvents {
    private ClaimCoreEvents() {
    }

    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        if (event.getPlacedBlock().is(WarfrontBlocks.CLAIM_CORE.get())) {
            handleClaimCorePlaced(event, level);
        } else if (event.getPlacedBlock().is(WarfrontBlocks.FRIENDLY_MORTAR.get())) {
            handleFriendlyMortarPlaced(event, level);
        } else if (event.getPlacedBlock().is(WarfrontBlocks.FRIENDLY_BALLISTA.get())) {
            handleFriendlyBallistaPlaced(event, level);
        }
    }

    private static void handleClaimCorePlaced(BlockEvent.EntityPlaceEvent event, ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) {
            event.setCanceled(true);
            if (event.getEntity() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable("chat.warfront.claim.overworld_only"), true);
            }
            return;
        }

        UUID playerFactionId = null;
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerFaction pf = PlayerFactionData.get(level).getFactionByPlayer(player.getUUID());
            if (pf == null) {
                event.setCanceled(true);
                player.sendSystemMessage(Component.translatable("chat.warfront.claim.must_be_in_faction"), true);
                return;
            }
            playerFactionId = pf.getId();
        }

        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(event.getPos());
        regions.claim(level, region.x(), region.z(), Faction.HUMANITY, 20.0F, 20.0F, BaseType.NONE, playerFactionId);
    }

    private static void handleFriendlyMortarPlaced(BlockEvent.EntityPlaceEvent event, ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) {
            cancelMortarPlacement(event, level);
            if (event.getEntity() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable("chat.warfront.mortar.overworld_only"), true);
            }
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            cancelMortarPlacement(event, level);
            return;
        }

        PlayerFaction pf = PlayerFactionData.get(level).getFactionByPlayer(player.getUUID());
        if (pf == null) {
            cancelMortarPlacement(event, level);
            player.sendSystemMessage(Component.translatable("chat.warfront.mortar.must_be_in_faction"), true);
            return;
        }

        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(event.getPos());

        // Mortar can ONLY be placed in a region belonging to the player's own faction
        if (region.owner() != Faction.HUMANITY || region.playerFactionId() == null || !region.playerFactionId().equals(pf.getId())) {
            cancelMortarPlacement(event, level);
            player.sendSystemMessage(Component.translatable("chat.warfront.mortar.not_own_faction_territory"), true);
            return;
        }

        // Hard cap: Maximum 3 friendly mortars per macro region (128x128).
        // Since setPlacedBy has already run, count includes the candidate block. > 3 blocks the 4th mortar.
        int regionMortars = PlayerConstructionTracker.getInstance().getBuildingCountInRegion(level, region.x(), region.z(), WarfrontBlocks.FRIENDLY_MORTAR.get());
        if (regionMortars > 3) {
            cancelMortarPlacement(event, level);
            player.sendSystemMessage(Component.translatable("chat.warfront.mortar.region_limit_reached"), true);
            return;
        }

        // Hard cap: Maximum 2 of any combination of heavy siege weapons (Mortars + Ballistas) per subregion (64x64).
        SubRegionPos subPos = SubRegionPos.fromChunk(event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        int subRegionMortars = PlayerConstructionTracker.getInstance().getBuildingCountInSubRegion(level, region.x(), region.z(), subPos.subX(), subPos.subZ(), WarfrontBlocks.FRIENDLY_MORTAR.get());
        int subRegionBallistas = PlayerConstructionTracker.getInstance().getBuildingCountInSubRegion(level, region.x(), region.z(), subPos.subX(), subPos.subZ(), WarfrontBlocks.FRIENDLY_BALLISTA.get());
        if ((subRegionMortars + subRegionBallistas) > 2) {
            cancelMortarPlacement(event, level);
            player.sendSystemMessage(Component.translatable("chat.warfront.fortification.subregion_combination_limit_reached"), true);
            return;
        }
    }

    private static void handleFriendlyBallistaPlaced(BlockEvent.EntityPlaceEvent event, ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) {
            cancelMortarPlacement(event, level);
            if (event.getEntity() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable("chat.warfront.ballista.overworld_only"), true);
            }
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            cancelMortarPlacement(event, level);
            return;
        }

        PlayerFaction pf = PlayerFactionData.get(level).getFactionByPlayer(player.getUUID());
        if (pf == null) {
            cancelMortarPlacement(event, level);
            player.sendSystemMessage(Component.translatable("chat.warfront.ballista.must_be_in_faction"), true);
            return;
        }

        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(event.getPos());

        // Ballista can ONLY be placed in a region belonging to the player's own faction
        if (region.owner() != Faction.HUMANITY || region.playerFactionId() == null || !region.playerFactionId().equals(pf.getId())) {
            cancelMortarPlacement(event, level);
            player.sendSystemMessage(Component.translatable("chat.warfront.ballista.not_own_faction_territory"), true);
            return;
        }

        // Hard cap: Maximum 3 friendly ballistas per macro region (128x128).
        int regionBallistas = PlayerConstructionTracker.getInstance().getBuildingCountInRegion(level, region.x(), region.z(), WarfrontBlocks.FRIENDLY_BALLISTA.get());
        if (regionBallistas > 3) {
            cancelMortarPlacement(event, level);
            player.sendSystemMessage(Component.translatable("chat.warfront.ballista.region_limit_reached"), true);
            return;
        }

        // Hard cap: Maximum 2 of any combination of heavy siege weapons (Mortars + Ballistas) per subregion (64x64).
        SubRegionPos subPos = SubRegionPos.fromChunk(event.getPos().getX() >> 4, event.getPos().getZ() >> 4);
        int subRegionMortars = PlayerConstructionTracker.getInstance().getBuildingCountInSubRegion(level, region.x(), region.z(), subPos.subX(), subPos.subZ(), WarfrontBlocks.FRIENDLY_MORTAR.get());
        int subRegionBallistas = PlayerConstructionTracker.getInstance().getBuildingCountInSubRegion(level, region.x(), region.z(), subPos.subX(), subPos.subZ(), WarfrontBlocks.FRIENDLY_BALLISTA.get());
        if ((subRegionMortars + subRegionBallistas) > 2) {
            cancelMortarPlacement(event, level);
            player.sendSystemMessage(Component.translatable("chat.warfront.fortification.subregion_combination_limit_reached"), true);
            return;
        }
    }

    private static void cancelMortarPlacement(BlockEvent.EntityPlaceEvent event, ServerLevel level) {
        event.setCanceled(true);
        // Roll back tracker entry if setPlacedBy already registered it before the event dispatched
        PlayerConstructionTracker.getInstance().onBlockBroken(level, event.getPos(), event.getPlacedBlock());
    }
}
