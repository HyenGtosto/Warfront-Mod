package com.warfront.block.entity;

import com.warfront.entity.BallistaBoltEntity;
import com.warfront.entity.SmokeScreenEntity;
import com.warfront.region.SubRegionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * BlockEntity controlling the automated targeting, horizontal traverse ("Body" bone),
 * vertical elevation ("Ballista" bone), and supersonic bolt firing of the Pillager Giant Ballista.
 */
public class PillagerBallistaBlockEntity extends BlockEntity implements GeoBlockEntity {

    // Slew speeds: 15 deg/sec yaw (0.75 deg/tick), 7.5 deg/sec pitch (0.375 deg/tick)
    public static final float YAW_SPEED_DEG_PER_TICK = 0.75F;
    public static final float PITCH_SPEED_DEG_PER_TICK = 0.375F;

    // Pitch angle limits
    public static final float MIN_PITCH = -25.0F;
    public static final float MAX_PITCH = 35.0F;

    // Firing cooldown: 15 seconds (300 ticks)
    public static final int COOLDOWN_TICKS = 300;

    // Supersonic ballistic parameters
    public static final double MUZZLE_VELOCITY = 7.0D; // 140 m/s
    public static final double GRAVITY = BallistaBoltEntity.GRAVITY; // 0.005 blocks/tick^2

    private static final RawAnimation FIRE_AND_RELOAD_ANIM =
            RawAnimation.begin()
                    .then("shoot", Animation.LoopType.PLAY_ONCE)
                    .then("reload", Animation.LoopType.PLAY_ONCE);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private float currentYaw = 0.0F;
    private float currentPitch = 0.0F;
    private float targetYaw = 0.0F;
    private float targetPitch = 0.0F;

    private float prevYaw = 0.0F;
    private float prevPitch = 0.0F;

    private int reloadCooldown = 0;
    private int fireAnimTicks = 0;
    private boolean hasTarget = false;
    private UUID targetPlayerUuid = null;
    private int scanInterval = 0;

    public PillagerBallistaBlockEntity(BlockPos pos, BlockState state) {
        super(WarfrontBlockEntities.PILLAGER_BALLISTA.get(), pos, state);
    }

    public void serverTick() {
        if (level == null || !(level instanceof ServerLevel serverLevel)) return;

        if (reloadCooldown > 0) reloadCooldown--;
        if (fireAnimTicks > 0) fireAnimTicks--;

        scanInterval++;
        if (scanInterval >= 20 || targetPlayerUuid == null) {
            scanInterval = 0;
            acquireTarget(serverLevel);
        }

        ServerPlayer targetPlayer = null;
        if (targetPlayerUuid != null) {
            targetPlayer = serverLevel.getServer().getPlayerList().getPlayer(targetPlayerUuid);
            if (targetPlayer == null || !isValidTarget(serverLevel, targetPlayer)) {
                targetPlayerUuid = null;
                hasTarget = false;
            }
        }

        if (targetPlayer != null) {
            this.hasTarget = true;
            calculateAimAngles(targetPlayer);
        }

        this.prevYaw = this.currentYaw;
        this.prevPitch = this.currentPitch;

        float yawDiff = Mth.degreesDifference(this.currentYaw, this.targetYaw);
        this.currentYaw = Mth.wrapDegrees(this.currentYaw + Mth.clamp(yawDiff, -YAW_SPEED_DEG_PER_TICK, YAW_SPEED_DEG_PER_TICK));
        this.currentPitch = Mth.approach(this.currentPitch, this.targetPitch, PITCH_SPEED_DEG_PER_TICK);

        if (hasTarget && reloadCooldown <= 0) {
            float absYawDiff = Math.abs(Mth.degreesDifference(this.currentYaw, this.targetYaw));
            float absPitchDiff = Math.abs(this.currentPitch - this.targetPitch);

            if (absYawDiff <= YAW_SPEED_DEG_PER_TICK && absPitchDiff <= PITCH_SPEED_DEG_PER_TICK) {
                this.currentYaw = this.targetYaw;
                this.currentPitch = this.targetPitch;
                fireBallista(serverLevel);
            }
        }

        if (scanInterval % 5 == 0) {
            setChanged();
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void clientTick() {
        this.prevYaw = this.currentYaw;
        this.prevPitch = this.currentPitch;

        float yawDiff = Mth.degreesDifference(this.currentYaw, this.targetYaw);
        this.currentYaw = Mth.wrapDegrees(this.currentYaw + Mth.clamp(yawDiff, -YAW_SPEED_DEG_PER_TICK, YAW_SPEED_DEG_PER_TICK));
        this.currentPitch = Mth.approach(this.currentPitch, this.targetPitch, PITCH_SPEED_DEG_PER_TICK);

        if (fireAnimTicks > 0) {
            fireAnimTicks--;
        }
    }

    private void acquireTarget(ServerLevel serverLevel) {
        ServerPlayer bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;

        for (ServerPlayer player : serverLevel.players()) {
            if (!isValidTarget(serverLevel, player)) continue;

            double distSq = player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.5625D, worldPosition.getZ() + 0.5D);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestTarget = player;
            }
        }

        if (bestTarget != null) {
            this.targetPlayerUuid = bestTarget.getUUID();
        } else {
            this.targetPlayerUuid = null;
            this.hasTarget = false;
        }
    }

