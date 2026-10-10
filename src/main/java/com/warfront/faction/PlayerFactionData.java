package com.warfront.faction;

import com.warfront.Warfront;
import com.warfront.network.faction.FactionActionFeedbackPayload;
import com.warfront.network.faction.SyncFactionDataPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PlayerFactionData extends SavedData {
    public static final String DATA_FILE_ID = "warfront_player_factions";

    private final Map<UUID, PlayerFaction> factionsById = new HashMap<>();
    private final Map<String, UUID> factionIdByName = new HashMap<>();
    private final Map<UUID, UUID> playerToFactionMap = new HashMap<>();

    public static final Factory<PlayerFactionData> FACTORY = new Factory<>(
            PlayerFactionData::new,
            PlayerFactionData::load
    );

    public static PlayerFactionData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_FILE_ID);
    }

    public static PlayerFactionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_FILE_ID);
    }

    public PlayerFactionData() {
    }

    @Nullable
    public PlayerFaction getFactionById(UUID id) {
        return factionsById.get(id);
    }

    @Nullable
    public PlayerFaction getFactionByPlayer(UUID playerUuid) {
        UUID factionId = playerToFactionMap.get(playerUuid);
        return factionId != null ? factionsById.get(factionId) : null;
    }

    @Nullable
    public PlayerFaction getFactionByName(String name) {
        if (name == null) return null;
        UUID id = factionIdByName.get(name.trim().toLowerCase(Locale.ROOT));
        return id != null ? factionsById.get(id) : null;
    }

    public boolean createFaction(ServerPlayer player, String name, String description, int color) {
        UUID playerUuid = player.getUUID();
        if (getFactionByPlayer(playerUuid) != null) {
            sendFeedback(player, false, "You are already in a faction!");
            return false;
        }

        String trimmedName = name != null ? name.trim() : "";
        if (trimmedName.length() < 2 || trimmedName.length() > 24) {
            sendFeedback(player, false, "Faction name must be between 2 and 24 characters!");
            return false;
        }

        String nameKey = trimmedName.toLowerCase(Locale.ROOT);
        if (factionIdByName.containsKey(nameKey)) {
            sendFeedback(player, false, "Faction name '" + trimmedName + "' is already taken!");
            return false;
        }

        UUID factionId = UUID.randomUUID();
        PlayerFaction faction = new PlayerFaction(factionId, trimmedName, description, color, playerUuid);
        faction.addMember(new FactionMember(playerUuid, player.getName().getString(), FactionRole.LEADER));
        faction.addChatMessage(FactionChatMessage.createSystem("Faction '" + trimmedName + "' was created by " + player.getName().getString() + "."));

        factionsById.put(factionId, faction);
        factionIdByName.put(nameKey, factionId);
        playerToFactionMap.put(playerUuid, factionId);

        setDirty();
        sendFeedback(player, true, "Faction '" + trimmedName + "' created successfully!");
        syncToPlayer(player);
        return true;
    }

    public boolean requestJoin(ServerPlayer player, String targetFactionName) {
        UUID playerUuid = player.getUUID();
        if (getFactionByPlayer(playerUuid) != null) {
            sendFeedback(player, false, "You are already in a faction!");
            return false;
        }

        PlayerFaction target = getFactionByName(targetFactionName);
        if (target == null) {
            sendFeedback(player, false, "Faction '" + targetFactionName + "' does not exist!");
            return false;
        }

        if (target.hasJoinRequest(playerUuid)) {
            sendFeedback(player, false, "You already have a pending request to join " + target.getName() + "!");
            return false;
        }

        target.addJoinRequest(playerUuid);
        FactionChatMessage requestMsg = FactionChatMessage.createJoinRequest(playerUuid, player.getName().getString());
        target.addChatMessage(requestMsg);

        setDirty();
        sendFeedback(player, true, "Join request sent to " + target.getName() + "!");
        syncToFaction(target, player.server);
        return true;
    }

    public boolean acceptJoinRequest(ServerPlayer approver, UUID requesterUuid) {
        PlayerFaction faction = getFactionByPlayer(approver.getUUID());
        if (faction == null) {
            sendFeedback(approver, false, "You are not in a faction!");
            return false;
        }

        if (!faction.isOwner(approver.getUUID())) {
            sendFeedback(approver, false, "Only the faction owner can approve join requests!");
            return false;
        }

        if (!faction.removeJoinRequest(requesterUuid)) {
            sendFeedback(approver, false, "No pending join request found for that player.");
            return false;
        }

        if (playerToFactionMap.containsKey(requesterUuid)) {
            sendFeedback(approver, false, "That player has already joined another faction.");
            setDirty();
            syncToFaction(faction, approver.server);
            return false;
        }

        ServerPlayer requesterPlayer = approver.server.getPlayerList().getPlayer(requesterUuid);
        String requesterName = requesterPlayer != null ? requesterPlayer.getName().getString() : "Unknown";

        // If requester is not online, find their name from previous chat message if possible
        if (requesterPlayer == null) {
            for (FactionChatMessage chat : faction.getChatHistory()) {
                if (chat.type() == FactionMessageType.JOIN_REQUEST && requesterUuid.equals(chat.targetPlayerUuid())) {
                    if (chat.targetPlayerName() != null) {
                        requesterName = chat.targetPlayerName();
                        break;
                    }
                }
            }
        }

        faction.addMember(new FactionMember(requesterUuid, requesterName, FactionRole.MEMBER));
        playerToFactionMap.put(requesterUuid, faction.getId());
        faction.addChatMessage(FactionChatMessage.createSystem(requesterName + " has joined the faction!"));

        setDirty();
        syncToFaction(faction, approver.server);

        if (requesterPlayer != null) {
            requesterPlayer.sendSystemMessage(Component.literal("§a[Warfront] Your request to join " + faction.getName() + " was accepted!"));
            syncToPlayer(requesterPlayer);
        }

        return true;
    }

    public boolean invitePlayer(ServerPlayer inviter, String targetPlayerName) {
        PlayerFaction faction = getFactionByPlayer(inviter.getUUID());
        if (faction == null) {
            sendFeedback(inviter, false, "You are not in a faction!");
            return false;
        }

        ServerPlayer target = inviter.server.getPlayerList().getPlayerByName(targetPlayerName);
        if (target == null) {
            sendFeedback(inviter, false, "Player '" + targetPlayerName + "' is not online!");
            return false;
        }

        if (getFactionByPlayer(target.getUUID()) != null) {
            sendFeedback(inviter, false, targetPlayerName + " is already in a faction!");
            return false;
        }

        faction.addInvite(target.getUUID());
        faction.addChatMessage(FactionChatMessage.createSystem(inviter.getName().getString() + " invited " + targetPlayerName + " to the faction."));

        setDirty();
        sendFeedback(inviter, true, "Invited " + targetPlayerName + " to the faction!");
        target.sendSystemMessage(Component.literal("§b[Warfront] " + inviter.getName().getString() + " invited you to join faction '§e" + faction.getName() + "§b'! Enter '" + faction.getName() + "' in the Faction menu to join."));
        syncToFaction(faction, inviter.server);
        return true;
    }

    public boolean kickPlayer(ServerPlayer kicker, UUID targetUuid) {
        PlayerFaction faction = getFactionByPlayer(kicker.getUUID());
        if (faction == null) {
            sendFeedback(kicker, false, "You are not in a faction!");
            return false;
        }

        if (!faction.isOwner(kicker.getUUID())) {
            sendFeedback(kicker, false, "Only the faction owner can kick players!");
            return false;
        }

        if (kicker.getUUID().equals(targetUuid)) {
            sendFeedback(kicker, false, "You cannot kick yourself! Use leave faction instead.");
            return false;
        }

        FactionMember targetMember = faction.getMember(targetUuid);
        if (targetMember == null) {
            sendFeedback(kicker, false, "Player is not in your faction!");
            return false;
        }

        faction.removeMember(targetUuid);
        playerToFactionMap.remove(targetUuid);
        faction.addChatMessage(FactionChatMessage.createSystem(targetMember.name() + " was kicked from the faction."));

        setDirty();
        sendFeedback(kicker, true, "Kicked " + targetMember.name() + " from the faction.");
        syncToFaction(faction, kicker.server);

        ServerPlayer kickedPlayer = kicker.server.getPlayerList().getPlayer(targetUuid);
        if (kickedPlayer != null) {
            kickedPlayer.sendSystemMessage(Component.literal("§c[Warfront] You have been kicked from faction " + faction.getName() + "."));
            syncToPlayer(kickedPlayer);
        }

        return true;
    }

    public boolean leaveFaction(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        PlayerFaction faction = getFactionByPlayer(playerUuid);
        if (faction == null) {
            sendFeedback(player, false, "You are not in a faction!");
            return false;
        }

        FactionMember member = faction.getMember(playerUuid);
        String playerName = member != null ? member.name() : player.getName().getString();

        if (faction.isOwner(playerUuid)) {
            // Owner is leaving
            if (faction.getMembers().size() <= 1) {
                // Last member -> disband
                return disbandFaction(player);
            } else {
                // Transfer ownership to another member
                UUID newOwnerUuid = null;
                for (UUID otherId : faction.getMembers().keySet()) {
                    if (!otherId.equals(playerUuid)) {
                        newOwnerUuid = otherId;
                        break;
                    }
                }
                if (newOwnerUuid != null) {
                    faction.setOwnerId(newOwnerUuid);
                    FactionMember oldLeader = faction.getMember(newOwnerUuid);
                    faction.addMember(new FactionMember(newOwnerUuid, oldLeader.name(), FactionRole.LEADER));
                    faction.addChatMessage(FactionChatMessage.createSystem("Leadership transferred to " + oldLeader.name() + "."));
                }
            }
        }

        faction.removeMember(playerUuid);
        playerToFactionMap.remove(playerUuid);
        faction.addChatMessage(FactionChatMessage.createSystem(playerName + " left the faction."));

        setDirty();
        sendFeedback(player, true, "You left faction " + faction.getName() + ".");
        syncToPlayer(player);
        syncToFaction(faction, player.server);
        return true;
    }

    public boolean disbandFaction(ServerPlayer owner) {
        PlayerFaction faction = getFactionByPlayer(owner.getUUID());
        if (faction == null) {
            sendFeedback(owner, false, "You are not in a faction!");
            return false;
        }

        if (!faction.isOwner(owner.getUUID())) {
            sendFeedback(owner, false, "Only the faction owner can disband the faction!");
            return false;
        }

        String name = faction.getName();
        factionsById.remove(faction.getId());
        factionIdByName.remove(name.toLowerCase(Locale.ROOT));

        List<UUID> memberUuids = new ArrayList<>(faction.getMembers().keySet());
        for (UUID memberUuid : memberUuids) {
            playerToFactionMap.remove(memberUuid);
            ServerPlayer memberPlayer = owner.server.getPlayerList().getPlayer(memberUuid);
            if (memberPlayer != null) {
                memberPlayer.sendSystemMessage(Component.literal("§c[Warfront] Faction '" + name + "' was disbanded by the owner."));
                syncToPlayer(memberPlayer);
            }
        }

        // Return all regions owned by that faction to unowned state and delete all attack campaigns
        ServerLevel overworld = owner.server.overworld();
        if (overworld != null) {
            com.warfront.region.RegionData regionData = com.warfront.region.RegionData.get(overworld);

            // 1. Instantly delete all attack campaigns started by this faction (and any field battles it participates in)
            List<Long> siegesToCancel = new ArrayList<>();
            for (Map.Entry<Long, com.warfront.region.RegionData.SiegeCampaign> entry : regionData.getActiveSieges().entrySet()) {
                com.warfront.region.RegionData.SiegeCampaign siege = entry.getValue();
                boolean isAttacker = siege.attackerPlayerFactionId() != null && siege.attackerPlayerFactionId().equals(faction.getId());
                boolean isDefender = siege.defenderPlayerFactionId() != null && siege.defenderPlayerFactionId().equals(faction.getId());
                if (isAttacker || isDefender) {
                    siegesToCancel.add(entry.getKey());
                }
            }

            for (long regKey : siegesToCancel) {
                int srx = net.minecraft.world.level.ChunkPos.getX(regKey);
                int srz = net.minecraft.world.level.ChunkPos.getZ(regKey);
                com.warfront.region.RegionData.SiegeCampaign sc = regionData.getActiveSieges().remove(regKey);
                regionData.setRegionSiege(srx, srz, false);
                com.warfront.mission.ActiveCampaignMissionManager.clearCampaign(overworld, srx, srz);
                regionData.revertRegionSubRegionsToOwner(overworld, srx, srz);
                if (sc != null && sc.isFieldBattle()) {
                    com.warfront.mission.FieldBattleManager.clearHudForFieldBattle(overworld, srx, srz, sc.attackerPlayerFactionId(), sc.defenderPlayerFactionId());
                }
                regionData.addLog(overworld, String.format("§eCampaign cancelled on Region (%d, %d) due to faction disband.", srx, srz));
            }

            // 2. Revert any owned regions to unowned
            List<com.warfront.region.RegionData.Region> ownedRegions = regionData.getRegionsOwnedBy(faction.getId());
            for (com.warfront.region.RegionData.Region reg : ownedRegions) {
                regionData.unclaim(overworld, reg.x(), reg.z());
            }

            if (!ownedRegions.isEmpty() || !siegesToCancel.isEmpty()) {
                com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(overworld);
                regionData.addLog(overworld, String.format("§eFaction '%s' disbanded: %d regions reverted to unowned, %d campaigns cancelled.", name, ownedRegions.size(), siegesToCancel.size()));
            }
        }

        setDirty();
        sendFeedback(owner, true, "Faction '" + name + "' was disbanded.");
        return true;
    }

    public boolean updateFactionSettings(ServerPlayer owner, String newName, String newDesc, int newColor) {
        PlayerFaction faction = getFactionByPlayer(owner.getUUID());
        if (faction == null) {
            sendFeedback(owner, false, "You are not in a faction!");
            return false;
        }

        if (!faction.isOwner(owner.getUUID())) {
            sendFeedback(owner, false, "Only the owner can update faction settings!");
            return false;
        }

        String trimmedName = newName != null ? newName.trim() : "";
        if (trimmedName.length() < 2 || trimmedName.length() > 24) {
            sendFeedback(owner, false, "Faction name must be between 2 and 24 characters!");
            return false;
        }

        if (!trimmedName.equalsIgnoreCase(faction.getName())) {
            String nameKey = trimmedName.toLowerCase(Locale.ROOT);
            if (factionIdByName.containsKey(nameKey)) {
                sendFeedback(owner, false, "Faction name '" + trimmedName + "' is already in use!");
                return false;
            }
            factionIdByName.remove(faction.getName().toLowerCase(Locale.ROOT));
            factionIdByName.put(nameKey, faction.getId());
            faction.setName(trimmedName);
        }

        faction.setDescription(newDesc != null ? newDesc.trim() : "");
        faction.setColor(newColor);
        faction.addChatMessage(FactionChatMessage.createSystem("Faction settings updated by owner."));

        setDirty();
        sendFeedback(owner, true, "Faction settings updated!");
        syncToFaction(faction, owner.server);
        return true;
    }

    public boolean sendChatMessage(ServerPlayer sender, String message) {
        PlayerFaction faction = getFactionByPlayer(sender.getUUID());
        if (faction == null) {
            sendFeedback(sender, false, "You are not in a faction!");
            return false;
        }

        String trimmed = message != null ? message.trim() : "";
        if (trimmed.isEmpty()) {
            return false;
        }

        faction.addChatMessage(FactionChatMessage.createChat(sender.getUUID(), sender.getName().getString(), trimmed));
        setDirty();
        syncToFaction(faction, sender.server);
        return true;
    }

    public void syncToPlayer(ServerPlayer player) {
        PlayerFaction faction = getFactionByPlayer(player.getUUID());
        if (faction == null) {
            PacketDistributor.sendToPlayer(player, SyncFactionDataPayload.createEmpty());
        } else {
            PacketDistributor.sendToPlayer(player, SyncFactionDataPayload.fromFaction(faction));
        }
    }

    public void syncToFaction(PlayerFaction faction, MinecraftServer server) {
        SyncFactionDataPayload payload = SyncFactionDataPayload.fromFaction(faction);
        for (UUID memberUuid : faction.getMembers().keySet()) {
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(memberUuid);
            if (memberPlayer != null) {
                PacketDistributor.sendToPlayer(memberPlayer, payload);
            }
        }
    }

    private void sendFeedback(ServerPlayer player, boolean success, String message) {
        PacketDistributor.sendToPlayer(player, new FactionActionFeedbackPayload(success, message));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag factionsTag = new ListTag();
        for (PlayerFaction faction : factionsById.values()) {
            factionsTag.add(faction.toNbt());
        }
        tag.put("factions", factionsTag);

        CompoundTag mappingsTag = new CompoundTag();
        for (Map.Entry<UUID, UUID> entry : playerToFactionMap.entrySet()) {
            mappingsTag.putUUID(entry.getKey().toString(), entry.getValue());
        }
        tag.put("player_mappings", mappingsTag);

        return tag;
    }

    public static PlayerFactionData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerFactionData data = new PlayerFactionData();

        ListTag factionsTag = tag.getList("factions", Tag.TAG_COMPOUND);
        for (int i = 0; i < factionsTag.size(); i++) {
            CompoundTag fTag = factionsTag.getCompound(i);
            PlayerFaction faction = PlayerFaction.fromNbt(fTag);
            data.factionsById.put(faction.getId(), faction);
            data.factionIdByName.put(faction.getName().toLowerCase(Locale.ROOT), faction.getId());
        }

        if (tag.contains("player_mappings", Tag.TAG_COMPOUND)) {
            CompoundTag mappingsTag = tag.getCompound("player_mappings");
            for (String key : mappingsTag.getAllKeys()) {
                try {
                    UUID playerUuid = UUID.fromString(key);
                    UUID factionId = mappingsTag.getUUID(key);
                    if (data.factionsById.containsKey(factionId)) {
                        data.playerToFactionMap.put(playerUuid, factionId);
                    }
                } catch (Exception ignored) {
                }
            }
        } else {
            // Rebuild from factions
            for (PlayerFaction faction : data.factionsById.values()) {
                for (UUID memberUuid : faction.getMembers().keySet()) {
                    data.playerToFactionMap.put(memberUuid, faction.getId());
                }
            }
        }

        return data;
    }
}
