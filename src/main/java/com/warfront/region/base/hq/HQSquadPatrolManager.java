package com.warfront.region.base.hq;

import com.warfront.Warfront;
import com.warfront.entity.*;
import com.warfront.entity.ai.goal.HQGroundPatrolGoal;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.base.PillagerHeadquartersGenerator;
import com.warfront.region.base.garrison.PillagerHQGarrisonDefinition;
import com.warfront.spawn.EnemyEncounterSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages dedicated Pillager Headquarters ground patrol squads.
 *
 * <p>Mechanics:</p>
 * <ul>
 *   <li>Each active Headquarters deploys up to 2 concurrent patrol squads (Clockwise & Counter-Clockwise).</li>
 *   <li>Each squad consists of 5 troops: 1 Armored Elite leader, 2 Marksmen/Riflemen
 *       (scaled via quadratic resistance probability $P(R) = (R/100)^2$), and 2 Warriors.</li>
 *   <li>Squads patrol the 4-corner exterior perimeter and, upon reaching gate approaches, have a 35%
 *       chance to enter and traverse the courtyard via ground paths.</li>
 *   <li>Gate passage triggers dynamic portcullis opening and closing via {@link HQGatePortcullisController}.</li>
 *   <li>60-second respawn cooldown upon full squad wipeout.</li>
 * </ul>
 */
public final class HQSquadPatrolManager {

    public static final int MAX_SQUADS_PER_HQ = 2;
    public static final int SQUAD_SIZE = 5;
    public static final long SQUAD_RESPAWN_COOLDOWN_TICKS = 60L * 20L; // 60 seconds
    public static final double PLAYER_SCAN_DIST_SQ = 160.0D * 160.0D;
    public static final float COURTYARD_ENTRY_CHANCE = 0.35F;

    private static final int[][] FORMATION_OFFSETS = new int[][]{
            {  0,  0 }, // Leader (Elite)
            { -1, -2 }, // Ranged 1
            {  1, -2 }, // Ranged 2
            { -1, -4 }, // Warrior 1
            {  1, -4 }  // Warrior 2
    };

    public enum PatrolMode {
        PERIMETER,
        COURTYARD_DETOUR
    }

    public static class HQSquad {
        private final UUID squadId;
        private final BlockPos hqAnchor;
        private final boolean clockwise;
        private final List<UUID> memberUuids = new CopyOnWriteArrayList<>();
        private UUID leaderUuid;

        private PatrolMode mode = PatrolMode.PERIMETER;
        private List<BlockPos> currentRoute = new ArrayList<>();
        private int routeIndex = 0;
        private BlockPos currentWaypoint = null;
        private LivingEntity sharedTarget = null;
        private int arrivalCooldown = 0;

        public HQSquad(UUID squadId, BlockPos hqAnchor, boolean clockwise) {
            this.squadId = squadId;
            this.hqAnchor = hqAnchor;
            this.clockwise = clockwise;
        }

        public UUID getSquadId() {
            return squadId;
        }

        public BlockPos getHqAnchor() {
            return hqAnchor;
        }

        public boolean isLeader(Mob mob) {
            return leaderUuid != null && leaderUuid.equals(mob.getUUID());
        }

        public BlockPos getCurrentWaypoint() {
            return currentWaypoint;
        }

        public void clearTarget() {
            this.sharedTarget = null;
        }

        public LivingEntity getSquadTarget(net.minecraft.world.level.Level level) {
            if (sharedTarget != null && !sharedTarget.isAlive()) {
                sharedTarget = null;
            }
            return sharedTarget;
        }

        public void alertSquadToTarget(net.minecraft.world.level.Level level, LivingEntity target) {
            if (target == null || !target.isAlive()) return;
            this.sharedTarget = target;
            if (level instanceof ServerLevel serverLevel) {
                for (UUID id : memberUuids) {
                    Entity e = serverLevel.getEntity(id);
                    if (e instanceof Mob mob && mob.isAlive() && (mob.getTarget() == null || !mob.getTarget().isAlive())) {
                        mob.setTarget(target);
                    }
                }
            }
        }

