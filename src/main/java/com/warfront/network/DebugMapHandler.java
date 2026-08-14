package com.warfront.network;

import com.warfront.map.MapViewType;
import net.minecraft.server.level.ServerPlayer;

/**
 * Isolated stand-alone Debug Map Handler.
 * Delegates debug map requests to RequestRegionMapPayload with MapViewType.DEBUG.
 */
public final class DebugMapHandler {
    private DebugMapHandler() {
    }

    public static void send3kDebugMapSnapshot(ServerPlayer player) {
        RequestRegionMapPayload.sendSnapshot(player, MapViewType.DEBUG, true);
    }
}
