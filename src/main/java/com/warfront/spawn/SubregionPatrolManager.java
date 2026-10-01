package com.warfront.spawn;

import com.warfront.Warfront;
import com.warfront.ai.goal.SubregionPatrolGoal;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
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
    public static final long SPAWN_COOLDOWN_TICKS = 40L * 20L; // 40 seconds = 800 ticks
    public static final double MIN_WAYPOINT_DIST = 48.0D;
    public static final int HOLD_GROUND_DURATION_TICKS = 15 * 20; // 15 seconds = 300 ticks

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

        /**
         * Called when members arrive at the current waypoint. Transitions squad to 15s hold ground.
         */
        public synchronized void notifyArrival(Level level) {
            if (!holdingGround) {
                holdingGround = true;
                holdTicksRemaining = HOLD_GROUND_DURATION_TICKS;
            }
        }

        /**
         * Ticks the squad's hold timer and picks the next waypoint when elapsed.
         */
        public synchronized void tickHoldTimer(ServerLevel level) {
            if (holdingGround) {
                if (--holdTicksRemaining <= 0) {
                    holdingGround = false;
                    BlockPos nextWaypoint = pickNextBorderWaypoint(level, regionX, regionZ, subX, subZ, currentWaypoint);
                    if (nextWaypoint != null) {
                        currentWaypoint = nextWaypoint;
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
                if (target != null && target.isAlive()) {
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

    private SubregionPatrolManager() {
    }

    public static long makeSubKey(int rx, int rz, int sx, int sz) {
        return (ChunkPos.asLong(rx, rz) << 2) | ((sz & 1) << 1) | (sx & 1);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;

        long gameTime = level.getGameTime();
        if (gameTime % EVAL_INTERVAL_TICKS != 0) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

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

        // 3. Evaluate each nearby subregion for spawning
        for (int[] sub : nearbySubRegions.values()) {
            int rx = sub[0];
            int rz = sub[1];
            int sx = sub[2];
            int sz = sub[3];

            RegionData.SubRegionState state = regions.subRegionAt(rx, rz, sx, sz);
            Faction owner = state.owner();
            if (!owner.isAI()) {
                continue; // Only AI hostile factions field patrol squads
            }

            long subKey = makeSubKey(rx, rz, sx, sz);
            List<PatrolSquad> squads = ACTIVE_PATROLS.computeIfAbsent(subKey, k -> new CopyOnWriteArrayList<>());

            if (squads.size() < MAX_SQUADS_PER_SUBREGION) {
                Long lastSpawn = LAST_SPAWN_TIME.get(subKey);
                if (lastSpawn == null || (gameTime - lastSpawn) >= SPAWN_COOLDOWN_TICKS) {
                    PatrolSquad spawned = spawnPatrolSquad(level, regions, rx, rz, sx, sz, owner);
                    if (spawned != null) {
                        squads.add(spawned);
                        LAST_SPAWN_TIME.put(subKey, gameTime);
                        Warfront.LOGGER.info("[PATROL] Spawned squad {} in Subregion ({}, {} - {}, {}). Active squads: {}/{}",
                                spawned.getSquadId(), rx, rz, sx, sz, squads.size(), MAX_SQUADS_PER_SUBREGION);
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

        // Size matches attack roamers (4 to 14)
        int squadSize = Math.clamp(tier.minSpawn() + level.getRandom().nextInt(Math.max(1, tier.maxSpawn() - tier.minSpawn() + 1)), 4, 14);

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

            // Add Player targeting goals (both retaliation when hurt and sighting players)
            if (mob instanceof net.minecraft.world.entity.PathfinderMob pfm) {
                mob.targetSelector.addGoal(1, new HurtByTargetGoal(pfm));
            }
            mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, Player.class, true));

            if (level.addFreshEntity(mob)) {
                mob.addTag("warfront_patrol");
                mob.addTag("warfront_squad_" + squadId);
                mob.getPersistentData().putBoolean("warfront_roaming", true);
                mob.getPersistentData().putBoolean("isMissionMob", false);
                mob.getPersistentData().putUUID("squadId", squadId);

                squad.addMember(mob.getUUID());
                RoamingEntityTracker.registerWandering(mob, rx, rz, sx, sz, faction);
            }
        }

        return squad.countLiving(level) > 0 ? squad : null;
    }

    /**
     * Builds role pool excluding SCOUT and CATAPULT.
     */
    private static List<Object> buildPatrolRolePool(Faction faction, EnemyResistanceTier tier) {
        List<Object> pool = new ArrayList<>();
        if (faction == Faction.PILLAGER_CONQUERORS) {
            for (PillagerEnemyRole role : PillagerEnemyRole.values()) {
                // EXCLUSION: Explicitly exclude SCOUT and CATAPULT
                if (role == PillagerEnemyRole.SCOUT || role == PillagerEnemyRole.CATAPULT) {
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
     * Picks a random surface point on one of the 4 borders of a subregion.
     */
    public static BlockPos pickRandomBorderPoint(ServerLevel level, int rx, int rz, int sx, int sz) {
        int subMinX = rx * RegionData.REGION_SIZE_BLOCKS + sx * SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = rz * RegionData.REGION_SIZE_BLOCKS + sz * SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + SUBREGION_SIZE_BLOCKS - 1;

        for (int attempt = 0; attempt < 16; attempt++) {
            int edge = level.getRandom().nextInt(4);
            int x, z;
            switch (edge) {
                case 0 -> { // North
                    x = subMinX + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
                    z = subMinZ;
                }
                case 1 -> { // South
                    x = subMinX + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
                    z = subMaxZ;
                }
                case 2 -> { // West
                    x = subMinX;
                    z = subMinZ + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
                }
                default -> { // East
                    x = subMaxX;
                    z = subMinZ + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
                }
            }

            int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
            if (y != Integer.MIN_VALUE) {
                return new BlockPos(x, y, z);
            }
        }
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

        for (int attempt = 0; attempt < 25; attempt++) {
            int edge = level.getRandom().nextInt(4);
            int x, z;
            switch (edge) {
                case 0 -> { // North
                    x = subMinX + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
                    z = subMinZ;
                }
                case 1 -> { // South
                    x = subMinX + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
                    z = subMaxZ;
                }
                case 2 -> { // West
                    x = subMinX;
                    z = subMinZ + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
                }
                default -> { // East
                    x = subMaxX;
                    z = subMinZ + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
                }
            }

            int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
            if (y == Integer.MIN_VALUE) continue;

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

        return bestFallback != null ? bestFallback : fromPos;
    }
}
