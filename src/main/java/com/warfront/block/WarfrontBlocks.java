package com.warfront.block;

import com.warfront.Warfront;
import com.warfront.item.MapTerminalItem;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
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
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_RIFLEMAN, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

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

    public static final DeferredItem<Item> PILLAGER_LEGIONNAIRE_SPAWN_EGG = ITEMS.register("pillager_legionnaire_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(com.warfront.entity.ModEntities.PILLAGER_LEGIONNAIRE, 0xFFFFFF, 0xFFFFFF, new Item.Properties()));

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

    // --- Biome Tactical Salvage & Specialized Components ---
    public static final DeferredItem<Item> OPTICAL_RANGEFINDER_LENS = ITEMS.register("optical_rangefinder_lens",
            () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> CURED_SIEGE_TIMBER = ITEMS.register("cured_siege_timber",
            () -> new Item(new Item.Properties().stacksTo(64)));
    public static final DeferredItem<Item> GIANT_BOLT_BUNDLE = ITEMS.register("giant_bolt_bundle",
            () -> new Item(new Item.Properties().stacksTo(16)) {
                @Override
                public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
                    ItemStack held = player.getItemInHand(hand);
                    if (!level.isClientSide()) {
                        held.shrink(1);
                        ItemStack bolts = new ItemStack(BALLISTA_BOLT_ITEM.get(), 16);
                        if (!player.getInventory().add(bolts)) {
                            player.drop(bolts, false);
                        }
                        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                                SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 1.0F, 1.0F);
                    }
                    return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
                }
            });
    public static final DeferredItem<Item> ALCHEMICAL_SULFUR_SLAG = ITEMS.register("alchemical_sulfur_slag",
            () -> new Item(new Item.Properties().stacksTo(64)) {
                @Override
                public int getBurnTime(ItemStack itemStack, @org.jetbrains.annotations.Nullable RecipeType<?> recipeType) {
                    return 6400; // 4x Coal burn duration (320 seconds)
                }
            });
    public static final DeferredItem<Item> FROST_TEMPERED_HEATSINK = ITEMS.register("frost_tempered_heatsink",
            () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> CRYO_ARTILLERY_SHELL = ITEMS.register("cryo_artillery_shell",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> PILLAGER_JAVELIN = ITEMS.register("pillager_javelin",
            () -> new com.warfront.item.PillagerJavelinItem(new Item.Properties().stacksTo(4)));

    // --- Zombie Faction Bio-Stims (No recipes yet; tested on Pillager gameplay first) ---
    public static final DeferredItem<Item> COMBAT_STIM = ITEMS.register("combat_stim",
            () -> new com.warfront.item.StimInjectorItem(
                    com.warfront.item.StimInjectorItem.StimType.COMBAT,
                    new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> ACROBATICS_STIM = ITEMS.register("acrobatics_stim",
            () -> new com.warfront.item.StimInjectorItem(
                    com.warfront.item.StimInjectorItem.StimType.ACROBATICS,
                    new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> HEALING_STIM = ITEMS.register("healing_stim",
            () -> new com.warfront.item.StimInjectorItem(
                    com.warfront.item.StimInjectorItem.StimType.HEALING,
                    new Item.Properties().stacksTo(16)));

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

    public static final DeferredBlock<PillagerBallistaBlock> PILLAGER_BALLISTA = BLOCKS.register("pillager_ballista", PillagerBallistaBlock::new);
    public static final DeferredItem<BlockItem> PILLAGER_BALLISTA_ITEM = ITEMS.registerSimpleBlockItem("pillager_ballista", PILLAGER_BALLISTA);

    public static final DeferredBlock<AmmoRackBlock> AMMO_RACK = BLOCKS.register("ammo_rack", () -> new AmmoRackBlock());
    public static final DeferredItem<BlockItem> AMMO_RACK_ITEM = ITEMS.registerSimpleBlockItem("ammo_rack", AMMO_RACK);

    public static final DeferredBlock<FriendlyMortarBlock> FRIENDLY_MORTAR = BLOCKS.register("friendly_mortar", FriendlyMortarBlock::new);
    public static final DeferredItem<BlockItem> FRIENDLY_MORTAR_ITEM = ITEMS.registerSimpleBlockItem("friendly_mortar", FRIENDLY_MORTAR);

    public static final DeferredBlock<FriendlyBallistaBlock> FRIENDLY_BALLISTA = BLOCKS.register("friendly_ballista", FriendlyBallistaBlock::new);
    public static final DeferredItem<BlockItem> FRIENDLY_BALLISTA_ITEM = ITEMS.registerSimpleBlockItem("friendly_ballista", FRIENDLY_BALLISTA);

    // --- Artillery & Ballista Ammunition ---
    public static final DeferredItem<Item> BALLISTA_BOLT_ITEM = ITEMS.register("ballista_bolt",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> HE_ARTILLERY_SHELL = ITEMS.register("he_artillery_shell",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> INCENDIARY_ARTILLERY_SHELL = ITEMS.register("incendiary_artillery_shell",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> AIRBURST_ARTILLERY_SHELL = ITEMS.register("airburst_artillery_shell",
            () -> new Item(new Item.Properties().stacksTo(16)));
    public static final DeferredItem<Item> SMOKE_ARTILLERY_SHELL = ITEMS.register("smoke_artillery_shell",
            () -> new Item(new Item.Properties().stacksTo(16)));

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
            event.accept(PILLAGER_BALLISTA_ITEM);
            event.accept(AMMO_RACK_ITEM);
            event.accept(FRIENDLY_MORTAR_ITEM);
            event.accept(FRIENDLY_BALLISTA_ITEM);
            event.accept(BALLISTA_BOLT_ITEM);
            event.accept(HE_ARTILLERY_SHELL);
            event.accept(INCENDIARY_ARTILLERY_SHELL);
            event.accept(AIRBURST_ARTILLERY_SHELL);
            event.accept(SMOKE_ARTILLERY_SHELL);
            event.accept(COMMANDER_WAR_DESK_ITEM);
            event.accept(ACOUSTIC_HORN_BELL_ITEM);
        } else if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(FRIENDLY_MORTAR_ITEM);
            event.accept(FRIENDLY_BALLISTA_ITEM);
            event.accept(BALLISTA_BOLT_ITEM);
            event.accept(GIANT_BOLT_BUNDLE);
            event.accept(HE_ARTILLERY_SHELL);
            event.accept(INCENDIARY_ARTILLERY_SHELL);
            event.accept(AIRBURST_ARTILLERY_SHELL);
            event.accept(SMOKE_ARTILLERY_SHELL);
            event.accept(CRYO_ARTILLERY_SHELL);
            event.accept(PILLAGER_JAVELIN);
            event.accept(COMBAT_STIM);
            event.accept(ACROBATICS_STIM);
            event.accept(HEALING_STIM);
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
            event.accept(OPTICAL_RANGEFINDER_LENS);
            event.accept(CURED_SIEGE_TIMBER);
            event.accept(GIANT_BOLT_BUNDLE);
            event.accept(ALCHEMICAL_SULFUR_SLAG);
            event.accept(FROST_TEMPERED_HEATSINK);
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
            event.accept(PILLAGER_LEGIONNAIRE_SPAWN_EGG);
        }
    }
}
