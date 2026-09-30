package com.warfront.block;

import com.warfront.Warfront;
import com.warfront.item.MapTerminalItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WarfrontBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Warfront.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Warfront.MOD_ID);

    public static final DeferredBlock<Block> CLAIM_CORE = BLOCKS.registerSimpleBlock("claim_core",
            BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT));
    public static final DeferredItem<BlockItem> CLAIM_CORE_ITEM = ITEMS.registerSimpleBlockItem("claim_core", CLAIM_CORE);

    public static final DeferredBlock<Block> COMMAND_TERMINAL = BLOCKS.register("command_terminal",
            () -> new CommandTerminalBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.DIRT)));
    public static final DeferredItem<BlockItem> COMMAND_TERMINAL_ITEM = ITEMS.registerSimpleBlockItem("command_terminal", COMMAND_TERMINAL);

    public static final DeferredItem<Item> MAP_TERMINAL = ITEMS.register("map_terminal",
            () -> new MapTerminalItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> TESTING_MAP_TERMINAL = ITEMS.register("testing_map_terminal",
            () -> new com.warfront.item.TestingMapTerminalItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> PILLAGER_ARMORED_ELITE_SPAWN_EGG = ITEMS.register("pillager_armored_elite_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_ARMORED_ELITE, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_WARRIOR_SPAWN_EGG = ITEMS.register("pillager_warrior_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_WARRIOR, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_SCOUT_SPAWN_EGG = ITEMS.register("pillager_scout_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_SCOUT, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_MARKSMAN_SPAWN_EGG = ITEMS.register("pillager_marksman_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_MARKSMAN, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_COMMANDER_SPAWN_EGG = ITEMS.register("pillager_commander_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_COMMANDER, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    private WarfrontBlocks() {
    }

    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS || event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CLAIM_CORE_ITEM);
            event.accept(COMMAND_TERMINAL_ITEM);
            event.accept(MAP_TERMINAL);
            event.accept(TESTING_MAP_TERMINAL);
        }
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(PILLAGER_ARMORED_ELITE_SPAWN_EGG);
            event.accept(PILLAGER_WARRIOR_SPAWN_EGG);
            event.accept(PILLAGER_SCOUT_SPAWN_EGG);
            event.accept(PILLAGER_MARKSMAN_SPAWN_EGG);
            event.accept(PILLAGER_COMMANDER_SPAWN_EGG);
        }
    }
}
