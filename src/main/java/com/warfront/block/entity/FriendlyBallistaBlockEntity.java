package com.warfront.block.entity;

import com.warfront.block.WarfrontBlocks;
import com.warfront.entity.AlliedFactionHelper;
import com.warfront.entity.BallistaBoltEntity;
import com.warfront.entity.SmokeScreenEntity;
import com.warfront.faction.PlayerFaction;
import com.warfront.faction.PlayerFactionData;
import com.warfront.menu.FriendlyBallistaMenu;
import com.warfront.region.RegionData;
import com.warfront.region.SubRegionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.UUID;

/**
 * BlockEntity controlling automated targeting, fuel consumption (furnace fuel/coal),
 * ballista bolt chambering, and direct-fire supersonic launching of the Friendly Giant Ballista.
 */
public class FriendlyBallistaBlockEntity extends BlockEntity implements GeoBlockEntity, MenuProvider, WorldlyContainer {

    public static final float YAW_SPEED_DEG_PER_TICK = 0.75F;   // 15 deg/sec
    public static final float PITCH_SPEED_DEG_PER_TICK = 0.375F; // 7.5 deg/sec
    public static final int COOLDOWN_TICKS = 300;                // 15 seconds
    public static final double MUZZLE_VELOCITY = 7.0D;           // 140 m/s
    public static final double GRAVITY = BallistaBoltEntity.GRAVITY;

    public static final int AMMO_SLOTS = 5;
    public static final int TOTAL_SLOTS = 6;
    public static final int FUEL_SLOT = 5;

    private static final int[] SLOTS_TOP = new int[]{0, 1, 2, 3, 4};
    private static final int[] SLOTS_BOTTOM = new int[]{5};
    private static final int[] SLOTS_SIDES = new int[]{0, 1, 2, 3, 4, 5};

