package com.warfront.network;

import com.warfront.Warfront;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class WarfrontPayloads {
    private WarfrontPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(RequestRegionMapPayload.TYPE, RequestRegionMapPayload.STREAM_CODEC,
                RequestRegionMapPayload::handle);
        registrar.playToServer(RequestRegionDetailsPayload.TYPE, RequestRegionDetailsPayload.STREAM_CODEC,
                RequestRegionDetailsPayload::handle);
        registrar.playToServer(LaunchAttackPayload.TYPE, LaunchAttackPayload.STREAM_CODEC,
                LaunchAttackPayload::handle);
        registrar.playToServer(CancelAttackPayload.TYPE, CancelAttackPayload.STREAM_CODEC,
                CancelAttackPayload::handle);
        registrar.playToServer(CloseMapSessionPayload.TYPE, CloseMapSessionPayload.STREAM_CODEC,
                CloseMapSessionPayload::handle);
        registrar.playToClient(RegionMapPayload.TYPE, RegionMapPayload.STREAM_CODEC, RegionMapPayload::handle);
        registrar.playToClient(RegionDetailsPayload.TYPE, RegionDetailsPayload.STREAM_CODEC, RegionDetailsPayload::handle);
        registrar.playToClient(ActiveMissionHudPayload.TYPE, ActiveMissionHudPayload.STREAM_CODEC, ActiveMissionHudPayload::handle);

        // Faction Payloads
        registrar.playToServer(com.warfront.network.faction.CreateFactionPayload.TYPE, com.warfront.network.faction.CreateFactionPayload.STREAM_CODEC,
                com.warfront.network.faction.CreateFactionPayload::handle);
        registrar.playToServer(com.warfront.network.faction.JoinFactionRequestPayload.TYPE, com.warfront.network.faction.JoinFactionRequestPayload.STREAM_CODEC,
                com.warfront.network.faction.JoinFactionRequestPayload::handle);
        registrar.playToServer(com.warfront.network.faction.AcceptJoinRequestPayload.TYPE, com.warfront.network.faction.AcceptJoinRequestPayload.STREAM_CODEC,
                com.warfront.network.faction.AcceptJoinRequestPayload::handle);
        registrar.playToServer(com.warfront.network.faction.InvitePlayerPayload.TYPE, com.warfront.network.faction.InvitePlayerPayload.STREAM_CODEC,
                com.warfront.network.faction.InvitePlayerPayload::handle);
        registrar.playToServer(com.warfront.network.faction.KickPlayerPayload.TYPE, com.warfront.network.faction.KickPlayerPayload.STREAM_CODEC,
                com.warfront.network.faction.KickPlayerPayload::handle);
        registrar.playToServer(com.warfront.network.faction.LeaveFactionPayload.TYPE, com.warfront.network.faction.LeaveFactionPayload.STREAM_CODEC,
                com.warfront.network.faction.LeaveFactionPayload::handle);
        registrar.playToServer(com.warfront.network.faction.DisbandFactionPayload.TYPE, com.warfront.network.faction.DisbandFactionPayload.STREAM_CODEC,
                com.warfront.network.faction.DisbandFactionPayload::handle);
        registrar.playToServer(com.warfront.network.faction.UpdateFactionSettingsPayload.TYPE, com.warfront.network.faction.UpdateFactionSettingsPayload.STREAM_CODEC,
                com.warfront.network.faction.UpdateFactionSettingsPayload::handle);
        registrar.playToServer(com.warfront.network.faction.SendFactionChatPayload.TYPE, com.warfront.network.faction.SendFactionChatPayload.STREAM_CODEC,
                com.warfront.network.faction.SendFactionChatPayload::handle);
        registrar.playToServer(com.warfront.network.faction.RequestFactionDataPayload.TYPE, com.warfront.network.faction.RequestFactionDataPayload.STREAM_CODEC,
                com.warfront.network.faction.RequestFactionDataPayload::handle);

        registrar.playToClient(com.warfront.network.faction.SyncFactionDataPayload.TYPE, com.warfront.network.faction.SyncFactionDataPayload.STREAM_CODEC,
                com.warfront.network.faction.SyncFactionDataPayload::handle);
        registrar.playToClient(com.warfront.network.faction.FactionActionFeedbackPayload.TYPE, com.warfront.network.faction.FactionActionFeedbackPayload.STREAM_CODEC,
                com.warfront.network.faction.FactionActionFeedbackPayload::handle);
    }
}
