package com.warfront.ai;

import com.warfront.Warfront;
import com.warfront.config.WarfrontConfig;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public final class AIAttackManager {
    private static final Random RANDOM = new Random();
    private static final long TEST_SIEGE_TIMEOUT_TICKS = 4000L; // 20 seconds (20 ticks/sec)
    private static final int PLAYER_SQUARE_RADIUS_REGIONS = 6; // AI attacks constrained within 6 regions of player
    private static long lastEvaluationTick = 0L;

    private AIAttackManager() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) {
            return;
        }

        long gameTime = level.getGameTime();

        // 1. Check 20-second siege timeout resolution test feature
        checkSiegeTimeouts(level, gameTime);

        // 2. Evaluate AI expansion cycles
        int intervalTicks = WarfrontConfig.AI_ATTACK_INTERVAL_TICKS.get();
        if (gameTime - lastEvaluationTick < intervalTicks) {
            return;
        }
        lastEvaluationTick = gameTime;

        evaluateAIFactionAttacks(level);
    }

    private static void checkSiegeTimeouts(ServerLevel level, long gameTime) {
        RegionData regions = RegionData.get(level);
        boolean mapStateChanged = false;

        Iterator<RegionData.SiegeCampaign> iterator = new ArrayList<>(regions.getActiveSieges().values()).iterator();
        while (iterator.hasNext()) {
            RegionData.SiegeCampaign campaign = iterator.next();
            if (gameTime - campaign.startTick() >= campaign.durationTicks()) {
                int trx = campaign.targetRegionX();
                int trz = campaign.targetRegionZ();
                Faction attacker = campaign.attacker();

                if (attacker != Faction.HUMANITY) {
                    int dominoThreshold = regions.calculateDominoThreshold(trx, trz);

                    int securedCount = 0;
                    for (int sx = 0; sx <= 1; sx++) {
                        for (int sz = 0; sz <= 1; sz++) {
                            if (!regions.subRegionAt(trx, trz, sx, sz).underSiege()) {
                                securedCount++;
                            }
                        }
                    }

                    if (securedCount >= dominoThreshold) {
                        // Player successfully defended enough sectors before timer expired -> DEFENSE VICTORY!
                        regions.setRegionSiege(trx, trz, false);
                        regions.addLog(level, String.format("§a[Warfront] DEFENSE SUCCESSFUL! Region (%d, %d) held off %s attack!", trx, trz, attacker.commandName()));
                        Warfront.LOGGER.info("AI Siege timeout: Region ({}, {}) defended successfully", trx, trz);
                    } else {
                        // Defense failed -> Defending region falls to attacking AI faction!
                        BaseType baseType = regions.determineBaseTypeForConqueredRegion(trx, trz, attacker);
                        regions.claim(level, trx, trz, attacker, 100.0F, 50.0F, baseType);
                        regions.addLog(level, String.format("§c[Warfront Alert] Region (%d, %d) has FALLEN to %s!", trx, trz, attacker.commandName()));
                        Warfront.LOGGER.info("AI Siege timeout: Region ({}, {}) conquered by {}", trx, trz, attacker.commandName());
                    }
                } else {
                    // Player campaign expired - conquered sub-regions are KEPT, siege window closes
                    regions.setRegionSiege(trx, trz, false);
                    int heldSectors = Integer.bitCount(regions.computeConqueredMask(trx, trz));
                    String outcome = (heldSectors > 0)
                            ? String.format("§e[Warfront] Campaign on (%d, %d) expired — %d sector(s) held. Launch a new campaign to finish.", trx, trz, heldSectors)
                            : String.format("§c[Warfront] Campaign on (%d, %d) failed — no sectors captured.", trx, trz);
                    regions.addLog(level, outcome);
                    Warfront.LOGGER.info("Player campaign expired on Region ({}, {}): {} sectors held", trx, trz, heldSectors);
                }

                mapStateChanged = true;
            }
        }

        if (mapStateChanged) {
            com.warfront.network.RequestRegionMapPayload.sendMapSnapshotToAllPlayers(level);
        }
    }

    private static void evaluateAIFactionAttacks(ServerLevel level) {
        RegionData regions = RegionData.get(level);
        double expansionChance = WarfrontConfig.AI_EXPANSION_CHANCE.get();
        int maxSieges = WarfrontConfig.AI_MAX_SIMULTANEOUS_SIEGES.get();

        Faction[] aiFactions = new Faction[] { Faction.PILLAGER_CONQUERORS, Faction.ZOMBIE_HORDE };

        for (Faction faction : aiFactions) {
            if (RANDOM.nextDouble() > expansionChance) {
                continue;
            }

            int activeSieges = regions.getActiveSieges().size();
            if (activeSieges >= maxSieges) {
                continue;
            }

            List<FrontlineAttackCandidate> candidates = findFrontlineAttackCandidates(level, regions, faction);
            if (!candidates.isEmpty()) {
                FrontlineAttackCandidate chosen = candidates.get(RANDOM.nextInt(candidates.size()));

                // Calculate reinforcements & encirclement status
                SiegeDetails details = calculateSiegeDetails(regions, chosen.targetRegionX(), chosen.targetRegionZ(),
                        chosen.sourceRegionX(), chosen.sourceRegionZ(), faction);

                float targetStability = regions.calculateEffectiveStability(chosen.targetRegionX(), chosen.targetRegionZ());
                // Siege timeout scales with player region stability (from 400 ticks / 20s up to 4000 ticks / 200s for 100 stability)
                long durationTicks = Math.max(400L, (long) (targetStability * 40.0F));

                RegionData.SiegeCampaign campaign = new RegionData.SiegeCampaign(
                        faction, // Explicit AI attacker identifier
                        chosen.targetRegionX(), chosen.targetRegionZ(),
                        details.sources(),
                        details.attackValue(),
                        details.encircled(),
                        level.getGameTime(),
                        durationTicks);

                regions.setRegionSiegeWithCampaign(chosen.targetRegionX(), chosen.targetRegionZ(), campaign);

                String logMsg;
                if (details.encircled()) {
                    logMsg = String.format("§4[CRITICAL SIEGE] %s have ENCIRCLED Region (%d, %d)! Threat Level: %d!",
                            faction.commandName(), chosen.targetRegionX(), chosen.targetRegionZ(), details.attackValue());
                } else if (details.sources().size() > 1) {
                    logMsg = String.format("§c[Warfront Alert] %s launched a MULTI-FLANK siege on Region (%d, %d) from %d directions!",
                            faction.commandName(), chosen.targetRegionX(), chosen.targetRegionZ(), details.sources().size());
                } else {
                    logMsg = String.format("§c[Warfront Alert] %s launched a siege campaign on Region (%d, %d)!",
                            faction.commandName(), chosen.targetRegionX(), chosen.targetRegionZ());
                }
                regions.addLog(level, logMsg);

                // Force update strategic map screen for all online players live
                com.warfront.network.RequestRegionMapPayload.sendMapSnapshotToAllPlayers(level);
                Warfront.LOGGER.info("{} launched attack on Region ({}, {}), AttackValue: {}, Sources: {}",
                        faction.commandName(), chosen.targetRegionX(), chosen.targetRegionZ(), details.attackValue(),
                        details.sources().size());
            }
        }
    }

    private record FrontlineAttackCandidate(int sourceRegionX, int sourceRegionZ, int targetRegionX,
            int targetRegionZ) {
    }

    private record SiegeDetails(int attackValue, List<RegionData.SourcePos> sources, boolean encircled) {
    }

    private static SiegeDetails calculateSiegeDetails(RegionData regions, int targetRX, int targetRZ, int primarySrcX,
            int primarySrcZ, Faction attacker) {
        int[][] cardinalOffsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
        int hostileNeighborCount = 0;
        List<RegionData.SourcePos> sources = new ArrayList<>();
        sources.add(new RegionData.SourcePos(primarySrcX, primarySrcZ));

        for (int[] offset : cardinalOffsets) {
            int nrx = targetRX + offset[0];
            int nrz = targetRZ + offset[1];
            RegionData.Region neighbor = regions.regionAt(nrx, nrz);

            if (neighbor.owner() != Faction.HUMANITY && neighbor.owner() != Faction.UNCLAIMED) {
                hostileNeighborCount++;
            }

            if (neighbor.owner() == attacker) {
                RegionData.SourcePos srcPos = new RegionData.SourcePos(nrx, nrz);
                if ((nrx == primarySrcX && nrz == primarySrcZ) || RANDOM.nextDouble() < 0.5D) {
                    if (!sources.contains(srcPos)) {
                        sources.add(srcPos);
                    }
                }
            }
        }

        boolean encircled = (hostileNeighborCount >= 4);
        int attackValue = sources.size();

        if (encircled) {
            attackValue += 20; // Absurd threat multiplier for encircled region
        }

        return new SiegeDetails(attackValue, sources, encircled);
    }

    private static List<FrontlineAttackCandidate> findFrontlineAttackCandidates(ServerLevel level, RegionData regions,
            Faction attacker) {
        List<FrontlineAttackCandidate> candidates = new ArrayList<>();
        int scanRadius = 15;

        for (int rx = -scanRadius; rx <= scanRadius; rx++) {
            for (int rz = -scanRadius; rz <= scanRadius; rz++) {
                RegionData.Region sourceRegion = regions.regionAt(rx, rz);
                if (sourceRegion.owner() == attacker) {
                    int[][] offsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
                    for (int[] offset : offsets) {
                        int targetRX = rx + offset[0];
                        int targetRZ = rz + offset[1];

                        // Testing constraint: AI attacks can ONLY generate within 6 regions of a player
                        if (!isWithinPlayerRadius(level, targetRX, targetRZ, PLAYER_SQUARE_RADIUS_REGIONS)) {
                            continue;
                        }

                        RegionData.Region targetRegion = regions.regionAt(targetRX, targetRZ);

                        if (targetRegion.owner() != attacker
                                && !regions.subRegionAt(targetRX, targetRZ, 0, 0).underSiege()
                                && com.warfront.region.generator.ProceduralRegionGenerator.getInstance().biomeAvailableForExpansion(level, targetRX, targetRZ)) {
                            candidates.add(new FrontlineAttackCandidate(rx, rz, targetRX, targetRZ));
                        }
                    }
                }
            }
        }

        return candidates;
    }

    private static boolean isWithinPlayerRadius(ServerLevel level, int targetRX, int targetRZ, int radiusRegions) {
        if (level.getServer() == null) {
            return true;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level) {
                int playerRX = Math.floorDiv(player.chunkPosition().x, 8);
                int playerRZ = Math.floorDiv(player.chunkPosition().z, 8);
                if (Math.abs(targetRX - playerRX) <= radiusRegions && Math.abs(targetRZ - playerRZ) <= radiusRegions) {
                    return true;
                }
            }
        }
        return false;
    }
}
