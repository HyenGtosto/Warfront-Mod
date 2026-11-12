package com.warfront.spawn;

import com.warfront.Warfront;
import com.warfront.ai.goal.SubregionPatrolGoal;
import com.warfront.mission.MissionProfile;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.entity.ModEntities;
import com.warfront.entity.PillagerScoutEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
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
 *   - Max 2 concurrent ambient squads per out-of-war subregion (3 in Kill Count war subregions).
 *   - 90 seconds (1800 ticks) ambient spawn cooldown per subregion.
 *   - No percentage-loss respawns — spawns operate purely on cooldown timer.
 *   - Spawns persist in active missions as well as peaceful exploration.
 *   - Composition and resistance tiering match Attack Roamers (scouts are excluded).
 *
 * Patrol Behavior:
 *   1. Spawn 4 blocks inside a subregion border.
 *   2. Select a random waypoint 4 blocks inside another border at least 48 blocks away.
 *   3. March in formation to that point.
 *   4. Hold ground for 15 seconds (300 ticks).
 *   5. Pick another inset border waypoint at least 48 blocks away and repeat.
 *   6. If any member spots a player, the whole squad engages (subject to home-subregion chase leash).
 */
public final class SubregionPatrolManager {

    public static final int SUBREGION_SIZE_BLOCKS = 64;
    public static final int BORDER_INSET_BLOCKS = 4;
    public static final int MAX_SQUADS_PER_SUBREGION = 2;
    public static final long SPAWN_COOLDOWN_TICKS = 90L * 20L; // 90 seconds = 1800 ticks
    public static final double MIN_WAYPOINT_DIST = 48.0D;
    public static final int HOLD_GROUND_DURATION_TICKS = 15 * 20; // 15 seconds = 300 ticks

    public static final double AMBIENT_CHASE_LEASH_BLOCKS = 96.0D;
    public static final double HOMING_CHASE_LEASH_BLOCKS = 128.0D;
    public static final long LEASH_NO_COMBAT_DISENGAGE_TICKS = 10L * 20L; // 10 seconds without combat exchanged
    public static final long LEASH_RETURN_COOLDOWN_TICKS = 10L * 20L; // 10 seconds disengage return grace

    public static final long SCOUT_SPAWN_COOLDOWN_TICKS = 5L * 60L * 20L; // 5 minutes = 6000 ticks
    public static final long REINFORCEMENT_COOLDOWN_TICKS = 3L * 60L * 20L; // 3 minutes = 3600 ticks (Option 1b)

    private static final int EVAL_INTERVAL_TICKS = 20; // 1 second evaluation cadence
    private static final int PLAYER_SCAN_RADIUS_BLOCKS = 64; // 64 blocks in each direction (128x128 footprint)

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
        private volatile boolean engagedInCombat = false;
        private long lastDirectCombatGameTime = 0L;
        private long leashDisengagedUntilGameTime = 0L;
        private boolean wasTargetingLastCheck = false;

        public PatrolSquad(UUID squadId, int regionX, int regionZ, int subX, int subZ, BlockPos initialWaypoint) {
            this.squadId = squadId;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.subX = subX;
            this.subZ = subZ;
            this.currentWaypoint = initialWaypoint;
        }

        public UUID getSquadId() { return squadId; }
        public int getRegionX() { return regionX; }
        public int getRegionZ() { return regionZ; }
        public int getSubX() { return subX; }
        public int getSubZ() { return subZ; }
        public BlockPos getCurrentWaypoint() { return currentWaypoint; }
        public boolean isHoldingGround() { return holdingGround; }
        public boolean wasEngagedInCombat() { return engagedInCombat; }
        public void markEngaged() { this.engagedInCombat = true; }

        public double getHomeCenterX() {
            return regionX * RegionData.REGION_SIZE_BLOCKS + subX * SUBREGION_SIZE_BLOCKS + (SUBREGION_SIZE_BLOCKS * 0.5D);
        }

        public double getHomeCenterZ() {
            return regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * SUBREGION_SIZE_BLOCKS + (SUBREGION_SIZE_BLOCKS * 0.5D);
        }

