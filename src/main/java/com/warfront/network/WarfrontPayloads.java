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
        registrar.playToServer(CloseMapSessionPayload.TYPE, CloseMapSessionPayload.STREAM_CODEC,
                CloseMapSessionPayload::handle);
        registrar.playToClient(RegionMapPayload.TYPE, RegionMapPayload.STREAM_CODEC, RegionMapPayload::handle);
        registrar.playToClient(RegionDetailsPayload.TYPE, RegionDetailsPayload.STREAM_CODEC, RegionDetailsPayload::handle);
    }
}
