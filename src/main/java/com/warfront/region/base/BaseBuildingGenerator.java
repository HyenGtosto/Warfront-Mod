package com.warfront.region.base;

/**
 * Interface contract for base building generators in Warfront.
 * Subclasses/implementations define the footprint, vertical extent, and block placement logic.
 */
public interface BaseBuildingGenerator {

    /**
     * Total horizontal width along the X axis in blocks.
     */
    int getSizeX();

    /**
     * Total horizontal depth along the Z axis in blocks.
     */
    int getSizeZ();

    /**
     * Total structural height along the Y axis in blocks.
     */
    int getHeight();

    /**
     * Places the building into the world at the anchor location specified in context.
     *
     * @param context the placement context including level, coords, faction, baseType, anchor, and seed
     * @return true if the structure was successfully placed
     */
    boolean place(BasePlacementContext context);
}
