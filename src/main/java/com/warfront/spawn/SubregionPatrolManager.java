package com.warfront.spawn;

import com.warfront.Warfront;
import com.warfront.ai.goal.SubregionPatrolGoal;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.entity.ModEntities;
import com.warfront.entity.PillagerScoutEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Subregion Patrolling Squads for hostile factions (replacing legacy random spawns).
 *
 * Rules:
 *   - Each squad belongs to a specific subregion (64×64 blocks).
 *   - Max 3 concurrent squads per subregion.
 *   - 40 seconds (800 ticks) spawn cooldown per subregion.
 *   - No percentage-loss respawns — spawns operate purely on cooldown timer.
 *   - Spawns persist in active missions as well as peaceful exploration.
 *   - Composition and resistance tiering match Attack Roamers (scouts are excluded).
 *
 * Patrol Behavior:
 *   1. Spawn from a subregion border.
 *   2. Select a random waypoint on another border at least 48 blocks away.
 *   3. March in formation to that point.
 *   4. Hold ground for 15 seconds (300 ticks).
 *   5. Pick another border waypoint at least 48 blocks away and repeat.
 *   6. If any member spots a player, the whole squad engages that player.
 */
public final class SubregionPatrolManager {

    public static final int SUBREGION_SIZE_BLOCKS = 64;
    public static final int MAX_SQUADS_PER_SUBREGION = 3;
    public static final long SPAWN_COOLDOWN_TICKS = 90L * 20L; // 90 seconds = 1800 ticks
    public static final double MIN_WAYPOINT_DIST = 48.0D;
    public static final int HOLD_GROUND_DURATION_TICKS = 15 * 20; // 15 seconds = 300 ticks

    public static final long SCOUT_SPAWN_COOLDOWN_TICKS = 5L * 60L * 20L; // 5 minutes = 6000 ticks

    private static final int EVAL_INTERVAL_TICKS = 20; // 1 second evaluation cadence
    private static final int PLAYER_SCAN_RADIUS_BLOCKS = 128;

    private static final int[][] SQUAD_SPREAD_OFFSETS = new int[][] {
            {  0,  0 }, // Center (Leader)
            { -2, -2 },
            {  2, -2 },
            { -2,  2 },
            {  2,  2 },
            {  0, -3 },
            {  0,  3 },
            { -3,  0 },
            {  3,  0 },
            { -3, -3 },
            {  3, -3 },
            { -3,  3 },
            {  3,  3 },
            { -1,  2 },
            {  1, -2 },
            {  2, -1 }
    };

    /**
     * Represents an active patrol squad within a subregion.
     */
    public static class PatrolSquad {
        private final UUID squadId;
        private final int regionX;
        private final int regionZ;
        private final int subX;
        private final int subZ;
        private final List<UUID> memberUuids = new CopyOnWriteArrayList<>();

        private BlockPos currentWaypoint;
        private boolean holdingGround = false;
        private int holdTicksRemaining = 0;

        public PatrolSquad(UUID squadId, int regionX, int regionZ, int subX, int subZ, BlockPos initialWaypoint) {
            this.squadId = squadId;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.subX = subX;
            this.subZ = subZ;
            this.currentWaypoint = initialWaypoint;
        }

        public UUID getSquadId() { return squadId; }
        public BlockPos getCurrentWaypoint() { return currentWaypoint; }
        public boolean isHoldingGround() { return holdingGround; }

        public void addMember(UUID uuid) {
            memberUuids.add(uuid);
        }

        public int countLiving(ServerLevel level) {
            int living = 0;
            for (UUID id : memberUuids) {
                Entity e = level.getEntity(id);
                if (e instanceof Mob mob && mob.isAlive()) {
                    living++;
                }
            }
            return living;
        }

        public List<Mob> getLivingMembers(Level level) {
            List<Mob> living = new ArrayList<>();
            for (UUID id : memberUuids) {
                Entity e = level instanceof ServerLevel sl ? sl.getEntity(id) : null;
                if (e instanceof Mob mob && mob.isAlive()) {
                    living.add(mob);
                }
            }
            return living;
        }

        private UUID homingPlayerUuid = null;

        public void enableHoming(UUID playerUuid) {
            this.homingPlayerUuid = playerUuid;
            this.holdingGround = false;
            this.holdTicksRemaining = 0;
        }

        public boolean isHoming() {
            return homingPlayerUuid != null;
        }

        public UUID getHomingPlayerUuid() {
            return homingPlayerUuid;
        }

        /**
         * Called when members arrive at the current waypoint. Transitions squad to 15s hold ground.
         */
        public synchronized void notifyArrival(Level level) {
            if (!holdingGround && homingPlayerUuid == null) {
                holdingGround = true;
                holdTicksRemaining = HOLD_GROUND_DURATION_TICKS;
            }
        }

