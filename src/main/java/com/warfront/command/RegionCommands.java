package com.warfront.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.warfront.Warfront;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import java.util.Arrays;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = Warfront.MOD_ID)
public final class RegionCommands {
    private RegionCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("warfront")
                        .then(Commands.literal("region")
                                .then(Commands.literal("set-owner")
                                        .requires(source -> source.hasPermission(2))
                                        .then(Commands.argument("faction", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                        Arrays.stream(Faction.values()).map(Faction::commandName), builder))
                                                .executes(RegionCommands::setOwner)))));
    }

    private static int setOwner(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Faction faction = Faction.byCommandName(StringArgumentType.getString(context, "faction"))
                .orElseThrow(() -> CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create());
        RegionData regions = RegionData.get(player.serverLevel());
        RegionData.Region region = regions.regionAt(player.blockPosition());
        regions.setOwner(region.x(), region.z(), faction);
        context.getSource().sendSuccess(
                () -> Component.translatable("command.warfront.region.owner_set", faction.displayName(), region.x(), region.z()), true);
        return Command.SINGLE_SUCCESS;
    }
}
