package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.region.RegionData;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LaunchAttackPayload(int regionX, int regionZ, int subX, int subZ, int activeSubRegionsMask) implements CustomPacketPayload {
    public LaunchAttackPayload(int regionX, int regionZ, int subX, int subZ) {
        this(regionX, regionZ, subX, subZ, 0xF); // Default all 4 sub-regions active (mask = 15 = 0b1111)
    }

    public static final Type<LaunchAttackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "launch_attack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LaunchAttackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::regionX,
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::regionZ,
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::subX,
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::subZ,
            ByteBufCodecs.VAR_INT, LaunchAttackPayload::activeSubRegionsMask,
            LaunchAttackPayload::new);

    public static void handle(LaunchAttackPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        ServerLevel level = player.serverLevel();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.overworld_only"));
            return;
        }
        RegionData regions = RegionData.get(level);

        com.warfront.faction.PlayerFaction playerFaction = com.warfront.faction.PlayerFactionData.get(level).getFactionByPlayer(player.getUUID());
        if (playerFaction == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.must_be_in_faction"));
            return;
        }

        if (!com.warfront.region.generator.ProceduralRegionGenerator.getInstance().biomeAvailableForExpansion(level, payload.regionX(), payload.regionZ())) {
            return;
        }

        RegionData.Region targetRegion = regions.regionAt(payload.regionX(), payload.regionZ());
        RegionData.SiegeCampaign existingSiege = regions.getSiege(payload.regionX(), payload.regionZ());

        if (existingSiege != null && existingSiege.isFieldBattle()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.field_battle_active"));
            return;
        }

        if (existingSiege != null && existingSiege.attacker() != com.warfront.region.Faction.HUMANITY) {
            UUID defPfId;
            if (targetRegion.owner() == com.warfront.region.Faction.HUMANITY) {
                // Defense Campaign Confirmation: player is activating defense missions for an ongoing AI siege
                UUID ownerPfId = targetRegion.playerFactionId();
                if (ownerPfId != null && !ownerPfId.equals(playerFaction.getId())) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.defense_owner_only"));
                    return;
                }
                defPfId = ownerPfId;
            } else if (targetRegion.owner() == com.warfront.region.Faction.UNCLAIMED) {
                // Wilderness Incursion Defense: player faction commits to defending unowned wilderness against AI expansion
                if (existingSiege.defenderPlayerFactionId() != null && !existingSiege.defenderPlayerFactionId().equals(playerFaction.getId())) {
                    com.warfront.faction.PlayerFaction defPf = com.warfront.faction.PlayerFactionData.get(level).getFactionById(existingSiege.defenderPlayerFactionId());
                    String defName = (defPf != null) ? defPf.getName() : "Another faction";
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            String.format("§c[Warfront] %s is already defending this wilderness incursion!", defName)));
                    return;
                }
                if (existingSiege.defenderPlayerFactionId() == null) {
                    if (!regions.isRegionReachable(payload.regionX(), payload.regionZ(), playerFaction.getId())) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.bordering_required_defend"));
                        return;
                    }
                    RegionData.Region playerRegion = regions.regionAt(player.blockPosition());
                    boolean playerInFactionRegion = (playerRegion.owner() == com.warfront.region.Faction.HUMANITY) && playerFaction.getId().equals(playerRegion.playerFactionId());
                    if (!playerInFactionRegion) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.must_be_in_friendly_defend"));
                        return;
                    }
                }
                defPfId = playerFaction.getId();
            } else {
                // Region is an AI region engaged in war between AI factions
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.ai_war_ongoing"));
                return;
            }

            int requestedMask = payload.activeSubRegionsMask();
            if (requestedMask == 0) {
                return;
            }
            int validDefenseMask = requestedMask;

            int finalMask = existingSiege.activeSubRegionsMask() | validDefenseMask;
            regions.setRegionSiegeWithCampaign(payload.regionX(), payload.regionZ(),
                    new RegionData.SiegeCampaign(existingSiege.attacker(), payload.regionX(), payload.regionZ(),
                            existingSiege.sources(), existingSiege.encircled(),
                            existingSiege.startTick(), existingSiege.durationTicks(), finalMask,
                            existingSiege.attackerClusterId(), existingSiege.attackerPlayerFactionId(),
                            defPfId, existingSiege.attackerProgress(), existingSiege.defenderProgress(),
                            existingSiege.isFieldBattle()));

            float effectiveResistance = regions.calculateEffectiveResistance(payload.regionX(), payload.regionZ());
            float effectiveStability = regions.calculateEffectiveStability(payload.regionX(), payload.regionZ());
            com.warfront.mission.ActiveCampaignMissionManager.startCampaign(
                    level, payload.regionX(), payload.regionZ(),
                    existingSiege.attacker(), targetRegion.baseType(),
                    effectiveResistance, effectiveStability, validDefenseMask);

            Warfront.LOGGER.info("Defense missions confirmed: {} ({}) → Region ({}, {}), active mask {}.",
                    player.getName().getString(), playerFaction.getName(), payload.regionX(), payload.regionZ(), finalMask);
            if (targetRegion.owner() == com.warfront.region.Faction.UNCLAIMED) {
                regions.addLog(level, String.format("§a⚔ Wilderness defense launched: %s committed to defending Region (%d, %d) against %s incursion!",
                        playerFaction.getName(), payload.regionX(), payload.regionZ(), existingSiege.attacker().commandName()));
            }
            RequestRegionMapPayload.notifyActiveMapTerminals(level);
            return;
        }

        // Humanity vs Humanity War: Single King of the Hill Field Battle without subregions
        if (targetRegion.owner() == com.warfront.region.Faction.HUMANITY) {
            RegionData.Region playerRegion = regions.regionAt(player.blockPosition());
            boolean playerInFactionRegion = (playerRegion.owner() == com.warfront.region.Faction.HUMANITY) && playerFaction.getId().equals(playerRegion.playerFactionId());
            if (!playerInFactionRegion) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.must_be_in_friendly_attack"));
                return;
            }

            UUID defenderPfId = targetRegion.playerFactionId();
            if (defenderPfId != null && defenderPfId.equals(playerFaction.getId())) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.cannot_attack_own"));
                return;
            }
            if (existingSiege != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.already_under_siege"));
                return;
            }

            // Find all bordering regions owned by the attacking faction
            java.util.List<RegionData.SourcePos> sources = new java.util.ArrayList<>();
            int[][] cardinalOffsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
            for (int[] offset : cardinalOffsets) {
                int nx = payload.regionX() + offset[0];
                int nz = payload.regionZ() + offset[1];
                RegionData.Region neighbor = regions.regionAt(nx, nz);
                if (neighbor.owner() == com.warfront.region.Faction.HUMANITY && playerFaction.getId().equals(neighbor.playerFactionId())) {
                    sources.add(new RegionData.SourcePos(nx, nz));
                }
            }

            if (sources.isEmpty()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.bordering_required_attack"));
                return;
            }

            long startTick = level.getGameTime();
            long durationTicks = 12000L; // 10 minutes (600 seconds) baseline for player field battles

            RegionData.SiegeCampaign fieldBattleSiege = new RegionData.SiegeCampaign(
                    com.warfront.region.Faction.HUMANITY,
                    payload.regionX(), payload.regionZ(),
                    sources, false,
                    startTick, durationTicks,
                    0xF, 0L,
                    playerFaction.getId(), defenderPfId,
                    0.0F, 0.0F, true
            );
            regions.setRegionSiegeWithCampaign(payload.regionX(), payload.regionZ(), fieldBattleSiege);

            String defenderName = "Unknown Faction";
            if (defenderPfId != null) {
                com.warfront.faction.PlayerFaction defPf = com.warfront.faction.PlayerFactionData.get(level).getFactionById(defenderPfId);
                if (defPf != null) defenderName = defPf.getName();
            }
            String attackerName = playerFaction.getName();

            if (sources.size() > 1) {
                regions.addLog(level, String.format("§c⚔ Flank attack launched: %s (%d vectors) → Region (%d, %d) [%s] - Field battle commenced!",
                        attackerName, sources.size(), payload.regionX(), payload.regionZ(), defenderName));
                regions.broadcastTitle(level,
                        net.minecraft.network.chat.Component.translatable("title.warfront.flank_assault"),
                        net.minecraft.network.chat.Component.translatable("subtitle.warfront.flank_assault", attackerName, defenderName, sources.size(), payload.regionX(), payload.regionZ()));
            } else {
                regions.addLog(level, String.format("§c⚔ Attack launched: %s → Region (%d, %d) [%s] - Field battle commenced!",
                        attackerName, payload.regionX(), payload.regionZ(), defenderName));
                regions.broadcastTitle(level,
                        net.minecraft.network.chat.Component.translatable("title.warfront.field_battle"),
                        net.minecraft.network.chat.Component.translatable("subtitle.warfront.field_battle", attackerName, defenderName, payload.regionX(), payload.regionZ()));
            }

            Warfront.LOGGER.info("Field battle launched: {} vs {} at Region ({}, {}) with {} vectors.",
                    attackerName, defenderName, payload.regionX(), payload.regionZ(), sources.size());

            RequestRegionMapPayload.notifyActiveMapTerminals(level);
            return;
        }

        // Filter out any requested sub-regions that are ALREADY conquered by HUMANITY
        int requestedMask = payload.activeSubRegionsMask();
        int validRequestedMask = 0;
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                int bit = sz * 2 + sx;
                if ((requestedMask & (1 << bit)) != 0) {
                    RegionData.SubRegionState subState = regions.subRegionAt(payload.regionX(), payload.regionZ(), sx, sz);
                    if (subState.owner() != com.warfront.region.Faction.HUMANITY) {
                        validRequestedMask |= (1 << bit);
                    }
                }
            }
        }

        if (validRequestedMask == 0) {
            return;
        }

        int finalMask = validRequestedMask;
        long startTick = level.getGameTime();
        long durationTicks = regions.calculateAttackDurationTicks(payload.regionX(), payload.regionZ());

        // Find all bordering regions owned by the attacking faction
        java.util.List<RegionData.SourcePos> sources = new java.util.ArrayList<>();
        int[][] cardinalOffsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
        for (int[] offset : cardinalOffsets) {
            int nx = payload.regionX() + offset[0];
            int nz = payload.regionZ() + offset[1];
            RegionData.Region neighbor = regions.regionAt(nx, nz);
            if (neighbor.owner() == com.warfront.region.Faction.HUMANITY && playerFaction.getId().equals(neighbor.playerFactionId())) {
                sources.add(new RegionData.SourcePos(nx, nz));
            }
        }

        if (existingSiege == null) {
            RegionData.Region playerRegion = regions.regionAt(player.blockPosition());
            boolean playerInFactionRegion = (playerRegion.owner() == com.warfront.region.Faction.HUMANITY) && playerFaction.getId().equals(playerRegion.playerFactionId());
            if (!playerInFactionRegion) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.must_be_in_friendly_attack"));
                return;
            }

            if (sources.isEmpty()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.warfront.attack.bordering_required_attack"));
                return;
            }
        }

        if (existingSiege != null && existingSiege.attacker() == com.warfront.region.Faction.HUMANITY) {
            UUID currentAttackerId = existingSiege.attackerPlayerFactionId();
            if (currentAttackerId != null && !currentAttackerId.equals(playerFaction.getId())) {
                String attackingFactionName = "Another faction";
                com.warfront.faction.PlayerFaction af = com.warfront.faction.PlayerFactionData.get(level).getFactionById(currentAttackerId);
                if (af != null) attackingFactionName = af.getName();
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        String.format("§c[Warfront] This region is already under siege by %s! You cannot intervene until their campaign concludes.", attackingFactionName)));
                return;
            }
            int conqueredMask = regions.computeConqueredMask(payload.regionX(), payload.regionZ(), playerFaction.getId());
            int cleanExistingMask = existingSiege.activeSubRegionsMask() & ~conqueredMask;
            finalMask = cleanExistingMask | validRequestedMask;
            startTick = existingSiege.startTick();
            durationTicks = existingSiege.durationTicks(); // preserve remaining campaign timer
            if (sources.isEmpty() && existingSiege.sources() != null && !existingSiege.sources().isEmpty()) {
                sources.addAll(existingSiege.sources());
            }
        }

        // Cancel awaiting reinforcement state if present so attack proceeds with degraded stats
        if (regions.hasActiveReinforcement(payload.regionX(), payload.regionZ())) {
            regions.cancelReinforcement(payload.regionX(), payload.regionZ());
        }

        regions.setRegionSiegeWithCampaign(payload.regionX(), payload.regionZ(),
                new RegionData.SiegeCampaign(
                        com.warfront.region.Faction.HUMANITY, payload.regionX(), payload.regionZ(),
                        sources, false, startTick, durationTicks, finalMask, 0L,
                        playerFaction.getId(), null, 0.0F, 0.0F, false));

        // Initialize server-side active campaign mission progress tracking
        float effectiveResistance = regions.calculateEffectiveResistance(payload.regionX(), payload.regionZ());
        float effectiveStability = regions.calculateEffectiveStability(payload.regionX(), payload.regionZ());
        com.warfront.mission.ActiveCampaignMissionManager.startCampaign(
                level, payload.regionX(), payload.regionZ(),
                targetRegion.owner(), targetRegion.baseType(),
                effectiveResistance, effectiveStability, finalMask);

        String targetFactionName = targetRegion.owner().commandName();
        if (sources.size() > 1) {
            regions.addLog(level, String.format("§c⚔ Flank attack launched: %s (%d vectors) → Region (%d, %d) [%s].",
                    playerFaction.getName(), sources.size(), payload.regionX(), payload.regionZ(), targetFactionName));
            regions.broadcastTitle(level,
                    net.minecraft.network.chat.Component.translatable("title.warfront.flank_assault"),
                    net.minecraft.network.chat.Component.translatable("subtitle.warfront.player_flank_assault", playerFaction.getName(), payload.regionX(), payload.regionZ(), sources.size()));
        } else {
            regions.addLog(level, String.format("§c⚔ Attack launched: %s → Region (%d, %d) [%s].",
                    playerFaction.getName(), payload.regionX(), payload.regionZ(), targetFactionName));
            regions.broadcastTitle(level,
                    net.minecraft.network.chat.Component.translatable("title.warfront.attack_commenced"),
                    net.minecraft.network.chat.Component.translatable("subtitle.warfront.attack_commenced", playerFaction.getName(), payload.regionX(), payload.regionZ()));
        }

        Warfront.LOGGER.info("Campaign launched/updated: {} ({}) → Region ({}, {}), active mask {}, vectors {}.",
                player.getName().getString(), playerFaction.getName(), payload.regionX(), payload.regionZ(), finalMask, sources.size());

        // Re-send updated Map snapshots to active map terminals
        RequestRegionMapPayload.notifyActiveMapTerminals(level);
    }

    @Override
    public Type<LaunchAttackPayload> type() {
        return TYPE;
    }
}
