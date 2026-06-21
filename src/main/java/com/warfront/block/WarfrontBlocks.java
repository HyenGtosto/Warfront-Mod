package com.warfront.block;

import com.warfront.Warfront;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
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

    private WarfrontBlocks() {
    }

    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(CLAIM_CORE_ITEM);
        }
    }
}
