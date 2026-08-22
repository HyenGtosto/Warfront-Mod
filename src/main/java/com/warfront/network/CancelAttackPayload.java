package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.map.MapViewType;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CancelAttackPayload(int regionX, int regionZ, int subX, int subZ, MapViewType viewType) implements CustomPacketPayload {

    public CancelAttackPayload(int regionX, int regionZ, int subX, int subZ) {
        this(regionX, regionZ, subX, subZ, MapViewType.COMMAND);
    }

    public static final Type<CancelAttackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "cancel_attack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CancelAttackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CancelAttackPayload::regionX,
            ByteBufCodecs.VAR_INT, CancelAttackPayload::regionZ,
            ByteBufCodecs.VAR_INT, CancelAttackPayload::subX,
            ByteBufCodecs.VAR_INT, CancelAttackPayload::subZ,
            ByteBufCodecs.VAR_INT, p -> p.viewType().id(),
            (rx, rz, sx, sz, id) -> new CancelAttackPayload(rx, rz, sx, sz, MapViewType.byId(id)));

    public static void handle(CancelAttackPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        ServerLevel level = player.serverLevel();
        RegionData regions = RegionData.get(level);

        RegionData.SiegeCampaign existingSiege = regions.getSiege(payload.regionX(), payload.regionZ());
        long regionKey = net.minecraft.world.level.ChunkPos.asLong(payload.regionX(), payload.regionZ());

        if (existingSiege != null && existingSiege.attacker() == Faction.HUMANITY) {
            // Cancel player campaign on server
            regions.setRegionSiege(payload.regionX(), payload.regionZ(), false);
            regions.getActiveSieges().remove(regionKey);
            ActiveCampaignMissionManager.clearCampaign(level, payload.regionX(), payload.regionZ());

            regions.addLog(level, String.format("§eCampaign cancelled: Region (%d, %d).", payload.regionX(), payload.regionZ()));
            Warfront.LOGGER.info("Campaign cancelled by player {} for Region ({}, {}).",
                    player.getName().getString(), payload.regionX(), payload.regionZ());

            RequestRegionMapPayload.notifyActiveMapTerminals(level);

            // Send updated details payload back to player
            RegionData.Region region = regions.regionAt(payload.regionX(), payload.regionZ());
            RegionData.SubRegionState subState = regions.subRegionAt(payload.regionX(), payload.regionZ(), payload.subX(), payload.subZ());
            float effectiveResistance = regions.calculateEffectiveResistance(payload.regionX(), payload.regionZ());
            float effectiveStability = regions.calculateEffectiveStability(payload.regionX(), payload.regionZ());

            int dominoThreshold = regions.calculateDominoThreshold(payload.regionX(), payload.regionZ());
            int reachableMask = regions.computeReachableMask(payload.regionX(), payload.regionZ());
            int conqueredMask = regions.computeConqueredMask(payload.regionX(), payload.regionZ());
            boolean regionReachable = regions.isRegionReachable(payload.regionX(), payload.regionZ());

            PacketDistributor.sendToPlayer(player, new RegionDetailsPayload(
                    region.x(), region.z(), payload.subX(), payload.subZ(),
                    subState.owner().id(), effectiveStability, effectiveResistance,
                    region.baseType().id(), false, true,
                    0L, dominoThreshold, reachableMask, regionReachable, 0, conqueredMask));
        }
    }

    @Override
    public Type<CancelAttackPayload> type() {
        return TYPE;
    }
}
