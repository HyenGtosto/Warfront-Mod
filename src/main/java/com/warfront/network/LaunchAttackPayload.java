package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.region.RegionData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LaunchAttackPayload(int regionX, int regionZ, int subX, int subZ, int activeSubRegionsMask) implements CustomPacketPayload {
    public LaunchAttackPayload(int regionX, int regionZ, int subX, int subZ) {
        this(regionX, regionZ, subX, subZ, 0xF); // Default all 4 sub-regions active (mask = 15 = 0b1111)
    }

    public static final Type<LaunchAttackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "launch_attack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LaunchAttackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::regionX,
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::regionZ,
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::subX,
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::subZ,
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::activeSubRegionsMask,
            LaunchAttackPayload::new);

    public static void handle(LaunchAttackPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        ServerLevel level = player.serverLevel();
        RegionData regions = RegionData.get(level);

        if (!com.warfront.region.generator.ProceduralRegionGenerator.getInstance().biomeAvailableForExpansion(level, payload.regionX(), payload.regionZ())) {
            return;
        }

        RegionData.Region targetRegion = regions.regionAt(payload.regionX(), payload.regionZ());
        RegionData.SiegeCampaign existingSiege = regions.getSiege(payload.regionX(), payload.regionZ());

        if (targetRegion.owner() == com.warfront.region.Faction.HUMANITY && existingSiege != null) {
            // Defense Campaign Confirmation: player is activating defense missions for an ongoing siege
            int requestedMask = payload.activeSubRegionsMask();
            int securedMask = regions.computeSecuredMask(payload.regionX(), payload.regionZ());
            int validDefenseMask = requestedMask & ~securedMask; // filter out already safe sub-regions

            if (validDefenseMask == 0) {
                return;
            }

            int finalMask = existingSiege.activeSubRegionsMask() | validDefenseMask;
            regions.setRegionSiegeWithCampaign(payload.regionX(), payload.regionZ(),
                    new RegionData.SiegeCampaign(existingSiege.attacker(), payload.regionX(), payload.regionZ(),
                            existingSiege.sources(), existingSiege.attackValue(), existingSiege.encircled(),
                            existingSiege.startTick(), existingSiege.durationTicks(), finalMask));

            Warfront.LOGGER.info("Defense missions confirmed: {} → Region ({}, {}), active mask {}.",
                    player.getName().getString(), payload.regionX(), payload.regionZ(), finalMask);
            RequestRegionMapPayload.notifyActiveMapTerminals(level);
            return;
        }

        // Filter out any requested sub-regions that are ALREADY conquered by HUMANITY
        int requestedMask = payload.activeSubRegionsMask();
        int validRequestedMask = 0;
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                int bit = sz * 2 + sx;
                if ((requestedMask & (1 << bit)) != 0) {
                    RegionData.SubRegionState subState = regions.subRegionAt(payload.regionX(), payload.regionZ(), sx, sz);
                    if (subState.owner() != com.warfront.region.Faction.HUMANITY) {
                        validRequestedMask |= (1 << bit);
                    }
                }
            }
        }

        if (validRequestedMask == 0) {
            return;
        }

        int finalMask = validRequestedMask;
        long startTick = level.getGameTime();
        long durationTicks = com.warfront.config.WarfrontConfig.SIEGE_RESOLUTION_DURATION_SECONDS.get() * 20L;

        if (existingSiege != null && existingSiege.attacker() == com.warfront.region.Faction.HUMANITY) {
            int conqueredMask = regions.computeConqueredMask(payload.regionX(), payload.regionZ());
            int cleanExistingMask = existingSiege.activeSubRegionsMask() & ~conqueredMask;
            finalMask = cleanExistingMask | validRequestedMask;
            startTick = existingSiege.startTick();
            durationTicks = existingSiege.durationTicks(); // preserve remaining campaign timer
        }

        java.util.List<RegionData.SourcePos> sources = java.util.List.of(new RegionData.SourcePos(payload.regionX() - 1, payload.regionZ()));
        regions.setRegionSiegeWithCampaign(payload.regionX(), payload.regionZ(),
                new RegionData.SiegeCampaign(com.warfront.region.Faction.HUMANITY, payload.regionX(), payload.regionZ(), sources, 1, false, startTick, durationTicks, finalMask));
        Warfront.LOGGER.info("Campaign launched/updated: {} → Region ({}, {}), active mask {}.",
                player.getName().getString(), payload.regionX(), payload.regionZ(), finalMask);

        // Re-send updated Map snapshots to active map terminals
        RequestRegionMapPayload.notifyActiveMapTerminals(level);
    }

    @Override
    public Type<LaunchAttackPayload> type() {
        return TYPE;
    }
}