    private boolean isValidTarget(ServerLevel serverLevel, ServerPlayer player) {
        if (!player.isAlive() || player.isCreative() || player.isSpectator()) {
            return false;
        }

        int chunkX = Math.floorDiv(worldPosition.getX(), 16);
        int chunkZ = Math.floorDiv(worldPosition.getZ(), 16);
        SubRegionPos ballistaSub = SubRegionPos.fromChunk(chunkX, chunkZ);
        int bGlobalX = ballistaSub.regionX() * 2 + ballistaSub.subX();
        int bGlobalZ = ballistaSub.regionZ() * 2 + ballistaSub.subZ();

        int pChunkX = Math.floorDiv(player.getBlockX(), 16);
        int pChunkZ = Math.floorDiv(player.getBlockZ(), 16);
        SubRegionPos playerSub = SubRegionPos.fromChunk(pChunkX, pChunkZ);
        int pGlobalX = playerSub.regionX() * 2 + playerSub.subX();
        int pGlobalZ = playerSub.regionZ() * 2 + playerSub.subZ();

        if (Math.abs(pGlobalX - bGlobalX) > 1 || Math.abs(pGlobalZ - bGlobalZ) > 1) {
            return false;
        }

        Vec3 muzzlePos = new Vec3(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.5625D, worldPosition.getZ() + 0.5D);
        double distSq = player.distanceToSqr(muzzlePos);
        // Minimum range: 8 blocks; Maximum range: 160 blocks
        if (distSq < 64.0D || distSq > 25600.0D) {
            return false;
        }

        // Mechanical vertical elevation check (pitch reachability)
        double dx = player.getX() - muzzlePos.x;
        double dz = player.getZ() - muzzlePos.z;
        double dy = (player.getY() + player.getBbHeight() * 0.70D) - muzzlePos.y;
        double distHoriz = Math.sqrt(dx * dx + dz * dz);
        if (distHoriz < 1.0E-4D) return false;
        double flightTicks = distHoriz / MUZZLE_VELOCITY;
        double dropComp = 0.5D * GRAVITY * flightTicks * flightTicks;
        double reqPitch = Math.toDegrees(Math.atan2(dy + dropComp, distHoriz));
        if (reqPitch < MIN_PITCH || reqPitch > MAX_PITCH) {
            return false; // Target is physically outside mechanical vertical elevation bounds
        }

        Vec3 targetEye = player.getEyePosition();
        if (SmokeScreenEntity.isInsideSmoke(player.position())
                || SmokeScreenEntity.isInsideSmoke(muzzlePos)
                || SmokeScreenEntity.isObstructedBySmoke(muzzlePos, targetEye)) {
            return false;
        }

        return hasLineOfSight(serverLevel, muzzlePos, targetEye);
    }

