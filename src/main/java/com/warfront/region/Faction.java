package com.warfront.region;

import net.minecraft.network.chat.Component;
import java.util.Optional;

public enum Faction {
    UNCLAIMED(0, "unclaimed", 0xD6D3D1),
    HUMANITY(1, "humanity", 0x21BCFF),
    ZOMBIE_HORDE(2, "zombie_horde", 0xFF6467),
    PILLAGER_CONQUERORS(3, "pillager_conquerors", 0xFFDF20);

    private final int id;
    private final String commandName;
    private final int color;

    Faction(int id, String commandName, int color) {
        this.id = id;
        this.commandName = commandName;
        this.color = color;
    }

    public int id() {
        return id;
    }

    public String commandName() {
        return commandName;
    }

    public int color() {
        return color;
    }

    public Component displayName() {
        return Component.translatable("faction.warfront." + commandName);
    }

    public static Faction byId(int id) {
        for (Faction faction : values()) {
            if (faction.id == id) {
                return faction;
            }
        }
        return UNCLAIMED;
    }

    public static Optional<Faction> byCommandName(String commandName) {
        for (Faction faction : values()) {
            if (faction.commandName.equals(commandName)) {
                return Optional.of(faction);
            }
        }
        return Optional.empty();
    }
}
