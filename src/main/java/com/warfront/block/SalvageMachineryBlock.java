package com.warfront.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Heavy durable machinery block that houses uncraftable Pristine Cores.
 * Immune to combat damage while locked; disarms and unlocks upon sector victory.
 */
public class SalvageMachineryBlock extends Block {

    public static final BooleanProperty DISARMED = BooleanProperty.create("disarmed");

    public enum SalvageType {
        MORTAR_MOUNT,
        HEAVY_VAULT,
        WAR_DESK,
        HORN_BELL
    }

    private final SalvageType salvageType;

    public SalvageMachineryBlock(SalvageType salvageType) {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5F, 3600000.0F) // Extremely blast resistant to prevent accidental TNT destruction
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
        this.salvageType = salvageType;
        registerDefaultState(this.stateDefinition.any().setValue(DISARMED, false));
    }

    public SalvageType getSalvageType() {
        return salvageType;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DISARMED);
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!state.getValue(DISARMED)) {
            // Unbreakable during active combat
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (!state.getValue(DISARMED) && !level.isClientSide) {
            level.playSound(null, pos, SoundEvents.SHIELD_BLOCK, SoundSource.BLOCKS, 0.8F, 1.2F);
            player.displayClientMessage(
                    Component.literal("§e[Warfront] §6🛡 Armored Machinery: §fProtected during combat! Disarms on Sector Victory."),
                    true
            );
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 6, 0.2D, 0.2D, 0.2D, 0.05D);
            }
        }
        super.attack(state, level, pos, player);
    }

    /**
     * Drops pristine machinery cores upon being mined in disarmed state.
     */
    public List<ItemStack> getSalvageDrops(BlockState state) {
        List<ItemStack> drops = new ArrayList<>();
        if (!state.getValue(DISARMED)) {
            drops.add(new ItemStack(WarfrontBlocks.SCRAP_METAL_DEBRIS.get(), 2));
            return drops;
        }

        switch (salvageType) {
            case MORTAR_MOUNT -> drops.add(new ItemStack(WarfrontBlocks.SALVAGED_MORTAR_CORE.get()));
            case HEAVY_VAULT -> {
                drops.add(new ItemStack(WarfrontBlocks.REINFORCED_REDOUBT_PLATE.get()));
                drops.add(new ItemStack(WarfrontBlocks.HEAVY_PIVOT_BEARING.get()));
            }
            case WAR_DESK -> drops.add(new ItemStack(WarfrontBlocks.COMMANDER_BATTLE_STANDARD.get()));
            case HORN_BELL -> drops.add(new ItemStack(WarfrontBlocks.ACOUSTIC_WAR_HORN_RELAY.get()));
        }
        return drops;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        return getSalvageDrops(state);
    }

    /**
     * Disarms the machinery block at the given position, unlocking it for harvest.
     */
    public static void disarm(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof SalvageMachineryBlock && !state.getValue(DISARMED)) {
            level.setBlock(pos, state.setValue(DISARMED, true), Block.UPDATE_ALL);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 12, 0.3D, 0.3D, 0.3D, 0.05D);
            level.playSound(null, pos, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.8F, 0.8F);
            level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6F, 1.4F);
        }
    }
}