        public double getActiveChaseLeashBlocks() {
            return homingPlayerUuid != null ? HOMING_CHASE_LEASH_BLOCKS : AMBIENT_CHASE_LEASH_BLOCKS;
        }

        public boolean isWithinChaseLeash(LivingEntity target) {
            if (target == null) return true;
            double leash = getActiveChaseLeashBlocks();
            double dx = target.getX() - getHomeCenterX();
            double dz = target.getZ() - getHomeCenterZ();
            return (dx * dx + dz * dz) <= (leash * leash);
        }

        public boolean shouldSuppressTargetAcquisition(Level level, LivingEntity mob, LivingEntity newTarget) {
            if (level == null || newTarget == null) return false;
            boolean directlyHurtMob = mob instanceof Mob m
                    && m.getLastHurtByMob() == newTarget
                    && m.getLastHurtByMobTimestamp() > 0
                    && (m.tickCount - m.getLastHurtByMobTimestamp()) <= 40;
            if (directlyHurtMob) {
                return false;
            }
            if (level.getGameTime() < this.leashDisengagedUntilGameTime) {
                return true;
            }
            return !isWithinChaseLeash(newTarget);
        }

        public void addMember(UUID uuid) {
            memberUuids.add(uuid);
            MOB_TO_SQUAD.put(uuid, this);
        }

