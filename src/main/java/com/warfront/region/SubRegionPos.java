package com.warfront.region;

public record SubRegionPos(int regionX, int regionZ, int subX, int subZ) {
    public SubRegionPos {
        if (subX < 0 || subX > 1 || subZ < 0 || subZ > 1) {
            throw new IllegalArgumentException("SubRegion coordinates subX and subZ must be 0 or 1");
        }
    }

    public static SubRegionPos fromChunk(int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, 8);
        int regionZ = Math.floorDiv(chunkZ, 8);
        int localChunkX = Math.floorMod(chunkX, 8);
        int localChunkZ = Math.floorMod(chunkZ, 8);
        int subX = localChunkX >= 4 ? 1 : 0;
        int subZ = localChunkZ >= 4 ? 1 : 0;
        return new SubRegionPos(regionX, regionZ, subX, subZ);
    }
}
