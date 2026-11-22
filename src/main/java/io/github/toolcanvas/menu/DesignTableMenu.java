package io.github.toolcanvas.menu;

import io.github.toolcanvas.CanvasRules;
import io.github.toolcanvas.ModRegistry;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class DesignTableMenu extends AbstractContainerMenu {
    public static final int TOOL_SLOT = 0;
    public static final int MATERIAL_SLOT = 1;
    public static final int REFERENCE_SLOT = 2;
    private static final int INV_START = 3;
    private static final int HOTBAR_START = INV_START + 27;
    private static final int INV_END = HOTBAR_START + 9;

    // Slot positions, shared with the screen layout.
    public static final int TOOL_X = 12, TOOL_Y = 20;
    public static final int MATERIAL_X = 12, MATERIAL_Y = 44;
    public static final int REFERENCE_X = 12, REFERENCE_Y = 68;
    public static final int INVENTORY_X = 8, INVENTORY_Y = 152;

    private final ContainerLevelAccess access;
    private final Container container = new SimpleContainer(3) {
        @Override
        public void setChanged() {
            super.setChanged();
            DesignTableMenu.this.slotsChanged(this);
        }
    };
    /** Server tick of the last apply/reset, to ignore click spam. */
    public long lastActionTick = -100;

    public DesignTableMenu(int id, Inventory inventory) {
        this(id, inventory, ContainerLevelAccess.NULL);
    }

    public DesignTableMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        super(ModRegistry.DESIGN_TABLE_MENU.get(), id);
        this.access = access;

        addSlot(new Slot(container, TOOL_SLOT, TOOL_X, TOOL_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return CanvasRules.isCustomizable(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        addSlot(new Slot(container, MATERIAL_SLOT, MATERIAL_X, MATERIAL_Y));
        // Any item: its look can be copied onto the canvas.
        addSlot(new Slot(container, REFERENCE_SLOT, REFERENCE_X, REFERENCE_Y) {
            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INVENTORY_X + col * 18, INVENTORY_Y + 58));
        }
    }

    public ItemStack getTool() {
        return container.getItem(TOOL_SLOT);
    }

    public ItemStack getMaterial() {
        return container.getItem(MATERIAL_SLOT);
    }

    public ItemStack getReference() {
        return container.getItem(REFERENCE_SLOT);
    }

    /** Call after changing the tool or material stacks in place, so the change is synced. */
    public void markChanged() {
        container.setChanged();
        broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModRegistry.DESIGN_TABLE.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        access.execute((level, pos) -> clearContainer(player, container));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < INV_START) {
            if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else if (CanvasRules.isCustomizable(stack) && !slots.get(TOOL_SLOT).hasItem()) {
            if (!moveItemStackTo(stack, TOOL_SLOT, TOOL_SLOT + 1, false)) return ItemStack.EMPTY;
        } else if (CanvasRules.isCustomizable(stack) && !slots.get(REFERENCE_SLOT).hasItem()) {
            if (!moveItemStackTo(stack, REFERENCE_SLOT, REFERENCE_SLOT + 1, false)) return ItemStack.EMPTY;
        } else if (CanvasRules.isValidCost(getTool(), stack)) {
            if (!moveItemStackTo(stack, MATERIAL_SLOT, MATERIAL_SLOT + 1, false)) return ItemStack.EMPTY;
        } else if (index < HOTBAR_START) {
            if (!moveItemStackTo(stack, HOTBAR_START, INV_END, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, INV_START, HOTBAR_START, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }
}
