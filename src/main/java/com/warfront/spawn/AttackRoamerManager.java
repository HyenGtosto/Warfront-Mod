package com.warfront.spawn;

import com.warfront.Warfront;
import com.warfront.ai.strategy.FrontlineShape;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Attack Roamer squads for active Pillager siege campaigns.
 *
 * Directs squads to spawn at random points along attacking borders and advance in formation
 * toward the closest point on the dynamically calculated frontline shape, spreading out to hold an 8x8 area.
 *
 * Features:
 *   - Continuous timer-based squad spawning (every 12 seconds) up to a concurrent squad cap of 6.
 *   - Each spawn adds 1 squad to the active total.
 *   - When >= 75% of a squad is eliminated (<= 25% alive), the squad is marked as wiped and removed,
 *     deducting 1 from the active total to open capacity for future wave spawns.
 *   - On defense conclusion (siege expired, defended, or conquered), all leftover squad mobs enter a terminal
 *     dispersal state (distant mobs > 64 blocks despawn immediately; nearby mobs <= 64 blocks disperse one-by-one with POOF particles).
 */
public final class AttackRoamerManager {

    /** How often (in server ticks) squad lifecycle evaluation runs (30 ticks = 1.5s). */
    private static final int EVAL_INTERVAL_TICKS = 30;

    /** Interval in ticks between squad spawns during an active siege (240 ticks = 12.0s). */
    public static final long SQUAD_SPAWN_INTERVAL_TICKS = 240L;

    /** Maximum concurrent active squads allowed in a single besieged region. */
    public static final int MAX_CONCURRENT_SQUADS = 6;

    /** Distance threshold in blocks beyond which terminal squad mobs despawn immediately. */
    public static final double TERMINAL_DESPAWN_RADIUS_SQ = 64.0D * 64.0D;

    public record ActiveSquad(
            UUID squadId,
            int initialSize,
            List<UUID> memberUuids,
            long spawnGameTime
    ) {
        public int countLiving(ServerLevel level) {
            int living = 0;
            for (UUID id : memberUuids) {
                Entity entity = level.getEntity(id);
                if (entity instanceof Mob mob && mob.isAlive()) {
                    living++;
                }
            }
            return living;
        }

        public boolean isWiped(ServerLevel level) {
            int living = countLiving(level);
            int threshold = Math.max(1, (int) Math.floor(initialSize * 0.25D));
            return living <= threshold;
        }

        public List<Mob> getLivingMobs(ServerLevel level) {
            List<Mob> living = new ArrayList<>();
            for (UUID id : memberUuids) {
                Entity entity = level.getEntity(id);
                if (entity instanceof Mob mob && mob.isAlive()) {
                    living.add(mob);
                }
            }
            return living;
        }
    }

    /** Active squads per siege region: RegionKey -> List of ActiveSquads */
    private static final Map<Long, List<ActiveSquad>> ACTIVE_SQUADS_BY_REGION = new ConcurrentHashMap<>();

    /** Terminal squads from concluded sieges awaiting staggered dispersal: RegionKey -> List of ActiveSquads */
    private static final Map<Long, List<ActiveSquad>> TERMINAL_SQUADS_BY_REGION = new ConcurrentHashMap<>();

    /** Last spawn game time per siege region: RegionKey -> gameTime */
    private static final Map<Long, Long> LAST_SPAWN_TIME_BY_REGION = new ConcurrentHashMap<>();

