package com.warfront.block.entity;

import com.warfront.entity.ArtilleryShellEntity;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.region.RegionData;
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
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * BlockEntity controlling the automated targeting, rotation, elevation, and ballistic
 * firing logic of the Pillager Heavy Mortar.
 */
public class MortarBlockEntity extends BlockEntity implements GeoBlockEntity {

    // Speeds: 10 deg/sec rotation (0.5 deg/tick), 5 deg/sec pitch (0.25 deg/tick)
    public static final float YAW_SPEED_DEG_PER_TICK = 0.5F;
    public static final float PITCH_SPEED_DEG_PER_TICK = 0.25F;

    // Cooldown: 15 seconds (300 ticks)
    public static final int COOLDOWN_TICKS = 300;

    // Ballistic parameters (Range ~180 blocks)
    public static final double MUZZLE_VELOCITY = 3.0D; // 60 m/s
    public static final double GRAVITY = 0.05D;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Dynamic rotation & pitch angles (in degrees)
    private float currentYaw = 0.0F;
    private float currentPitch = 45.0F;
    private float targetYaw = 0.0F;
    private float targetPitch = 45.0F;

    // Previous angles for smooth frame interpolation in renderer
    private float prevYaw = 0.0F;
    private float prevPitch = 45.0F;

    private int reloadCooldown = 0;
    private int recoilTicks = 0;
    private boolean hasTarget = false;
    private UUID targetPlayerUuid = null;
    private int scanInterval = 0;

    public MortarBlockEntity(BlockPos pos, BlockState state) {
        super(WarfrontBlockEntities.MORTAR.get(), pos, state);
    }

    public void serverTick() {
        if (level == null || !(level instanceof ServerLevel serverLevel)) return;

        if (reloadCooldown > 0) reloadCooldown--;
        if (recoilTicks > 0) recoilTicks--;

        scanInterval++;
        if (scanInterval >= 20 || targetPlayerUuid == null) {
            scanInterval = 0;
            acquireTarget(serverLevel);
        }

        // Track targeted player position if valid
        ServerPlayer targetPlayer = null;
        if (targetPlayerUuid != null) {
            targetPlayer = serverLevel.getServer().getPlayerList().getPlayer(targetPlayerUuid);
            if (targetPlayer == null || !isValidTarget(serverLevel, targetPlayer)) {
                targetPlayerUuid = null;
                hasTarget = false;
            }
        }

        if (targetPlayer != null) {
            calculateAimAngles(targetPlayer);
            hasTarget = true;
        }

        // Slew current angles toward target angles (10 deg/sec yaw, 5 deg/sec pitch)
        this.prevYaw = this.currentYaw;
        this.prevPitch = this.currentPitch;

        float yawDiff = Mth.degreesDifference(this.currentYaw, this.targetYaw);
        this.currentYaw = Mth.wrapDegrees(this.currentYaw + Mth.clamp(yawDiff, -YAW_SPEED_DEG_PER_TICK, YAW_SPEED_DEG_PER_TICK));
        this.currentPitch = Mth.approach(this.currentPitch, this.targetPitch, PITCH_SPEED_DEG_PER_TICK);

        // Check if aligned and ready to fire (cooldown gates next shot)
        if (hasTarget && reloadCooldown <= 0) {
            float absYawDiff = Math.abs(Mth.degreesDifference(this.currentYaw, this.targetYaw));
            float absPitchDiff = Math.abs(this.currentPitch - this.targetPitch);

            if (absYawDiff <= 0.8F && absPitchDiff <= 0.5F) {
                fireMortar(serverLevel);
            }
        }

        // Periodic sync to client
        if (scanInterval % 5 == 0) {
            setChanged();
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void clientTick() {
        this.prevYaw = this.currentYaw;
        this.prevPitch = this.currentPitch;

        // Smooth client interpolation toward target angles
        float yawDiff = Mth.degreesDifference(this.currentYaw, this.targetYaw);
        this.currentYaw = Mth.wrapDegrees(this.currentYaw + Mth.clamp(yawDiff, -YAW_SPEED_DEG_PER_TICK, YAW_SPEED_DEG_PER_TICK));
        this.currentPitch = Mth.approach(this.currentPitch, this.targetPitch, PITCH_SPEED_DEG_PER_TICK);

        if (recoilTicks > 0) {
            recoilTicks--;
        }
    }

    /**
     * Acquires player entities within 3x3 subregions around this mortar.
     */
    private void acquireTarget(ServerLevel serverLevel) {
        ServerPlayer bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;

        for (ServerPlayer player : serverLevel.players()) {
            if (!isValidTarget(serverLevel, player)) continue;

            double distSq = player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.0D, worldPosition.getZ() + 0.5D);
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
        SubRegionPos mortarSub = SubRegionPos.fromChunk(chunkX, chunkZ);
        int mGlobalX = mortarSub.regionX() * 2 + mortarSub.subX();
        int mGlobalZ = mortarSub.regionZ() * 2 + mortarSub.subZ();

        int pChunkX = Math.floorDiv(player.getBlockX(), 16);
        int pChunkZ = Math.floorDiv(player.getBlockZ(), 16);
        SubRegionPos playerSub = SubRegionPos.fromChunk(pChunkX, pChunkZ);
        int pGlobalX = playerSub.regionX() * 2 + playerSub.subX();
        int pGlobalZ = playerSub.regionZ() * 2 + playerSub.subZ();

        // 3x3 subregions footprint
        if (Math.abs(pGlobalX - mGlobalX) > 1 || Math.abs(pGlobalZ - mGlobalZ) > 1) {
            return false;
        }

        // Minimum range: 12 blocks
        double distSq = player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.0D, worldPosition.getZ() + 0.5D);
        if (distSq < 144.0D) {
            return false;
        }

        // Must see sky: "assume there isnt anything between the shell and player if player can see sky"
        BlockPos playerHeadPos = player.blockPosition().above();
        if (!serverLevel.canSeeSky(playerHeadPos)) {
            int surfaceY = serverLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, player.blockPosition()).getY();
            if (player.getY() < surfaceY - 2) {
                return false;
            }
        }
        return true;
    }