    private boolean hasLineOfSight(ServerLevel serverLevel, Vec3 start, Vec3 end) {
        Vec3 dir = end.subtract(start);
        double totalDist = dir.length();
        if (totalDist < 1.0D) return true;
        Vec3 unitDir = dir.scale(1.0D / totalDist);
        // Start outside the ballista's own 3x3 block footprint
        Vec3 rayStart = start.add(unitDir.scale(1.8D));
        if (rayStart.distanceToSqr(start) >= end.distanceToSqr(start)) {
            return true;
        }

        for (int attempt = 0; attempt < 8; attempt++) {
            BlockHitResult hit = serverLevel.clip(new ClipContext(
                    rayStart,
                    end,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    CollisionContext.empty()
            ));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
            BlockPos hitPos = hit.getBlockPos();
            if (Math.abs(hitPos.getX() - worldPosition.getX()) <= 1
                    && Math.abs(hitPos.getY() - worldPosition.getY()) <= 2
                    && Math.abs(hitPos.getZ() - worldPosition.getZ()) <= 1) {
                rayStart = hit.getLocation().add(unitDir.scale(0.25D));
                continue;
            }
            BlockState hitState = serverLevel.getBlockState(hitPos);
            if (isPassableFoliage(serverLevel, hitState, hitPos)) {
                rayStart = hit.getLocation().add(unitDir.scale(0.2D));
                if (rayStart.distanceToSqr(start) >= end.distanceToSqr(start)) {
                    return true;
                }
            } else {
                return false;
            }
        }
        return false;
    }

