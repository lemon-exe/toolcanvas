package io.github.toolcanvas;

import io.github.toolcanvas.block.DesignTableBlock;
import io.github.toolcanvas.menu.DesignTableMenu;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ToolCanvas.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ToolCanvas.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ToolCanvas.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ToolCanvas.MODID);

    public static final DeferredBlock<DesignTableBlock> DESIGN_TABLE = BLOCKS.register("design_table",
            () -> new DesignTableBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.5F)
                    .sound(SoundType.WOOD)
                    .ignitedByLava()));

    public static final DeferredItem<BlockItem> DESIGN_TABLE_ITEM = ITEMS.registerSimpleBlockItem(DESIGN_TABLE, new Item.Properties());

    public static final DeferredHolder<MenuType<?>, MenuType<DesignTableMenu>> DESIGN_TABLE_MENU = MENUS.register("design_table",
            () -> IMenuTypeExtension.create((id, inventory, buf) -> new DesignTableMenu(id, inventory)));

    /**
     * The only thing stored on the item: a 64-bit id pointing at pixel data kept in the world save.
     * Keeping the stack tiny means inventory syncs, entity equipment packets and chunk saves stay as cheap as vanilla.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CanvasDesign>> DESIGN = COMPONENTS.registerComponentType("design",
            builder -> builder.persistent(CanvasDesign.CODEC).networkSynchronized(CanvasDesign.STREAM_CODEC));

    private ModRegistry() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);
        COMPONENTS.register(modBus);
    }
}
