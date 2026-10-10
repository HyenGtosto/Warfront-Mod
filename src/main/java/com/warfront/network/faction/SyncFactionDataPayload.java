package com.warfront.network.faction;

import com.warfront.Warfront;
import com.warfront.client.faction.FactionClientState;
import com.warfront.faction.FactionChatMessage;
import com.warfront.faction.FactionMember;
import com.warfront.faction.PlayerFaction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SyncFactionDataPayload(
        boolean inFaction,
        @Nullable UUID factionId,
        String factionName,
        String description,
        int color,
        @Nullable UUID ownerId,
        List<FactionMember> members,
        List<FactionChatMessage> chatHistory
) implements CustomPacketPayload {

    public static final Type<SyncFactionDataPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "sync_faction_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncFactionDataPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SyncFactionDataPayload decode(RegistryFriendlyByteBuf buf) {
            boolean inFaction = buf.readBoolean();
            if (!inFaction) {
                return new SyncFactionDataPayload(false, null, "", "", 0, null, Collections.emptyList(), Collections.emptyList());
            }

            UUID factionId = buf.readUUID();
            String factionName = buf.readUtf();
            String description = buf.readUtf();
            int color = buf.readInt();
            UUID ownerId = buf.readUUID();

            int memberCount = buf.readVarInt();
            List<FactionMember> members = new ArrayList<>(memberCount);
            for (int i = 0; i < memberCount; i++) {
                UUID mUuid = buf.readUUID();
                String mName = buf.readUtf();
                int roleId = buf.readVarInt();
                members.add(new FactionMember(mUuid, mName, roleId == 0 ? com.warfront.faction.FactionRole.LEADER : com.warfront.faction.FactionRole.MEMBER));
            }

            int chatCount = buf.readVarInt();
            List<FactionChatMessage> chat = new ArrayList<>(chatCount);
            for (int i = 0; i < chatCount; i++) {
                UUID msgId = buf.readUUID();
                boolean hasSender = buf.readBoolean();
                UUID senderUuid = hasSender ? buf.readUUID() : null;
                String senderName = buf.readUtf();
                String text = buf.readUtf();
                long time = buf.readLong();
                int typeId = buf.readVarInt();
                boolean hasTarget = buf.readBoolean();
                UUID targetUuid = hasTarget ? buf.readUUID() : null;
                boolean hasTargetName = buf.readBoolean();
                String targetName = hasTargetName ? buf.readUtf() : null;

                chat.add(new FactionChatMessage(msgId, senderUuid, senderName, text, time, com.warfront.faction.FactionMessageType.byId(typeId), targetUuid, targetName));
            }

            return new SyncFactionDataPayload(true, factionId, factionName, description, color, ownerId, members, chat);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SyncFactionDataPayload payload) {
            buf.writeBoolean(payload.inFaction);
            if (!payload.inFaction) {
                return;
            }

            buf.writeUUID(payload.factionId != null ? payload.factionId : UUID.randomUUID());
            buf.writeUtf(payload.factionName);
            buf.writeUtf(payload.description);
            buf.writeInt(payload.color);
            buf.writeUUID(payload.ownerId != null ? payload.ownerId : UUID.randomUUID());

            buf.writeVarInt(payload.members.size());
            for (FactionMember member : payload.members) {
                buf.writeUUID(member.uuid());
                buf.writeUtf(member.name());
                buf.writeVarInt(member.role() == com.warfront.faction.FactionRole.LEADER ? 0 : 1);
            }

            buf.writeVarInt(payload.chatHistory.size());
            for (FactionChatMessage msg : payload.chatHistory) {
                buf.writeUUID(msg.messageId());
                buf.writeBoolean(msg.senderUuid() != null);
                if (msg.senderUuid() != null) {
                    buf.writeUUID(msg.senderUuid());
                }
                buf.writeUtf(msg.senderName());
                buf.writeUtf(msg.text());
                buf.writeLong(msg.timestampMs());
                buf.writeVarInt(msg.type().id());
                buf.writeBoolean(msg.targetPlayerUuid() != null);
                if (msg.targetPlayerUuid() != null) {
                    buf.writeUUID(msg.targetPlayerUuid());
                }
                buf.writeBoolean(msg.targetPlayerName() != null);
                if (msg.targetPlayerName() != null) {
                    buf.writeUtf(msg.targetPlayerName());
                }
            }
        }
    };

    public static SyncFactionDataPayload createEmpty() {
        return new SyncFactionDataPayload(false, null, "", "", 0, null, Collections.emptyList(), Collections.emptyList());
    }

    public static SyncFactionDataPayload fromFaction(PlayerFaction faction) {
        return new SyncFactionDataPayload(
                true,
                faction.getId(),
                faction.getName(),
                faction.getDescription(),
                faction.getColor(),
                faction.getOwnerId(),
                new ArrayList<>(faction.getMembers().values()),
                new ArrayList<>(faction.getChatHistory())
        );
    }

    public static void handle(SyncFactionDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FactionClientState.get().updateFromPayload(payload);
        });
    }

    @Override
    public Type<SyncFactionDataPayload> type() {
        return TYPE;
    }
}