        /**
         * Ticks the squad's hold timer and picks the next waypoint when elapsed.
         */
        public synchronized void tickHoldTimer(ServerLevel level) {
            if (homingPlayerUuid != null) {
                Entity e = level.getEntity(homingPlayerUuid);
                if (e instanceof Player p && p.isAlive()) {
                    this.currentWaypoint = p.blockPosition();
                    alertSquadToTarget(level, p);
                    return;
                } else {
                    this.homingPlayerUuid = null;
                }
            }
            if (holdingGround) {
                holdTicksRemaining -= EVAL_INTERVAL_TICKS;
                if (holdTicksRemaining <= 0) {
                    holdingGround = false;
                    holdTicksRemaining = 0;
                    BlockPos nextWaypoint = pickNextBorderWaypoint(level, regionX, regionZ, subX, subZ, currentWaypoint);
                    if (nextWaypoint != null && !nextWaypoint.equals(currentWaypoint)) {
                        currentWaypoint = nextWaypoint;
                        Warfront.LOGGER.info("[PATROL] Squad {} hold complete. Reselected next waypoint: {}", squadId, currentWaypoint);
                    }
                }
            }
        }

        /**
         * Returns an active living target if any squad member currently has one.
         */
        public LivingEntity getSquadTarget(Level level) {
            for (Mob mob : getLivingMembers(level)) {
                LivingEntity target = mob.getTarget();
                if (target != null && target.isAlive() && !com.warfront.entity.AlliedFactionHelper.isAllied(mob, target)) {
                    return target;
                }
            }
            return null;
        }

        /**
         * Alerts all living squad members to engage the detected target.
         */
        public void alertSquadToTarget(Level level, LivingEntity target) {
            if (target == null || !target.isAlive()) return;
            for (Mob mob : getLivingMembers(level)) {
                if (com.warfront.entity.AlliedFactionHelper.isAllied(mob, target)) continue;
                if (mob.getTarget() == null || !mob.getTarget().isAlive()) {
                    mob.setTarget(target);
                }
            }
        }

        /**
         * Synchronizes target across all squad members if any member has engaged an enemy.
         */
        public void syncSquadTarget(Level level) {
            LivingEntity target = getSquadTarget(level);
            if (target != null) {
                alertSquadToTarget(level, target);
            }
        }
    }

    /** Active squads per subregion: SubKey -> List of PatrolSquads */
    private static final Map<Long, List<PatrolSquad>> ACTIVE_PATROLS = new ConcurrentHashMap<>();

    /** Last spawn game time per subregion: SubKey -> GameTime */
    private static final Map<Long, Long> LAST_SPAWN_TIME = new ConcurrentHashMap<>();

    /** Active autonomous scout per subregion: SubKey -> Scout Mob UUID */
    private static final Map<Long, UUID> ACTIVE_SCOUTS = new ConcurrentHashMap<>();

    /** Last autonomous scout spawn game time per subregion: SubKey -> GameTime */
    private static final Map<Long, Long> LAST_SCOUT_SPAWN_TIME = new ConcurrentHashMap<>();

    /** Natural patrol spawn suppression until game time: SubKey -> GameTime */
    private static final Map<Long, Long> NATURAL_SPAWN_SUPPRESSION_UNTIL = new ConcurrentHashMap<>();

    /** Scheduled reinforcement actions from scout horn alerts */
    public static class ScheduledReinforcementTask {
        public final long executeAtTick;
        public final int rx, rz, sx, sz;
        public final UUID targetPlayerUuid;
        public final boolean isSpawn;
        public final UUID squadIdToAggro;

        public ScheduledReinforcementTask(long executeAtTick, int rx, int rz, int sx, int sz, UUID targetPlayerUuid, boolean isSpawn, UUID squadIdToAggro) {
            this.executeAtTick = executeAtTick;
            this.rx = rx;
            this.rz = rz;
            this.sx = sx;
            this.sz = sz;
            this.targetPlayerUuid = targetPlayerUuid;
            this.isSpawn = isSpawn;
            this.squadIdToAggro = squadIdToAggro;
        }
    }

    private static final List<ScheduledReinforcementTask> PENDING_REINFORCEMENTS = new CopyOnWriteArrayList<>();

    private SubregionPatrolManager() {
    }

    public static long makeSubKey(int rx, int rz, int sx, int sz) {
        return (ChunkPos.asLong(rx, rz) << 2) | ((sz & 1) << 1) | (sx & 1);
    }

    /** Despawn interval for captured sector patrols: 50 ticks = 2.5 seconds per mob. */
    public static final int TERMINAL_DESPAWN_INTERVAL_TICKS = 50;
    public static final double TERMINAL_DESPAWN_RADIUS_SQ = 64.0D * 64.0D;

    /** Mobs belonging to captured subregions pending terminal dispersal: Mob UUIDs */
    private static final List<UUID> TERMINAL_PATROL_MOBS = new CopyOnWriteArrayList<>();
    private static long lastTerminalDespawnTick = 0L;