    private static boolean isPassableFoliage(ServerLevel level, BlockState state, BlockPos pos) {
        if (state.isAir() || !state.blocksMotion() || state.canBeReplaced()) {
            return true;
        }
        if (state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS) || state.is(BlockTags.CROPS)) {
            return true;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    private void calculateAimAngles(ServerPlayer player) {
        double muzzleX = worldPosition.getX() + 0.5D;
        double muzzleY = worldPosition.getY() + 1.5625D;
        double muzzleZ = worldPosition.getZ() + 0.5D;

        double rawDx = player.getX() - muzzleX;
        double rawDz = player.getZ() - muzzleZ;
        double rawDist = Math.sqrt(rawDx * rawDx + rawDz * rawDz);
        double flightTicks = rawDist / MUZZLE_VELOCITY;

        // Target velocity lead compensation (100% velocity vector without under-lead)
        Vec3 playerVel = player.getDeltaMovement();
        double targetX = player.getX() + playerVel.x * flightTicks;
        double targetY = player.getY() + player.getBbHeight() * 0.70D;
        double targetZ = player.getZ() + playerVel.z * flightTicks;

        double dx = targetX - muzzleX;
        double dz = targetZ - muzzleZ;
        double dy = targetY - muzzleY;
        double distHoriz = Math.sqrt(dx * dx + dz * dz);

        // Yaw angle (Ballista faces North -Z at yaw 0: North=0, East=+90, South=180, West=-90)
        this.targetYaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dx, -dz)));

        // Direct-fire flat ballistic elevation with gravity drop compensation
        double dropCompensation = 0.5D * GRAVITY * flightTicks * flightTicks;
        double pitchRad = Math.atan2(dy + dropCompensation, Math.max(1.0D, distHoriz));
        float rawPitch = (float) Math.toDegrees(pitchRad);
        if (rawPitch < MIN_PITCH || rawPitch > MAX_PITCH) {
            this.hasTarget = false;
            this.targetPlayerUuid = null;
            return;
        }
        this.targetPitch = rawPitch;
    }

    private void fireBallista(ServerLevel serverLevel) {
        double pitchRad = Math.toRadians(this.currentPitch);
        double yawRad = Math.toRadians(this.currentYaw);

        double dirX = Math.sin(yawRad) * Math.cos(pitchRad);
        double dirY = Math.sin(pitchRad);
        double dirZ = -Math.cos(yawRad) * Math.cos(pitchRad);

        double vx = dirX * MUZZLE_VELOCITY;
        double vy = dirY * MUZZLE_VELOCITY;
        double vz = dirZ * MUZZLE_VELOCITY;

        double spawnOffset = 1.6D;
        double spawnX = worldPosition.getX() + 0.5D + dirX * spawnOffset;
        double spawnY = worldPosition.getY() + 1.5625D + dirY * spawnOffset;
        double spawnZ = worldPosition.getZ() + 0.5D + dirZ * spawnOffset;

        BallistaBoltEntity bolt = new BallistaBoltEntity(serverLevel, spawnX, spawnY, spawnZ, true, null);
        bolt.setDeltaMovement(vx, vy, vz);
        serverLevel.addFreshEntity(bolt);

        // Supersonic whipcrack & mechanical release audio
        serverLevel.playSound(null, worldPosition, SoundEvents.CROSSBOW_SHOOT, SoundSource.BLOCKS, 6.0F, 0.55F);
        serverLevel.playSound(null, worldPosition, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.BLOCKS, 4.5F, 1.35F);
        serverLevel.playSound(null, worldPosition, SoundEvents.IRON_GOLEM_ATTACK, SoundSource.BLOCKS, 3.5F, 0.75F);

        // Muzzle sonic boom ring & dust burst
        serverLevel.sendParticles(ParticleTypes.SONIC_BOOM, spawnX, spawnY, spawnZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        serverLevel.sendParticles(ParticleTypes.CLOUD, spawnX, spawnY, spawnZ, 14, 0.25D, 0.25D, 0.25D, 0.08D);
        serverLevel.sendParticles(ParticleTypes.CRIT, spawnX, spawnY, spawnZ, 10, 0.2D, 0.2D, 0.2D, 0.15D);

        this.fireAnimTicks = 85; // 0.25s shoot (5t) + 4.0s reload (80t)
        this.reloadCooldown = COOLDOWN_TICKS;

        triggerAnim("fire_controller", "fire_cycle");

        setChanged();
        serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public float getYaw(float partialTicks) {
        return Mth.rotLerp(partialTicks, this.prevYaw, this.currentYaw);
    }

    public float getPitch(float partialTicks) {
        return Mth.lerp(partialTicks, this.prevPitch, this.currentPitch);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("CurrentYaw", this.currentYaw);
        tag.putFloat("CurrentPitch", this.currentPitch);
        tag.putFloat("TargetYaw", this.targetYaw);
        tag.putFloat("TargetPitch", this.targetPitch);
        tag.putInt("ReloadCooldown", this.reloadCooldown);
        tag.putInt("FireAnimTicks", this.fireAnimTicks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.currentYaw = tag.getFloat("CurrentYaw");
        this.currentPitch = tag.getFloat("CurrentPitch");
        this.targetYaw = tag.getFloat("TargetYaw");
        this.targetPitch = tag.getFloat("TargetPitch");
        this.reloadCooldown = tag.getInt("ReloadCooldown");
        this.fireAnimTicks = tag.getInt("FireAnimTicks");
        this.prevYaw = this.currentYaw;
        this.prevPitch = this.currentPitch;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putFloat("CurrentYaw", this.currentYaw);
        tag.putFloat("CurrentPitch", this.currentPitch);
        tag.putFloat("TargetYaw", this.targetYaw);
        tag.putFloat("TargetPitch", this.targetPitch);
        tag.putInt("FireAnimTicks", this.fireAnimTicks);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider lookupProvider) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            float newYaw = tag.getFloat("CurrentYaw");
            float newPitch = tag.getFloat("CurrentPitch");
            this.targetYaw = tag.getFloat("TargetYaw");
            this.targetPitch = tag.getFloat("TargetPitch");
            int newFireTicks = tag.getInt("FireAnimTicks");
            if (newFireTicks > 0 && this.fireAnimTicks <= 0) {
                triggerAnim("fire_controller", "fire_cycle");
            }
            this.fireAnimTicks = newFireTicks;

            if (Math.abs(Mth.degreesDifference(this.currentYaw, newYaw)) > 5.0F) {
                this.currentYaw = newYaw;
                this.prevYaw = newYaw;
            }
            if (Math.abs(this.currentPitch - newPitch) > 5.0F) {
                this.currentPitch = newPitch;
                this.prevPitch = newPitch;
            }
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "fire_controller", 0, state -> {
            if (this.fireAnimTicks > 0) {
                return state.setAndContinue(FIRE_AND_RELOAD_ANIM);
            }
            state.getController().stop();
            return PlayState.STOP;
        }).triggerableAnim("fire_cycle", FIRE_AND_RELOAD_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
