package com.warfront.block.entity;

import com.warfront.block.WarfrontBlocks;
import com.warfront.entity.FriendlyArtilleryShellEntity;
import com.warfront.menu.FriendlyMortarMenu;
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
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BlockEntity controlling the automated targeting, fuel consumption, random chambering,
 * and ballistic firing logic of the Friendly Mortar Support emplacement.
 */
public class FriendlyMortarBlockEntity extends BlockEntity implements GeoBlockEntity, MenuProvider, WorldlyContainer {

    public static final float YAW_SPEED_DEG_PER_TICK = 0.5F;
    public static final float PITCH_SPEED_DEG_PER_TICK = 0.25F;
    public static final int COOLDOWN_TICKS = 300; // 15 seconds
    public static final double MUZZLE_VELOCITY = 3.0D; // 60 m/s
    public static final double GRAVITY = 0.05D;

    public static final int AMMO_SLOTS = 5;
    public static final int TOTAL_SLOTS = 6;
    public static final int FUEL_SLOT = 5;

    private static final int[] SLOTS_TOP = new int[]{0, 1, 2, 3, 4};
    private static final int[] SLOTS_BOTTOM = new int[]{5};
    private static final int[] SLOTS_SIDES = new int[]{0, 1, 2, 3, 4, 5};

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);

    // Aim angles in degrees
    private float currentYaw = 0.0F;
    private float currentPitch = 45.0F;
    private float targetYaw = 0.0F;
    private float targetPitch = 45.0F;
    private float prevYaw = 0.0F;
    private float prevPitch = 45.0F;

    private int reloadCooldown = 0;
    private int recoilTicks = 0;
    private boolean hasTarget = false;
    private int scanInterval = 0;

    // Fuel & Chamber State
    private int burnTimeRemaining = 0;
    private int currentFuelDuration = 0;
    private LoadedShellType currentLoadedShell = LoadedShellType.NONE;
    private boolean burnPaused = false;
    private float targetGroundY = 64.0F;

    // Faction Ownership & Territorial Lifecycle
    private UUID ownerFactionId = null;
    private boolean deactivated = false;
    private int territoryCheckInterval = 0;

    // Targeted target tracking
    private Vec3 currentAimTarget = null;

    protected final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> burnTimeRemaining;
                case 1 -> currentFuelDuration;
                case 2 -> calculateMaxFuelTicks() & 0xFFFF;
                case 3 -> (calculateMaxFuelTicks() >> 16) & 0xFFFF;
                case 4 -> currentLoadedShell.getId();
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
                case 4 -> currentLoadedShell = LoadedShellType.fromId(value);
                case 5 -> burnPaused = (value == 1);
                case 6 -> deactivated = (value == 1);
            }
        }

        @Override
        public int getCount() {
            return 7;
        }
    };

    public FriendlyMortarBlockEntity(BlockPos pos, BlockState state) {
        super(WarfrontBlockEntities.FRIENDLY_MORTAR.get(), pos, state);
    }

    public void serverTick() {
        if (level == null || !(level instanceof ServerLevel serverLevel)) return;

        // Periodic Territorial Verification
        this.territoryCheckInterval++;
        if (this.territoryCheckInterval >= 40) {
            this.territoryCheckInterval = 0;
            if (this.ownerFactionId != null) {
                com.warfront.region.RegionData.Region reg = com.warfront.region.RegionData.get(serverLevel).regionAt(this.worldPosition);
                if (reg.owner().isAI()) {
                    // AI takeover cleanup: Void contents & chamber, destroy block with zero drops
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

        // When deactivated (e.g. fallen to Wilderness or foreign player faction), all operations are halted
        if (this.deactivated) {
            return;
        }

        if (reloadCooldown > 0) reloadCooldown--;
        if (recoilTicks > 0) recoilTicks--;

        boolean stateChanged = false;

        // 1. Chambering: When chamber is empty, pick a random non-empty ammo slot and void 1 item
        if (this.currentLoadedShell == LoadedShellType.NONE) {
            List<Integer> availableSlots = new ArrayList<>();
            for (int i = 0; i < AMMO_SLOTS; i++) {
                ItemStack stack = this.items.get(i);
                if (!stack.isEmpty() && LoadedShellType.fromItem(stack.getItem()) != LoadedShellType.NONE) {
                    availableSlots.add(i);
                }
            }
            if (!availableSlots.isEmpty()) {
                int chosenIndex = availableSlots.get(serverLevel.random.nextInt(availableSlots.size()));
                ItemStack chosenStack = this.items.get(chosenIndex);
                this.currentLoadedShell = LoadedShellType.fromItem(chosenStack.getItem());
                chosenStack.shrink(1);
                stateChanged = true;
            }
        }

        // 2. Conditional Fuel Burn Logic
        // Fuel only burns if both fuel and ammunition (or loaded chamber) are supplied.
        boolean hasAmmoOrChamber = (this.currentLoadedShell != LoadedShellType.NONE) || hasAmmoInSlots();
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
            // Paused: zero fuel burn while unarmed
            this.burnPaused = true;
        }

        // 3. Target Acquisition & Aiming (Only operates if fueled)
        if (isOperational() && this.currentLoadedShell != LoadedShellType.NONE) {
            this.scanInterval++;
            if (this.scanInterval >= 20 || !this.hasTarget) {
                this.scanInterval = 0;
                acquireTarget(serverLevel);
            }

            if (this.currentAimTarget != null) {
                calculateAimAngles(this.currentAimTarget);
                this.hasTarget = true;
            } else {
                this.hasTarget = false;
            }
        } else {
            this.hasTarget = false;
            this.currentAimTarget = null;
        }

        // 4. Slew angles toward target (10 deg/sec yaw, 5 deg/sec pitch)
        this.prevYaw = this.currentYaw;
        this.prevPitch = this.currentPitch;

        float yawDiff = Mth.degreesDifference(this.currentYaw, this.targetYaw);
        this.currentYaw = Mth.wrapDegrees(this.currentYaw + Mth.clamp(yawDiff, -YAW_SPEED_DEG_PER_TICK, YAW_SPEED_DEG_PER_TICK));
        this.currentPitch = Mth.approach(this.currentPitch, this.targetPitch, PITCH_SPEED_DEG_PER_TICK);

        // 5. Fire mortar when aligned and ready
        if (this.hasTarget && this.reloadCooldown <= 0 && isOperational() && this.currentLoadedShell != LoadedShellType.NONE) {
            float absYawDiff = Math.abs(Mth.degreesDifference(this.currentYaw, this.targetYaw));
            float absPitchDiff = Math.abs(this.currentPitch - this.targetPitch);

            if (absYawDiff <= 0.8F && absPitchDiff <= 0.5F) {
                fireMortar(serverLevel);
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

        if (this.recoilTicks > 0) {
            this.recoilTicks--;
        }
    }

    /**
     * Acquires targets within 3x3 subregions around this mortar.
     * If loaded with Smoke Shell: targets midpoint between friendly player and pursuing hostile.
     * Otherwise: targets closest hostile monster.
     */
    private void acquireTarget(ServerLevel serverLevel) {
        if (this.currentLoadedShell == LoadedShellType.SMOKE) {
            acquireSmokeTarget(serverLevel);
        } else {
            acquireHostileTarget(serverLevel);
        }
    }

    private void acquireSmokeTarget(ServerLevel serverLevel) {
        Vec3 bestMidpoint = null;
        double bestDistSq = Double.MAX_VALUE;

        for (ServerPlayer player : serverLevel.players()) {
            if (!player.isAlive() || player.isCreative() || player.isSpectator()) continue;
            if (!isInRange(player.blockPosition())) continue;

            // Search for hostile mobs aggroed to this player
            for (Mob mob : serverLevel.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(32.0D))) {
                if (mob.isAlive() && mob.getTarget() == player) {
                    double distSq = player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.0D, worldPosition.getZ() + 0.5D);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestMidpoint = player.position().add(mob.position()).scale(0.5D);
                        this.targetGroundY = (float) Math.min(player.getY(), mob.getY());
                    }
                }
            }
        }

        this.currentAimTarget = bestMidpoint;
    }

    private void acquireHostileTarget(ServerLevel serverLevel) {
        Monster bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;

        // Search within 3x3 subregions (~180 blocks)
        for (Monster monster : serverLevel.getEntitiesOfClass(Monster.class,
                new net.minecraft.world.phys.AABB(worldPosition).inflate(180.0D, 64.0D, 180.0D))) {
            if (!monster.isAlive()) continue;
            if (!isInRange(monster.blockPosition())) continue;

            // Check if can see sky or near surface
            BlockPos headPos = monster.blockPosition().above();
            if (!serverLevel.canSeeSky(headPos)) {
                int surfaceY = serverLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, monster.blockPosition()).getY();
                if (monster.getY() < surfaceY - 2) {
                    continue;
                }
            }

            double distSq = monster.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.0D, worldPosition.getZ() + 0.5D);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestTarget = monster;
            }
        }

        if (bestTarget != null) {
            this.currentAimTarget = bestTarget.position();
            this.targetGroundY = (float) bestTarget.getY();
        } else {
            this.currentAimTarget = null;
        }
    }

    private boolean isInRange(BlockPos targetPos) {
        // Minimum arming range: 12 blocks
        double distSq = targetPos.distToCenterSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 1.0D, worldPosition.getZ() + 0.5D);
        if (distSq < 144.0D) {
            return false;
        }

        // 3x3 subregions footprint
        int chunkX = Math.floorDiv(worldPosition.getX(), 16);
        int chunkZ = Math.floorDiv(worldPosition.getZ(), 16);
        SubRegionPos mortarSub = SubRegionPos.fromChunk(chunkX, chunkZ);
        int mGlobalX = mortarSub.regionX() * 2 + mortarSub.subX();
        int mGlobalZ = mortarSub.regionZ() * 2 + mortarSub.subZ();

        int tChunkX = Math.floorDiv(targetPos.getX(), 16);
        int tChunkZ = Math.floorDiv(targetPos.getZ(), 16);
        SubRegionPos targetSub = SubRegionPos.fromChunk(tChunkX, tChunkZ);
        int tGlobalX = targetSub.regionX() * 2 + targetSub.subX();
        int tGlobalZ = targetSub.regionZ() * 2 + targetSub.subZ();

        return Math.abs(tGlobalX - mGlobalX) <= 1 && Math.abs(tGlobalZ - mGlobalZ) <= 1;
    }

    private void calculateAimAngles(Vec3 target) {
        double muzzleX = worldPosition.getX() + 0.5D;
        double muzzleY = worldPosition.getY() + 1.8D;
        double muzzleZ = worldPosition.getZ() + 0.5D;

        double dx = target.x - muzzleX;
        double dy = target.y - muzzleY;
        double dz = target.z - muzzleZ;
        double distHoriz = Math.sqrt(dx * dx + dz * dz);

        this.targetYaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dx, -dz)));

        double v0 = MUZZLE_VELOCITY;
        double g = GRAVITY;
        double v0Sq = v0 * v0;
        double term = v0Sq * v0Sq - g * (g * distHoriz * distHoriz + 2.0D * dy * v0Sq);

        if (term >= 0 && distHoriz > 1.0D) {
            double tanTheta = (v0Sq + Math.sqrt(term)) / (g * distHoriz);
            this.targetPitch = Mth.clamp((float) Math.toDegrees(Math.atan(tanTheta)), 45.0F, 87.0F);
        } else {
            this.targetPitch = 60.0F;
        }
    }

    private void fireMortar(ServerLevel serverLevel) {
        double pitchRad = Math.toRadians(this.currentPitch);
        double yawRad = Math.toRadians(this.currentYaw);

        double barrelLength = 1.8D;
        double vHoriz = MUZZLE_VELOCITY * Math.cos(pitchRad);
        double vx = vHoriz * Math.sin(yawRad);
        double vz = -vHoriz * Math.cos(yawRad);
        double vy = MUZZLE_VELOCITY * Math.sin(pitchRad);

        double spawnX = worldPosition.getX() + 0.5D + (barrelLength * Math.sin(yawRad) * Math.cos(pitchRad));
        double spawnY = worldPosition.getY() + 1.8D + (barrelLength * Math.sin(pitchRad));
        double spawnZ = worldPosition.getZ() + 0.5D + (-barrelLength * Math.cos(yawRad) * Math.cos(pitchRad));

        // Spawn friendly shell projectile with loaded payload
        FriendlyArtilleryShellEntity shell = new FriendlyArtilleryShellEntity(serverLevel, spawnX, spawnY, spawnZ, this.currentLoadedShell, this.targetGroundY);
        shell.setDeltaMovement(vx, vy, vz);
        serverLevel.addFreshEntity(shell);

        // Firing sound & FX
        serverLevel.playSound(null, worldPosition, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 10.0F, 0.65F);
        serverLevel.playSound(null, worldPosition, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 8.0F, 0.90F);

        serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, spawnX, spawnY, spawnZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, spawnX, spawnY, spawnZ, 18, 0.3D, 0.3D, 0.3D, 0.12D);
        serverLevel.sendParticles(ParticleTypes.FLAME, spawnX, spawnY, spawnZ, 10, 0.2D, 0.2D, 0.2D, 0.15D);

        this.recoilTicks = 15;
        this.reloadCooldown = COOLDOWN_TICKS;
        this.currentLoadedShell = LoadedShellType.NONE;

        triggerAnim("recoil_controller", "recoil");

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
        this.currentLoadedShell = LoadedShellType.NONE;
        setChanged();
    }

    public boolean isOperational() {
        if (this.deactivated) {
            return false;
        }
        return this.burnTimeRemaining > 0 || (!this.items.get(FUEL_SLOT).isEmpty() && (this.currentLoadedShell != LoadedShellType.NONE || hasAmmoInSlots()));
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
        return LoadedShellType.fromItem(stack.getItem()) != LoadedShellType.NONE;
    }

    @Override
    public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction direction) {
        return true;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.warfront.friendly_mortar");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new FriendlyMortarMenu(containerId, playerInventory, this, this.dataAccess);
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
        tag.putInt("RecoilTicks", this.recoilTicks);
        tag.putInt("BurnTimeRemaining", this.burnTimeRemaining);
        tag.putInt("CurrentFuelDuration", this.currentFuelDuration);
        tag.putByte("LoadedShell", (byte) this.currentLoadedShell.getId());
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
        this.recoilTicks = tag.getInt("RecoilTicks");
        this.burnTimeRemaining = tag.getInt("BurnTimeRemaining");
        this.currentFuelDuration = tag.getInt("CurrentFuelDuration");
        this.currentLoadedShell = LoadedShellType.fromId(tag.getByte("LoadedShell"));
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
        tag.putInt("RecoilTicks", this.recoilTicks);
        tag.putByte("LoadedShell", (byte) this.currentLoadedShell.getId());
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
            this.currentLoadedShell = LoadedShellType.fromId(tag.getByte("LoadedShell"));
            this.deactivated = tag.getBoolean("Deactivated");

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

    // --- GeckoLib Animatable ---

    private static final RawAnimation RECOIL_ANIM = RawAnimation.begin().thenPlay("recoil");

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "recoil_controller", 0, state -> {
            if (this.recoilTicks > 0) {
                return state.setAndContinue(RECOIL_ANIM);
            }
            return PlayState.STOP;
        }).triggerableAnim("recoil", RECOIL_ANIM));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
