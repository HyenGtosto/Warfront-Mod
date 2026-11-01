package com.warfront.block.entity;

import com.warfront.block.WarfrontBlocks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public enum LoadedShellType {
    NONE(0, Component.translatable("screen.warfront.friendly_mortar.chamber_empty")),
    HE(1, Component.translatable("item.warfront.he_artillery_shell")),
    INCENDIARY(2, Component.translatable("item.warfront.incendiary_artillery_shell")),
    AIRBURST(3, Component.translatable("item.warfront.airburst_artillery_shell")),
    SMOKE(4, Component.translatable("item.warfront.smoke_artillery_shell"));

    private final int id;
    private final Component displayName;

    LoadedShellType(int id, Component displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public int getId() {
        return id;
    }

    public Component getDisplayName() {
        return displayName;
    }

    public static LoadedShellType fromId(int id) {
        for (LoadedShellType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return NONE;
    }

    public static LoadedShellType fromItem(Item item) {
        if (item == WarfrontBlocks.HE_ARTILLERY_SHELL.get()) {
            return HE;
        } else if (item == WarfrontBlocks.INCENDIARY_ARTILLERY_SHELL.get()) {
            return INCENDIARY;
        } else if (item == WarfrontBlocks.AIRBURST_ARTILLERY_SHELL.get()) {
            return AIRBURST;
        } else if (item == WarfrontBlocks.SMOKE_ARTILLERY_SHELL.get()) {
            return SMOKE;
        }
        return NONE;
    }

    public ItemStack createRepresentativeStack() {
        return switch (this) {
            case HE -> new ItemStack(WarfrontBlocks.HE_ARTILLERY_SHELL.get());
            case INCENDIARY -> new ItemStack(WarfrontBlocks.INCENDIARY_ARTILLERY_SHELL.get());
            case AIRBURST -> new ItemStack(WarfrontBlocks.AIRBURST_ARTILLERY_SHELL.get());
            case SMOKE -> new ItemStack(WarfrontBlocks.SMOKE_ARTILLERY_SHELL.get());
            case NONE -> ItemStack.EMPTY;
        };
    }
}
