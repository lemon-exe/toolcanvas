package io.github.toolcanvas;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config (world/serverconfig/toolcanvas-server.toml). NeoForge syncs it to joining players,
 * so the editor shows the same costs and limits the host enforces.
 */
public final class CanvasConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue COST_ENABLED = BUILDER
            .comment("If true, applying a new design costs 1 of the tool's repair material (iron ingot for iron tools, etc.).")
            .define("costEnabled", true);

    private static final ModConfigSpec.BooleanValue FREE_IN_CREATIVE = BUILDER
            .comment("If true, players in creative mode never pay.")
            .define("freeInCreative", true);

    private static final ModConfigSpec.ConfigValue<String> FALLBACK_COST_ITEM = BUILDER
            .comment("Cost item for tools that have no repair material (for example some modded weapons).")
            .define("fallbackCostItem", "minecraft:iron_ingot");

    private static final ModConfigSpec.IntValue MAX_CANVAS_SIZE = BUILDER
            .comment("Largest canvas edge in pixels. Textures bigger than this are scaled down when you start editing.")
            .defineInRange("maxCanvasSize", 64, 16, 64);

    static final ModConfigSpec SPEC = BUILDER.build();

    private CanvasConfig() {}

    public static boolean costEnabled() {
        return get(COST_ENABLED, true);
    }

    public static boolean freeInCreative() {
        return get(FREE_IN_CREATIVE, true);
    }

    public static int maxCanvasSize() {
        return get(MAX_CANVAS_SIZE, 64);
    }

    public static Item fallbackCostItem() {
        ResourceLocation id = ResourceLocation.tryParse(get(FALLBACK_COST_ITEM, "minecraft:iron_ingot"));
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? Items.IRON_INGOT : item;
    }

    /** Server configs are only loaded while a world is open; fall back to defaults otherwise. */
    private static <T> T get(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
