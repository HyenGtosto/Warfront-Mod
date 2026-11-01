package com.warfront.menu;

import com.warfront.Warfront;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WarfrontMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Warfront.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<FriendlyMortarMenu>> FRIENDLY_MORTAR_MENU =
            MENUS.register("friendly_mortar", () -> IMenuTypeExtension.create(new IContainerFactory<FriendlyMortarMenu>() {
                @Override
                public FriendlyMortarMenu create(int windowId, Inventory inv, RegistryFriendlyByteBuf data) {
                    return new FriendlyMortarMenu(windowId, inv, data);
                }

                @Override
                public FriendlyMortarMenu create(int windowId, Inventory inv) {
                    return new FriendlyMortarMenu(windowId, inv);
                }
            }));

    public static final DeferredHolder<MenuType<?>, MenuType<FriendlyBallistaMenu>> FRIENDLY_BALLISTA_MENU =
            MENUS.register("friendly_ballista", () -> IMenuTypeExtension.create(new IContainerFactory<FriendlyBallistaMenu>() {
                @Override
                public FriendlyBallistaMenu create(int windowId, Inventory inv, RegistryFriendlyByteBuf data) {
                    return new FriendlyBallistaMenu(windowId, inv, data);
                }

                @Override
                public FriendlyBallistaMenu create(int windowId, Inventory inv) {
                    return new FriendlyBallistaMenu(windowId, inv);
                }
            }));

    private WarfrontMenus() {
    }
}
