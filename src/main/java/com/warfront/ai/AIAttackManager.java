package com.warfront.ai;

import com.warfront.Warfront;
import com.warfront.ai.strategy.AttackContext;

import com.warfront.ai.strategy.HQPos;
import com.warfront.config.WarfrontConfig;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public final class AIAttackManager {
    private static final Random RANDOM = new Random();
    private static final int PLAYER_SQUARE_RADIUS_REGIONS = 12; // 24x24 region grid (radius 12)
    private static long lastEvaluationTick = 0L;

    private AIAttackManager() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) {
            return;
        }

        long gameTime = level.getGameTime();

        // 1. Check active siege resolution timeouts
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
            try {
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
                            regions.addLog(level, String.format("§aSiege expired: Region (%d, %d) defended against %s.", trx, trz, attacker.commandName()));
                            Warfront.LOGGER.info("Siege expired: Region ({}, {}) defended.", trx, trz);
                        } else {
                            // Defense failed -> Defending region falls to attacking AI faction!
                            BaseType baseType = regions.determineBaseTypeForConqueredRegion(trx, trz, attacker);
                            regions.claim(level, trx, trz, attacker, 0.0F, 0.0F, baseType);
                            regions.addLog(level, String.format("§cSiege expired: Region (%d, %d) conquered by %s.", trx, trz, attacker.commandName()));
                            Warfront.LOGGER.info("Siege expired: Region ({}, {}) conquered by {}.", trx, trz, attacker.commandName());
                        }
                    } else {
                        // Player campaign expired - conquered sub-regions are KEPT, siege window closes
                        regions.setRegionSiege(trx, trz, false);
                        int heldSectors = Integer.bitCount(regions.computeConqueredMask(trx, trz));
                        String outcome = String.format("§eCampaign expired: Region (%d, %d), %d sectors held.", trx, trz, heldSectors);
                        regions.addLog(level, outcome);
                        Warfront.LOGGER.info("Campaign expired: Region ({}, {}), {} sectors held.", trx, trz, heldSectors);
                    }

                    mapStateChanged = true;
                }
            } catch (Exception e) {
                Warfront.LOGGER.error("Siege timeout error: Region ({}, {}): {}", campaign.targetRegionX(), campaign.targetRegionZ(), e.getMessage(), e);
            }
        }

        if (mapStateChanged) {
            com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);
        }
    }

    private static void evaluateAIFactionAttacks(ServerLevel level) {
        RegionData regions = RegionData.get(level);
        regions.decayZombieRetaliation();

        double expansionChance = WarfrontConfig.AI_EXPANSION_CHANCE.get();
        int maxSieges = WarfrontConfig.AI_MAX_SIMULTANEOUS_SIEGES.get();

        int activeSiegesCount = regions.getActiveSieges().size();
        if (activeSiegesCount >= maxSieges) {
            return;
        }

        int openSlots = maxSieges - activeSiegesCount;

        Set<Long> pillagerClusterIds = regions.getAllClusterIds(level, Faction.PILLAGER_CONQUERORS);
        Set<Long> zombieClusterIds = regions.getAllClusterIds(level, Faction.ZOMBIE_HORDE);

        List<StrategicActor> actors = new ArrayList<>();
        if (pillagerClusterIds.isEmpty()) {
            actors.add(new StrategicActor(Faction.PILLAGER_CONQUERORS, 0L));
        } else {
            for (long cid : pillagerClusterIds) {
                actors.add(new StrategicActor(Faction.PILLAGER_CONQUERORS, cid));
            }
        }
        if (zombieClusterIds.isEmpty()) {
            actors.add(new StrategicActor(Faction.ZOMBIE_HORDE, 0L));
        } else {
            for (long cid : zombieClusterIds) {
                actors.add(new StrategicActor(Faction.ZOMBIE_HORDE, cid));
            }
        }

        // Randomize the complete actor pool without replacement for emergent warfare patterns
        Collections.shuffle(actors, RANDOM);

        // Instantiate short-lived BFS component cache for this evaluation cycle
        Map<Long, Optional<HQPos>> evaluationCache = new HashMap<>();

        int slotAttemptsExecuted = 0;
        int turnIndex = 0;
        Set<StrategicActor> exhaustedActors = new HashSet<>();
        boolean anyAttackLaunched = false;

        while (slotAttemptsExecuted < openSlots && regions.getActiveSieges().size() < maxSieges) {
            if (exhaustedActors.size() >= actors.size()) {
                break; // All strategic actors candidate-exhausted -> Terminate cycle cleanly
            }

            StrategicActor actor = actors.get(turnIndex % actors.size());
            turnIndex++;

            if (exhaustedActors.contains(actor)) {
                continue; // Skip exhausted actor without consuming slot attempt opportunity
            }

            Faction faction = actor.faction();
            List<com.warfront.ai.strategy.AttackCandidate> localCandidates = new ArrayList<>();
            List<com.warfront.ai.strategy.AttackCandidate> remoteCandidates = new ArrayList<>();
            findFrontlineAttackCandidatesDualZone(level, regions, faction, actor.clusterId(), localCandidates, remoteCandidates);

            List<com.warfront.ai.strategy.AttackCandidate> candidates;
            if (!localCandidates.isEmpty() && !remoteCandidates.isEmpty()) {
                // Weighted Dual-Zone: 80% chance for local active player grid (24x24), 20% for remote territory
                candidates = (RANDOM.nextDouble() < 0.8D) ? localCandidates : remoteCandidates;
            } else if (!localCandidates.isEmpty()) {
                candidates = localCandidates;
            } else {
                candidates = remoteCandidates;
            }

            if (candidates.isEmpty()) {
                exhaustedActors.add(actor);
                continue; // Do NOT count as slot attempt opportunity; pass turn immediately
            }

            // Actor has candidates -> consume 1 slot attempt opportunity
            slotAttemptsExecuted++;

            // Roll independent per-slot expansion chance
            if (RANDOM.nextDouble() > expansionChance) {
                continue; // Skipped slot attempt based on roll
            }

            com.warfront.ai.strategy.FactionAttackStrategy strategy = com.warfront.ai.strategy.FactionStrategyRegistry.getStrategy(faction);
            AttackContext context = new AttackContext(level, regions, faction, PLAYER_SQUARE_RADIUS_REGIONS, RANDOM, evaluationCache);

            Optional<com.warfront.ai.strategy.AttackTarget> targetOpt = strategy.chooseAttackTarget(faction, context, candidates);
            if (targetOpt.isEmpty()) {
                exhaustedActors.add(actor);
                continue;
            }

            com.warfront.ai.strategy.AttackTarget chosen = targetOpt.get();

            // Calculate reinforcements & encirclement status
            SiegeDetails details = calculateSiegeDetails(level, regions, chosen.targetRegionX(), chosen.targetRegionZ(),
                    chosen.sourceRegionX(), chosen.sourceRegionZ(), faction);

            long durationTicks = WarfrontConfig.SIEGE_RESOLUTION_DURATION_SECONDS.get() * 20L;

            long attackerClusterId = chosen.attackerClusterId();
            if (attackerClusterId == 0L && faction.isAI()) {
                attackerClusterId = getRawOrSavedRegionState(level, regions, chosen.sourceRegionX(), chosen.sourceRegionZ()).clusterId();
            }

            RegionData.SiegeCampaign campaign = new RegionData.SiegeCampaign(
                    faction, // Explicit AI attacker identifier
                    chosen.targetRegionX(), chosen.targetRegionZ(),
                    details.sources(),
                    details.attackValue(),
                    details.encircled(),
                    level.getGameTime(),
                    durationTicks,
                    0xF,
                    attackerClusterId);

            regions.setRegionSiegeWithCampaign(chosen.targetRegionX(), chosen.targetRegionZ(), campaign);
            anyAttackLaunched = true;

            RegionData.RegionState targetRegion = getRawOrSavedRegionState(level, regions, chosen.targetRegionX(), chosen.targetRegionZ());
            String defenderInfo = String.format("[%s - %s]", targetRegion.owner().commandName(), targetRegion.baseType().name());

            String logMsg;
            if (details.encircled()) {
                logMsg = String.format("§cAttack launched: %s → Region (%d, %d) %s, attack value %d, encircled.",
                        faction.commandName(), chosen.targetRegionX(), chosen.targetRegionZ(), defenderInfo, details.attackValue());
            } else if (details.sources().size() > 1) {
                logMsg = String.format("§cAttack launched: %s → Region (%d, %d) %s, attack value %d, %d sources.",
                        faction.commandName(), chosen.targetRegionX(), chosen.targetRegionZ(), defenderInfo, details.attackValue(), details.sources().size());
            } else {
                logMsg = String.format("§cAttack launched: %s → Region (%d, %d) %s.",
                        faction.commandName(), chosen.targetRegionX(), chosen.targetRegionZ(), defenderInfo);
            }
            regions.addLog(level, logMsg);

            Warfront.LOGGER.info("Attack launched: {} → Region ({}, {}) {}, attack value {}, {} sources.",
                    faction.commandName(), chosen.targetRegionX(), chosen.targetRegionZ(), defenderInfo, details.attackValue(),
                    details.sources().size());
        }

        // Force update strategic map screen for active map terminals AT MOST ONCE after complete loop
        if (anyAttackLaunched) {
            com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);
        }
    }

    private record StrategicActor(Faction faction, long clusterId) {}

    private record SiegeDetails(int attackValue, List<RegionData.SourcePos> sources, boolean encircled) {
    }

    private static RegionData.RegionState getRawOrSavedRegionState(ServerLevel level, RegionData regions, int rx, int rz) {
        RegionData.RegionState state = regions.getSavedRegionState(rx, rz);
        if (state != null) {
            return state;
        }
        long seed = (level != null) ? level.getSeed() : 0L;
        return com.warfront.region.generator.ProceduralRegionGenerator.getInstance().generateRawRegionState(level, seed, rx, rz);
    }

    private static SiegeDetails calculateSiegeDetails(ServerLevel level, RegionData regions, int targetRX, int targetRZ, int primarySrcX,
            int primarySrcZ, Faction attacker) {
        int[][] cardinalOffsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };
        int hostileNeighborCount = 0;
        List<RegionData.SourcePos> sources = new ArrayList<>();
        sources.add(new RegionData.SourcePos(primarySrcX, primarySrcZ));

        for (int[] offset : cardinalOffsets) {
            int nrx = targetRX + offset[0];
            int nrz = targetRZ + offset[1];
            RegionData.RegionState neighbor = getRawOrSavedRegionState(level, regions, nrx, nrz);

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

    private static void findFrontlineAttackCandidatesDualZone(ServerLevel level, RegionData regions, Faction attacker, long clusterId,
            List<com.warfront.ai.strategy.AttackCandidate> localOut, List<com.warfront.ai.strategy.AttackCandidate> remoteOut) {
        long startNano = System.nanoTime();
        List<RegionData.Region> attackerRegions = regions.getRegionsOwnedBy(attacker);
        Set<Long> processedKeys = new HashSet<>();
        for (RegionData.Region r : attackerRegions) {
            processedKeys.add(ChunkPos.asLong(r.x(), r.z()));
        }

        // Also scan procedural regions around all active players and claimed HUMANITY regions
        List<RegionData.Region> humanityRegions = regions.getRegionsOwnedBy(Faction.HUMANITY);
        List<ChunkPos> scanOrigins = new ArrayList<>();

        if (level != null && level.getServer() != null) {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (player.level() == level) {
                    scanOrigins.add(new ChunkPos(Math.floorDiv(player.chunkPosition().x, 8), Math.floorDiv(player.chunkPosition().z, 8)));
                }
            }
        }
        for (RegionData.Region hr : humanityRegions) {
            scanOrigins.add(new ChunkPos(hr.x(), hr.z()));
        }

        int scanRadius = 15;
        int rawStateLookups = 0;
        for (ChunkPos origin : scanOrigins) {
            for (int rx = origin.x - scanRadius; rx <= origin.x + scanRadius; rx++) {
                for (int rz = origin.z - scanRadius; rz <= origin.z + scanRadius; rz++) {
                    long key = ChunkPos.asLong(rx, rz);
                    if (processedKeys.add(key)) {
                        rawStateLookups++;
                        RegionData.RegionState state = getRawOrSavedRegionState(level, regions, rx, rz);
                        if (state.owner() == attacker) {
                            attackerRegions.add(new RegionData.Region(rx, rz, state.owner(), state.stability(), state.resistance(), state.baseType(), state.clusterId()));
                        }
                    }
                }
            }
        }

        int[][] offsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };

        for (RegionData.Region sourceRegion : attackerRegions) {
            if (clusterId != 0L && sourceRegion.clusterId() != clusterId) {
                continue;
            }

            int rx = sourceRegion.x();
            int rz = sourceRegion.z();

            boolean isLocal = isNearPlayerEntity(level, rx, rz, PLAYER_SQUARE_RADIUS_REGIONS);
            boolean isRemote = !isLocal && isNearHumanityTerritory(regions, rx, rz, PLAYER_SQUARE_RADIUS_REGIONS);

            if (!isLocal && !isRemote) {
                continue;
            }

            for (int[] offset : offsets) {
                int targetRX = rx + offset[0];
                int targetRZ = rz + offset[1];

                RegionData.RegionState targetState = getRawOrSavedRegionState(level, regions, targetRX, targetRZ);

                if (targetState.owner() != attacker
                        && !regions.subRegionAt(targetRX, targetRZ, 0, 0).underSiege()
                        && com.warfront.region.generator.ProceduralRegionGenerator.getInstance().biomeAvailableForExpansion(level, targetRX, targetRZ)) {
                    com.warfront.ai.strategy.AttackCandidate candidate = new com.warfront.ai.strategy.AttackCandidate(rx, rz, targetRX, targetRZ);
                    if (isLocal) {
                        localOut.add(candidate);
                    } else {
                        remoteOut.add(candidate);
                    }
                }
            }
        }

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
        Warfront.LOGGER.info("AI Candidate Scan ({}) completed in {} ms: {} regions evaluated, 0 strength calculations, 0 biome queries",
                attacker.commandName(), elapsedMs, rawStateLookups);
    }

    private static boolean isNearPlayerEntity(ServerLevel level, int rx, int rz, int radiusRegions) {
        if (level == null || level.getServer() == null) return false;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level) {
                int prx = Math.floorDiv(player.chunkPosition().x, 8);
                int prz = Math.floorDiv(player.chunkPosition().z, 8);
                if (Math.abs(rx - prx) <= radiusRegions && Math.abs(rz - prz) <= radiusRegions) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNearHumanityTerritory(RegionData regions, int rx, int rz, int radiusRegions) {
        List<RegionData.Region> humanityRegions = regions.getRegionsOwnedBy(Faction.HUMANITY);
        for (RegionData.Region hr : humanityRegions) {
            if (Math.abs(rx - hr.x()) <= radiusRegions && Math.abs(rz - hr.z()) <= radiusRegions) {
                return true;
            }
        }
        return false;
    }
}
