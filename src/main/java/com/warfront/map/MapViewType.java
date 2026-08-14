package com.warfront.map;

public enum MapViewType {
    SCOUT(0, 64, false, false, false),      // 8x8 region grid (64 chunks), local scouting, no attack actions
    COMMAND(1, 192, true, true, false),     // 24x24 region grid (192 chunks), fog of war enabled, attack planning enabled
    DEBUG(2, 192, false, true, true);       // 24x24 region grid (192 chunks), no fog of war (unrestricted), testing mode

    private final int id;
    private final int chunkDiameter;
    private final boolean hasFogOfWar;
    private final boolean canLaunchAttacks;
    private final boolean isDebug;

    MapViewType(int id, int chunkDiameter, boolean hasFogOfWar, boolean canLaunchAttacks, boolean isDebug) {
        this.id = id;
        this.chunkDiameter = chunkDiameter;
        this.hasFogOfWar = hasFogOfWar;
        this.canLaunchAttacks = canLaunchAttacks;
        this.isDebug = isDebug;
    }

    public int id() {
        return id;
    }

    public int chunkDiameter() {
        return chunkDiameter;
    }

    public boolean hasFogOfWar() {
        return hasFogOfWar;
    }

    public boolean canLaunchAttacks() {
        return canLaunchAttacks;
    }

    public boolean isDebug() {
        return isDebug;
    }

    public static MapViewType byId(int id) {
        for (MapViewType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return COMMAND;
    }
}
