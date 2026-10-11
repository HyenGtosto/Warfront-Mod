package com.warfront.block;

import com.warfront.block.entity.MortarBlockEntity;
import com.warfront.block.entity.WarfrontBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Heavy Automaton/Pillager Mortar Emplacement block.
 *
 * Characteristics:
 * - Immune to mining damage (hardness -1.0F / 0 destroy progress).
 * - Weak to explosives (TNT / heavy explosions break it).
 * - Underneath the base is a salvageable reward block (SalvageMachineryBlock.MORTAR_MOUNT).
 */
public class MortarBlock extends Block implements EntityBlock {

    // 3x3 footprint bounds centered on this block (from -1 to +2 in block coords, height 1.75)
    private static final VoxelShape SHAPE = Shapes.or(
            Shapes.box(-1.0D, 0.0D, -1.0D, 2.0D, 0.75D, 2.0D),
            Shapes.box(-0.6D, 0.75D, -0.6D, 1.6D, 2.0D, 1.6D)
    );

    public MortarBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(-1.0F, 1.5F) // Unbreakable by mining (-1.0F), weak to explosives (1.5F)
                .sound(SoundType.NETHERITE_BLOCK)
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
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        // Absolutely immune to mining
        return 0.0F;
    }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (!level.isClientSide()) {
            player.displayClientMessage(
                    Component.literal("§c[Warfront] §7Heavy reinforced chassis is §eimmune to mining§7! Detonate with §cTNT / Explosives§7."),
                    true
            );
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5D, pos.getY() + 0.8D, pos.getZ() + 0.5D, 5, 0.2D, 0.2D, 0.2D, 0.1D);
            }
        }
    }

    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            // Concussive shrapnel & destruction feedback
            serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 2, 0.4D, 0.4D, 0.4D, 0.0D);
            serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 15, 0.6D, 0.6D, 0.6D, 0.08D);
            serverLevel.playSound(null, pos, SoundEvents.ANVIL_DESTROY, SoundSource.BLOCKS, 2.5F, 0.8F);
            serverLevel.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 3.0F, 0.9F);
        }
        super.onBlockExploded(state, level, pos, explosion);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MortarBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (blockEntityType != WarfrontBlockEntities.MORTAR.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof MortarBlockEntity mortar) {
                if (lvl.isClientSide()) {
                    mortar.clientTick();
                } else {
                    mortar.serverTick();
                }
            }
        };
    }
}
