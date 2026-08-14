package com.warfront.block;

import com.warfront.network.RequestRegionMapPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class CommandTerminalBlock extends Block {
    public CommandTerminalBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            com.warfront.network.RequestRegionMapPayload.sendSnapshot(serverPlayer, com.warfront.map.MapViewType.COMMAND, true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