        public int countLiving(ServerLevel level) {
            int living = 0;
            int unloaded = 0;
            int minChunkX = (regionX * 8) + (subX * 4);
            int minChunkZ = (regionZ * 8) + (subZ * 4);
            boolean anyLoaded = false;
            for (int cx = minChunkX; cx < minChunkX + 4; cx++) {
                for (int cz = minChunkZ; cz < minChunkZ + 4; cz++) {
                    if (level.getChunkSource().hasChunk(cx, cz)) {
                        anyLoaded = true;
                        break;
                    }
                }
                if (anyLoaded) break;
            }

            for (UUID id : memberUuids) {
                Entity e = level.getEntity(id);
                if (e instanceof Mob mob) {
                    if (mob.isAlive()) {
                        living++;
                        if (mob.getTarget() != null || mob.getLastHurtByMob() != null) {
                            this.engagedInCombat = true;
                        }
                    } else {
                        // Combat casualty occurred
                        this.engagedInCombat = true;
                    }
                } else if (e == null && !anyLoaded) {
                    unloaded++;
                }
            }
            return living + unloaded;
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
            this.leashDisengagedUntilGameTime = 0L;
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
                    } else {
                        // Waypoint didn't change: perturb destination across subregion to break infinite hold loop
                        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * SUBREGION_SIZE_BLOCKS;
                        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * SUBREGION_SIZE_BLOCKS;
                        int currX = currentWaypoint != null ? currentWaypoint.getX() : subMinX + 32;
                        int currZ = currentWaypoint != null ? currentWaypoint.getZ() : subMinZ + 32;
                        int px = subMinX + Math.floorMod(currX - subMinX + 32, SUBREGION_SIZE_BLOCKS);
                        int pz = subMinZ + Math.floorMod(currZ - subMinZ + 32, SUBREGION_SIZE_BLOCKS);
                        int py = EnemyEncounterSpawner.findDryLandSurfaceY(level, px, pz);
                        if (py != Integer.MIN_VALUE) {
                            currentWaypoint = new BlockPos(px, py, pz);
                            Warfront.LOGGER.info("[PATROL] Squad {} hold complete. Perturbed waypoint across subregion to: {}", squadId, currentWaypoint);
                        }
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
                if (shouldSuppressTargetAcquisition(level, mob, target)) continue;
                this.engagedInCombat = true;
                if (mob.getTarget() == null || !mob.getTarget().isAlive()) {
                    mob.setTarget(target);
                }
            }
        }

        /**
         * Enforces the home-subregion chase leash for this squad.
         * If any targeted entity has moved beyond the leash radius without recent combat
         * for 10 seconds, all living members disengage and return to the home subregion.
         */
        public synchronized void enforceChaseLeash(ServerLevel level, long gameTime) {
            List<Mob> living = getLivingMembers(level);
            if (living.isEmpty()) {
                this.wasTargetingLastCheck = false;
                return;
            }

            LivingEntity activeTarget = null;
            for (Mob mob : living) {
                if ((mob.getLastHurtByMobTimestamp() > 0 && (mob.tickCount - mob.getLastHurtByMobTimestamp()) <= EVAL_INTERVAL_TICKS)
                        || (mob.getLastHurtMobTimestamp() > 0 && (mob.tickCount - mob.getLastHurtMobTimestamp()) <= EVAL_INTERVAL_TICKS)) {
                    this.lastDirectCombatGameTime = gameTime;
                }
                if (activeTarget == null) {
                    LivingEntity t = mob.getTarget();
                    if (t != null && t.isAlive() && !com.warfront.entity.AlliedFactionHelper.isAllied(mob, t)) {
                        activeTarget = t;
                    }
                }
            }

            if (activeTarget == null && this.homingPlayerUuid != null) {
                Entity homingEntity = level.getEntity(this.homingPlayerUuid);
                if (homingEntity instanceof Player p && p.isAlive()) {
                    activeTarget = p;
                }
            }

            if (activeTarget == null) {
                this.wasTargetingLastCheck = false;
                return;
            }

            if (!this.wasTargetingLastCheck) {
                this.wasTargetingLastCheck = true;
                this.lastDirectCombatGameTime = Math.max(this.lastDirectCombatGameTime, gameTime);
            }

            if (!isWithinChaseLeash(activeTarget)
                    && (gameTime - this.lastDirectCombatGameTime >= LEASH_NO_COMBAT_DISENGAGE_TICKS)) {
                this.homingPlayerUuid = null;
                this.holdingGround = false;
                this.holdTicksRemaining = 0;
                this.leashDisengagedUntilGameTime = gameTime + LEASH_RETURN_COOLDOWN_TICKS;
                this.wasTargetingLastCheck = false;

                int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * SUBREGION_SIZE_BLOCKS;
                int subMaxX = subMinX + SUBREGION_SIZE_BLOCKS - 1;
                int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * SUBREGION_SIZE_BLOCKS;
                int subMaxZ = subMinZ + SUBREGION_SIZE_BLOCKS - 1;
                if (this.currentWaypoint == null
                        || this.currentWaypoint.getX() < subMinX || this.currentWaypoint.getX() > subMaxX
                        || this.currentWaypoint.getZ() < subMinZ || this.currentWaypoint.getZ() > subMaxZ) {
                    BlockPos homeWaypoint = pickRandomBorderPoint(level, regionX, regionZ, subX, subZ);
                    if (homeWaypoint != null) {
                        this.currentWaypoint = homeWaypoint;
                    }
                }

                for (Mob mob : living) {
                    mob.setTarget(null);
                    mob.setLastHurtByMob(null);
                    mob.setAggressive(false);
                    mob.getNavigation().stop();
                }

                Warfront.LOGGER.debug("[PATROL] Squad {} exceeded {}m home leash without combat for 10s; disengaging.",
                        squadId, (int) AMBIENT_CHASE_LEASH_BLOCKS);
            }
        }

        /**
         * Synchronizes target across all squad members if any member has engaged an enemy.
         */
        public void syncSquadTarget(Level level) {
            List<Mob> living = getLivingMembers(level);
            LivingEntity target = null;
            for (Mob mob : living) {
                LivingEntity t = mob.getTarget();
                if (t != null && t.isAlive()
                        && !com.warfront.entity.AlliedFactionHelper.isAllied(mob, t)
                        && !shouldSuppressTargetAcquisition(level, mob, t)) {
                    target = t;
                    break;
                }
            }
            if (target != null) {
                this.engagedInCombat = true;
                for (Mob mob : living) {
                    if (com.warfront.entity.AlliedFactionHelper.isAllied(mob, target)) continue;
                    if (shouldSuppressTargetAcquisition(level, mob, target)) continue;
                    if (mob.getTarget() == null || !mob.getTarget().isAlive()) {
                        mob.setTarget(target);
                    }
                }
            }
        }
    }

    /** Active squads per subregion: SubKey -> List of PatrolSquads */
    private static final Map<Long, List<PatrolSquad>> ACTIVE_PATROLS = new ConcurrentHashMap<>();

    /** Reverse index from living patrol member UUID to owning PatrolSquad */
    private static final Map<UUID, PatrolSquad> MOB_TO_SQUAD = new ConcurrentHashMap<>();

    /** Last spawn game time per subregion: SubKey -> GameTime */
    private static final Map<Long, Long> LAST_SPAWN_TIME = new ConcurrentHashMap<>();

    /** Active autonomous scout per subregion: SubKey -> Scout Mob UUID */
    private static final Map<Long, UUID> ACTIVE_SCOUTS = new ConcurrentHashMap<>();

    /** Last autonomous scout spawn game time per subregion: SubKey -> GameTime */
    private static final Map<Long, Long> LAST_SCOUT_SPAWN_TIME = new ConcurrentHashMap<>();

    /** Natural patrol spawn suppression until game time: SubKey -> GameTime */
    private static final Map<Long, Long> NATURAL_SPAWN_SUPPRESSION_UNTIL = new ConcurrentHashMap<>();

    /** Last reinforcement horn alert game time per subregion: SubKey -> GameTime */
    private static final Map<Long, Long> LAST_REINFORCEMENT_HORN_TIME = new ConcurrentHashMap<>();

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
                removeSquadMembersFromLookup(squad);
                for (UUID id : squad.memberUuids) {
                    if (!TERMINAL_PATROL_MOBS.contains(id)) {
                        TERMINAL_PATROL_MOBS.add(id);
                    }
                }
            }
        }
    }

    private static void removeSquadMembersFromLookup(PatrolSquad squad) {
        if (squad == null) return;
        for (UUID id : squad.memberUuids) {
            MOB_TO_SQUAD.remove(id, squad);
        }
    }

    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity entity = event.getEntity();
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        if (entity == null || newTarget == null || entity.level().isClientSide()) {
            return;
        }
        PatrolSquad squad = MOB_TO_SQUAD.get(entity.getUUID());
        if (squad != null && squad.shouldSuppressTargetAcquisition(entity.level(), entity, newTarget)) {
            event.setNewAboutToBeSetTarget(null);
            event.setCanceled(true);
        }
    }

    public static void onRegionCaptured(ServerLevel level, int rx, int rz) {
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                onSubregionCaptured(level, rx, rz, sx, sz);
            }
        }
    }

    /**
     * Called when a roaming patrol mob is discarded due to distance despawn (e.g. player flew away).
     * If the squad was never engaged in combat, this refunds the subregion's spawn cooldown.
     */
    public static void notifySquadDistanceDespawned(UUID squadId, int rx, int rz, int sx, int sz) {
        long subKey = makeSubKey(rx, rz, sx, sz);
        List<PatrolSquad> squads = ACTIVE_PATROLS.get(subKey);
        if (squads != null) {
            for (PatrolSquad squad : squads) {
                if (squad.getSquadId().equals(squadId)) {
                    if (!squad.wasEngagedInCombat()) {
                        LAST_SPAWN_TIME.remove(subKey);
                        Warfront.LOGGER.debug("[PATROL] Squad {} distance-despawned without combat in Subregion ({}, {} - {}, {}). Cooldown refunded.",
                                squadId, rx, rz, sx, sz);
                    }
                    break;
                }
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;

        // In Peaceful mode, suppress all patrol, scout, and reinforcement spawning and clean up tracking
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            if (!ACTIVE_PATROLS.isEmpty()) ACTIVE_PATROLS.clear();
            if (!MOB_TO_SQUAD.isEmpty()) MOB_TO_SQUAD.clear();
            if (!ACTIVE_SCOUTS.isEmpty()) ACTIVE_SCOUTS.clear();
            if (!PENDING_REINFORCEMENTS.isEmpty()) PENDING_REINFORCEMENTS.clear();
            if (!TERMINAL_PATROL_MOBS.isEmpty()) TERMINAL_PATROL_MOBS.clear();
            return;
        }

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

        // 1. Tick hold timers, enforce chase leashes, sync targets, and clean up empty squads
        for (Map.Entry<Long, List<PatrolSquad>> entry : ACTIVE_PATROLS.entrySet()) {
            long subKey = entry.getKey();
            List<PatrolSquad> squads = entry.getValue();
            squads.removeIf(squad -> {
                if (squad.countLiving(level) == 0) {
                    removeSquadMembersFromLookup(squad);
                    if (!squad.wasEngagedInCombat() && level.getDifficulty() != Difficulty.PEACEFUL) {
                        LAST_SPAWN_TIME.remove(subKey);
                        Warfront.LOGGER.debug("[PATROL] Squad {} emptied without combat. Subregion cooldown refunded.", squad.getSquadId());
                    }
                    return true;
                }
                return false;
            });
            for (PatrolSquad squad : squads) {
                squad.enforceChaseLeash(level, gameTime);
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

            // Suppress natural ambient patrol squads inside Headquarters and Mega Base footprints
            RegionData.Region parentReg = regions.regionAt(rx, rz);
            if (parentReg.baseType() == BaseType.HEADQUARTERS || parentReg.baseType() == BaseType.MEGA_BASE) {
                int occupiedMask = MissionProfile.getOccupiedBaseSubRegionsMask(
                        rx, rz, parentReg.baseType(), parentReg.baseAnchor());
                int subBit = (sz & 1) * 2 + (sx & 1);
                if (parentReg.baseAnchor() == null || (occupiedMask & (1 << subBit)) != 0) {
                    continue;
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
                // Out-of-war Subregion: 2 max squads, 90s cooldown
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
                    } else {
                        // Back off for 10 seconds (200 ticks) when border spawn fails (e.g. ocean/unloaded border)
                        LAST_SPAWN_TIME.put(subKey, gameTime - cooldownTicks + 200L);
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

            // Suppress autonomous scout spawns inside Headquarters and Mega Base footprints
            RegionData.Region scoutParentReg = regions.regionAt(rx, rz);
            if (scoutParentReg.baseType() == BaseType.HEADQUARTERS || scoutParentReg.baseType() == BaseType.MEGA_BASE) {
                int occupiedMask = MissionProfile.getOccupiedBaseSubRegionsMask(
                        rx, rz, scoutParentReg.baseType(), scoutParentReg.baseAnchor());
                int subBit = (sz & 1) * 2 + (sx & 1);
                if (scoutParentReg.baseAnchor() == null || (occupiedMask & (1 << subBit)) != 0) {
                    continue;
                }
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
                long minCooldown = 3L * 60L * 20L;
                if (lastSpawn != null && (gameTime - lastSpawn) < minCooldown) {
                    continue;
                }
                long scoutCooldown = SCOUT_SPAWN_COOLDOWN_TICKS;
                com.warfront.region.terrain.TerrainClassifier.SubRegionTerrainData subTerrain =
                        com.warfront.region.terrain.TerrainClassifier.getSubRegionTerrain(level, rx, rz, sx, sz);
                int subCenterCX = (rx * 8) + (sx * 4) + 2;
                int subCenterCZ = (rz * 8) + (sz * 4) + 2;
                int elevation = com.warfront.region.terrain.BiomeElevation.getChunkElevation(
                        level, subCenterCX, subCenterCZ, com.warfront.region.generator.ProceduralRegionGenerator.sampleBiomeFast(level, subCenterCX * 16 + 8, level.getSeaLevel(), subCenterCZ * 16 + 8));
                if (TerrainSpawnDoctrine.isScoutAcceleratedTerrain(subTerrain.primary(), elevation)) {
                    scoutCooldown = minCooldown; // 3 minutes = 3600 ticks in rugged / high elevation
                }

                if (lastSpawn == null || (gameTime - lastSpawn) >= scoutCooldown) {
                    PillagerScoutEntity spawnedScout = spawnSubregionScout(level, rx, rz, sx, sz);
                    if (spawnedScout != null) {
                        ACTIVE_SCOUTS.put(subKey, spawnedScout.getUUID());
                        LAST_SCOUT_SPAWN_TIME.put(subKey, gameTime);
                        Warfront.LOGGER.info("[SCOUT] Spawned autonomous scout {} in Subregion ({}, {} - {}, {})",
                                spawnedScout.getUUID(), rx, rz, sx, sz);
                    } else {
                        LAST_SCOUT_SPAWN_TIME.put(subKey, gameTime - scoutCooldown + 200L);
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

        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            return null;
        }

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

        com.warfront.region.terrain.TerrainClassifier.SubRegionTerrainData subTerrain =
                com.warfront.region.terrain.TerrainClassifier.getSubRegionTerrain(level, rx, rz, sx, sz);
        int subCenterCX = (rx * 8) + (sx * 4) + 2;
        int subCenterCZ = (rz * 8) + (sz * 4) + 2;
        int elevation = com.warfront.region.terrain.BiomeElevation.getChunkElevation(
                level, subCenterCX, subCenterCZ, com.warfront.region.generator.ProceduralRegionGenerator.sampleBiomeFast(level, subCenterCX * 16 + 8, level.getSeaLevel(), subCenterCZ * 16 + 8));

        List<Object> rolePool = TerrainSpawnDoctrine.buildPatrolRolePool(
                faction, tier, subTerrain.primary(), subTerrain.secondary(), elevation);
        if (rolePool.isEmpty()) {
            rolePool = buildPatrolRolePool(faction, tier);
        }
        if (rolePool.isEmpty()) return null;

        UUID squadId = UUID.randomUUID();
        PatrolSquad squad = new PatrolSquad(squadId, rx, rz, sx, sz, destBorder);

        SquadUpgradeTracker upgradeTracker = SquadUpgradeTracker.forSquadSize(squadSize);
        boolean hasCommander = false;
        int eliteCount = 0;
        int rangedCount = 0;
        final int maxElites = 2;
        final int maxRanged = Math.max(1, (int) Math.ceil(squadSize * 0.55));
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

            // Cap Armored Elites to max 2 per squad
            if (role == PillagerEnemyRole.ARMORED_ELITE) {
                if (eliteCount >= maxElites) {
                    role = PillagerEnemyRole.FIGHTER;
                } else {
                    eliteCount++;
                }
            }

            // Cap Ranged units to preserve melee infantry core
            if (role == PillagerEnemyRole.RANGED) {
                if (rangedCount >= maxRanged) {
                    role = PillagerEnemyRole.FIGHTER;
                } else {
                    rangedCount++;
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

            Entity entity = resolveEntity(faction, role, level, resistance, upgradeTracker);
            if (!(entity instanceof Mob mob)) continue;

            mob.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(
                    mob, level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);
            mob.setPersistenceRequired();

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

    private static Entity resolveEntity(Faction faction, Object role, ServerLevel level, float resistance, SquadUpgradeTracker tracker) {
        if (faction == Faction.PILLAGER_CONQUERORS && role instanceof PillagerEnemyRole pr) {
            return EnemyEntityResolver.resolvePillagerRole(pr, level, resistance, tracker);
        }
        if (faction == Faction.ZOMBIE_HORDE && role instanceof ZombieEnemyRole zr) {
            return EnemyEntityResolver.resolveZombieRole(zr, level);
        }
        return null;
    }

    private static Entity resolveEntity(Faction faction, Object role, ServerLevel level, float resistance) {
        return resolveEntity(faction, role, level, resistance, null);
    }

    /**
     * Checks if a border coordinate has stable dry land suitable for biped infantry patrol squads.
     * Supports tree canopies (forests) and normal elevation stepping (<= 3 blocks drop).
     * Rejects liquid water/lava, suffocating blocks, and sheer vertical falls.
     */
    public static boolean isValidInfantryBorderPoint(ServerLevel level, int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return false;
        }

        int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
        if (y == Integer.MIN_VALUE) return false;

        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.above();
        if (level.getBlockState(feet).blocksMotion() || level.getBlockState(head).blocksMotion()) {
            return false;
        }
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(head).isEmpty()) {
            return false;
        }

        // Stepping check: at least 1 adjacent cardinal neighbor must be within 3 blocks elevation
        int[][] neighbors = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] offset : neighbors) {
            int nx = x + offset[0];
            int nz = z + offset[1];
            if (!level.getChunkSource().hasChunk(nx >> 4, nz >> 4)) continue;
            int ny = EnemyEncounterSpawner.findDryLandSurfaceY(level, nx, nz);
            if (ny != Integer.MIN_VALUE && Math.abs(ny - y) <= 3) {
                return true;
            }
        }

        return false;
    }

    private static BlockPos pickInsetBorderCandidate(ServerLevel level, int subMinX, int subMaxX, int subMinZ, int subMaxZ) {
        int innerSpan = SUBREGION_SIZE_BLOCKS - (BORDER_INSET_BLOCKS * 2);
        int edge = level.getRandom().nextInt(4);
        int inset = BORDER_INSET_BLOCKS + level.getRandom().nextInt(2); // 4 or 5 blocks inside border
        int x, z;
        switch (edge) {
            case 0 -> { x = subMinX + BORDER_INSET_BLOCKS + level.getRandom().nextInt(innerSpan); z = subMinZ + inset; }
            case 1 -> { x = subMinX + BORDER_INSET_BLOCKS + level.getRandom().nextInt(innerSpan); z = subMaxZ - inset; }
            case 2 -> { x = subMinX + inset; z = subMinZ + BORDER_INSET_BLOCKS + level.getRandom().nextInt(innerSpan); }
            default -> { x = subMaxX - inset; z = subMinZ + BORDER_INSET_BLOCKS + level.getRandom().nextInt(innerSpan); }
        }
        if (!isValidInfantryBorderPoint(level, x, z)) {
            return null;
        }
        int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
        return y != Integer.MIN_VALUE ? new BlockPos(x, y, z) : null;
    }

    /**
     * Picks a random surface point 4 blocks inside one of the 4 borders of a subregion using infantry border validation.
     */
    public static BlockPos pickRandomBorderPoint(ServerLevel level, int rx, int rz, int sx, int sz) {
        int subMinX = rx * RegionData.REGION_SIZE_BLOCKS + sx * SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = rz * RegionData.REGION_SIZE_BLOCKS + sz * SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + SUBREGION_SIZE_BLOCKS - 1;

        // 1. Randomized search across 4 inset perimeter edges
        for (int attempt = 0; attempt < 80; attempt++) {
            BlockPos candidate = pickInsetBorderCandidate(level, subMinX, subMaxX, subMinZ, subMaxZ);
            if (candidate != null) {
                return candidate;
            }
        }

        // 2. Systematic perimeter sweep along 4-block inset borders
        for (int edge = 0; edge < 4; edge++) {
            for (int offset = BORDER_INSET_BLOCKS; offset < SUBREGION_SIZE_BLOCKS - BORDER_INSET_BLOCKS; offset += 2) {
                int x, z;
                switch (edge) {
                    case 0 -> { x = subMinX + offset; z = subMinZ + BORDER_INSET_BLOCKS; }
                    case 1 -> { x = subMinX + offset; z = subMaxZ - BORDER_INSET_BLOCKS; }
                    case 2 -> { x = subMinX + BORDER_INSET_BLOCKS; z = subMinZ + offset; }
                    default -> { x = subMaxX - BORDER_INSET_BLOCKS; z = subMinZ + offset; }
                }
                if (isValidInfantryBorderPoint(level, x, z)) {
                    int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
                    return new BlockPos(x, y, z);
                }
            }
        }

        Warfront.LOGGER.debug("[PATROL] Unable to find valid border spawn point in Region ({}, {}) Sub ({}, {})", rx, rz, sx, sz);
        return null;
    }

    /**
     * Picks an inset border waypoint on another border at least 48 blocks away from the current location.
     */
    public static BlockPos pickNextBorderWaypoint(ServerLevel level, int rx, int rz, int sx, int sz, BlockPos fromPos) {
        int subMinX = rx * RegionData.REGION_SIZE_BLOCKS + sx * SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = rz * RegionData.REGION_SIZE_BLOCKS + sz * SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + SUBREGION_SIZE_BLOCKS - 1;

        BlockPos bestFallback = null;
        double bestDist = 0.0D;

        for (int attempt = 0; attempt < 120; attempt++) {
            BlockPos candidate = pickInsetBorderCandidate(level, subMinX, subMaxX, subMinZ, subMaxZ);
            if (candidate == null) continue;
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

        if (bestFallback != null && bestDist >= 20.0D) {
            return bestFallback;
        }

        if (bestFallback != null) {
            return bestFallback;
        }

        // Interior cross-subregion fallback if all borders are walled off or obstructed
        int subCenterX = subMinX + SUBREGION_SIZE_BLOCKS / 2;
        int subCenterZ = subMinZ + SUBREGION_SIZE_BLOCKS / 2;
        int oppX = fromPos != null ? subCenterX + (subCenterX - fromPos.getX()) : subCenterX;
        int oppZ = fromPos != null ? subCenterZ + (subCenterZ - fromPos.getZ()) : subCenterZ;
        oppX = Math.clamp(oppX, subMinX + 4, subMaxX - 4);
        oppZ = Math.clamp(oppZ, subMinZ + 4, subMaxZ - 4);
        int oppY = EnemyEncounterSpawner.findDryLandSurfaceY(level, oppX, oppZ);
        if (oppY != Integer.MIN_VALUE) {
            return new BlockPos(oppX, oppY, oppZ);
        }

        Warfront.LOGGER.debug("[PATROL] Unable to find valid border waypoint in Region ({}, {}) Sub ({}, {}) from Pos {}", rx, rz, sx, sz, fromPos);
        return null;
    }

    /**
     * Checks whether reinforcement calls in this subregion are currently on cooldown.
     */
    public static boolean isReinforcementOnCooldown(long gameTime, int rx, int rz, int sx, int sz) {
        long subKey = makeSubKey(rx, rz, sx, sz);
        long lastHorn = LAST_REINFORCEMENT_HORN_TIME.getOrDefault(subKey, 0L);
        return gameTime < lastHorn + REINFORCEMENT_COOLDOWN_TICKS;
    }

    /**
     * Called by a Pillager Scout upon concluding a successful spyglass scan on a player.
     * Evaluates current active squads in the subregion and triggers reinforcements per Scenarios 1, 2, or 3.
     */
    public static void triggerScoutReinforcements(ServerLevel level, int rx, int rz, int sx, int sz, Player player) {
        if (level == null || player == null || !player.isAlive()) return;
        if (level.getDifficulty() == Difficulty.PEACEFUL) return;

        RegionData regions = RegionData.get(level);
        RegionData.SubRegionState state = regions.subRegionAt(rx, rz, sx, sz);
        if (state.owner() != Faction.PILLAGER_CONQUERORS) {
            return; // Restrict strictly to pillager regions
        }

        long gameTime = level.getGameTime();
        long subKey = makeSubKey(rx, rz, sx, sz);
        long lastHorn = LAST_REINFORCEMENT_HORN_TIME.getOrDefault(subKey, 0L);
        if (gameTime < lastHorn + REINFORCEMENT_COOLDOWN_TICKS) {
            Warfront.LOGGER.info("[SCOUT REINFORCE] Horn ignored in Subregion ({}, {} - {}, {}): On cooldown ({}s remaining).",
                    rx, rz, sx, sz, (lastHorn + REINFORCEMENT_COOLDOWN_TICKS - gameTime) / 20L);
            return;
        }
        LAST_REINFORCEMENT_HORN_TIME.put(subKey, gameTime);

        List<PatrolSquad> squads = ACTIVE_PATROLS.computeIfAbsent(subKey, k -> new CopyOnWriteArrayList<>());
        squads.removeIf(s -> s.countLiving(level) == 0);
        int currentSquadCount = squads.size();
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
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            return null;
        }

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
