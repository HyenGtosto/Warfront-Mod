package com.warfront.faction;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;

public record FactionChatMessage(
        UUID messageId,
        @Nullable UUID senderUuid,
        String senderName,
        String text,
        long timestampMs,
        FactionMessageType type,
        @Nullable UUID targetPlayerUuid,
        @Nullable String targetPlayerName
) {
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("message_id", messageId);
        if (senderUuid != null) {
            tag.putUUID("sender_uuid", senderUuid);
        }
        tag.putString("sender_name", senderName);
        tag.putString("text", text);
        tag.putLong("timestamp_ms", timestampMs);
        tag.putInt("type", type.id());
        if (targetPlayerUuid != null) {
            tag.putUUID("target_uuid", targetPlayerUuid);
        }
        if (targetPlayerName != null) {
            tag.putString("target_name", targetPlayerName);
        }
        return tag;
    }

    public static FactionChatMessage fromNbt(CompoundTag tag) {
        UUID messageId = tag.contains("message_id") ? tag.getUUID("message_id") : UUID.randomUUID();
        UUID senderUuid = tag.contains("sender_uuid") ? tag.getUUID("sender_uuid") : null;
        String senderName = tag.getString("sender_name");
        String text = tag.getString("text");
        long timestampMs = tag.getLong("timestamp_ms");
        FactionMessageType type = FactionMessageType.byId(tag.getInt("type"));
        UUID targetPlayerUuid = tag.contains("target_uuid") ? tag.getUUID("target_uuid") : null;
        String targetPlayerName = tag.contains("target_name") ? tag.getString("target_name") : null;
        return new FactionChatMessage(messageId, senderUuid, senderName, text, timestampMs, type, targetPlayerUuid, targetPlayerName);
    }

    public static FactionChatMessage createChat(UUID senderUuid, String senderName, String text) {
        return new FactionChatMessage(UUID.randomUUID(), senderUuid, senderName, text, System.currentTimeMillis(), FactionMessageType.CHAT, null, null);
    }

    public static FactionChatMessage createSystem(String text) {
        return new FactionChatMessage(UUID.randomUUID(), null, "System", text, System.currentTimeMillis(), FactionMessageType.SYSTEM, null, null);
    }

    public static FactionChatMessage createJoinRequest(UUID requesterUuid, String requesterName) {
        return new FactionChatMessage(
                UUID.randomUUID(),
                requesterUuid,
                requesterName,
                requesterName + " has requested to join the faction.",
                System.currentTimeMillis(),
                FactionMessageType.JOIN_REQUEST,
                requesterUuid,
                requesterName
        );
    }
}