    public static void onSubregionCaptured(ServerLevel level, int rx, int rz, int sx, int sz) {
        long subKey = makeSubKey(rx, rz, sx, sz);
        List<PatrolSquad> squads = ACTIVE_PATROLS.remove(subKey);
        LAST_SPAWN_TIME.remove(subKey);
        ACTIVE_SCOUTS.remove(subKey);
        LAST_SCOUT_SPAWN_TIME.remove(subKey);
        NATURAL_SPAWN_SUPPRESSION_UNTIL.remove(subKey);
        PENDING_REINFORCEMENTS.removeIf(task -> task.rx == rx && task.rz == rz && task.sx == sx && task.sz == sz);
        if (squads != null) {
            for (PatrolSquad squad : squads) {
                for (UUID id : squad.memberUuids) {
                    if (!TERMINAL_PATROL_MOBS.contains(id)) {
                        TERMINAL_PATROL_MOBS.add(id);
                    }
                }
            }
        }
    }

    public static void onRegionCaptured(ServerLevel level, int rx, int rz) {
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                onSubregionCaptured(level, rx, rz, sx, sz);
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;

        long gameTime = level.getGameTime();

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        // Process terminal patrol dispersal: exactly 1 nearby mob every 50 ticks (2.5 seconds)
        if (!TERMINAL_PATROL_MOBS.isEmpty() && (gameTime - lastTerminalDespawnTick >= TERMINAL_DESPAWN_INTERVAL_TICKS)) {
            lastTerminalDespawnTick = gameTime;
            while (!TERMINAL_PATROL_MOBS.isEmpty()) {
                UUID mobId = TERMINAL_PATROL_MOBS.remove(0);
                Entity e = level.getEntity(mobId);
                if (!(e instanceof Mob mob) || !mob.isAlive()) {
                    continue;
                }

                double minDistSq = Double.MAX_VALUE;
                for (ServerPlayer player : players) {
                    double dSq = mob.distanceToSqr(player);
                    if (dSq < minDistSq) {
                        minDistSq = dSq;
                    }
                }

                if (minDistSq > TERMINAL_DESPAWN_RADIUS_SQ) {
                    // Distant leftover mob despawns immediately
                    mob.discard();
                    continue;
                } else {
                    // Nearby leftover mob: despawn 1 mob with POOF particles and sound
                    level.sendParticles(
                            net.minecraft.core.particles.ParticleTypes.POOF,
                            mob.getX(),
                            mob.getY() + Math.max(0.5D, mob.getBbHeight() / 2.0D),
                            mob.getZ(),
                            12,
                            0.35D, 0.45D, 0.35D, 0.05D
                    );
                    level.playSound(
                            null,
                            mob.blockPosition(),
                            net.minecraft.sounds.SoundEvents.CHICKEN_EGG,
                            net.minecraft.sounds.SoundSource.HOSTILE,
                            0.8F,
                            1.2F
                    );
                    mob.discard();
                    break; // 1 mob per 2.5 seconds
                }
            }
        }

        // 0. Process scheduled scout reinforcements
        if (!PENDING_REINFORCEMENTS.isEmpty()) {
            List<ScheduledReinforcementTask> ready = new ArrayList<>();
            for (ScheduledReinforcementTask task : PENDING_REINFORCEMENTS) {
                if (gameTime >= task.executeAtTick) {
                    ready.add(task);
                }
            }
            for (ScheduledReinforcementTask task : ready) {
                PENDING_REINFORCEMENTS.remove(task);
                ServerPlayer targetPlayer = level.getServer().getPlayerList().getPlayer(task.targetPlayerUuid);
                if (targetPlayer == null || !targetPlayer.isAlive()) continue;

                long taskSubKey = makeSubKey(task.rx, task.rz, task.sx, task.sz);
                List<PatrolSquad> subSquads = ACTIVE_PATROLS.computeIfAbsent(taskSubKey, k -> new CopyOnWriteArrayList<>());
                subSquads.removeIf(s -> s.countLiving(level) == 0);

                if (task.isSpawn) {
                    RegionData regions = RegionData.get(level);
                    PatrolSquad spawned = spawnPatrolSquad(level, regions, task.rx, task.rz, task.sx, task.sz, Faction.PILLAGER_CONQUERORS);
                    if (spawned != null) {
                        subSquads.add(spawned);
                        LAST_SPAWN_TIME.put(taskSubKey, gameTime);
                        dispatchSquadHoming(level, spawned, targetPlayer);
                        Warfront.LOGGER.info("[SCOUT REINFORCE] Spawned reinforcement squad {} against player {}",
                                spawned.getSquadId(), targetPlayer.getName().getString());
                    }
                } else {
                    for (PatrolSquad s : subSquads) {
                        if (s.getSquadId().equals(task.squadIdToAggro) && s.countLiving(level) > 0) {
                            dispatchSquadHoming(level, s, targetPlayer);
                            Warfront.LOGGER.info("[SCOUT REINFORCE] Dispatched existing squad {} against player {}",
                                    s.getSquadId(), targetPlayer.getName().getString());
                            break;
                        }
                    }
                }
            }
        }

        if (gameTime % EVAL_INTERVAL_TICKS != 0) return;

        RegionData regions = RegionData.get(level);

        // 1. Tick hold timers, sync targets, and clean up empty squads
        for (Map.Entry<Long, List<PatrolSquad>> entry : ACTIVE_PATROLS.entrySet()) {
            List<PatrolSquad> squads = entry.getValue();
            squads.removeIf(squad -> squad.countLiving(level) == 0);
            for (PatrolSquad squad : squads) {
                squad.tickHoldTimer(level);
                squad.syncSquadTarget(level);
            }
        }

        // 2. Identify nearby subregions around active players
        Map<Long, int[]> nearbySubRegions = new ConcurrentHashMap<>();

        for (ServerPlayer player : players) {
            int px = player.getBlockX();
            int pz = player.getBlockZ();

            int minX = px - PLAYER_SCAN_RADIUS_BLOCKS;
            int maxX = px + PLAYER_SCAN_RADIUS_BLOCKS;
            int minZ = pz - PLAYER_SCAN_RADIUS_BLOCKS;
            int maxZ = pz + PLAYER_SCAN_RADIUS_BLOCKS;

            int minChunkX = Math.floorDiv(minX, 16);
            int maxChunkX = Math.floorDiv(maxX, 16);
            int minChunkZ = Math.floorDiv(minZ, 16);
            int maxChunkZ = Math.floorDiv(maxZ, 16);

            for (int cx = minChunkX; cx <= maxChunkX; cx += 4) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz += 4) {
                    int rx = Math.floorDiv(cx, 8);
                    int rz = Math.floorDiv(cz, 8);
                    int localCx = Math.floorMod(cx, 8);
                    int localCz = Math.floorMod(cz, 8);
                    int sx = localCx >= 4 ? 1 : 0;
                    int sz = localCz >= 4 ? 1 : 0;

                    long key = makeSubKey(rx, rz, sx, sz);
                    nearbySubRegions.putIfAbsent(key, new int[]{rx, rz, sx, sz});
                }
            }
        }

        // 3. Evaluate each nearby subregion for natural patrol spawning
        for (int[] sub : nearbySubRegions.values()) {
            int rx = sub[0];
            int rz = sub[1];
            int sx = sub[2];
            int sz = sub[3];

            long subKey = makeSubKey(rx, rz, sx, sz);
            Long suppressedUntil = NATURAL_SPAWN_SUPPRESSION_UNTIL.get(subKey);
            if (suppressedUntil != null) {
                if (gameTime < suppressedUntil) {
                    continue; // Natural squads suppressed during scout reinforcement phase
                } else {
                    NATURAL_SPAWN_SUPPRESSION_UNTIL.remove(subKey);
                }
            }

            RegionData.SubRegionState state = regions.subRegionAt(rx, rz, sx, sz);
            com.warfront.mission.ActiveCampaignMissionManager.ActiveSubRegionProgress progress =
                    com.warfront.mission.ActiveCampaignMissionManager.getActiveProgress(rx, rz, sx, sz);

            int maxSquads;
            long cooldownTicks;
            Faction targetFaction;

            if (progress != null && !progress.isCompleted()) {
                // Active War Subregion
                targetFaction = progress.targetFaction();
                if (!targetFaction.isAI()) {
                    continue;
                }
                if (!com.warfront.mission.ActiveCampaignMissionManager.isAttackingPlayerPresentInRegion(level, rx, rz)) {
                    continue; // Skip spawning war patrols if attacking faction player is not present in region
                }
                if (progress.missionType() == com.warfront.mission.MissionType.WAR_HORN_RELAY) {
                    continue; // Disabled in Warhorn Relay: handled exclusively by WarHornRelayMissionHandler's border wave system
                }
                if (progress.missionType() == com.warfront.mission.MissionType.KILL_COUNT) {
                    maxSquads = 3;
                    cooldownTicks = 45L * 20L; // 45 seconds = 900 ticks
                } else {
                    maxSquads = 2;
                    cooldownTicks = 90L * 20L; // 90 seconds = 1800 ticks
                }
            } else {
                // Out-of-war Subregion: 3 max squads, 90s cooldown
                Faction owner = state.owner();
                if (!owner.isAI() || state.underSiege()) {
                    continue; // Skip non-hostile subregions or subregions under siege
                }
                targetFaction = owner;
                maxSquads = MAX_SQUADS_PER_SUBREGION;
                cooldownTicks = SPAWN_COOLDOWN_TICKS; // 90 seconds = 1800 ticks
            }

            List<PatrolSquad> squads = ACTIVE_PATROLS.computeIfAbsent(subKey, k -> new CopyOnWriteArrayList<>());

            if (squads.size() < maxSquads) {
                Long lastSpawn = LAST_SPAWN_TIME.get(subKey);
                if (lastSpawn == null || (gameTime - lastSpawn) >= cooldownTicks) {
                    PatrolSquad spawned = spawnPatrolSquad(level, regions, rx, rz, sx, sz, targetFaction);
                    if (spawned != null) {
                        squads.add(spawned);
                        LAST_SPAWN_TIME.put(subKey, gameTime);
                        Warfront.LOGGER.info("[PATROL] Spawned squad {} in Subregion ({}, {} - {}, {}). Active squads: {}/{}",
                                spawned.getSquadId(), rx, rz, sx, sz, squads.size(), maxSquads);
                    }
                }
            }
        }

        // 4. Autonomous Pillager Scout Spawning (1 per subregion, 5-minute cooldown)
        for (int[] sub : nearbySubRegions.values()) {
            int rx = sub[0];
            int rz = sub[1];
            int sx = sub[2];
            int sz = sub[3];

            RegionData.SubRegionState state = regions.subRegionAt(rx, rz, sx, sz);
            if (state.owner() != Faction.PILLAGER_CONQUERORS) {
                continue; // Restrict strictly to pillager regions
            }

            long subKey = makeSubKey(rx, rz, sx, sz);
            UUID existingScoutId = ACTIVE_SCOUTS.get(subKey);
            boolean scoutAlive = false;
            if (existingScoutId != null) {
                Entity e = level.getEntity(existingScoutId);
                if (e instanceof PillagerScoutEntity scout && scout.isAlive()) {
                    scoutAlive = true;
                } else {
                    ACTIVE_SCOUTS.remove(subKey);
                }
            }

            if (!scoutAlive) {
                Long lastSpawn = LAST_SCOUT_SPAWN_TIME.get(subKey);
                if (lastSpawn == null || (gameTime - lastSpawn) >= SCOUT_SPAWN_COOLDOWN_TICKS) {
                    PillagerScoutEntity spawnedScout = spawnSubregionScout(level, rx, rz, sx, sz);
                    if (spawnedScout != null) {
                        ACTIVE_SCOUTS.put(subKey, spawnedScout.getUUID());
                        LAST_SCOUT_SPAWN_TIME.put(subKey, gameTime);
                        Warfront.LOGGER.info("[SCOUT] Spawned autonomous scout {} in Subregion ({}, {} - {}, {})",
                                spawnedScout.getUUID(), rx, rz, sx, sz);
                    }
                }
            }
        }
    }

    private static PatrolSquad spawnPatrolSquad(
            ServerLevel level,
            RegionData regions,
            int rx, int rz,
            int sx, int sz,
            Faction faction) {

        BlockPos spawnBorder = pickRandomBorderPoint(level, rx, rz, sx, sz);
        if (spawnBorder == null) return null;

        BlockPos destBorder = pickNextBorderWaypoint(level, rx, rz, sx, sz, spawnBorder);
        if (destBorder == null) {
            destBorder = spawnBorder;
        }

        float resistance = regions.calculateEffectiveResistance(rx, rz);
        EnemyResistanceTier tier = EnemyResistanceTier.fromResistance(resistance);

        // Size matches resistance tier (3 to 16 max)
        int squadSize = Math.clamp(tier.minSpawn() + level.getRandom().nextInt(Math.max(1, tier.maxSpawn() - tier.minSpawn() + 1)), 3, 16);

        List<Object> rolePool = buildPatrolRolePool(faction, tier);
        if (rolePool.isEmpty()) return null;

        UUID squadId = UUID.randomUUID();
        PatrolSquad squad = new PatrolSquad(squadId, rx, rz, sx, sz, destBorder);

        boolean hasCommander = false;
        final int SCATTER = 3;

        for (int i = 0; i < squadSize; i++) {
            Object role = null;

            // In HIGH / EXTREME tiers, squad leader has 65% chance to be a Commander
            if (i == 0 && (tier == EnemyResistanceTier.HIGH || tier == EnemyResistanceTier.EXTREME)) {
                if (level.getRandom().nextFloat() < 0.65F) {
                    role = PillagerEnemyRole.COMMANDER;
                    hasCommander = true;
                }
            }

            if (role == null) {
                role = rolePool.get(level.getRandom().nextInt(rolePool.size()));
                if (role == PillagerEnemyRole.COMMANDER) {
                    if (hasCommander) {
                        // Avoid redundant commanders
                        role = PillagerEnemyRole.FIGHTER;
                    } else {
                        hasCommander = true;
                    }
                }
            }

            // EXCLUSION: Never spawn scouts in patrol squads as requested
            if (role == PillagerEnemyRole.SCOUT) {
                role = PillagerEnemyRole.FIGHTER;
            }

            int spawnX = spawnBorder.getX() + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER;
            int spawnZ = spawnBorder.getZ() + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER;
            int spawnY = EnemyEncounterSpawner.findDryLandSurfaceY(level, spawnX, spawnZ);
            if (spawnY == Integer.MIN_VALUE) {
                spawnX = spawnBorder.getX();
                spawnZ = spawnBorder.getZ();
                spawnY = spawnBorder.getY();
            }

            Entity entity = resolveEntity(faction, role, level);
            if (!(entity instanceof Mob mob)) continue;

            mob.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                    mob, level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);

            // Remove random wander goals so they adhere to squad patrolling
            mob.goalSelector.getAvailableGoals().removeIf(g ->
                    g.getGoal() instanceof net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
                    || g.getGoal() instanceof net.minecraft.world.entity.ai.goal.RandomStrollGoal);

            int[] offset = SQUAD_SPREAD_OFFSETS[i % SQUAD_SPREAD_OFFSETS.length];

            // Add SubregionPatrolGoal
            mob.goalSelector.addGoal(2, new SubregionPatrolGoal(mob, squad, offset[0], offset[1]));

            // Add Player targeting goals (retaliation when hurt and sighting players, ignoring allied raiders)
            if (mob instanceof net.minecraft.world.entity.PathfinderMob pfm) {
                mob.targetSelector.addGoal(1, new HurtByTargetGoal(pfm, net.minecraft.world.entity.raid.Raider.class));
            }
            mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, Player.class, true));

            if (level.addFreshEntity(mob)) {
                mob.addTag("warfront_patrol");
                mob.addTag("warfront_squad_" + squadId);
                mob.getPersistentData().putBoolean("warfront_roaming", true);
                mob.getPersistentData().putBoolean("isMissionMob", false);
                mob.getPersistentData().putBoolean("isPatrolMob", true);
                mob.getPersistentData().putUUID("squadId", squadId);
                mob.getPersistentData().putInt("faction", faction.id());
                mob.getPersistentData().putInt("originRegionX", rx);
                mob.getPersistentData().putInt("originRegionZ", rz);
                mob.getPersistentData().putInt("originSubX", sx);
                mob.getPersistentData().putInt("originSubZ", sz);

                squad.addMember(mob.getUUID());
                RoamingEntityTracker.registerWandering(mob, rx, rz, sx, sz, faction);
            }
        }

        return squad.countLiving(level) > 0 ? squad : null;
    }

    /**
     * Builds role pool excluding SCOUT and base-defense roles.
     */
    private static List<Object> buildPatrolRolePool(Faction faction, EnemyResistanceTier tier) {
        List<Object> pool = new ArrayList<>();
        if (faction == Faction.PILLAGER_CONQUERORS) {
            for (PillagerEnemyRole role : PillagerEnemyRole.values()) {
                // EXCLUSION: Explicitly exclude SCOUT and base-defense roles (CANNONEER, RAPIDBOW, IRONCLAD)
                if (role == PillagerEnemyRole.SCOUT || role.isBaseDefenseOnly()) {
                    continue;
                }
                if (role.isAvailableForRoaming(tier)) {
                    for (int w = 0; w < role.weight(); w++) {
                        pool.add(role);
                    }
                }
            }
        } else if (faction == Faction.ZOMBIE_HORDE) {
            for (ZombieEnemyRole role : ZombieEnemyRole.values()) {
                if (role.isAvailableAt(tier)) {
                    for (int w = 0; w < role.weight(); w++) {
                        pool.add(role);
                    }
                }
            }
        }
        return pool;
    }

    private static Entity resolveEntity(Faction faction, Object role, ServerLevel level) {
        if (faction == Faction.PILLAGER_CONQUERORS && role instanceof PillagerEnemyRole pr) {
            return EnemyEntityResolver.resolvePillagerRole(pr, level);
        }
        if (faction == Faction.ZOMBIE_HORDE && role instanceof ZombieEnemyRole zr) {
            return EnemyEntityResolver.resolveZombieRole(zr, level);
        }
        return null;
    }

    /**
     * Checks if a border coordinate has stable, flat, open-air dry land suitable for large convoy wagons.
     * Rejects caves, crater edges, cliffs, overhangs, and water.
     */
    public static boolean isValidHarshBorderPoint(ServerLevel level, int x, int z) {
        int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
        if (y == Integer.MIN_VALUE) return false;

        BlockPos pos = new BlockPos(x, y, z);
        // Must have direct view of the sky (not underground in a mine or cave)
        if (!level.canSeeSky(pos)) return false;

        // Check clear 3-block headroom for wagons and horses
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos head = pos.above(dy);
            if (!level.getFluidState(head).isEmpty() || level.getBlockState(head).blocksMotion()) {
                return false;
            }
        }

        // Harsh slope / crater drop check: adjacent cardinal blocks must not be a sheer drop (>2 blocks) or water
        int[][] neighbors = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] offset : neighbors) {
            int nx = x + offset[0];
            int nz = z + offset[1];
            int ny = EnemyEncounterSpawner.findDryLandSurfaceY(level, nx, nz);
            if (ny == Integer.MIN_VALUE || Math.abs(ny - y) > 2) {
                return false; // Drop into crater, cliff ledge, or water boundary
            }
        }

        return true;
    }

    /**
     * Picks a random surface point on one of the 4 borders of a subregion using harsh border validation.
     */
    public static BlockPos pickRandomBorderPoint(ServerLevel level, int rx, int rz, int sx, int sz) {
        int subMinX = rx * RegionData.REGION_SIZE_BLOCKS + sx * SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = rz * RegionData.REGION_SIZE_BLOCKS + sz * SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + SUBREGION_SIZE_BLOCKS - 1;

        // 1. Randomized harsh search across 4 perimeter edges
        for (int attempt = 0; attempt < 80; attempt++) {
            int edge = level.getRandom().nextInt(4);
            int inset = level.getRandom().nextInt(2); // 0 or 1 block inset
            int x, z;
            switch (edge) {
                case 0 -> { x = subMinX + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS); z = subMinZ + inset; }
                case 1 -> { x = subMinX + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS); z = subMaxZ - inset; }
                case 2 -> { x = subMinX + inset; z = subMinZ + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS); }
                default -> { x = subMaxX - inset; z = subMinZ + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS); }
            }

            if (isValidHarshBorderPoint(level, x, z)) {
                int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
                return new BlockPos(x, y, z);
            }
        }

        // 2. Systematic perimeter sweep along borders
        for (int edge = 0; edge < 4; edge++) {
            for (int offset = 1; offset < SUBREGION_SIZE_BLOCKS - 1; offset += 2) {
                int x, z;
                switch (edge) {
                    case 0 -> { x = subMinX + offset; z = subMinZ; }
                    case 1 -> { x = subMinX + offset; z = subMaxZ; }
                    case 2 -> { x = subMinX; z = subMinZ + offset; }
                    default -> { x = subMaxX; z = subMinZ + offset; }
                }
                if (isValidHarshBorderPoint(level, x, z)) {
                    int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
                    return new BlockPos(x, y, z);
                }
            }
        }

        Warfront.LOGGER.error("[Warfront Convoy] Can't find next waypoint (failed to find valid border starting point) in Region ({}, {}) Sub ({}, {})!", rx, rz, sx, sz);
        return null;
    }

    /**
     * Picks a border waypoint on another border at least 48 blocks away from the current location.
     */
    public static BlockPos pickNextBorderWaypoint(ServerLevel level, int rx, int rz, int sx, int sz, BlockPos fromPos) {
        int subMinX = rx * RegionData.REGION_SIZE_BLOCKS + sx * SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = rz * RegionData.REGION_SIZE_BLOCKS + sz * SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + SUBREGION_SIZE_BLOCKS - 1;

        BlockPos bestFallback = null;
        double bestDist = 0.0D;

        for (int attempt = 0; attempt < 120; attempt++) {
            int edge = level.getRandom().nextInt(4);
            int inset = level.getRandom().nextInt(2);
            int x, z;
            switch (edge) {
                case 0 -> { x = subMinX + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS); z = subMinZ + inset; }
                case 1 -> { x = subMinX + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS); z = subMaxZ - inset; }
                case 2 -> { x = subMinX + inset; z = subMinZ + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS); }
                default -> { x = subMaxX - inset; z = subMinZ + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS); }
            }

            if (!isValidHarshBorderPoint(level, x, z)) continue;

            int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (fromPos == null) return candidate;

            double dist = Math.hypot(candidate.getX() - fromPos.getX(), candidate.getZ() - fromPos.getZ());
            if (dist >= MIN_WAYPOINT_DIST) {
                return candidate;
            }

            if (dist > bestDist) {
                bestDist = dist;
                bestFallback = candidate;
            }
        }

        if (bestFallback != null && bestDist >= 32.0D) {
            return bestFallback;
        }

        // Console error logging when the check fails
        Warfront.LOGGER.error("[Warfront Convoy] Can't find next waypoint for convoy in Region ({}, {}) Sub ({}, {}) from Pos {}!", rx, rz, sx, sz, fromPos);
        return null;
    }

    /**
     * Called by a Pillager Scout upon concluding a successful spyglass scan on a player.
     * Evaluates current active squads in the subregion and triggers reinforcements per Scenarios 1, 2, or 3.
     */
    public static void triggerScoutReinforcements(ServerLevel level, int rx, int rz, int sx, int sz, Player player) {
        if (level == null || player == null || !player.isAlive()) return;

        RegionData regions = RegionData.get(level);
        RegionData.SubRegionState state = regions.subRegionAt(rx, rz, sx, sz);
        if (state.owner() != Faction.PILLAGER_CONQUERORS) {
            return; // Restrict strictly to pillager regions
        }

        long subKey = makeSubKey(rx, rz, sx, sz);
        List<PatrolSquad> squads = ACTIVE_PATROLS.computeIfAbsent(subKey, k -> new CopyOnWriteArrayList<>());
        squads.removeIf(s -> s.countLiving(level) == 0);
        int currentSquadCount = squads.size();
        long gameTime = level.getGameTime();
        UUID playerUuid = player.getUUID();

        Warfront.LOGGER.info("[SCOUT REINFORCE] Horn sounded in Subregion ({}, {} - {}, {}). Current squads: {}",
                rx, rz, sx, sz, currentSquadCount);

        if (currentSquadCount <= 1) {
            // Scenario 1: Squad count is 0 or 1.
            // Natural squads suppressed until second squad spawns at +40s (800 ticks).
            NATURAL_SPAWN_SUPPRESSION_UNTIL.put(subKey, gameTime + 800L);
            // Squad 1: spawns 10 seconds later (200 ticks)
            PENDING_REINFORCEMENTS.add(new ScheduledReinforcementTask(gameTime + 200L, rx, rz, sx, sz, playerUuid, true, null));
            // Squad 2: spawns 40 seconds later (800 ticks)
            PENDING_REINFORCEMENTS.add(new ScheduledReinforcementTask(gameTime + 800L, rx, rz, sx, sz, playerUuid, true, null));
        } else if (currentSquadCount == 2) {
            // Scenario 2: Squad count is 2.
            // One non-annihilated squad immediately aggroes to player.
            dispatchSquadHoming(level, squads.get(0), player);
            // Natural squads suppressed until second squad spawns at +30s (600 ticks).
            NATURAL_SPAWN_SUPPRESSION_UNTIL.put(subKey, gameTime + 600L);
            // Another squad spawns 30 seconds later (600 ticks)
            PENDING_REINFORCEMENTS.add(new ScheduledReinforcementTask(gameTime + 600L, rx, rz, sx, sz, playerUuid, true, null));
        } else {
            // Scenario 3: Squad count is maxed (>= 3).
            // One squad immediately aggroes to player.
            dispatchSquadHoming(level, squads.get(0), player);
            // Another squad aggroes 30 seconds later (600 ticks).
            PatrolSquad secondSquad = squads.size() > 1 ? squads.get(1) : squads.get(0);
            PENDING_REINFORCEMENTS.add(new ScheduledReinforcementTask(gameTime + 600L, rx, rz, sx, sz, playerUuid, false, secondSquad.getSquadId()));
        }
    }

    /**
     * Directs an entire patrol squad to track and converge directly upon the player's position.
     */
    public static void dispatchSquadHoming(ServerLevel level, PatrolSquad squad, Player player) {
        if (squad == null || player == null || !player.isAlive()) return;
        squad.enableHoming(player.getUUID());
        squad.alertSquadToTarget(level, player);
        for (Mob mob : squad.getLivingMembers(level)) {
            if (mob.getAttribute(Attributes.FOLLOW_RANGE) != null) {
                mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64.0D);
            }
            mob.setTarget(player);
            mob.getNavigation().moveTo(player, 1.25D);
        }
    }

    /**
     * Spawns an autonomous Pillager Scout in the subregion independent of patrol squads.
     */
    private static PillagerScoutEntity spawnSubregionScout(ServerLevel level, int rx, int rz, int sx, int sz) {
        BlockPos borderPoint = pickRandomBorderPoint(level, rx, rz, sx, sz);
        int spawnX = borderPoint != null ? borderPoint.getX() : (rx * 128 + sx * 64 + 32);
        int spawnZ = borderPoint != null ? borderPoint.getZ() : (rz * 128 + sz * 64 + 32);
        int spawnY = EnemyEncounterSpawner.findDryLandSurfaceY(level, spawnX, spawnZ);
        if (spawnY == Integer.MIN_VALUE) {
            return null;
        }

        Entity entity = ModEntities.PILLAGER_SCOUT.get().create(level);
        if (!(entity instanceof PillagerScoutEntity scout)) {
            return null;
        }

        scout.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                scout, level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);

        if (level.addFreshEntity(scout)) {
            scout.addTag("warfront_scout");
            scout.getPersistentData().putBoolean("warfront_roaming", true);
            scout.getPersistentData().putBoolean("isMissionMob", false);
            scout.getPersistentData().putBoolean("isScoutMob", true);
            scout.getPersistentData().putInt("faction", Faction.PILLAGER_CONQUERORS.id());
            scout.getPersistentData().putInt("originRegionX", rx);
            scout.getPersistentData().putInt("originRegionZ", rz);
            scout.getPersistentData().putInt("originSubX", sx);
            scout.getPersistentData().putInt("originSubZ", sz);

            RoamingEntityTracker.registerWandering(scout, rx, rz, sx, sz, Faction.PILLAGER_CONQUERORS);
            return scout;
        }
        return null;
    }
}
