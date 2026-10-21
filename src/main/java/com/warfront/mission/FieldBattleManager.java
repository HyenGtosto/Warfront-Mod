package com.warfront.mission;

import com.warfront.Warfront;
import com.warfront.faction.PlayerFaction;
import com.warfront.faction.PlayerFactionData;
import com.warfront.network.ActiveMissionHudPayload;
import com.warfront.network.RequestRegionMapPayload;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

/**
 * Server-authoritative manager for King of the Hill Field Battles between player factions.
 * Ticks every 1 second (20 server ticks), calculating player presence, differential progress,
 * stalemate detection, victory resolution, and broadcasting live HUD updates.
 */
public final class FieldBattleManager {

    private FieldBattleManager() {
    }

    private static int tickCounter = 0;
    private static final Set<UUID> playersTrackingFieldBattle = new HashSet<>();

    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter % 20 != 0) {
            return;
        }

        for (ServerLevel level : event.getServer().getAllLevels()) {
            tickLevel(level);
        }
    }

    private static void tickLevel(ServerLevel level) {
        RegionData regions = RegionData.get(level);
        if (regions == null) {
            return;
        }

        PlayerFactionData factionData = PlayerFactionData.get(level);
        if (factionData == null) {
            return;
        }

        Map<Long, RegionData.SiegeCampaign> sieges = regions.getActiveSieges();
        if (sieges.isEmpty()) {
            return;
        }

        List<Map.Entry<Long, RegionData.SiegeCampaign>> entries = new ArrayList<>(sieges.entrySet());
        List<Long> resolvedSieges = new ArrayList<>();
        Set<UUID> currentTracking = new HashSet<>();

        for (Map.Entry<Long, RegionData.SiegeCampaign> entry : entries) {
            long regionKey = entry.getKey();
            RegionData.SiegeCampaign siege = entry.getValue();

            if (!siege.isFieldBattle()) {
                continue;
            }

            int rx = ChunkPos.getX(regionKey);
            int rz = ChunkPos.getZ(regionKey);

            UUID attackerPfId = siege.attackerPlayerFactionId();
            UUID defenderPfId = siege.defenderPlayerFactionId();

            PlayerFaction attackerPf = (attackerPfId != null) ? factionData.getFactionById(attackerPfId) : null;
            PlayerFaction defenderPf = (defenderPfId != null) ? factionData.getFactionById(defenderPfId) : null;

            String attackerName = (attackerPf != null) ? attackerPf.getName() : "Attacker";
            String defenderName = (defenderPf != null) ? defenderPf.getName() : "Defender";

            int minX = rx * RegionData.REGION_SIZE_BLOCKS;
            int maxX = minX + RegionData.REGION_SIZE_BLOCKS;
            int minZ = rz * RegionData.REGION_SIZE_BLOCKS;
            int maxZ = minZ + RegionData.REGION_SIZE_BLOCKS;

            int attackerCount = 0;
            int defenderCount = 0;

            for (ServerPlayer player : level.players()) {
                if (!player.isAlive() || player.isSpectator()) {
                    continue;
                }
                double px = player.getX();
                double pz = player.getZ();
                if (px >= minX && px < maxX && pz >= minZ && pz < maxZ) {
                    PlayerFaction pf = factionData.getFactionByPlayer(player.getUUID());
                    if (pf != null) {
                        if (attackerPfId != null && pf.getId().equals(attackerPfId)) {
                            attackerCount++;
                        } else if (defenderPfId != null && pf.getId().equals(defenderPfId)) {
                            defenderCount++;
                        }
                    }
                }
            }

            float currentAttProg = siege.attackerProgress();
            float currentDefProg = siege.defenderProgress();

            // King of the Hill progress calculation:
            // The faction with more players inside the region gets their progress bar increased.
            // Each extra player accelerates progress.
            // Stalemate (equal player count, including 0 vs 0) causes zero progress gain for both sides.
            if (attackerCount > defenderCount) {
                int diff = attackerCount - defenderCount;
                currentAttProg = Math.min(100.0F, currentAttProg + (diff * 1.0F));
            } else if (defenderCount > attackerCount) {
                int diff = defenderCount - attackerCount;
                currentDefProg = Math.min(100.0F, currentDefProg + (diff * 1.0F));
            }

            long elapsed = level.getGameTime() - siege.startTick();
            long remainingTicks = Math.max(0L, siege.durationTicks() - elapsed);

            boolean attackerWon = (currentAttProg >= 100.0F);
            boolean defenderWon = (currentDefProg >= 100.0F) || (remainingTicks <= 0L);

            if (attackerWon) {
                resolvedSieges.add(regionKey);

                // Transfer region and subregions to Attacking Faction
                regions.setRegion(level, rx, rz, Faction.HUMANITY, 20.0F, 20.0F, com.warfront.region.BaseType.NONE, 0L, null, attackerPfId);

                regions.addLog(level, String.format("§aField battle won: Region (%d, %d) captured by %s against %s.",
                        rx, rz, attackerName, defenderName));
                regions.broadcastTitle(level,
                        Component.literal("§a§lFIELD BATTLE WON!"),
                        Component.literal(String.format("§7%s captured Region (%d, %d)", attackerName, rx, rz)));

                Warfront.LOGGER.info("Field battle won: Region ({}, {}) captured by {} against {}.",
                        rx, rz, attackerName, defenderName);

                clearHudForFieldBattle(level, rx, rz, attackerPfId, defenderPfId);
            } else if (defenderWon) {
                resolvedSieges.add(regionKey);

                // Defender held their territory: retain current owner
                regions.setRegionSiege(rx, rz, false);
                regions.setDirty();

                regions.addLog(level, String.format("§9Field battle defended: Region (%d, %d) held by %s against %s.",
                        rx, rz, defenderName, attackerName));
                regions.broadcastTitle(level,
                        Component.literal("§9§lFIELD BATTLE DEFENDED!"),
                        Component.literal(String.format("§7%s defended Region (%d, %d)", defenderName, rx, rz)));

                Warfront.LOGGER.info("Field battle defended: Region ({}, {}) held by {} against {}.",
                        rx, rz, defenderName, attackerName);

                clearHudForFieldBattle(level, rx, rz, attackerPfId, defenderPfId);
            } else {
                // Battle is ongoing: persist updated progress in SiegeCampaign
                RegionData.SiegeCampaign updated = new RegionData.SiegeCampaign(
                        siege.attacker(),
                        siege.targetRegionX(), siege.targetRegionZ(),
                        siege.sources(), siege.encircled(),
                        siege.startTick(), siege.durationTicks(),
                        siege.activeSubRegionsMask(), siege.attackerClusterId(),
                        attackerPfId, defenderPfId,
                        currentAttProg, currentDefProg, true
                );
                regions.setRegionSiegeWithCampaign(rx, rz, updated);

                String statusDesc;
                if (attackerCount == defenderCount) {
                    statusDesc = (attackerCount == 0)
                            ? "Stalemate: No forces present"
                            : String.format("Stalemate: Contested (%d vs %d)", attackerCount, defenderCount);
                } else if (attackerCount > defenderCount) {
                    statusDesc = String.format("%s capturing (+%d)", attackerName, attackerCount - defenderCount);
                } else {
                    statusDesc = String.format("%s holding (+%d)", defenderName, defenderCount - attackerCount);
                }

                String progDisplay = String.format("%d%% vs %d%%", Math.round(currentAttProg), Math.round(currentDefProg));

                ActiveMissionHudPayload hudPayload = new ActiveMissionHudPayload(
                        true,
                        rx, rz, 0, 0,
                        "Field battle",
                        statusDesc,
                        Math.round(currentAttProg),
                        100,
                        progDisplay,
                        remainingTicks,
                        Faction.HUMANITY.id(),
                        false,
                        true,
                        currentAttProg,
                        currentDefProg,
                        attackerName,
                        defenderName,
                        attackerCount,
                        defenderCount
                );

                for (ServerPlayer player : level.players()) {
                    boolean inRegion = (player.getX() >= minX && player.getX() < maxX && player.getZ() >= minZ && player.getZ() < maxZ);
                    PlayerFaction pf = factionData.getFactionByPlayer(player.getUUID());
                    boolean inInvolvedFaction = (pf != null && (pf.getId().equals(attackerPfId) || pf.getId().equals(defenderPfId)));

                    if (inRegion) {
                        currentTracking.add(player.getUUID());
                        player.connection.send(hudPayload);
                    }
                }
            }
        }

        // Send empty HUD to players who left the field battle
        for (UUID trackedUuid : playersTrackingFieldBattle) {
            if (!currentTracking.contains(trackedUuid)) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(trackedUuid);
                if (player != null) {
                    player.connection.send(ActiveMissionHudPayload.empty());
                }
            }
        }
        playersTrackingFieldBattle.clear();
        playersTrackingFieldBattle.addAll(currentTracking);

        if (!resolvedSieges.isEmpty()) {
            for (long key : resolvedSieges) {
                regions.getActiveSieges().remove(key);
                regions.setRegionSiege(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            }
            RequestRegionMapPayload.notifyActiveMapTerminals(level);
        }
    }

    public static void clearHudForFieldBattle(ServerLevel level, int rx, int rz, UUID attackerPfId, UUID defenderPfId) {
        int minX = rx * RegionData.REGION_SIZE_BLOCKS;
        int maxX = minX + RegionData.REGION_SIZE_BLOCKS;
        int minZ = rz * RegionData.REGION_SIZE_BLOCKS;
        int maxZ = minZ + RegionData.REGION_SIZE_BLOCKS;
        PlayerFactionData factionData = PlayerFactionData.get(level);

        ActiveMissionHudPayload emptyPayload = ActiveMissionHudPayload.empty();
        for (ServerPlayer player : level.players()) {
            boolean inRegion = (player.getX() >= minX && player.getX() < maxX && player.getZ() >= minZ && player.getZ() < maxZ);
            PlayerFaction pf = (factionData != null) ? factionData.getFactionByPlayer(player.getUUID()) : null;
            boolean inInvolvedFaction = (pf != null && (pf.getId().equals(attackerPfId) || pf.getId().equals(defenderPfId)));

            if (inRegion || inInvolvedFaction) {
                player.connection.send(emptyPayload);
                playersTrackingFieldBattle.remove(player.getUUID());
            }
        }
    }
}
