package com.warfront.block;

import com.warfront.block.entity.FriendlyBallistaBlockEntity;
import com.warfront.block.entity.WarfrontBlockEntities;
import com.warfront.claim.PlayerConstructionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Friendly Giant Ballista Support emplacement constructed by players.
 *
 * Characteristics:
 * - Direct-fire supersonic piercing bolts against hostile entities.
 * - Solid 1x1 block interaction box for reliable player click targeting.
 * - Powered by standard furnace fuel (Coal, Charcoal, etc.) with Ballista Bolt ammunition.
 * - Participates in regional fortification quotas and strategic stability.
 */
public class FriendlyBallistaBlock extends Block implements EntityBlock {

    private static final VoxelShape SHAPE = Shapes.block();

    public FriendlyBallistaBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(5.0F, 6.0F)
                .sound(SoundType.WOOD)
                .requiresCorrectToolForDrops()
                .noOcclusion());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (player.isSecondaryUseActive()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof FriendlyBallistaBlockEntity ballista && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(ballista, pos);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof FriendlyBallistaBlockEntity ballista && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(ballista, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            BlockEntity be = serverLevel.getBlockEntity(pos);
            if (be instanceof FriendlyBallistaBlockEntity ballista && placer instanceof ServerPlayer player) {
                com.warfront.faction.PlayerFaction pf = com.warfront.faction.PlayerFactionData.get(serverLevel).getFactionByPlayer(player.getUUID());
                if (pf != null) {
                    ballista.setOwnerFactionId(pf.getId());
                }
            }
            PlayerConstructionTracker.getInstance().onBlockPlaced(serverLevel, pos, state);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof FriendlyBallistaBlockEntity ballista) {
                Containers.dropContents(level, pos, ballista);
                level.updateNeighbourForOutputSignal(pos, this);
            }
            if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
                PlayerConstructionTracker.getInstance().onBlockBroken(serverLevel, pos, state);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FriendlyBallistaBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (blockEntityType != WarfrontBlockEntities.FRIENDLY_BALLISTA.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof FriendlyBallistaBlockEntity ballista) {
                if (lvl.isClientSide()) {
                    ballista.clientTick();
                } else {
                    ballista.serverTick();
                }
            }
        };
    }
}
