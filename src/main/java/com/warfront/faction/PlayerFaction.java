package com.warfront.faction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public class PlayerFaction {
    private final UUID id;
    private String name;
    private String description;
    private int color;
    private UUID ownerId;
    private final Map<UUID, FactionMember> members = new LinkedHashMap<>();
    private final List<FactionChatMessage> chatHistory = new ArrayList<>();
    private final Set<UUID> pendingJoinRequests = new HashSet<>();
    private final Set<UUID> pendingInvites = new HashSet<>();

    public PlayerFaction(UUID id, String name, String description, int color, UUID ownerId) {
        this.id = id;
        this.name = name;
        this.description = description != null ? description : "";
        this.color = color;
        this.ownerId = ownerId;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description != null ? description : "";
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isOwner(UUID playerUuid) {
        return ownerId != null && ownerId.equals(playerUuid);
    }

    public Map<UUID, FactionMember> getMembers() {
        return Collections.unmodifiableMap(members);
    }

    public boolean hasMember(UUID playerUuid) {
        return members.containsKey(playerUuid);
    }

    public void addMember(FactionMember member) {
        members.put(member.uuid(), member);
    }

    public boolean removeMember(UUID playerUuid) {
        return members.remove(playerUuid) != null;
    }

    public FactionMember getMember(UUID playerUuid) {
        return members.get(playerUuid);
    }

    public List<FactionChatMessage> getChatHistory() {
        return Collections.unmodifiableList(chatHistory);
    }

    public void addChatMessage(FactionChatMessage message) {
        chatHistory.add(message);
        if (chatHistory.size() > 100) {
            chatHistory.remove(0);
        }
    }

    public Set<UUID> getPendingJoinRequests() {
        return pendingJoinRequests;
    }

    public void addJoinRequest(UUID playerUuid) {
        pendingJoinRequests.add(playerUuid);
    }

    public boolean removeJoinRequest(UUID playerUuid) {
        return pendingJoinRequests.remove(playerUuid);
    }

    public boolean hasJoinRequest(UUID playerUuid) {
        return pendingJoinRequests.contains(playerUuid);
    }

    public Set<UUID> getPendingInvites() {
        return pendingInvites;
    }

    public void addInvite(UUID playerUuid) {
        pendingInvites.add(playerUuid);
    }

    public boolean removeInvite(UUID playerUuid) {
        return pendingInvites.remove(playerUuid);
    }

    public boolean hasInvite(UUID playerUuid) {
        return pendingInvites.contains(playerUuid);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putString("description", description);
        tag.putInt("color", color);
        tag.putUUID("owner_id", ownerId);

        ListTag membersTag = new ListTag();
        for (FactionMember member : members.values()) {
            membersTag.add(member.toNbt());
        }
        tag.put("members", membersTag);

        ListTag chatTag = new ListTag();
        for (FactionChatMessage chat : chatHistory) {
            chatTag.add(chat.toNbt());
        }
        tag.put("chat", chatTag);

        ListTag requestsTag = new ListTag();
        for (UUID req : pendingJoinRequests) {
            CompoundTag uTag = new CompoundTag();
            uTag.putUUID("uuid", req);
            requestsTag.add(uTag);
        }
        tag.put("join_requests", requestsTag);

        ListTag invitesTag = new ListTag();
        for (UUID inv : pendingInvites) {
            CompoundTag uTag = new CompoundTag();
            uTag.putUUID("uuid", inv);
            invitesTag.add(uTag);
        }
        tag.put("invites", invitesTag);

        return tag;
    }

    public static PlayerFaction fromNbt(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        String name = tag.getString("name");
        String description = tag.getString("description");
        int color = tag.getInt("color");
        UUID ownerId = tag.getUUID("owner_id");

        PlayerFaction faction = new PlayerFaction(id, name, description, color, ownerId);

        ListTag membersTag = tag.getList("members", Tag.TAG_COMPOUND);
        for (int i = 0; i < membersTag.size(); i++) {
            faction.addMember(FactionMember.fromNbt(membersTag.getCompound(i)));
        }

        ListTag chatTag = tag.getList("chat", Tag.TAG_COMPOUND);
        for (int i = 0; i < chatTag.size(); i++) {
            faction.addChatMessage(FactionChatMessage.fromNbt(chatTag.getCompound(i)));
        }

        ListTag requestsTag = tag.getList("join_requests", Tag.TAG_COMPOUND);
        for (int i = 0; i < requestsTag.size(); i++) {
            faction.addJoinRequest(requestsTag.getCompound(i).getUUID("uuid"));
        }

        ListTag invitesTag = tag.getList("invites", Tag.TAG_COMPOUND);
        for (int i = 0; i < invitesTag.size(); i++) {
            faction.addInvite(invitesTag.getCompound(i).getUUID("uuid"));
        }

        return faction;
    }
}
