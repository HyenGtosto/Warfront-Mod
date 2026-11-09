package com.warfront.region.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Represents a procedurally placed military arch bridge spanning a river
 * across the center of a subregion.
 */
public record SubregionBridge(
        long subKey,
        int regionX,
        int regionZ,
        int subX,
        int subZ,
        Direction.Axis axis,
        BlockPos bankA,
        BlockPos centerAnchor,
        BlockPos bankB,
        int spanLength,
        int width
) {

    /**
     * Checks whether two horizontal points are on opposite sides of the river
     * divided by the bridge's central axis.
     */
    public boolean isOppositeSides(double x1, double z1, double x2, double z2) {
        if (axis == Direction.Axis.X) {
            // Bridge spans along X (West <-> East), river runs North <-> South (along Z).
            // Bisector is at centerAnchor.getX().
            boolean side1 = x1 < centerAnchor.getX();
            boolean side2 = x2 < centerAnchor.getX();
            return side1 != side2;
        } else {
            // Bridge spans along Z (North <-> South), river runs West <-> East (along X).
            // Bisector is at centerAnchor.getZ().
            boolean side1 = z1 < centerAnchor.getZ();
            boolean side2 = z2 < centerAnchor.getZ();
            return side1 != side2;
        }
    }

    /**
     * Returns the bridge approach entry waypoint on the same bank as the given position.
     */
    public BlockPos getApproachPos(double x, double z) {
        if (axis == Direction.Axis.X) {
            return (x < centerAnchor.getX()) ? bankA : bankB;
        } else {
            return (z < centerAnchor.getZ()) ? bankA : bankB;
        }
    }

    /**
     * Returns the bridge exit approach waypoint on the opposite bank from the given position.
     */
    public BlockPos getExitPos(double x, double z) {
        if (axis == Direction.Axis.X) {
            return (x < centerAnchor.getX()) ? bankB : bankA;
        } else {
            return (z < centerAnchor.getZ()) ? bankB : bankA;
        }
    }

    /**
     * Checks whether the given position is within the physical roadway footprint of the bridge.
     */
    public boolean isOnBridgeDeck(double x, double z) {
        double halfWidth = (width / 2.0D) + 1.0D;
        if (axis == Direction.Axis.X) {
            double minX = Math.min(bankA.getX(), bankB.getX()) - 1.0D;
            double maxX = Math.max(bankA.getX(), bankB.getX()) + 1.0D;
            double minZ = centerAnchor.getZ() - halfWidth;
            double maxZ = centerAnchor.getZ() + halfWidth;
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        } else {
            double minZ = Math.min(bankA.getZ(), bankB.getZ()) - 1.0D;
            double maxZ = Math.max(bankA.getZ(), bankB.getZ()) + 1.0D;
            double minX = centerAnchor.getX() - halfWidth;
            double maxX = centerAnchor.getX() + halfWidth;
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /**
     * Checks if the given position is on the same side of the center anchor as the specified approach.
     */
    public boolean isSameSideAsApproach(double x, double z, BlockPos approach) {
        if (axis == Direction.Axis.X) {
            boolean ptSide = x < centerAnchor.getX();
            boolean appSide = approach.getX() < centerAnchor.getX();
            return ptSide == appSide;
        } else {
            boolean ptSide = z < centerAnchor.getZ();
            boolean appSide = approach.getZ() < centerAnchor.getZ();
            return ptSide == appSide;
        }
    }

    /**
     * Checks if the bridge deck is structurally intact and passable.
     * A bridge is considered severed if a breach, crater, or TNT explosion has destroyed
     * the crown walkway (less than 2 of the 3 lateral walkway blocks are solid).
     */
    public boolean isIntact(ServerLevel level) {
        int cx = centerAnchor.getX();
        int cz = centerAnchor.getZ();
        int cy = centerAnchor.getY();

        int walkableCount = 0;
        for (int w = -1; w <= 1; w++) {
            int x = (axis == Direction.Axis.X) ? cx : cx + w;
            int z = (axis == Direction.Axis.X) ? cz + w : cz;
            boolean hasSolidFloor = false;
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos deckPos = new BlockPos(x, cy + dy, z);
                BlockState state = level.getBlockState(deckPos);
                if (!state.isAir() && state.blocksMotion() && level.getFluidState(deckPos).isEmpty()) {
                    hasSolidFloor = true;
                    break;
                }
            }
            if (hasSolidFloor) {
                walkableCount++;
            }
        }
        return walkableCount >= 2;
    }

    public CompoundTag toCompoundTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("sub_key", subKey);
        tag.putInt("rx", regionX);
        tag.putInt("rz", regionZ);
        tag.putInt("sx", subX);
        tag.putInt("sz", subZ);
        tag.putString("axis", axis.name());
        tag.put("bank_a", NbtUtils.writeBlockPos(bankA));
        tag.put("anchor", NbtUtils.writeBlockPos(centerAnchor));
        tag.put("bank_b", NbtUtils.writeBlockPos(bankB));
        tag.putInt("span", spanLength);
        tag.putInt("width", width);
        return tag;
    }

    public static SubregionBridge fromCompoundTag(CompoundTag tag) {
        long subKey = tag.getLong("sub_key");
        int rx = tag.getInt("rx");
        int rz = tag.getInt("rz");
        int sx = tag.getInt("sx");
        int sz = tag.getInt("sz");
        Direction.Axis axis = Direction.Axis.valueOf(tag.getString("axis"));
        BlockPos bankA = NbtUtils.readBlockPos(tag, "bank_a").orElse(BlockPos.ZERO);
        BlockPos anchor = NbtUtils.readBlockPos(tag, "anchor").orElse(BlockPos.ZERO);
        BlockPos bankB = NbtUtils.readBlockPos(tag, "bank_b").orElse(BlockPos.ZERO);
        int span = tag.getInt("span");
        int width = tag.getInt("width");
        return new SubregionBridge(subKey, rx, rz, sx, sz, axis, bankA, anchor, bankB, span, width);
    }
}
