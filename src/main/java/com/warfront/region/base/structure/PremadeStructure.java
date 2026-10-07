package com.warfront.region.base.structure;

import com.warfront.region.base.BasePlacementContext;

/**
 * Interface contract for deterministic, premade 3D base structures in Warfront.
 * Structures define exact dimensions and deterministic block placement.
 */
public interface PremadeStructure {

    /**
     * Unique identifier for this structure blueprint.
     */
    String getId();

    /**
     * Total width along the X axis in blocks.
     */
    int getSizeX();

    /**
     * Total depth along the Z axis in blocks.
     */
    int getSizeZ();

    /**
     * Total height above ground along the Y axis in blocks.
     */
    int getHeight();

    /**
     * Places the structure into the world at the anchor position specified in the context.
     *
     * @param context the placement context
     * @return true if the structure was successfully placed
     */
    boolean place(BasePlacementContext context);
}
