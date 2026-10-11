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

        com.warfront.faction.PlayerFaction playerFaction = com.warfront.faction.PlayerFactionData.get(level).getFactionByPlayer(player.getUUID());
        if (playerFaction == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Warfront] You cannot interact with the war without being in a faction!"));
            return;
        }

        RegionData regions = RegionData.get(level);

        RegionData.SiegeCampaign existingSiege = regions.getSiege(payload.regionX(), payload.regionZ());
        long regionKey = net.minecraft.world.level.ChunkPos.asLong(payload.regionX(), payload.regionZ());

        if (existingSiege != null) {
            boolean isDefense = existingSiege.attacker() != Faction.HUMANITY;
            if (existingSiege.isFieldBattle()) {
                boolean isAttackerFaction = existingSiege.attackerPlayerFactionId() != null && existingSiege.attackerPlayerFactionId().equals(playerFaction.getId());
                boolean isDefenderFaction = existingSiege.defenderPlayerFactionId() != null && existingSiege.defenderPlayerFactionId().equals(playerFaction.getId());
                if (!isAttackerFaction && !isDefenderFaction) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Warfront] You cannot cancel a field battle your faction is not part of!"));
                    return;
                }
                regions.setRegionSiege(payload.regionX(), payload.regionZ(), false);
                regions.getActiveSieges().remove(regionKey);
                regions.revertRegionSubRegionsToOwner(level, payload.regionX(), payload.regionZ());
                com.warfront.mission.FieldBattleManager.clearHudForFieldBattle(level, payload.regionX(), payload.regionZ(), existingSiege.attackerPlayerFactionId(), existingSiege.defenderPlayerFactionId());
                regions.addLog(level, String.format("§eField battle cancelled: Region (%d, %d).", payload.regionX(), payload.regionZ()));
                Warfront.LOGGER.info("Field battle cancelled by player {} for Region ({}, {}).",
                        player.getName().getString(), payload.regionX(), payload.regionZ());
            } else {
                if (!isDefense) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Warfront] Commitment to War: Attack campaigns cannot be voluntarily cancelled! You must achieve victory or await campaign timeout."));
                    return;
                } else {
                    java.util.UUID defenderId = existingSiege.defenderPlayerFactionId();
                    if (defenderId == null) {
                        RegionData.Region target = regions.regionAt(payload.regionX(), payload.regionZ());
                        if (target.owner() == Faction.HUMANITY) {
                            defenderId = target.playerFactionId();
                        }
                    }
                    if (defenderId != null && !defenderId.equals(playerFaction.getId())) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Warfront] Only the defending faction can cancel defense missions!"));
                        return;
                    }
                    // Cancel player defense missions on server (AI siege remains active at region level)
                    ActiveCampaignMissionManager.clearCampaign(level, payload.regionX(), payload.regionZ());
                    regions.addLog(level, String.format("§eDefense missions cancelled: Region (%d, %d).", payload.regionX(), payload.regionZ()));
                    Warfront.LOGGER.info("Defense missions cancelled by player {} for Region ({}, {}).",
                            player.getName().getString(), payload.regionX(), payload.regionZ());
                }
            }

            RequestRegionMapPayload.notifyActiveMapTerminals(level);

            // Send updated details payload back to player
            RegionData.Region region = regions.regionAt(payload.regionX(), payload.regionZ());
            float effectiveResistance = regions.calculateEffectiveResistance(payload.regionX(), payload.regionZ());
            float effectiveStability = regions.calculateEffectiveStability(payload.regionX(), payload.regionZ());

            int dominoThreshold = regions.calculateDominoThreshold(payload.regionX(), payload.regionZ());
            int reachableMask = regions.computeReachableMask(payload.regionX(), payload.regionZ(), playerFaction.getId());
            int conqueredMask = regions.computeConqueredMask(payload.regionX(), payload.regionZ(), playerFaction.getId());
            boolean regionReachable = regions.isRegionReachable(payload.regionX(), payload.regionZ(), playerFaction.getId());

            long remainingTicks = 0L;
            if (isDefense) {
                long elapsed = level.getGameTime() - existingSiege.startTick();
                remainingTicks = Math.max(0L, existingSiege.durationTicks() - elapsed);
            }

            boolean isAwaitingReinf = regions.hasActiveReinforcement(payload.regionX(), payload.regionZ());
            long reinfRemainingTicks = 0L;
            boolean isEncircled = regions.isEncircled(payload.regionX(), payload.regionZ(), region.owner());
            if (isAwaitingReinf) {
                RegionData.ReinforcementState rs = regions.getReinforcement(payload.regionX(), payload.regionZ());
                if (rs != null) {
                    long elapsed = level.getGameTime() - rs.startTick();
                    reinfRemainingTicks = Math.max(0L, rs.durationTicks() - elapsed);
                }
            }

            long missionSeed = regions.calculateMissionSeed(region.x(), region.z());
            net.minecraft.core.BlockPos baseAnchor = region.baseAnchor();

            PacketDistributor.sendToPlayer(player, new RegionDetailsPayload(
                    region.x(), region.z(), payload.subX(), payload.subZ(),
                    region.owner().id(), effectiveStability, effectiveResistance,
                    region.baseType().id(), isDefense, true,
                    remainingTicks, dominoThreshold, reachableMask, regionReachable, 0, conqueredMask,
                    isDefense ? existingSiege.attacker().id() : Faction.UNCLAIMED.id(),
                    isAwaitingReinf, reinfRemainingTicks, isEncircled,
                    baseAnchor, missionSeed));
        }
    }

    @Override
    public Type<CancelAttackPayload> type() {
        return TYPE;
    }
}
