package com.warfront.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.warfront.Warfront;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import java.util.Arrays;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
                                                .executes(RegionCommands::setOwner))))
                        .then(Commands.literal("wipe-faction")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("faction", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(Faction.values()).filter(f -> f != Faction.UNCLAIMED).map(Faction::commandName), builder))
                                        .executes(RegionCommands::wipeFaction)))
                        .then(Commands.literal("base")
                                .then(Commands.literal("locate")
                                        .executes(RegionCommands::locateBase))
                                .then(Commands.literal("place")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(RegionCommands::placeBaseHere)
                                        .then(Commands.argument("rx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .then(Commands.argument("rz", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                        .executes(RegionCommands::placeBaseAtRegion))))
                                .then(Commands.literal("clear")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(RegionCommands::clearBasePlacement))));
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

    private static int wipeFaction(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Faction faction = Faction.byCommandName(StringArgumentType.getString(context, "faction"))
                .orElseThrow(() -> CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create());

        ServerLevel level = player.serverLevel();
        RegionData regions = RegionData.get(level);

        RegionData.Region currentRegion = regions.regionAt(player.blockPosition());
        int centerRX = currentRegion.x();
        int centerRZ = currentRegion.z();

        int wipedCount = 0;
        for (int dx = -12; dx <= 11; dx++) {
            for (int dz = -12; dz <= 11; dz++) {
                int rx = centerRX + dx;
                int rz = centerRZ + dz;
                RegionData.Region region = regions.regionAt(rx, rz);
                if (region.owner() == faction) {
                    regions.setOwner(rx, rz, Faction.UNCLAIMED);
                    wipedCount++;
                }
            }
        }

        com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);

        final int count = wipedCount;
        context.getSource().sendSuccess(
                () -> Component.literal(String.format("§a[Warfront] Wiped %d region(s) owned by %s from visible 24x24 grid.", count, faction.displayName())), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int locateBase(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(player.blockPosition());

        BlockPos anchor = region.baseAnchor();
        if (anchor == null && region.baseType() != BaseType.NONE) {
            anchor = com.warfront.region.generator.ProceduralRegionGenerator.getInstance()
                    .findPhysicalBaseAnchor(level, level.getSeed(), region.x(), region.z(), region.baseType())
                    .orElse(null);
        }

        String anchorStr = (anchor != null)
                ? String.format("[%d, %d, %d] (distance: %.1f blocks)", anchor.getX(), anchor.getY(), anchor.getZ(),
                Math.sqrt(player.distanceToSqr(anchor.getX(), anchor.getY(), anchor.getZ())))
                : "None";

        String statusMsg = String.format(
                "§6[Warfront Base Info]§r\n Region: (%d, %d)\n Owner: %s\n Base Type: %s\n Base Anchor: %s\n Placed in World: %s",
                region.x(), region.z(),
                region.owner().displayName().getString(),
                region.baseType().name(),
                anchorStr,
                region.basePlaced() ? "§aYES§r" : "§cNO§r"
        );

        context.getSource().sendSuccess(() -> Component.literal(statusMsg), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int placeBaseHere(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(player.blockPosition());
        return doPlaceBase(context, level, region.x(), region.z());
    }

    private static int placeBaseAtRegion(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        int rx = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "rx");
        int rz = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "rz");
        return doPlaceBase(context, level, rx, rz);
    }

    private static int doPlaceBase(CommandContext<CommandSourceStack> context, ServerLevel level, int rx, int rz) {
        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(rx, rz);

        if (region.baseType() == BaseType.NONE) {
            context.getSource().sendFailure(Component.literal(String.format("§cRegion (%d, %d) has no base type assigned (NONE).", rx, rz)));
            return 0;
        }

        boolean success = com.warfront.region.base.BasePlacementManager.tryPlaceBase(level, rx, rz, true);
        if (success) {
            BlockPos anchor = regions.regionAt(rx, rz).baseAnchor();
            context.getSource().sendSuccess(
                    () -> Component.literal(String.format("§a[Warfront] Successfully placed base for %s at %s in region (%d, %d)!",
                            region.owner().displayName().getString(), anchor != null ? anchor.toShortString() : "anchor", rx, rz)), true);
            return Command.SINGLE_SUCCESS;
        } else {
            context.getSource().sendFailure(Component.literal(String.format("§cFailed to place base for region (%d, %d). Check owner and anchor validity.", rx, rz)));
            return 0;
        }
    }

    private static int clearBasePlacement(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        RegionData regions = RegionData.get(level);
        RegionData.Region region = regions.regionAt(player.blockPosition());
        regions.setBasePlaced(region.x(), region.z(), false);
        context.getSource().sendSuccess(
                () -> Component.literal(String.format("§a[Warfront] Reset base placement state for region (%d, %d). You can now test placing it again.", region.x(), region.z())), true);
        return Command.SINGLE_SUCCESS;
    }
}
