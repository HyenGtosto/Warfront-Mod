package com.warfront.region.base;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.base.structure.PremadeStructure;
import com.warfront.region.base.structure.StructureVariantRegistry;

/**
 * Concrete base building generator for Pillager Outposts.
 *
 * Places native Minecraft structure templates (.nbt) registered in {@link StructureVariantRegistry}.
 */
public class PillagerOutpostGenerator implements BaseBuildingGenerator {

    public static final int DEFAULT_SIZE_X = 27;
    public static final int DEFAULT_SIZE_Z = 27;
    public static final int DEFAULT_HEIGHT = 23;

    @Override
    public int getSizeX() {
        PremadeStructure variant = StructureVariantRegistry.getDefaultVariant(Faction.PILLAGER_CONQUERORS, BaseType.OUTPOST);
        return variant != null ? variant.getSizeX() : DEFAULT_SIZE_X;
    }

    @Override
    public int getSizeZ() {
        PremadeStructure variant = StructureVariantRegistry.getDefaultVariant(Faction.PILLAGER_CONQUERORS, BaseType.OUTPOST);
        return variant != null ? variant.getSizeZ() : DEFAULT_SIZE_Z;
    }

    @Override
    public int getHeight() {
        PremadeStructure variant = StructureVariantRegistry.getDefaultVariant(Faction.PILLAGER_CONQUERORS, BaseType.OUTPOST);
        return variant != null ? variant.getHeight() : DEFAULT_HEIGHT;
    }

    @Override
    public boolean place(BasePlacementContext context) {
        PremadeStructure variant = StructureVariantRegistry.getDefaultVariant(context.faction(), context.baseType());
        if (variant != null) {
            return variant.place(context);
        }
        return false;
    }

    @Override
    public java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> getPristineBlocks(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos anchor
    ) {
        PremadeStructure variant = StructureVariantRegistry.getDefaultVariant(Faction.PILLAGER_CONQUERORS, BaseType.OUTPOST);
        if (variant != null) {
            return variant.getPristineBlocks(level, anchor);
        }
        return java.util.Map.of();
    }
}