        public boolean isNearGate(BlockPos pos) {
            if (pos == null) return false;
            HQGatePortcullisController controller = CONTROLLER_REF;
            if (controller == null) return false;

            HQGatePortcullisController.GateState n = controller.getGate(HQGatePortcullisController.GateId.NORTH);
            if (n != null && n.getCenterPos().distSqr(pos) <= 36.0D) return true;

            HQGatePortcullisController.GateState s = controller.getGate(HQGatePortcullisController.GateId.SOUTH);
            return s != null && s.getCenterPos().distSqr(pos) <= 36.0D;
        }

        private HQGatePortcullisController CONTROLLER_REF;

        public void setControllerRef(HQGatePortcullisController controller) {
            this.CONTROLLER_REF = controller;
        }

        public void initRoute(ServerLevel level) {
            this.mode = PatrolMode.PERIMETER;
            this.currentRoute = buildPerimeterRoute(level, hqAnchor, clockwise);
            this.routeIndex = 0;
            if (!currentRoute.isEmpty()) {
                this.currentWaypoint = currentRoute.get(0);
            }
        }

        public void tickLeader(Mob leaderMob) {
            if (currentWaypoint == null || currentRoute.isEmpty()) return;

            if (arrivalCooldown > 0) {
                arrivalCooldown--;
                return;
            }

            double dx = leaderMob.getX() - (currentWaypoint.getX() + 0.5D);
            double dz = leaderMob.getZ() - (currentWaypoint.getZ() + 0.5D);
            double distSq2D = dx * dx + dz * dz;

            if (distSq2D <= 9.0D) {
                advanceWaypoint((ServerLevel) leaderMob.level(), leaderMob);
            }
        }

        private void advanceWaypoint(ServerLevel level, Mob leaderMob) {
            HQGatePortcullisController controller = HQGatePortcullisController.getOrCreate(level, hqAnchor);

            if (mode == PatrolMode.PERIMETER) {
                // Check if current waypoint is a gate approach
                boolean isNorthApproach = isNearPos(currentWaypoint, toWorldPos(level, hqAnchor, 25, -4));
                boolean isSouthApproach = isNearPos(currentWaypoint, toWorldPos(level, hqAnchor, 25, 96));

                if ((isNorthApproach || isSouthApproach) && level.getRandom().nextFloat() < COURTYARD_ENTRY_CHANCE) {
                    // Enter courtyard detour!
                    this.mode = PatrolMode.COURTYARD_DETOUR;
                    this.currentRoute = buildCourtyardDetourRoute(level, hqAnchor, isNorthApproach);
                    this.routeIndex = 0;
                    this.currentWaypoint = currentRoute.get(0);
                    this.arrivalCooldown = 20;

                    // Trigger gate opening for initial gate approach
                    HQGatePortcullisController.GateId enterGate = isNorthApproach
                            ? HQGatePortcullisController.GateId.NORTH
                            : HQGatePortcullisController.GateId.SOUTH;
                    controller.requestOpen(level, enterGate, squadId);
                    return;
                }

                // Standard perimeter progression
                routeIndex = (routeIndex + 1) % currentRoute.size();
                currentWaypoint = currentRoute.get(routeIndex);
                arrivalCooldown = 15;

            } else {
                // Inside Courtyard Detour
                // Route Indices:
                // 0: Entry Outer Archway -> requested open
                // 1: Entry Inner Archway -> trigger close of entry gate
                // 2: Plaza 1
                // 3: Center Courtyard Road
                // 4: Plaza 2
                // 5: Exit Inner Archway -> trigger open of exit gate
                // 6: Exit Outer Archway -> trigger close of exit gate & resume perimeter

                if (routeIndex == 0) {
                    // Passed entry archway into courtyard -> close entry gate
                    boolean isNorthEntry = isNearPos(currentWaypoint, toWorldPos(level, hqAnchor, 25, 6));
                    HQGatePortcullisController.GateId enterGate = isNorthEntry
                            ? HQGatePortcullisController.GateId.NORTH
                            : HQGatePortcullisController.GateId.SOUTH;
                    controller.requestClose(level, enterGate, squadId);
                } else if (routeIndex == 4) {
                    // Approaching exit gate from inside -> open exit gate
                    boolean isSouthExit = isNearPos(currentRoute.get(5), toWorldPos(level, hqAnchor, 25, 86));
                    HQGatePortcullisController.GateId exitGate = isSouthExit
                            ? HQGatePortcullisController.GateId.SOUTH
                            : HQGatePortcullisController.GateId.NORTH;
                    controller.requestOpen(level, exitGate, squadId);
                } else if (routeIndex == 5) {
                    // Crossed exit gate into outer perimeter -> close exit gate
                    boolean isSouthExit = isNearPos(currentWaypoint, toWorldPos(level, hqAnchor, 25, 96));
                    HQGatePortcullisController.GateId exitGate = isSouthExit
                            ? HQGatePortcullisController.GateId.SOUTH
                            : HQGatePortcullisController.GateId.NORTH;
                    controller.requestClose(level, exitGate, squadId);

                    // Resume exterior perimeter route
                    this.mode = PatrolMode.PERIMETER;
                    this.currentRoute = buildPerimeterRoute(level, hqAnchor, clockwise);
                    this.routeIndex = isSouthExit ? (clockwise ? 5 : 3) : (clockwise ? 1 : 7);
                    this.currentWaypoint = currentRoute.get(routeIndex);
                    this.arrivalCooldown = 20;
                    return;
                }

                routeIndex++;
                if (routeIndex < currentRoute.size()) {
                    currentWaypoint = currentRoute.get(routeIndex);
                    arrivalCooldown = 15;
                } else {
                    // Safety return to perimeter
                    this.mode = PatrolMode.PERIMETER;
                    this.currentRoute = buildPerimeterRoute(level, hqAnchor, clockwise);
                    this.routeIndex = 0;
                    this.currentWaypoint = currentRoute.get(0);
                }
            }
        }