    private AttackRoamerManager() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) {
            return;
        }

        long gameTime = level.getGameTime();
        if (gameTime % EVAL_INTERVAL_TICKS != 0) {
            return;
        }

        RegionData regions = RegionData.get(level);
        Map<Long, RegionData.SiegeCampaign> activeSieges = regions.getActiveSieges();

        List<ServerPlayer> players = level.players();

        // 1. Detect concluded sieges and transfer active squads to terminal dispersal
        Set<Long> concludedRegions = new HashSet<>();
        for (Long regionKey : ACTIVE_SQUADS_BY_REGION.keySet()) {
            if (!activeSieges.containsKey(regionKey)) {
                concludedRegions.add(regionKey);
            }
        }
        for (Long regionKey : concludedRegions) {
            List<ActiveSquad> squads = ACTIVE_SQUADS_BY_REGION.remove(regionKey);
            LAST_SPAWN_TIME_BY_REGION.remove(regionKey);
            if (squads != null && !squads.isEmpty()) {
                TERMINAL_SQUADS_BY_REGION.computeIfAbsent(regionKey, k -> new CopyOnWriteArrayList<>()).addAll(squads);
                Warfront.LOGGER.info("Siege concluded for region key {}. {} Attack Roamer squad(s) entered terminal dispersal.",
                        regionKey, squads.size());
            }
        }

        // 2. Process terminal dispersal for concluded sieges (similar to mission mob cleanup)
        if (!TERMINAL_SQUADS_BY_REGION.isEmpty() && !players.isEmpty()) {
            List<Long> emptyTerminalRegions = new ArrayList<>();

            for (Map.Entry<Long, List<ActiveSquad>> entry : TERMINAL_SQUADS_BY_REGION.entrySet()) {
                Long regionKey = entry.getKey();
                List<ActiveSquad> terminalSquads = entry.getValue();

                List<Mob> allLivingMobs = new ArrayList<>();
                for (ActiveSquad squad : terminalSquads) {
                    allLivingMobs.addAll(squad.getLivingMobs(level));
                }

                if (allLivingMobs.isEmpty()) {
                    emptyTerminalRegions.add(regionKey);
                    continue;
                }

                List<Mob> nearbyLeftovers = new ArrayList<>();
                for (Mob mob : allLivingMobs) {
                    double minDistSq = getMinDistanceSqToPlayers(mob, players, level);
                    if (minDistSq > TERMINAL_DESPAWN_RADIUS_SQ) {
                        // Despawn distant leftover mob immediately
                        mob.discard();
                    } else {
                        nearbyLeftovers.add(mob);
                    }
                }

                // Staggered Dispersal: Disperse 1 nearby leftover mob per interval with POOF particles
                if (!nearbyLeftovers.isEmpty()) {
                    Mob chosenToDisperse = nearbyLeftovers.get(0);
                    level.sendParticles(
                            ParticleTypes.POOF,
                            chosenToDisperse.getX(),
                            chosenToDisperse.getY() + 1.0D,
                            chosenToDisperse.getZ(),
                            10,
                            0.3D, 0.5D, 0.3D, 0.05D
                    );
                    chosenToDisperse.discard();
                }
            }

            for (Long emptyKey : emptyTerminalRegions) {
                TERMINAL_SQUADS_BY_REGION.remove(emptyKey);
            }
        }

        if (activeSieges.isEmpty() || players.isEmpty()) {
            return;
        }

        // 3. Evaluate active Pillager sieges
        for (RegionData.SiegeCampaign campaign : activeSieges.values()) {
            if (campaign.attacker() != Faction.PILLAGER_CONQUERORS) {
                continue; // Exclusive to Pillagers (Zombies deferred)
            }

            int rx = campaign.targetRegionX();
            int rz = campaign.targetRegionZ();
            long regionKey = ChunkPos.asLong(rx, rz);

            // Verify a player is in or near the attacked region (within 2 regions = 256 blocks)
            if (!isPlayerNearRegion(rx, rz, players)) {
                continue;
            }

            List<ActiveSquad> squads = ACTIVE_SQUADS_BY_REGION.computeIfAbsent(regionKey, k -> new CopyOnWriteArrayList<>());

            // Clean up squads where >= 75% are killed (wiped trigger removes squad and deducts 1 from active count)
            List<ActiveSquad> wipedSquads = new ArrayList<>();
            for (ActiveSquad squad : squads) {
                if (squad.isWiped(level)) {
                    wipedSquads.add(squad);
                }
            }
            if (!wipedSquads.isEmpty()) {
                squads.removeAll(wipedSquads);
                Warfront.LOGGER.debug("Region ({}, {}) eliminated {} Attack Roamer squad(s). Active squads now: {}/{}",
                        rx, rz, wipedSquads.size(), squads.size(), MAX_CONCURRENT_SQUADS);
            }

            // Check if we can spawn a new squad (below cap of 6 and timer ready)
            if (squads.size() < MAX_CONCURRENT_SQUADS) {
                Long lastSpawn = LAST_SPAWN_TIME_BY_REGION.get(regionKey);
                boolean timerReady = (lastSpawn == null) || ((gameTime - lastSpawn) >= SQUAD_SPAWN_INTERVAL_TICKS);

                if (timerReady) {
                    ActiveSquad newSquad = spawnNewSquadForSiege(level, regions, rx, rz, campaign);
                    if (newSquad != null) {
                        squads.add(newSquad);
                        LAST_SPAWN_TIME_BY_REGION.put(regionKey, gameTime);
                        Warfront.LOGGER.info("Attack Roamer squad spawned for Region ({}, {}). Active squads: {}/{}",
                                rx, rz, squads.size(), MAX_CONCURRENT_SQUADS);
                    }
                }
            }
        }
    }

    private static ActiveSquad spawnNewSquadForSiege(
            ServerLevel level,
            RegionData regions,
            int rx, int rz,
            RegionData.SiegeCampaign campaign) {

        FrontlineShape shape = new FrontlineShape(rx, rz, campaign.sources());
        BlockPos borderSpawnPos = shape.getRandomBorderSpawnPos(level, level.getRandom());
        if (borderSpawnPos == null) {
            return null;
        }

        BlockPos destPos = shape.calculateFrontlineDestination(borderSpawnPos.getX(), borderSpawnPos.getZ(), level);

        // Determine which attacking source region this squad is staging from based on the border spawn position
        int minX = rx * RegionData.REGION_SIZE_BLOCKS;
        int maxX = minX + RegionData.REGION_SIZE_BLOCKS - 1;
        int minZ = rz * RegionData.REGION_SIZE_BLOCKS;
        int maxZ = minZ + RegionData.REGION_SIZE_BLOCKS - 1;

        int distNorth = Math.abs(borderSpawnPos.getZ() - minZ);
        int distSouth = Math.abs(borderSpawnPos.getZ() - maxZ);
        int distWest = Math.abs(borderSpawnPos.getX() - minX);
        int distEast = Math.abs(borderSpawnPos.getX() - maxX);

        int srcX = rx;
        int srcZ = rz;
        int minDist = Math.min(Math.min(distNorth, distSouth), Math.min(distWest, distEast));

        if (minDist == distNorth) {
            srcZ = rz - 1;
        } else if (minDist == distSouth) {
            srcZ = rz + 1;
        } else if (minDist == distWest) {
            srcX = rx - 1;
        } else {
            srcX = rx + 1;
        }

        float attackerResistance = regions.calculateEffectiveResistance(srcX, srcZ);

        // Fallback: If border source region resistance is non-positive, evaluate all declared campaign sources
        if (attackerResistance <= 0.0F && campaign.sources() != null && !campaign.sources().isEmpty()) {
            for (RegionData.SourcePos src : campaign.sources()) {
                float r = regions.calculateEffectiveResistance(src.x(), src.z());
                if (r > attackerResistance) {
                    attackerResistance = r;
                }
            }
        }

        // Baseline fallback: Ensure at least LOW resistance (35.0F) so attack forces can field melee combatants
        if (attackerResistance <= 0.0F) {
            attackerResistance = 35.0F;
        }

        UUID newSquadId = UUID.randomUUID();

        List<Mob> spawned = EnemyEncounterSpawner.spawnPillagerAttackRoamerSquad(
                level,
                rx, rz,
                attackerResistance,
                borderSpawnPos.getX(), borderSpawnPos.getZ(),
                destPos.getX(), destPos.getZ(),
                newSquadId
        );

        if (spawned.isEmpty()) {
            return null;
        }

        EnemyResistanceTier tier = EnemyResistanceTier.fromResistance(attackerResistance);
        Warfront.LOGGER.info("[ATTACK ROAMER] Spawned squad {} (size {}) for Siege at Region ({}, {}) from Source Region ({}, {}) [Attacker Res: {} -> Tier: {}]",
                newSquadId, spawned.size(), rx, rz, srcX, srcZ, String.format("%.1f", attackerResistance), tier.name());

        List<UUID> memberUuids = new ArrayList<>();
        for (Mob mob : spawned) {
            memberUuids.add(mob.getUUID());
        }

        return new ActiveSquad(
                newSquadId,
                spawned.size(),
                memberUuids,
                level.getGameTime()
        );
    }

    private static double getMinDistanceSqToPlayers(Mob mob, List<ServerPlayer> players, ServerLevel level) {
        double minDistSq = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            if (player.level() != level) continue;
            double dSq = mob.distanceToSqr(player);
            if (dSq < minDistSq) {
                minDistSq = dSq;
            }
        }
        return minDistSq;
    }

    private static boolean isPlayerNearRegion(int rx, int rz, List<ServerPlayer> players) {
        int regionMinX = rx * RegionData.REGION_SIZE_BLOCKS;
        int regionMaxX = regionMinX + RegionData.REGION_SIZE_BLOCKS;
        int regionMinZ = rz * RegionData.REGION_SIZE_BLOCKS;
        int regionMaxZ = regionMinZ + RegionData.REGION_SIZE_BLOCKS;

        for (ServerPlayer player : players) {
            int px = player.getBlockX();
            int pz = player.getBlockZ();
            int dx = Math.max(0, Math.max(regionMinX - px, px - regionMaxX));
            int dz = Math.max(0, Math.max(regionMinZ - pz, pz - regionMaxZ));
            if (Math.hypot(dx, dz) <= 256.0D) {
                return true;
            }
        }
        return false;
    }
}
