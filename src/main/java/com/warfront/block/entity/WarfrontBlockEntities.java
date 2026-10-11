package com.warfront.block.entity;

import com.warfront.Warfront;
import com.warfront.block.WarfrontBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WarfrontBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Warfront.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MortarBlockEntity>> MORTAR =
            BLOCK_ENTITIES.register("mortar", () ->
                    BlockEntityType.Builder.of(MortarBlockEntity::new, WarfrontBlocks.MORTAR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AmmoRackBlockEntity>> AMMO_RACK =
            BLOCK_ENTITIES.register("ammo_rack", () ->
                    BlockEntityType.Builder.of(AmmoRackBlockEntity::new, WarfrontBlocks.AMMO_RACK.get()).build(null));

    private WarfrontBlockEntities() {
    }
}