    private static final RawAnimation FIRE_AND_RELOAD_ANIM =
            RawAnimation.begin().thenPlay("shoot").thenPlay("reload");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);

    private float currentYaw = 0.0F;
    private float currentPitch = 0.0F;
    private float targetYaw = 0.0F;
    private float targetPitch = 0.0F;
    private float prevYaw = 0.0F;
    private float prevPitch = 0.0F;

    private int reloadCooldown = 0;
    private int fireAnimTicks = 0;
    private boolean hasTarget = false;
    private int scanInterval = 0;

    // Fuel & Chamber State
    private int burnTimeRemaining = 0;
    private int currentFuelDuration = 0;
    private boolean boltChambered = false;
    private boolean burnPaused = false;

    // Faction Ownership & Territorial Lifecycle
    private UUID ownerFactionId = null;
    private boolean deactivated = false;
    private int territoryCheckInterval = 0;

    private UUID targetEntityUuid = null;

    protected final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> burnTimeRemaining;
                case 1 -> currentFuelDuration;
                case 2 -> calculateMaxFuelTicks() & 0xFFFF;
                case 3 -> (calculateMaxFuelTicks() >> 16) & 0xFFFF;
                case 4 -> boltChambered ? 1 : 0;
                case 5 -> burnPaused ? 1 : 0;
                case 6 -> deactivated ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> burnTimeRemaining = value;
                case 1 -> currentFuelDuration = value;
                case 4 -> boltChambered = (value == 1);
                case 5 -> burnPaused = (value == 1);
                case 6 -> deactivated = (value == 1);
            }
        }

        @Override
        public int getCount() {
            return 7;
        }
    };

    public FriendlyBallistaBlockEntity(BlockPos pos, BlockState state) {
        super(WarfrontBlockEntities.FRIENDLY_BALLISTA.get(), pos, state);
    }

    public void serverTick() {
        if (level == null || !(level instanceof ServerLevel serverLevel)) return;

        // 1. Periodic Territorial Verification (every 2 seconds)
        this.territoryCheckInterval++;
        if (this.territoryCheckInterval >= 40) {
            this.territoryCheckInterval = 0;
            if (this.ownerFactionId != null) {
                RegionData.Region reg = RegionData.get(serverLevel).regionAt(this.worldPosition);
                if (reg.owner().isAI()) {
                    clearContent();
                    voidChamber();
                    serverLevel.destroyBlock(this.worldPosition, false);
                    return;
                }
                boolean shouldBeActive = (reg.owner() == com.warfront.region.Faction.HUMANITY
                        && reg.playerFactionId() != null
                        && reg.playerFactionId().equals(this.ownerFactionId));
                if (this.deactivated == shouldBeActive) {
                    this.deactivated = !shouldBeActive;
                    setChanged();
                    serverLevel.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), 3);
                }
            }
        }

        if (this.deactivated) {
            return;
        }

        if (reloadCooldown > 0) reloadCooldown--;
        if (fireAnimTicks > 0) fireAnimTicks--;

        boolean stateChanged = false;

        // 2. Chambering: When chamber is empty, draw 1 Giant Ballista Bolt from ammo slots
        if (!this.boltChambered) {
            for (int i = 0; i < AMMO_SLOTS; i++) {
                ItemStack stack = this.items.get(i);
                if (!stack.isEmpty() && stack.is(WarfrontBlocks.BALLISTA_BOLT_ITEM.get())) {
                    stack.shrink(1);
                    this.boltChambered = true;
                    stateChanged = true;
                    break;
                }
            }
        }

        // 3. Conditional Fuel Burn Logic
        boolean hasAmmoOrChamber = this.boltChambered || hasAmmoInSlots();
        if (hasAmmoOrChamber) {
            this.burnPaused = false;
            if (this.burnTimeRemaining > 0) {
                this.burnTimeRemaining--;
            } else {
                ItemStack fuelStack = this.items.get(FUEL_SLOT);
                if (!fuelStack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(fuelStack)) {
                    int duration = fuelStack.getBurnTime(net.minecraft.world.item.crafting.RecipeType.SMELTING);
                    if (duration > 0) {
                        fuelStack.shrink(1);
                        this.burnTimeRemaining = duration;
                        this.currentFuelDuration = duration;
                        stateChanged = true;
                    }
                }
            }
        } else {
            this.burnPaused = true;
        }

        // 4. Target Acquisition & Direct-Fire Aiming
        LivingEntity currentTarget = null;
        if (isOperational() && this.boltChambered) {
            this.scanInterval++;
            if (this.scanInterval >= 20 || this.targetEntityUuid == null) {
                this.scanInterval = 0;
                acquireTarget(serverLevel);
            }

            if (this.targetEntityUuid != null) {
                if (serverLevel.getEntity(this.targetEntityUuid) instanceof LivingEntity living && isValidTarget(living)) {
                    currentTarget = living;
                    this.hasTarget = true;
                } else {
                    this.targetEntityUuid = null;
                    this.hasTarget = false;
                }
            }
        } else {
            this.hasTarget = false;
            this.targetEntityUuid = null;
        }

        if (currentTarget != null) {
            calculateAimAngles(currentTarget);
        }

        // 5. Slew angles toward target
        this.prevYaw = this.currentYaw;
        this.prevPitch = this.currentPitch;

        float yawDiff = Mth.degreesDifference(this.currentYaw, this.targetYaw);
        this.currentYaw = Mth.wrapDegrees(this.currentYaw + Mth.clamp(yawDiff, -YAW_SPEED_DEG_PER_TICK, YAW_SPEED_DEG_PER_TICK));
        this.currentPitch = Mth.approach(this.currentPitch, this.targetPitch, PITCH_SPEED_DEG_PER_TICK);

        // 6. Fire ballista when aligned and ready
        if (this.hasTarget && this.reloadCooldown <= 0 && isOperational() && this.boltChambered) {
            float absYawDiff = Math.abs(Mth.degreesDifference(this.currentYaw, this.targetYaw));
            float absPitchDiff = Math.abs(this.currentPitch - this.targetPitch);

            if (absYawDiff <= 1.5F && absPitchDiff <= 1.0F) {
                fireBallista(serverLevel);
                stateChanged = true;
            }
        }

        if (stateChanged || (scanInterval % 10 == 0)) {
            setChanged();
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void clientTick() {
        this.prevYaw = this.currentYaw;
        this.prevPitch = this.currentPitch;

        if (this.deactivated) {
            return;
        }

        float yawDiff = Mth.degreesDifference(this.currentYaw, this.targetYaw);
        this.currentYaw = Mth.wrapDegrees(this.currentYaw + Mth.clamp(yawDiff, -YAW_SPEED_DEG_PER_TICK, YAW_SPEED_DEG_PER_TICK));
        this.currentPitch = Mth.approach(this.currentPitch, this.targetPitch, PITCH_SPEED_DEG_PER_TICK);

        if (this.fireAnimTicks > 0) {
            this.fireAnimTicks--;
        }
    }

    private void acquireTarget(ServerLevel serverLevel) {
        LivingEntity bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;
        Vec3 muzzlePos = new Vec3(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.5625D, worldPosition.getZ() + 0.5D);

        AABB searchBox = new AABB(worldPosition).inflate(160.0D, 64.0D, 160.0D);
        List<LivingEntity> entities = serverLevel.getEntitiesOfClass(LivingEntity.class, searchBox, this::isValidTarget);

        for (LivingEntity entity : entities) {
            double distSq = entity.distanceToSqr(muzzlePos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestTarget = entity;
            }
        }

        if (bestTarget != null) {
            this.targetEntityUuid = bestTarget.getUUID();
        } else {
            this.targetEntityUuid = null;
            this.hasTarget = false;
        }
    }

    private boolean isValidTarget(LivingEntity target) {
        if (!target.isAlive() || target.isSpectator()) {
            return false;
        }
        if (target instanceof Player player && player.isCreative()) {
            return false;
        }

        // Hostile targets: Monsters, hostile Illagers/Raiders, or enemy faction members
        boolean isHostile = (target instanceof Monster) || (target instanceof Raider);
        if (target instanceof Player player && this.level instanceof ServerLevel serverLevel) {
            if (this.ownerFactionId != null) {
                PlayerFactionData data = PlayerFactionData.get(serverLevel);
                PlayerFaction pf = data.getFactionByPlayer(player.getUUID());
                if (pf != null && this.ownerFactionId.equals(pf.getId())) {
                    return false; // Friendly player
                }
            }
        } else if (!isHostile) {
            return false;
        }

        if (this.ownerFactionId != null && this.level instanceof ServerLevel serverLevel) {
            if (AlliedFactionHelper.isAllied(null, target)) {
                // Keep allied checks respected
            }
        }

        // Range check: 8 blocks to 160 blocks
        Vec3 muzzlePos = new Vec3(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.5625D, worldPosition.getZ() + 0.5D);
        double distSq = target.distanceToSqr(muzzlePos);
        if (distSq < 64.0D || distSq > 25600.0D) {
            return false;
        }

        // 3x3 subregions footprint
        int chunkX = Math.floorDiv(worldPosition.getX(), 16);
        int chunkZ = Math.floorDiv(worldPosition.getZ(), 16);
        SubRegionPos ballistaSub = SubRegionPos.fromChunk(chunkX, chunkZ);
        int bGlobalX = ballistaSub.regionX() * 2 + ballistaSub.subX();
        int bGlobalZ = ballistaSub.regionZ() * 2 + ballistaSub.subZ();

        int tChunkX = Math.floorDiv(target.getBlockX(), 16);
        int tChunkZ = Math.floorDiv(target.getBlockZ(), 16);
        SubRegionPos targetSub = SubRegionPos.fromChunk(tChunkX, tChunkZ);
        int tGlobalX = targetSub.regionX() * 2 + targetSub.subX();
        int tGlobalZ = targetSub.regionZ() * 2 + targetSub.subZ();

        if (Math.abs(tGlobalX - bGlobalX) > 1 || Math.abs(tGlobalZ - bGlobalZ) > 1) {
            return false;
        }

        Vec3 targetEye = target.getEyePosition();
        if (SmokeScreenEntity.isInsideSmoke(target.position())
                || SmokeScreenEntity.isInsideSmoke(muzzlePos)
                || SmokeScreenEntity.isObstructedBySmoke(muzzlePos, targetEye)) {
            return false;
        }

        return this.level instanceof ServerLevel sl && hasLineOfSight(sl, muzzlePos, targetEye);
    }

    private boolean hasLineOfSight(ServerLevel serverLevel, Vec3 start, Vec3 end) {
        Vec3 dir = end.subtract(start);
        double totalDist = dir.length();
        if (totalDist < 1.0D) return true;
        Vec3 unitDir = dir.scale(1.0D / totalDist);

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
            if (isPassableFoliage(hitState, hitPos)) {
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

    private boolean isPassableFoliage(BlockState state, BlockPos pos) {
        if (state.isAir() || !state.blocksMotion() || state.canBeReplaced()) {
            return true;
        }
        if (state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS) || state.is(BlockTags.CROPS)) {
            return true;
        }
        return this.level != null && state.getCollisionShape(this.level, pos).isEmpty();
    }

    private void calculateAimAngles(LivingEntity target) {
        double muzzleX = worldPosition.getX() + 0.5D;
        double muzzleY = worldPosition.getY() + 1.5625D;
        double muzzleZ = worldPosition.getZ() + 0.5D;

        double rawDx = target.getX() - muzzleX;
        double rawDz = target.getZ() - muzzleZ;
        double rawDist = Math.sqrt(rawDx * rawDx + rawDz * rawDz);
        double flightTicks = rawDist / MUZZLE_VELOCITY;

        Vec3 targetVel = target.getDeltaMovement();
        double targetX = target.getX() + targetVel.x * flightTicks * 0.65D;
        double targetY = target.getY() + target.getBbHeight() * 0.55D;
        double targetZ = target.getZ() + targetVel.z * flightTicks * 0.65D;

        double dx = targetX - muzzleX;
        double dz = targetZ - muzzleZ;
        double dy = targetY - muzzleY;
        double distHoriz = Math.sqrt(dx * dx + dz * dz);

        this.targetYaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dx, -dz)));

        double dropCompensation = 0.5D * GRAVITY * flightTicks * flightTicks;
        double pitchRad = Math.atan2(dy + dropCompensation, Math.max(1.0D, distHoriz));
        this.targetPitch = Mth.clamp((float) Math.toDegrees(pitchRad), -25.0F, 35.0F);
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

        BallistaBoltEntity bolt = new BallistaBoltEntity(serverLevel, spawnX, spawnY, spawnZ, false, this.ownerFactionId);
        bolt.setDeltaMovement(vx, vy, vz);
        serverLevel.addFreshEntity(bolt);

        serverLevel.playSound(null, worldPosition, SoundEvents.CROSSBOW_SHOOT, SoundSource.BLOCKS, 6.0F, 0.55F);
        serverLevel.playSound(null, worldPosition, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.BLOCKS, 4.5F, 1.35F);
        serverLevel.playSound(null, worldPosition, SoundEvents.IRON_GOLEM_ATTACK, SoundSource.BLOCKS, 3.5F, 0.75F);

        serverLevel.sendParticles(ParticleTypes.SONIC_BOOM, spawnX, spawnY, spawnZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        serverLevel.sendParticles(ParticleTypes.CLOUD, spawnX, spawnY, spawnZ, 14, 0.25D, 0.25D, 0.25D, 0.08D);
        serverLevel.sendParticles(ParticleTypes.CRIT, spawnX, spawnY, spawnZ, 10, 0.2D, 0.2D, 0.2D, 0.15D);

        this.fireAnimTicks = 85;
        this.reloadCooldown = COOLDOWN_TICKS;
        this.boltChambered = false;

        triggerAnim("fire_controller", "fire_cycle");

        setChanged();
        serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public UUID getOwnerFactionId() {
        return this.ownerFactionId;
    }

    public void setOwnerFactionId(UUID ownerFactionId) {
        this.ownerFactionId = ownerFactionId;
        setChanged();
    }

    public boolean isDeactivated() {
        return this.deactivated;
    }

    public void setDeactivated(boolean deactivated) {
        this.deactivated = deactivated;
        setChanged();
    }

    public void voidChamber() {
        this.boltChambered = false;
        setChanged();
    }

    public boolean isOperational() {
        if (this.deactivated) {
            return false;
        }
        return this.burnTimeRemaining > 0 || (!this.items.get(FUEL_SLOT).isEmpty() && (this.boltChambered || hasAmmoInSlots()));
    }

    public boolean hasAmmoInSlots() {
        for (int i = 0; i < AMMO_SLOTS; i++) {
            if (!this.items.get(i).isEmpty()) return true;
        }
        return false;
    }

    public int calculateMaxFuelTicks() {
        int total = this.burnTimeRemaining;
        ItemStack fuelStack = this.items.get(FUEL_SLOT);
        if (!fuelStack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(fuelStack)) {
            int duration = fuelStack.getBurnTime(net.minecraft.world.item.crafting.RecipeType.SMELTING);
            total += duration * fuelStack.getCount();
        }
        return total;
    }

    public float getYaw(float partialTicks) {
        return Mth.rotLerp(partialTicks, this.prevYaw, this.currentYaw);
    }

    public float getPitch(float partialTicks) {
        return Mth.lerp(partialTicks, this.prevPitch, this.currentPitch);
    }

    // --- Container & Inventory Implementation ---

    @Override
    public int getContainerSize() {
        return TOTAL_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(this.items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        this.items.clear();
        setChanged();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        if (side == Direction.DOWN) return SLOTS_BOTTOM;
        if (side == Direction.UP) return SLOTS_TOP;
        return SLOTS_SIDES;
    }

    @Override
    public boolean canPlaceItemThroughFace(int index, ItemStack stack, @Nullable Direction direction) {
        if (index == FUEL_SLOT) {
            return AbstractFurnaceBlockEntity.isFuel(stack);
        }
        return stack.is(WarfrontBlocks.BALLISTA_BOLT_ITEM.get());
    }

    @Override
    public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction direction) {
        return true;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.warfront.friendly_ballista");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new FriendlyBallistaMenu(containerId, playerInventory, this, this.dataAccess);
    }

    // --- Serialization & Sync ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, this.items, registries);
        tag.putFloat("CurrentYaw", this.currentYaw);
        tag.putFloat("CurrentPitch", this.currentPitch);
        tag.putFloat("TargetYaw", this.targetYaw);
        tag.putFloat("TargetPitch", this.targetPitch);
        tag.putInt("ReloadCooldown", this.reloadCooldown);
        tag.putInt("FireAnimTicks", this.fireAnimTicks);
        tag.putInt("BurnTimeRemaining", this.burnTimeRemaining);
        tag.putInt("CurrentFuelDuration", this.currentFuelDuration);
        tag.putBoolean("BoltChambered", this.boltChambered);
        tag.putBoolean("BurnPaused", this.burnPaused);
        tag.putBoolean("Deactivated", this.deactivated);
        if (this.ownerFactionId != null) {
            tag.putUUID("OwnerFactionId", this.ownerFactionId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items, registries);
        this.currentYaw = tag.getFloat("CurrentYaw");
        this.currentPitch = tag.getFloat("CurrentPitch");
        this.targetYaw = tag.getFloat("TargetYaw");
        this.targetPitch = tag.getFloat("TargetPitch");
        this.reloadCooldown = tag.getInt("ReloadCooldown");
        this.fireAnimTicks = tag.getInt("FireAnimTicks");
        this.burnTimeRemaining = tag.getInt("BurnTimeRemaining");
        this.currentFuelDuration = tag.getInt("CurrentFuelDuration");
        this.boltChambered = tag.getBoolean("BoltChambered");
        this.burnPaused = tag.getBoolean("BurnPaused");
        this.deactivated = tag.getBoolean("Deactivated");
        this.ownerFactionId = tag.hasUUID("OwnerFactionId") ? tag.getUUID("OwnerFactionId") : null;
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
        tag.putBoolean("BoltChambered", this.boltChambered);
        tag.putBoolean("Deactivated", this.deactivated);
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
            this.boltChambered = tag.getBoolean("BoltChambered");
            this.deactivated = tag.getBoolean("Deactivated");

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
            return PlayState.STOP;
        }).triggerableAnim("fire_cycle", FIRE_AND_RELOAD_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
