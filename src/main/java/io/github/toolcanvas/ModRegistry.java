package io.github.toolcanvas;

import com.mojang.serialization.Codec;
import io.github.toolcanvas.block.DesignTableBlock;
import io.github.toolcanvas.menu.DesignTableMenu;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
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
    public static final int MIN_SCALE = 50, MAX_SCALE = 300;

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

    /** Enchantment glint colour (0xRRGGBB) for one tool. Absent = vanilla purple. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> GLINT = COMPONENTS.registerComponentType("glint",
            builder -> builder.persistent(Codec.intRange(0, 0xFFFFFF)).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** Size in percent when held/displayed (not in inventory slots). Absent = 100. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> SCALE = COMPONENTS.registerComponentType("scale",
            builder -> builder.persistent(Codec.intRange(MIN_SCALE, MAX_SCALE)).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** Present (true) = the size setting also applies when the tool is held. Absent = displays only. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> SCALE_IN_HAND = COMPONENTS.registerComponentType("scale_in_hand",
            builder -> builder.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));

    /**
     * Where the hand holds the tool, as (u << 16) | v in thousandths of the texture width/height (from the top-left).
     * Absent = wherever the tool's model normally puts the hand.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> GRIP = COMPONENTS.registerComponentType("grip",
            builder -> builder.persistent(Codec.intRange(0, (1000 << 16) | 1000)).networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * The texture point vanilla's held-item pose (item/handheld, third person) puts in the hand: just below the
     * guard on a sword. Worked out from the handheld transform (rotation 0,-90,55, translation 0,4,0.5, scale 0.85).
     */
    public static final int DEFAULT_GRIP_U = 238, DEFAULT_GRIP_V = 639;

    public static boolean validGrip(int grip) {
        return grip >= 0 && (grip >>> 16) <= 1000 && (grip & 0xFFFF) <= 1000;
    }

    private ModRegistry() {}

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);
        COMPONENTS.register(modBus);
    }
}