        public int countLiving(ServerLevel level) {
            int living = 0;
            UUID activeLeader = null;
            for (UUID id : memberUuids) {
                Entity e = level.getEntity(id);
                if (e instanceof Mob mob && mob.isAlive()) {
                    living++;
                    if (id.equals(leaderUuid)) {
                        activeLeader = id;
                    }
                }
            }

            // Promote successor if leader died
            if (activeLeader == null && living > 0) {
                for (UUID id : memberUuids) {
                    Entity e = level.getEntity(id);
                    if (e instanceof Mob mob && mob.isAlive()) {
                        leaderUuid = id;
                        break;
                    }
                }
            }

            return living;
        }

        public void disband(ServerLevel level) {
            for (UUID id : memberUuids) {
                Entity e = level.getEntity(id);
                if (e instanceof Mob mob) {
                    mob.discard();
                }
            }
            memberUuids.clear();
        }
    }

    private static final Map<BlockPos, List<HQSquad>> ACTIVE_HQ_SQUADS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> SQUAD_COOLDOWNS = new ConcurrentHashMap<>();

    private HQSquadPatrolManager() {
    }

    public static void cleanup() {
        ACTIVE_HQ_SQUADS.clear();
        SQUAD_COOLDOWNS.clear();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;

        long gameTime = level.getGameTime();
        if (gameTime % 20L != 0L) return; // 1-second cadence

        // Peaceful mode: cleanup all squads
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            for (List<HQSquad> list : ACTIVE_HQ_SQUADS.values()) {
                for (HQSquad squad : list) {
                    squad.disband(level);
                }
            }
            ACTIVE_HQ_SQUADS.clear();
            return;
        }

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        RegionData regions = RegionData.get(level);
        if (regions == null) return;

