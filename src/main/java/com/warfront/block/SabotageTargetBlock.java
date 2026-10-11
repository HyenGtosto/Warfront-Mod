package com.warfront.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Volatile enemy installation block that players must sabotage/destroy to complete mission objectives.
 * Has low explosion resistance (0.5F) to incentivize TNT detonations.
 */
public class SabotageTargetBlock extends Block {

    public enum SabotageType {
        AMMUNITION_CRATE,
        SHIELD_RELAY,
        SIGNAL_FUSE
    }

    private final SabotageType sabotageType;

    public SabotageTargetBlock(SabotageType sabotageType) {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_RED)
                .strength(2.0F, 0.5F)
                .sound(sabotageType == SabotageType.AMMUNITION_CRATE ? SoundType.WOOD : SoundType.METAL)
                .requiresCorrectToolForDrops());
        this.sabotageType = sabotageType;
    }

    public SabotageType getSabotageType() {
        return sabotageType;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            triggerSabotageEffects(serverLevel, pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (!level.isClientSide) {
            player.displayClientMessage(
                    Component.literal("§c[Warfront] §4⚔ SABOTAGE TARGET: §fDestroy to advance mission objective!"),
                    true
            );
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.SMALL_FLAME, pos.getX() + 0.5D, pos.getY() + 0.8D, pos.getZ() + 0.5D, 4, 0.2D, 0.2D, 0.2D, 0.05D);
            }
        }
        super.attack(state, level, pos, player);
    }

    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        return java.util.List.of(new net.minecraft.world.item.ItemStack(WarfrontBlocks.SCRAP_METAL_DEBRIS.get()));
    }

    public void triggerSabotageEffects(ServerLevel level, BlockPos pos) {
        switch (sabotageType) {
            case AMMUNITION_CRATE -> {
                // Secondary ammunition pop and fire smoke
                level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0F, 1.2F);
                level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 1, 0, 0, 0, 0);
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 10, 0.3D, 0.3D, 0.3D, 0.05D);
            }
            case SHIELD_RELAY -> {
                level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 0.8F, 1.8F);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 15, 0.4D, 0.4D, 0.4D, 0.1D);
                level.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 8, 0.2D, 0.2D, 0.2D, 0.03D);
            }
            case SIGNAL_FUSE -> {
                level.playSound(null, pos, SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F);
                level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 12, 0.3D, 0.3D, 0.3D, 0.04D);
            }
        }
    }
}
