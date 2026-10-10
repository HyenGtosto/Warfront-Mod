package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.client.hud.ActiveMissionHudOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-to-client payload synchronizing active subregion mission status and progress
 * to the player's HUD overlay.
 */
public record ActiveMissionHudPayload(
        boolean hasActiveMission,
        int regionX,
        int regionZ,
        int subX,
        int subZ,
        String missionName,
        String objectiveDescription,
        int currentProgress,
        int targetProgress,
        String progressDisplayString,
        long remainingTicks,
        int factionId,
        boolean isDefense,
        boolean isFieldBattle,
        float attackerProgress,
        float defenderProgress,
        String attackerFactionName,
        String defenderFactionName,
        int attackerCount,
        int defenderCount
) implements CustomPacketPayload {

    public ActiveMissionHudPayload(
            boolean hasActiveMission,
            int regionX,
            int regionZ,
            int subX,
            int subZ,
            String missionName,
            String objectiveDescription,
            int currentProgress,
            int targetProgress,
            String progressDisplayString,
            long remainingTicks,
            int factionId,
            boolean isDefense
    ) {
        this(hasActiveMission, regionX, regionZ, subX, subZ, missionName, objectiveDescription, currentProgress, targetProgress, progressDisplayString, remainingTicks, factionId, isDefense, false, 0.0F, 0.0F, "", "", 0, 0);
    }

    public static final Type<ActiveMissionHudPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "active_mission_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActiveMissionHudPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ActiveMissionHudPayload decode(RegistryFriendlyByteBuf buf) {
            boolean hasActiveMission = ByteBufCodecs.BOOL.decode(buf);
            int rx = ByteBufCodecs.VAR_INT.decode(buf);
            int rz = ByteBufCodecs.VAR_INT.decode(buf);
            int sx = ByteBufCodecs.VAR_INT.decode(buf);
            int sz = ByteBufCodecs.VAR_INT.decode(buf);
            String mName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String objDesc = ByteBufCodecs.STRING_UTF8.decode(buf);
            int curProg = ByteBufCodecs.VAR_INT.decode(buf);
            int tgtProg = ByteBufCodecs.VAR_INT.decode(buf);
            String progDisp = ByteBufCodecs.STRING_UTF8.decode(buf);
            long remTicks = ByteBufCodecs.VAR_LONG.decode(buf);
            int fId = ByteBufCodecs.VAR_INT.decode(buf);
            boolean isDef = ByteBufCodecs.BOOL.decode(buf);
            boolean isField = ByteBufCodecs.BOOL.decode(buf);
            float attProg = ByteBufCodecs.FLOAT.decode(buf);
            float defProg = ByteBufCodecs.FLOAT.decode(buf);
            String attName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String defName = ByteBufCodecs.STRING_UTF8.decode(buf);
            int attCount = ByteBufCodecs.VAR_INT.decode(buf);
            int defCount = ByteBufCodecs.VAR_INT.decode(buf);

            return new ActiveMissionHudPayload(
                    hasActiveMission, rx, rz, sx, sz,
                    mName, objDesc, curProg, tgtProg, progDisp, remTicks, fId, isDef,
                    isField, attProg, defProg, attName, defName, attCount, defCount
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ActiveMissionHudPayload payload) {
            ByteBufCodecs.BOOL.encode(buf, payload.hasActiveMission());
            ByteBufCodecs.VAR_INT.encode(buf, payload.regionX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.regionZ());
            ByteBufCodecs.VAR_INT.encode(buf, payload.subX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.subZ());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.missionName() != null ? payload.missionName() : "");
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.objectiveDescription() != null ? payload.objectiveDescription() : "");
            ByteBufCodecs.VAR_INT.encode(buf, payload.currentProgress());
            ByteBufCodecs.VAR_INT.encode(buf, payload.targetProgress());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.progressDisplayString() != null ? payload.progressDisplayString() : "");
            ByteBufCodecs.VAR_LONG.encode(buf, payload.remainingTicks());
            ByteBufCodecs.VAR_INT.encode(buf, payload.factionId());
            ByteBufCodecs.BOOL.encode(buf, payload.isDefense());
            ByteBufCodecs.BOOL.encode(buf, payload.isFieldBattle());
            ByteBufCodecs.FLOAT.encode(buf, payload.attackerProgress());
            ByteBufCodecs.FLOAT.encode(buf, payload.defenderProgress());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.attackerFactionName() != null ? payload.attackerFactionName() : "");
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.defenderFactionName() != null ? payload.defenderFactionName() : "");
            ByteBufCodecs.VAR_INT.encode(buf, payload.attackerCount());
            ByteBufCodecs.VAR_INT.encode(buf, payload.defenderCount());
        }
    };

    /**
     * Backward-compatibility helper for kill count queries.
     */
    public int currentKills() {
        return currentProgress;
    }

    /**
     * Backward-compatibility helper for required kill count queries.
     */
    public int requiredKills() {
        return targetProgress;
    }

    /**
     * Factory for an empty inactive HUD payload.
     */
    public static ActiveMissionHudPayload empty() {
        return new ActiveMissionHudPayload(
                false, 0, 0, 0, 0, "", "", 0, 0, "", 0L, 0, false, false, 0.0F, 0.0F, "", "", 0, 0
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ActiveMissionHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ActiveMissionHudOverlay.updateHud(payload));
    }
}
