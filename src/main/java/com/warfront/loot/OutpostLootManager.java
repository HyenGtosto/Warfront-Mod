package com.warfront.loot;

import com.warfront.block.WarfrontBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages chest loot population and zero-drop cleanup for Pillager Outposts.
 *
 * Rules:
 * - Barrels are ignored; only chests are targeted.
 * - Lookout tower chests (4 at bottom Y=1): arrow and bow supplies.
 * - Floor 1 chests (2 between beds at Y=1): warrior combat gear.
 * - Floor 2 chest (1 double chest at Y=5): war spoils and valuables.
 * - Loot is populated only when mission is active.
 * - On mission defeat/abandonment, inventories are cleared cleanly without dropping items into the world.
 */
public final class OutpostLootManager {

    private static final int TEMPLATE_HALF_X = 14;
    private static final int TEMPLATE_HALF_Z = 14;
    private static final int TEMPLATE_Y_OFFSET = -1;

    // Relative chest coordinates in pillager_outpost.nbt (29x25x29)
    private static final List<BlockPos> RELATIVE_TOWER_CHESTS = List.of(
            new BlockPos(3, 1, 3),    // NW tower
            new BlockPos(3, 1, 26),   // SW tower
            new BlockPos(25, 1, 3),   // NE tower
            new BlockPos(25, 1, 26)   // SE tower
    );

    private static final List<BlockPos> RELATIVE_FLOOR1_CHESTS = List.of(
            new BlockPos(10, 1, 12),  // Barracks bed chest 1
            new BlockPos(10, 1, 16)   // Barracks bed chest 2
    );

    private static final List<BlockPos> RELATIVE_FLOOR2_DOUBLE_CHEST = List.of(
            new BlockPos(17, 5, 18),  // Double chest left
            new BlockPos(18, 5, 18)   // Double chest right
    );

    private OutpostLootManager() {
    }

    public static BlockPos getStructureOrigin(BlockPos anchor) {
        return new BlockPos(
                anchor.getX() - TEMPLATE_HALF_X,
                anchor.getY() + TEMPLATE_Y_OFFSET,
                anchor.getZ() - TEMPLATE_HALF_Z
        );
    }

    public static List<BlockPos> getAllOutpostChestPositions(BlockPos anchor) {
        BlockPos origin = getStructureOrigin(anchor);
        List<BlockPos> positions = new ArrayList<>(8);
        for (BlockPos rel : RELATIVE_TOWER_CHESTS) {
            positions.add(origin.offset(rel));
        }
        for (BlockPos rel : RELATIVE_FLOOR1_CHESTS) {
            positions.add(origin.offset(rel));
        }
        for (BlockPos rel : RELATIVE_FLOOR2_DOUBLE_CHEST) {
            positions.add(origin.offset(rel));
        }
        return positions;
    }

