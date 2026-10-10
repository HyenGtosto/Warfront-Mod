package com.warfront.client.faction;

import com.warfront.faction.FactionChatMessage;
import com.warfront.faction.FactionMember;
import com.warfront.network.faction.SyncFactionDataPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;

public final class FactionClientState {
    private static final FactionClientState INSTANCE = new FactionClientState();

    public static FactionClientState get() {
        return INSTANCE;
    }

    private boolean inFaction = false;
    @Nullable private UUID factionId = null;
    private String factionName = "";
    private String description = "";
    private int color = 0x21BCFF;
    @Nullable private UUID ownerId = null;
    private final List<FactionMember> members = new ArrayList<>();
    private final List<FactionChatMessage> chatHistory = new ArrayList<>();

    @Nullable private String lastFeedback = null;
    private boolean lastFeedbackSuccess = true;
    private long feedbackTimestampMs = 0L;

    @Nullable private Runnable onStateChanged = null;

    private FactionClientState() {
    }

    public void setOnStateChanged(@Nullable Runnable listener) {
        this.onStateChanged = listener;
    }

    public boolean isInFaction() {
        return inFaction;
    }

    @Nullable
    public UUID getFactionId() {
        return factionId;
    }

    public String getFactionName() {
        return factionName;
    }

    public String getDescription() {
        return description;
    }

    public int getColor() {
        return color;
    }

    @Nullable
    public UUID getOwnerId() {
        return ownerId;
    }

    public boolean isOwner() {
        if (!inFaction || ownerId == null) return false;
        var player = Minecraft.getInstance().player;
        return player != null && ownerId.equals(player.getUUID());
    }

    public List<FactionMember> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public List<FactionChatMessage> getChatHistory() {
        return Collections.unmodifiableList(chatHistory);
    }

    @Nullable
    public String getLastFeedback() {
        if (System.currentTimeMillis() - feedbackTimestampMs > 5000L) {
            return null;
        }
        return lastFeedback;
    }

    public boolean isLastFeedbackSuccess() {
        return lastFeedbackSuccess;
    }

    public void setFeedback(boolean success, String message) {
        this.lastFeedback = message;
        this.lastFeedbackSuccess = success;
        this.feedbackTimestampMs = System.currentTimeMillis();
        if (onStateChanged != null) {
            onStateChanged.run();
        }
    }

    public void clearFeedback() {
        this.lastFeedback = null;
    }

    public void updateFromPayload(SyncFactionDataPayload payload) {
        this.inFaction = payload.inFaction();
        this.factionId = payload.factionId();
        this.factionName = payload.factionName();
        this.description = payload.description();
        this.color = payload.color();
        this.ownerId = payload.ownerId();

        this.members.clear();
        this.members.addAll(payload.members());

        this.chatHistory.clear();
        this.chatHistory.addAll(payload.chatHistory());

        if (onStateChanged != null) {
            onStateChanged.run();
        }
    }
}
