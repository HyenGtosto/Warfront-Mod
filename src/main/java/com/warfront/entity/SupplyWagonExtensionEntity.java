package com.warfront.entity;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * Supply Wagon Extension (Rear convoy wagon / trailer).
 *
 * Couples behind {@link SupplyWagonCartEntity} with no independent movement AI of its own.
 * Features an interactive 27-slot chest with randomized military logistics loot.
 * Drops all contents safely upon destruction.
 */
public class SupplyWagonExtensionEntity extends PathfinderMob implements GeoEntity, MenuProvider {

    private static final EntityDataAccessor<Boolean> IS_MOVING =
            SynchedEntityData.defineId(SupplyWagonExtensionEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> IS_CHEST_OPEN =
            SynchedEntityData.defineId(SupplyWagonExtensionEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> HAS_OPENED_ONCE =
            SynchedEntityData.defineId(SupplyWagonExtensionEntity.class, EntityDataSerializers.BOOLEAN);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private UUID cartWagonUuid = null;
    private final SimpleContainer inventory = new SimpleContainer(27);
    private boolean initializedLoot = false;

    public SupplyWagonExtensionEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                // Strictly lock body rotation to entity yaw to prevent head-swivel drift or snapping
                yBodyRot = getYRot();
                yHeadRot = getYRot();
            }
        };
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 80.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.STEP_HEIGHT, 1.25D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(IS_MOVING, false);
        builder.define(IS_CHEST_OPEN, false);
        builder.define(HAS_OPENED_ONCE, false);
    }

    public boolean isMoving() {
        return this.entityData.get(IS_MOVING);
    }

    public void setMoving(boolean moving) {
        this.entityData.set(IS_MOVING, moving);
    }

    public boolean isChestOpen() {
        return this.entityData.get(IS_CHEST_OPEN);
    }

    public void setChestOpen(boolean open) {
        this.entityData.set(IS_CHEST_OPEN, open);
    }

    public boolean hasOpenedOnce() {
        return this.entityData.get(HAS_OPENED_ONCE);
    }

    public void setHasOpenedOnce(boolean opened) {
        this.entityData.set(HAS_OPENED_ONCE, opened);
    }

    public void setCartWagonUuid(UUID cartWagonUuid) {
        this.cartWagonUuid = cartWagonUuid;
    }

    public UUID getCartWagonUuid() {
        return this.cartWagonUuid;
    }

    public SimpleContainer getInventory() {
        return this.inventory;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.warfront.supply_wagon_chest");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return ChestMenu.threeRows(containerId, playerInventory, this.inventory);
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide) {
            if (!this.initializedLoot) {
                initRandomLoot();
            }

            if (player instanceof ServerPlayer serverPlayer) {
                this.setHasOpenedOnce(true);
                serverPlayer.openMenu(this);
            }
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    public void initRandomLoot() {
        if (this.initializedLoot) return;
        this.initializedLoot = true;

        RandomSource random = this.getRandom();
        this.inventory.clearContent();

        // Military logistical loot
        this.inventory.setItem(1, new ItemStack(Items.IRON_INGOT, 8 + random.nextInt(9)));
        this.inventory.setItem(3, new ItemStack(Items.GOLD_INGOT, 4 + random.nextInt(6)));
        this.inventory.setItem(5, new ItemStack(Items.EMERALD, 3 + random.nextInt(6)));
        this.inventory.setItem(7, new ItemStack(Items.CROSSBOW));
        this.inventory.setItem(10, new ItemStack(Items.ARROW, 24 + random.nextInt(25)));
        this.inventory.setItem(12, new ItemStack(Items.COOKED_BEEF, 8 + random.nextInt(9)));
        this.inventory.setItem(14, new ItemStack(Items.BREAD, 6 + random.nextInt(7)));
        this.inventory.setItem(16, new ItemStack(Items.REDSTONE, 6 + random.nextInt(10)));

        if (random.nextBoolean()) {
            this.inventory.setItem(19, new ItemStack(Items.GOLDEN_APPLE, 1 + random.nextInt(2)));
        }
        if (random.nextBoolean()) {
            this.inventory.setItem(21, new ItemStack(Items.TNT, 2 + random.nextInt(3)));
        } else {
            this.inventory.setItem(21, new ItemStack(Items.GUNPOWDER, 4 + random.nextInt(6)));
        }

        if (random.nextFloat() < 0.65F) {
            this.inventory.setItem(23, new ItemStack(Items.SHIELD));
        } else {
            this.inventory.setItem(23, new ItemStack(Items.IRON_SWORD));
        }

        if (random.nextFloat() < 0.50F) {
            this.inventory.setItem(25, new ItemStack(Items.IRON_CHESTPLATE));
        }
    }

    @Override
    public void push(Entity other) {
        if (other == null) return;
        UUID otherId = other.getUUID();
        if (otherId.equals(cartWagonUuid)) {
            return;
        }
        super.push(other);
    }

    @Override
    public void dropLeash(boolean broadcastPacket, boolean dropItem) {
        super.dropLeash(broadcastPacket, false); // Never drop lead items
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            this.yBodyRot = this.getYRot();
            this.yHeadRot = this.getYRot();
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level();

        // 1. Follow Front Cart Wagon (Trailer hitch physics)
        Entity cartEntity = cartWagonUuid != null ? serverLevel.getEntity(cartWagonUuid) : null;
        if (cartEntity instanceof SupplyWagonCartEntity cartWagon && cartWagon.isAlive()) {
            float cartYaw = cartWagon.getYRot();
            float cartYawRad = cartYaw * Mth.DEG_TO_RAD;
            double fwdX = -Mth.sin(cartYawRad);
            double fwdZ = Mth.cos(cartYawRad);

            // Follow position 4.4 blocks directly behind the front cart
            double targetX = cartWagon.getX() - fwdX * 4.4D;
            double targetZ = cartWagon.getZ() - fwdZ * 4.4D;
            int targetY = this.level().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.round(targetX),
                    (int) Math.round(targetZ)
            );

            double dx = targetX - this.getX();
            double dz = targetZ - this.getZ();
            double distSq = dx * dx + dz * dz;

            if (distSq > 400.0D) {
                // Catch up teleport if separated
                this.teleportTo(targetX, targetY, targetZ);
                this.setYRot(cartYaw);
                this.setYHeadRot(cartYaw);
                this.yBodyRot = cartYaw;
            } else {
                // Smooth rotation tracking matching front cart yaw - eliminates violent 360 snapping
                float newYaw = Mth.rotLerp(0.20F, this.getYRot(), cartYaw);
                this.setYRot(newYaw);
                this.setYHeadRot(newYaw);
                this.yBodyRot = newYaw;

                if (distSq > 0.04D) {
                    double dist = Math.sqrt(distSq);
                    double speed = Math.min(dist * 0.35D, 0.40D);
                    double moveX = (dx / dist) * speed;
                    double moveZ = (dz / dist) * speed;
                    double moveY = (targetY > this.getY() + 0.1D) ? 0.25D : (targetY < this.getY() - 0.2D ? -0.25D : this.getDeltaMovement().y * 0.5D);
                    this.setDeltaMovement(moveX, moveY, moveZ);
                } else {
                    this.setDeltaMovement(0.0D, this.getDeltaMovement().y * 0.5D, 0.0D);
                }
            }
        }

        boolean moving = this.getDeltaMovement().horizontalDistanceSqr() > 0.0004D;
        this.setMoving(moving);

        // 2. Chest open/close detection and animation synchronization
        boolean hasOpener = false;
        for (Player player : serverLevel.players()) {
            if (player.distanceToSqr(this) <= 64.0D
                    && player.containerMenu instanceof ChestMenu menu
                    && menu.getContainer() == this.inventory) {
                hasOpener = true;
                break;
            }
        }

        boolean wasOpen = this.isChestOpen();
        if (hasOpener != wasOpen) {
            this.setChestOpen(hasOpener);
            if (hasOpener) {
                this.setHasOpenedOnce(true);
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6F, 1.0F);
            } else {
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.6F, 1.0F);
            }
        }
    }

    @Override
    public void die(DamageSource damageSource) {
        super.die(damageSource);
        if (!this.level().isClientSide) {
            if (!this.initializedLoot) {
                initRandomLoot();
            }
            // Drop entire inventory without loss upon destruction
            Containers.dropContents(this.level(), this.blockPosition(), this.inventory);
            this.inventory.clearContent();
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.cartWagonUuid != null) {
            tag.putUUID("CartWagon", this.cartWagonUuid);
        }
        tag.putBoolean("InitializedLoot", this.initializedLoot);
        tag.putBoolean("HasOpenedOnce", hasOpenedOnce());

        ListTag itemsTag = new ListTag();
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                itemsTag.add(stack.save(this.registryAccess(), itemTag));
            }
        }
        tag.put("Inventory", itemsTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("CartWagon")) {
            this.cartWagonUuid = tag.getUUID("CartWagon");
        }
        this.initializedLoot = tag.getBoolean("InitializedLoot");
        if (tag.contains("HasOpenedOnce")) {
            this.setHasOpenedOnce(tag.getBoolean("HasOpenedOnce"));
        }

        if (tag.contains("Inventory", Tag.TAG_LIST)) {
            ListTag itemsTag = tag.getList("Inventory", Tag.TAG_COMPOUND);
            this.inventory.clearContent();
            for (int i = 0; i < itemsTag.size(); i++) {
                CompoundTag itemTag = itemsTag.getCompound(i);
                int slot = itemTag.getByte("Slot") & 255;
                if (slot < this.inventory.getContainerSize()) {
                    ItemStack.parse(this.registryAccess(), itemTag).ifPresent(stack -> this.inventory.setItem(slot, stack));
                }
            }
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 2, state -> {
            if (this.isMoving()) {
                return state.setAndContinue(RawAnimation.begin().thenLoop("move"));
            }
            return PlayState.STOP;
        }));

        controllers.add(new AnimationController<>(this, "chest", 0, state -> {
            if (this.isChestOpen()) {
                return state.setAndContinue(RawAnimation.begin().thenPlay("chest_open"));
            } else if (this.hasOpenedOnce()) {
                return state.setAndContinue(RawAnimation.begin().thenPlay("chest_close"));
            }
            return PlayState.STOP;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
