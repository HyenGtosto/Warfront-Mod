package com.warfront.region.base;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Context container passed to a {@link BaseBuildingGenerator} when placing a base.
 */
public record BasePlacementContext(
        ServerLevel level,
        int regionX,
        int regionZ,
        Faction faction,
        BaseType baseType,
        BlockPos anchor,
        long worldSeed
) {
}
