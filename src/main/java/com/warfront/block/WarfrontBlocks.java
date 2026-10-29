package com.warfront.block;

import com.warfront.Warfront;
import com.warfront.item.MapTerminalItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
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

    public static final DeferredBlock<Block> MISSION_TARGET_CORE = BLOCKS.registerSimpleBlock("mission_target_core",
            BlockBehaviour.Properties.of()
                    .mapColor(net.minecraft.world.level.material.MapColor.COLOR_RED)
                    .strength(50.0F, 0.5F)
                    .sound(net.minecraft.world.level.block.SoundType.METAL)
                    .requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> MISSION_TARGET_CORE_ITEM = ITEMS.registerSimpleBlockItem("mission_target_core", MISSION_TARGET_CORE);

    public static final DeferredItem<Item> MAP_TERMINAL = ITEMS.register("map_terminal",
            () -> new MapTerminalItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> TESTING_MAP_TERMINAL = ITEMS.register("testing_map_terminal",
            () -> new com.warfront.item.TestingMapTerminalItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> PILLAGER_ELITE_SPAWN_EGG = ITEMS.register("pillager_elite_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_ELITE, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_WARRIOR_SPAWN_EGG = ITEMS.register("pillager_warrior_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_WARRIOR, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_SCOUT_SPAWN_EGG = ITEMS.register("pillager_scout_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_SCOUT, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_RIFLEMAN_SPAWN_EGG = ITEMS.register("pillager_rifleman_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_RIFLEMAN, 0x4B5320, 0x8B4513, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_MARKSMAN_SPAWN_EGG = ITEMS.register("pillager_marksman_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_MARKSMAN, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_COMMANDER_SPAWN_EGG = ITEMS.register("pillager_commander_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_COMMANDER, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_CANNONEER_SPAWN_EGG = ITEMS.register("pillager_cannoneer_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_CANNONEER, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_RAPIDBOW_SPAWN_EGG = ITEMS.register("pillager_rapidbow_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_RAPIDBOW, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    public static final DeferredItem<Item> PILLAGER_IRONCLAD_SPAWN_EGG = ITEMS.register("pillager_ironclad_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_IRONCLAD, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

    // --- Reward / Pristine Machinery Blocks & Items ---
    public static final DeferredBlock<Block> SALVAGED_MORTAR_CORE = BLOCKS.registerSimpleBlock("salvaged_mortar_core",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 1200.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> SALVAGED_MORTAR_CORE_ITEM = ITEMS.registerSimpleBlockItem("salvaged_mortar_core", SALVAGED_MORTAR_CORE);

    public static final DeferredBlock<Block> HEAVY_PIVOT_BEARING = BLOCKS.registerSimpleBlock("heavy_pivot_bearing",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 1200.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> HEAVY_PIVOT_BEARING_ITEM = ITEMS.registerSimpleBlockItem("heavy_pivot_bearing", HEAVY_PIVOT_BEARING);

    public static final DeferredItem<Item> REINFORCED_REDOUBT_PLATE = ITEMS.register("reinforced_redoubt_plate",
            () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> ACOUSTIC_WAR_HORN_RELAY = ITEMS.register("acoustic_war_horn_relay",
            () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> COMMANDER_BATTLE_STANDARD = ITEMS.register("commander_battle_standard",
            () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> SCRAP_METAL_DEBRIS = ITEMS.register("scrap_metal_debris",
            () -> new Item(new Item.Properties()));

    // --- Sabotage Combat Targets (Volatile, Destroyed mid-combat) ---
    public static final DeferredBlock<Block> AMMUNITION_CRATE_TARGET = BLOCKS.register("ammunition_crate_target",
            () -> new SabotageTargetBlock(SabotageTargetBlock.SabotageType.AMMUNITION_CRATE));
    public static final DeferredItem<BlockItem> AMMUNITION_CRATE_TARGET_ITEM = ITEMS.registerSimpleBlockItem("ammunition_crate_target", AMMUNITION_CRATE_TARGET);

    public static final DeferredBlock<Block> DEFENSE_SHIELD_RELAY = BLOCKS.register("defense_shield_relay",
            () -> new SabotageTargetBlock(SabotageTargetBlock.SabotageType.SHIELD_RELAY));
    public static final DeferredItem<BlockItem> DEFENSE_SHIELD_RELAY_ITEM = ITEMS.registerSimpleBlockItem("defense_shield_relay", DEFENSE_SHIELD_RELAY);

    public static final DeferredBlock<Block> SIGNAL_FUSE_RELAY = BLOCKS.register("signal_fuse_relay",
            () -> new SabotageTargetBlock(SabotageTargetBlock.SabotageType.SIGNAL_FUSE));
    public static final DeferredItem<BlockItem> SIGNAL_FUSE_RELAY_ITEM = ITEMS.registerSimpleBlockItem("signal_fuse_relay", SIGNAL_FUSE_RELAY);

    // --- Salvage Machinery Blocks (Armored during combat, disarmed on victory) ---
    public static final DeferredBlock<Block> HEAVY_MACHINERY_VAULT = BLOCKS.register("heavy_machinery_vault",
            () -> new SalvageMachineryBlock(SalvageMachineryBlock.SalvageType.HEAVY_VAULT));
    public static final DeferredItem<BlockItem> HEAVY_MACHINERY_VAULT_ITEM = ITEMS.registerSimpleBlockItem("heavy_machinery_vault", HEAVY_MACHINERY_VAULT);

    public static final DeferredBlock<Block> MORTAR_CANNON_MOUNT = BLOCKS.register("mortar_cannon_mount",
            () -> new SalvageMachineryBlock(SalvageMachineryBlock.SalvageType.MORTAR_MOUNT));
    public static final DeferredItem<BlockItem> MORTAR_CANNON_MOUNT_ITEM = ITEMS.registerSimpleBlockItem("mortar_cannon_mount", MORTAR_CANNON_MOUNT);

    public static final DeferredBlock<Block> COMMANDER_WAR_DESK = BLOCKS.register("commander_war_desk",
            () -> new SalvageMachineryBlock(SalvageMachineryBlock.SalvageType.WAR_DESK));
    public static final DeferredItem<BlockItem> COMMANDER_WAR_DESK_ITEM = ITEMS.registerSimpleBlockItem("commander_war_desk", COMMANDER_WAR_DESK);

    public static final DeferredBlock<Block> ACOUSTIC_HORN_BELL = BLOCKS.register("acoustic_horn_bell",
            () -> new SalvageMachineryBlock(SalvageMachineryBlock.SalvageType.HORN_BELL));
    public static final DeferredItem<BlockItem> ACOUSTIC_HORN_BELL_ITEM = ITEMS.registerSimpleBlockItem("acoustic_horn_bell", ACOUSTIC_HORN_BELL);

    // --- Heavy Emplacements ---
    public static final DeferredBlock<MortarBlock> MORTAR = BLOCKS.register("mortar", MortarBlock::new);
    public static final DeferredItem<BlockItem> MORTAR_ITEM = ITEMS.registerSimpleBlockItem("mortar", MORTAR);

    public static final DeferredBlock<AmmoRackBlock> AMMO_RACK = BLOCKS.register("ammo_rack", () -> new AmmoRackBlock());
    public static final DeferredItem<BlockItem> AMMO_RACK_ITEM = ITEMS.registerSimpleBlockItem("ammo_rack", AMMO_RACK);

    private WarfrontBlocks() {
    }

    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(CLAIM_CORE_ITEM);
            event.accept(COMMAND_TERMINAL_ITEM);
            event.accept(MISSION_TARGET_CORE_ITEM);
            // Sabotage & Salvage Blocks
            event.accept(SALVAGED_MORTAR_CORE_ITEM);
            event.accept(HEAVY_PIVOT_BEARING_ITEM);
            event.accept(AMMUNITION_CRATE_TARGET_ITEM);
            event.accept(DEFENSE_SHIELD_RELAY_ITEM);
            event.accept(SIGNAL_FUSE_RELAY_ITEM);
            event.accept(HEAVY_MACHINERY_VAULT_ITEM);
            event.accept(MORTAR_CANNON_MOUNT_ITEM);
            event.accept(MORTAR_ITEM);
            event.accept(AMMO_RACK_ITEM);
            event.accept(COMMANDER_WAR_DESK_ITEM);
            event.accept(ACOUSTIC_HORN_BELL_ITEM);
        } else if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(MAP_TERMINAL);
            event.accept(TESTING_MAP_TERMINAL);
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(SALVAGED_MORTAR_CORE_ITEM);
            event.accept(HEAVY_PIVOT_BEARING_ITEM);
            event.accept(REINFORCED_REDOUBT_PLATE);
            event.accept(ACOUSTIC_WAR_HORN_RELAY);
            event.accept(COMMANDER_BATTLE_STANDARD);
            event.accept(SCRAP_METAL_DEBRIS);
        } else if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(PILLAGER_ELITE_SPAWN_EGG);
            event.accept(PILLAGER_WARRIOR_SPAWN_EGG);
            event.accept(PILLAGER_SCOUT_SPAWN_EGG);
            event.accept(PILLAGER_RIFLEMAN_SPAWN_EGG);
            event.accept(PILLAGER_MARKSMAN_SPAWN_EGG);
            event.accept(PILLAGER_COMMANDER_SPAWN_EGG);
            event.accept(PILLAGER_CANNONEER_SPAWN_EGG);
            event.accept(PILLAGER_RAPIDBOW_SPAWN_EGG);
            event.accept(PILLAGER_IRONCLAD_SPAWN_EGG);
        }
    }
}