        // Collect placed, AI-owned Headquarters near players
        Set<BlockPos> activeHqAnchors = new HashSet<>();
        for (RegionData.Region reg : regions.getAllPlacedBaseRegions()) {
            if (reg.baseType() != BaseType.HEADQUARTERS || reg.owner() != Faction.PILLAGER_CONQUERORS) {
                continue;
            }

            BlockPos anchor = reg.baseAnchor();
            if (anchor == null) continue;

            boolean nearPlayer = false;
            for (ServerPlayer player : players) {
                if (player.distanceToSqr(anchor.getX() + 0.5D, anchor.getY() + 0.5D, anchor.getZ() + 0.5D) <= PLAYER_SCAN_DIST_SQ) {
                    nearPlayer = true;
                    break;
                }
            }

            if (nearPlayer) {
                activeHqAnchors.add(anchor.immutable());
            }
        }

        // Fast O(1) 3x3 region neighborhood scan around each player for immediate discovery
        for (ServerPlayer player : players) {
            int prx = Math.floorDiv(player.getBlockX(), RegionData.REGION_SIZE_BLOCKS);
            int prz = Math.floorDiv(player.getBlockZ(), RegionData.REGION_SIZE_BLOCKS);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    RegionData.Region nearReg = regions.regionAt(prx + dx, prz + dz);
                    if (nearReg.baseType() == BaseType.HEADQUARTERS && nearReg.owner() == Faction.PILLAGER_CONQUERORS) {
                        BlockPos anchor = nearReg.baseAnchor();
                        if (anchor != null && nearReg.basePlaced()) {
                            activeHqAnchors.add(anchor.immutable());
                        }
                    }
                }
            }
        }

        // Clean up squads for HQs that are no longer active
        ACTIVE_HQ_SQUADS.keySet().removeIf(anchor -> {
            if (!activeHqAnchors.contains(anchor)) {
                List<HQSquad> squads = ACTIVE_HQ_SQUADS.get(anchor);
                if (squads != null) {
                    for (HQSquad s : squads) s.disband(level);
                }
                return true;
            }
            return false;
        });

        // Tick active HQs
        for (BlockPos anchor : activeHqAnchors) {
            HQGatePortcullisController controller = HQGatePortcullisController.getOrCreate(level, anchor);
            controller.tick(level);

            List<HQSquad> squads = ACTIVE_HQ_SQUADS.computeIfAbsent(anchor, a -> new CopyOnWriteArrayList<>());

            // Purge dead squads
            squads.removeIf(s -> {
                if (s.countLiving(level) == 0) {
                    SQUAD_COOLDOWNS.put(anchor, gameTime);
                    Warfront.LOGGER.info("[HQ SQUAD] Squad {} at HQ {} eliminated. Cooldown started.", s.getSquadId(), anchor);
                    return true;
                }
                return false;
            });

            // Evaluate squad spawning
            if (squads.size() < MAX_SQUADS_PER_HQ) {
                Long lastWipeout = SQUAD_COOLDOWNS.get(anchor);
                if (lastWipeout == null || (gameTime - lastWipeout) >= SQUAD_RESPAWN_COOLDOWN_TICKS) {
                    boolean clockwise = squads.isEmpty();
                    HQSquad newSquad = spawnHQSquad(level, anchor, clockwise);
                    if (newSquad != null) {
                        newSquad.setControllerRef(controller);
                        squads.add(newSquad);
                        Warfront.LOGGER.info("[HQ SQUAD] Deployed HQ patrol squad {} ({} mode) at {}.",
                                newSquad.getSquadId(), clockwise ? "Clockwise" : "Counter-Clockwise", anchor);
                    }
                }
            }
        }
    }

    private static HQSquad spawnHQSquad(ServerLevel level, BlockPos hqAnchor, boolean clockwise) {
        RegionData regions = RegionData.get(level);
        int rx = Math.floorDiv(hqAnchor.getX(), RegionData.REGION_SIZE_BLOCKS);
        int rz = Math.floorDiv(hqAnchor.getZ(), RegionData.REGION_SIZE_BLOCKS);
        float resistance = (regions != null) ? regions.calculateEffectiveResistance(rx, rz) : 0.0F;

        // Spawn origin: outside North gate for clockwise, outside South gate for counter-clockwise
        BlockPos spawnOrigin = clockwise
                ? toWorldPos(level, hqAnchor, 25, -4)
                : toWorldPos(level, hqAnchor, 25, 96);

        if (!level.hasChunkAt(spawnOrigin)) {
            return null;
        }

        UUID squadId = UUID.randomUUID();
        HQSquad squad = new HQSquad(squadId, hqAnchor, clockwise);
        squad.initRoute(level);

        float clampedR = Math.clamp(resistance, 0.0F, 100.0F);
        float pRifleman = (clampedR / 100.0F) * (clampedR / 100.0F);

        for (int i = 0; i < SQUAD_SIZE; i++) {
            Mob mob;
            if (i == 0) {
                // Squad Leader: Armored Elite
                mob = ModEntities.PILLAGER_ELITE.get().create(level);
            } else if (i == 1 || i == 2) {
                // Ranged: Marksman with quadratic upgrade to Rifleman
                boolean upgrade = level.getRandom().nextFloat() < pRifleman;
                mob = upgrade
                        ? ModEntities.PILLAGER_RIFLEMAN.get().create(level)
                        : ModEntities.PILLAGER_MARKSMAN.get().create(level);
            } else {
                // Melee: Pillager Warrior
                mob = ModEntities.PILLAGER_WARRIOR.get().create(level);
            }

            if (mob == null) continue;

            int ox = FORMATION_OFFSETS[i][0];
            int oz = FORMATION_OFFSETS[i][1];
            int spawnX = spawnOrigin.getX() + ox;
            int spawnZ = spawnOrigin.getZ() + oz;
            int spawnY = EnemyEncounterSpawner.findDryLandSurfaceY(level, spawnX, spawnZ);
            if (spawnY == Integer.MIN_VALUE) {
                spawnY = spawnOrigin.getY();
            }

            mob.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, 0.0F, 0.0F);
            EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);
            mob.setPersistenceRequired();
            mob.addTag("warfront_hq_patrol");
            mob.addTag("warfront_squad_" + squadId);

            mob.goalSelector.getAvailableGoals().removeIf(w -> w.getGoal() instanceof WaterAvoidingRandomStrollGoal);
            mob.goalSelector.addGoal(1, new HQGroundPatrolGoal(mob, squad, ox, oz));

            if (level.addFreshEntity(mob)) {
                squad.memberUuids.add(mob.getUUID());
                if (i == 0) {
                    squad.leaderUuid = mob.getUUID();
                }
            }
        }

        return squad.memberUuids.isEmpty() ? null : squad;
    }

    public static BlockPos toWorldPos(ServerLevel level, BlockPos hqAnchor, int lx, int lz) {
        boolean isEastWest = PillagerHeadquartersGenerator.isEastWest(hqAnchor);
        BlockPos shellBoxMin = PillagerHeadquartersGenerator.getShellBoxMin(hqAnchor);
        Direction targetShellFacing = isEastWest ? Direction.SOUTH : Direction.WEST;
        Rotation shellRot = PillagerHeadquartersGenerator.getRotationBetween(
                PillagerHeadquartersGenerator.SHELL_INITIAL_FACING, targetShellFacing);
        BlockPos shellOrigin = PillagerHeadquartersGenerator.getRotatedOrigin(
                shellBoxMin, PillagerHQGarrisonDefinition.SHELL_SIZE, shellRot);

        BlockPos rawWorld = shellOrigin.offset(
                StructureTemplate.transform(new BlockPos(lx, 1, lz), Mirror.NONE, shellRot, BlockPos.ZERO));

        // Inside fortress shell (ground is flat at hqAnchor Y level)
        if (lx >= 0 && lx <= 51 && lz >= 0 && lz <= 92) {
            return new BlockPos(rawWorld.getX(), hqAnchor.getY() + 1, rawWorld.getZ());
        }

        // Outside fortress shell: sample surface ground
        int groundY = EnemyEncounterSpawner.findDryLandSurfaceY(level, rawWorld.getX(), rawWorld.getZ());
        if (groundY == Integer.MIN_VALUE) {
            groundY = hqAnchor.getY() + 1;
        }
        return new BlockPos(rawWorld.getX(), groundY, rawWorld.getZ());
    }

    private static List<BlockPos> buildPerimeterRoute(ServerLevel level, BlockPos hqAnchor, boolean clockwise) {
        List<BlockPos> list = new ArrayList<>(8);
        if (clockwise) {
            list.add(toWorldPos(level, hqAnchor, -4, -4)); // NW
            list.add(toWorldPos(level, hqAnchor, 25, -4)); // North Approach
            list.add(toWorldPos(level, hqAnchor, 56, -4)); // NE
            list.add(toWorldPos(level, hqAnchor, 56, 46)); // East Flank
            list.add(toWorldPos(level, hqAnchor, 56, 96)); // SE
            list.add(toWorldPos(level, hqAnchor, 25, 96)); // South Approach
            list.add(toWorldPos(level, hqAnchor, -4, 96)); // SW
            list.add(toWorldPos(level, hqAnchor, -4, 46)); // West Flank
        } else {
            list.add(toWorldPos(level, hqAnchor, -4, -4)); // NW
            list.add(toWorldPos(level, hqAnchor, -4, 46)); // West Flank
            list.add(toWorldPos(level, hqAnchor, -4, 96)); // SW
            list.add(toWorldPos(level, hqAnchor, 25, 96)); // South Approach
            list.add(toWorldPos(level, hqAnchor, 56, 96)); // SE
            list.add(toWorldPos(level, hqAnchor, 56, 46)); // East Flank
            list.add(toWorldPos(level, hqAnchor, 56, 56)); // NE
            list.add(toWorldPos(level, hqAnchor, 25, -4)); // North Approach
        }
        return list;
    }

    private static List<BlockPos> buildCourtyardDetourRoute(ServerLevel level, BlockPos hqAnchor, boolean enterFromNorth) {
        List<BlockPos> list = new ArrayList<>(7);
        if (enterFromNorth) {
            list.add(toWorldPos(level, hqAnchor, 25, 0));  // 0: Approach North Gate Outer Archway
            list.add(toWorldPos(level, hqAnchor, 25, 6));  // 1: North Gate Inner Archway (Inside)
            list.add(toWorldPos(level, hqAnchor, 25, 20)); // 2: North Courtyard Plaza
            list.add(toWorldPos(level, hqAnchor, 25, 46)); // 3: Central Courtyard Road
            list.add(toWorldPos(level, hqAnchor, 25, 72)); // 4: South Courtyard Plaza
            list.add(toWorldPos(level, hqAnchor, 25, 86)); // 5: South Gate Inner Archway
            list.add(toWorldPos(level, hqAnchor, 25, 96)); // 6: South Gate Outer Approach (Exit)
        } else {
            list.add(toWorldPos(level, hqAnchor, 25, 92)); // 0: Approach South Gate Outer Archway
            list.add(toWorldPos(level, hqAnchor, 25, 86)); // 1: South Gate Inner Archway (Inside)
            list.add(toWorldPos(level, hqAnchor, 25, 72)); // 2: South Courtyard Plaza
            list.add(toWorldPos(level, hqAnchor, 25, 46)); // 3: Central Courtyard Road
            list.add(toWorldPos(level, hqAnchor, 25, 20)); // 4: North Courtyard Plaza
            list.add(toWorldPos(level, hqAnchor, 25, 6));  // 5: North Gate Inner Archway
            list.add(toWorldPos(level, hqAnchor, 25, -4)); // 6: North Gate Outer Approach (Exit)
        }
        return list;
    }

    private static boolean isNearPos(BlockPos p1, BlockPos p2) {
        if (p1 == null || p2 == null) return false;
        return p1.distSqr(p2) <= 16.0D;
    }
}