    /**
     * Calculates the ballistic high-arc elevation and azimuth toward the player.
     */
    private void calculateAimAngles(ServerPlayer player) {
        double muzzleX = worldPosition.getX() + 0.5D;
        double muzzleY = worldPosition.getY() + 1.8D;
        double muzzleZ = worldPosition.getZ() + 0.5D;

        double targetX = player.getX();
        double targetY = player.getY();
        double targetZ = player.getZ();

        double dx = targetX - muzzleX;
        double dz = targetZ - muzzleZ;
        double dy = targetY - muzzleY;
        double distHoriz = Math.sqrt(dx * dx + dz * dz);

        // Yaw angle (Cannon faces -Z at yaw 0: North=0, East=+90, South=180, West=-90)
        this.targetYaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dx, -dz)));

        // High-arc ballistic solution
        double v0 = MUZZLE_VELOCITY;
        double g = GRAVITY;
        double v0Sq = v0 * v0;
        double term = v0Sq * v0Sq - g * (g * distHoriz * distHoriz + 2.0D * dy * v0Sq);

        if (term >= 0 && distHoriz > 1.0D) {
            // Mortars strictly use the high arc (+ sign)
            double tanTheta = (v0Sq + Math.sqrt(term)) / (g * distHoriz);
            double thetaRad = Math.atan(tanTheta);
            this.targetPitch = Mth.clamp((float) Math.toDegrees(thetaRad), 45.0F, 87.0F);
        } else {
            // Out of range: maximum mortar elevation
            this.targetPitch = 60.0F;
        }
    }

    private void fireMortar(ServerLevel serverLevel) {
        double pitchRad = Math.toRadians(this.currentPitch);
        double yawRad = Math.toRadians(this.currentYaw);

        // Barrel offset vector (approx 1.8 blocks long from trunnion)
        double barrelLength = 1.8D;
        double vHoriz = MUZZLE_VELOCITY * Math.cos(pitchRad);
        double vx = vHoriz * Math.sin(yawRad);
        double vz = -vHoriz * Math.cos(yawRad);
        double vy = MUZZLE_VELOCITY * Math.sin(pitchRad);

        double spawnX = worldPosition.getX() + 0.5D + (barrelLength * Math.sin(yawRad) * Math.cos(pitchRad));
        double spawnY = worldPosition.getY() + 1.8D + (barrelLength * Math.sin(pitchRad));
        double spawnZ = worldPosition.getZ() + 0.5D + (-barrelLength * Math.cos(yawRad) * Math.cos(pitchRad));

        // Spawn artillery projectile
        ArtilleryShellEntity shell = new ArtilleryShellEntity(serverLevel, spawnX, spawnY, spawnZ);
        shell.setDeltaMovement(vx, vy, vz);
        serverLevel.addFreshEntity(shell);

        // Loud firing noise to warn players across the area
        serverLevel.playSound(null, worldPosition, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 12.0F, 0.55F);
        serverLevel.playSound(null, worldPosition, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 9.0F, 0.85F);

        // Muzzle blast visual FX
        serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, spawnX, spawnY, spawnZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, spawnX, spawnY, spawnZ, 20, 0.3D, 0.3D, 0.3D, 0.12D);
        serverLevel.sendParticles(ParticleTypes.FLAME, spawnX, spawnY, spawnZ, 12, 0.2D, 0.2D, 0.2D, 0.15D);

        this.recoilTicks = 15;
        this.reloadCooldown = COOLDOWN_TICKS; // 15 seconds cooldown

        triggerAnim("recoil_controller", "recoil");

        setChanged();
        serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public float getYaw(float partialTicks) {
        return Mth.rotLerp(partialTicks, this.prevYaw, this.currentYaw);
    }

    public float getPitch(float partialTicks) {
        return Mth.lerp(partialTicks, this.prevPitch, this.currentPitch);
    }

    public float getRecoilOffset(float partialTicks) {
        if (this.recoilTicks <= 0) return 0.0F;
        float progress = (this.recoilTicks - partialTicks) / 10.0F;
        if (progress < 0.3F) {
            return (progress / 0.3F) * 3.5F; // Kick back 3.5 pixels
        } else {
            return (1.0F - (progress - 0.3F) / 0.7F) * 3.5F; // Smooth return
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat("CurrentYaw", this.currentYaw);
        tag.putFloat("CurrentPitch", this.currentPitch);
        tag.putFloat("TargetYaw", this.targetYaw);
        tag.putFloat("TargetPitch", this.targetPitch);
        tag.putInt("ReloadCooldown", this.reloadCooldown);
        tag.putInt("RecoilTicks", this.recoilTicks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.currentYaw = tag.getFloat("CurrentYaw");
        this.currentPitch = tag.getFloat("CurrentPitch");
        this.targetYaw = tag.getFloat("TargetYaw");
        this.targetPitch = tag.getFloat("TargetPitch");
        this.reloadCooldown = tag.getInt("ReloadCooldown");
        this.recoilTicks = tag.getInt("RecoilTicks");
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
        tag.putInt("RecoilTicks", this.recoilTicks);
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
            int newRecoil = tag.getInt("RecoilTicks");
            if (newRecoil > 0 && this.recoilTicks <= 0) {
                triggerAnim("recoil_controller", "recoil");
            }
            this.recoilTicks = newRecoil;

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

    private static final software.bernie.geckolib.animation.RawAnimation RECOIL_ANIM =
            software.bernie.geckolib.animation.RawAnimation.begin().thenPlay("recoil");

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new software.bernie.geckolib.animation.AnimationController<>(this, "recoil_controller", 0, state -> {
            if (this.recoilTicks > 0) {
                return state.setAndContinue(RECOIL_ANIM);
            }
            return software.bernie.geckolib.animation.PlayState.STOP;
        }).triggerableAnim("recoil", RECOIL_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
