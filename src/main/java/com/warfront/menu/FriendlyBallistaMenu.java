package com.warfront.menu;

import com.warfront.block.WarfrontBlocks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

public class FriendlyBallistaMenu extends AbstractContainerMenu {

    public static final int AMMO_SLOT_COUNT = 5;
    public static final int TOTAL_CONTAINER_SLOTS = 6;
    public static final int FUEL_SLOT_INDEX = 5;

    private final Container container;
    private final ContainerData data;

    public FriendlyBallistaMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, new SimpleContainer(TOTAL_CONTAINER_SLOTS), new SimpleContainerData(7));
    }

    public FriendlyBallistaMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, new SimpleContainer(TOTAL_CONTAINER_SLOTS), new SimpleContainerData(7));
        if (buf != null && buf.readableBytes() >= 8) {
            buf.readBlockPos();
        }
    }

    public FriendlyBallistaMenu(int containerId, Inventory playerInventory, Container container, ContainerData data) {
        super(WarfrontMenus.FRIENDLY_BALLISTA_MENU.get(), containerId);
        checkContainerSize(container, TOTAL_CONTAINER_SLOTS);
        checkContainerDataCount(data, 7);
        this.container = container;
        this.data = data;

        container.startOpen(playerInventory.player);

        // 5 Ammunition Slots (Slots 0 to 4) - only Giant Ballista Bolts
        for (int i = 0; i < AMMO_SLOT_COUNT; i++) {
            this.addSlot(new Slot(container, i, 44 + i * 18, 20) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.is(WarfrontBlocks.BALLISTA_BOLT_ITEM.get());
                }
            });
        }

        // 1 Fuel Slot (Slot 5) - Furnace Fuel (Coal, Charcoal, etc.)
        this.addSlot(new Slot(container, FUEL_SLOT_INDEX, 134, 53) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return AbstractFurnaceBlockEntity.isFuel(stack);
            }
        });

        // Player Inventory (3 rows x 9 columns)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }

        // Player Hotbar (9 slots)
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        this.addDataSlots(data);
    }

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }

    public int getBurnTimeRemaining() {
        return this.data.get(0);
    }

    public int getCurrentFuelDuration() {
        return this.data.get(1);
    }

    public int getMaxFuelTicks() {
        int low = this.data.get(2) & 0xFFFF;
        int high = this.data.get(3);
        return (high << 16) | low;
    }

    public boolean isBoltChambered() {
        return this.data.get(4) == 1;
    }

    public boolean isBurnPaused() {
        return this.data.get(5) == 1;
    }

    public boolean isDeactivated() {
        return this.data.get(6) == 1;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            itemstack = slotStack.copy();

            if (index < TOTAL_CONTAINER_SLOTS) {
                if (!this.moveItemStackTo(slotStack, TOTAL_CONTAINER_SLOTS, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (slotStack.is(WarfrontBlocks.BALLISTA_BOLT_ITEM.get())) {
                    if (!this.moveItemStackTo(slotStack, 0, AMMO_SLOT_COUNT, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (AbstractFurnaceBlockEntity.isFuel(slotStack)) {
                    if (!this.moveItemStackTo(slotStack, FUEL_SLOT_INDEX, FUEL_SLOT_INDEX + 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index < TOTAL_CONTAINER_SLOTS + 27) {
                    if (!this.moveItemStackTo(slotStack, TOTAL_CONTAINER_SLOTS + 27, this.slots.size(), false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!this.moveItemStackTo(slotStack, TOTAL_CONTAINER_SLOTS, TOTAL_CONTAINER_SLOTS + 27, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (slotStack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }

        return itemstack;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }
}