    /**
     * Clears all chest contents in the outpost so no items drop into the world.
     */
    public static void clearOutpostChests(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) {
            return;
        }
        List<BlockPos> chests = getAllOutpostChestPositions(anchor);
        for (BlockPos pos : chests) {
            if (level.hasChunkAt(pos)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof Container container) {
                    container.clearContent();
                    be.setChanged();
                }
            }
        }
    }

    /**
     * Populates all outpost chests with curated, balanced mission loot.
     */
    public static void populateOutpostChests(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) {
            return;
        }

        BlockPos origin = getStructureOrigin(anchor);
        RandomSource random = level.getRandom();

        // 1. Tower chests (Bow & Arrow supplies)
        for (BlockPos rel : RELATIVE_TOWER_CHESTS) {
            BlockPos worldPos = origin.offset(rel);
            if (!level.hasChunkAt(worldPos)) continue;
            BlockEntity be = level.getBlockEntity(worldPos);
            if (be instanceof Container container) {
                container.clearContent();
                fillTowerChest(container, random);
                be.setChanged();
            }
        }

        // 2. Floor 1 Barracks chests (Warrior combat gear)
        for (BlockPos rel : RELATIVE_FLOOR1_CHESTS) {
            BlockPos worldPos = origin.offset(rel);
            if (!level.hasChunkAt(worldPos)) continue;
            BlockEntity be = level.getBlockEntity(worldPos);
            if (be instanceof Container container) {
                container.clearContent();
                fillFloor1Chest(container, random);
                be.setChanged();
            }
        }

        // 3. Floor 2 Commander double chest (War spoils & valuables)
        // Double chest consists of 2 half-blocks; populate across the two containers
        List<ItemStack> valuables = generateFloor2Valuables(level, random);
        int halfSize = valuables.size() / 2;
        List<ItemStack> part1 = valuables.subList(0, halfSize);
        List<ItemStack> part2 = valuables.subList(halfSize, valuables.size());

        for (int i = 0; i < RELATIVE_FLOOR2_DOUBLE_CHEST.size(); i++) {
            BlockPos worldPos = origin.offset(RELATIVE_FLOOR2_DOUBLE_CHEST.get(i));
            if (!level.hasChunkAt(worldPos)) continue;
            BlockEntity be = level.getBlockEntity(worldPos);
            if (be instanceof Container container) {
                container.clearContent();
                List<ItemStack> toPlace = (i == 0) ? part1 : part2;
                distributeItems(container, toPlace, random);
                be.setChanged();
            }
        }
    }

    private static void fillTowerChest(Container container, RandomSource random) {
        List<ItemStack> items = new ArrayList<>();
        // Arrows: 12..24
        items.add(new ItemStack(Items.ARROW, 12 + random.nextInt(13)));

        // Tipped arrows (slowness or poison)
        ItemStack tipped = PotionContents.createItemStack(Items.TIPPED_ARROW,
                random.nextBoolean() ? Potions.SLOWNESS : Potions.POISON);
        tipped.setCount(2 + random.nextInt(3));
        items.add(tipped);

        // Crossbow (40% chance) or Bow (40% chance)
        float weaponRoll = random.nextFloat();
        if (weaponRoll < 0.40F) {
            ItemStack crossbow = new ItemStack(Items.CROSSBOW);
            crossbow.setDamageValue(crossbow.getMaxDamage() / 4 + random.nextInt(crossbow.getMaxDamage() / 2));
            items.add(crossbow);
        } else if (weaponRoll < 0.80F) {
            ItemStack bow = new ItemStack(Items.BOW);
            bow.setDamageValue(bow.getMaxDamage() / 4 + random.nextInt(bow.getMaxDamage() / 2));
            items.add(bow);
        }

        // Crafting supplies
        items.add(new ItemStack(Items.FEATHER, 4 + random.nextInt(5)));
        items.add(new ItemStack(Items.STRING, 2 + random.nextInt(4)));
        items.add(new ItemStack(Items.FLINT, 3 + random.nextInt(4)));

        distributeItems(container, items, random);
    }

    private static void fillFloor1Chest(Container container, RandomSource random) {
        List<ItemStack> items = new ArrayList<>();

        // Melee weapon (Iron Axe 50% or Iron Sword 50%)
        if (random.nextBoolean()) {
            ItemStack axe = new ItemStack(Items.IRON_AXE);
            axe.setDamageValue(random.nextInt(axe.getMaxDamage() / 3));
            items.add(axe);
        } else {
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.setDamageValue(random.nextInt(sword.getMaxDamage() / 3));
            items.add(sword);
        }

        // Shield (35% chance)
        if (random.nextFloat() < 0.35F) {
            items.add(new ItemStack(Items.SHIELD));
        }

        // Armor piece (30% chance)
        if (random.nextFloat() < 0.30F) {
            ItemStack[] armorPool = {
                    new ItemStack(Items.CHAINMAIL_HELMET),
                    new ItemStack(Items.CHAINMAIL_BOOTS),
                    new ItemStack(Items.IRON_HELMET),
                    new ItemStack(Items.IRON_BOOTS)
            };
            items.add(armorPool[random.nextInt(armorPool.length)]);
        }

        // Supplies
        items.add(new ItemStack(random.nextBoolean() ? Items.BREAD : Items.COOKED_BEEF, 3 + random.nextInt(4)));
        items.add(new ItemStack(Items.IRON_INGOT, 2 + random.nextInt(3)));
        items.add(new ItemStack(Items.LEATHER, 2 + random.nextInt(4)));

        distributeItems(container, items, random);
    }

    private static List<ItemStack> generateFloor2Valuables(ServerLevel level, RandomSource random) {
        List<ItemStack> items = new ArrayList<>();

        // Emeralds: 4..8
        items.add(new ItemStack(Items.EMERALD, 4 + random.nextInt(5)));

        // Gold Ingots: 3..6
        items.add(new ItemStack(Items.GOLD_INGOT, 3 + random.nextInt(4)));

        // Diamond: 1..2 (50% chance)
        if (random.nextFloat() < 0.50F) {
            items.add(new ItemStack(Items.DIAMOND, 1 + random.nextInt(2)));
        }

        // Golden Apple (40% chance)
        if (random.nextFloat() < 0.40F) {
            items.add(new ItemStack(Items.GOLDEN_APPLE));
        }

        // Iron Ingots: 3..6
        items.add(new ItemStack(Items.IRON_INGOT, 3 + random.nextInt(4)));

        // Scrap Metal Debris: 2..4
        items.add(new ItemStack(WarfrontBlocks.SCRAP_METAL_DEBRIS.get(), 2 + random.nextInt(3)));

        // Enchanted Book (1 book with modest enchantment)
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        Holder<Enchantment> enchant = getRandomCombatEnchant(level, random);
        if (enchant != null) {
            book.enchant(enchant, 1 + random.nextInt(2));
            items.add(book);
        }

        return items;
    }

    private static Holder<Enchantment> getRandomCombatEnchant(ServerLevel level, RandomSource random) {
        var enchantRegistry = level.registryAccess().registry(Registries.ENCHANTMENT).orElse(null);
        if (enchantRegistry == null) {
            return null;
        }
        var keys = List.of(
                Enchantments.PROTECTION,
                Enchantments.SHARPNESS,
                Enchantments.QUICK_CHARGE,
                Enchantments.UNBREAKING,
                Enchantments.POWER
        );
        var chosenKey = keys.get(random.nextInt(keys.size()));
        return enchantRegistry.getHolder(chosenKey).orElse(null);
    }

    private static void distributeItems(Container container, List<ItemStack> items, RandomSource random) {
        int containerSize = container.getContainerSize();
        if (containerSize <= 0) return;

        List<Integer> availableSlots = new ArrayList<>(containerSize);
        for (int i = 0; i < containerSize; i++) {
            availableSlots.add(i);
        }
        Collections.shuffle(availableSlots);

        int slotIdx = 0;
        for (ItemStack stack : items) {
            if (slotIdx >= availableSlots.size()) break;
            container.setItem(availableSlots.get(slotIdx++), stack);
        }
    }
}
