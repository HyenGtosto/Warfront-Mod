package com.warfront.region;

import net.minecraft.network.chat.Component;

public enum BaseType {
    NONE(0, "none"),
    OUTPOST(1, "outpost"),
    HEADQUARTERS(2, "headquarters"),
    MEGA_BASE(3, "mega_base");

    private final int id;
    private final String name;

    BaseType(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int id() {
        return id;
    }

    public static BaseType byId(int id) {
        for (BaseType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return NONE;
    }

    public Component getDisplayName(Faction faction) {
        if (this == NONE) {
            return Component.translatable("base_type.warfront.none");
        }

        return switch (faction) {
            case PILLAGER_CONQUERORS -> switch (this) {
                case OUTPOST -> Component.translatable("base_type.warfront.pillager.outpost");
                case HEADQUARTERS -> Component.translatable("base_type.warfront.pillager.headquarters");
                case MEGA_BASE -> Component.translatable("base_type.warfront.pillager.mega_command_center");
                default -> Component.translatable("base_type.warfront.none");
            };
            case ZOMBIE_HORDE -> switch (this) {
                case OUTPOST -> Component.translatable("base_type.warfront.zombie.minor_hive");
                case HEADQUARTERS -> Component.translatable("base_type.warfront.zombie.major_hive");
                case MEGA_BASE -> Component.translatable("base_type.warfront.zombie.heart_of_infection");
                default -> Component.translatable("base_type.warfront.none");
            };
            default -> switch (this) {
                case OUTPOST -> Component.translatable("base_type.warfront.humanity.outpost");
                case HEADQUARTERS -> Component.translatable("base_type.warfront.humanity.headquarters");
                case MEGA_BASE -> Component.translatable("base_type.warfront.humanity.fortress");
                default -> Component.translatable("base_type.warfront.none");
            };
        };
    }
}
