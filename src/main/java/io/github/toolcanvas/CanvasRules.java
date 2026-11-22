package io.github.toolcanvas;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Which items can be repainted, and what repainting them costs. Shared by server and client. */
public final class CanvasRules {
    /** Force-allow extra (damageable) items, e.g. a modded weapon that isn't detected automatically. */
    public static final TagKey<Item> CUSTOMIZABLE = TagKey.create(Registries.ITEM, ToolCanvas.id("customizable"));
    /** Force-deny items whose rendering doesn't work with a flat repaint (e.g. the trident). */
    public static final TagKey<Item> BLACKLIST = TagKey.create(Registries.ITEM, ToolCanvas.id("blacklist"));

    private static final Map<Item, Boolean> CUSTOMIZABLE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Item, List<Item>> REPAIR_CACHE = new ConcurrentHashMap<>();

    private CanvasRules() {}

    public static void clearCaches() {
        CUSTOMIZABLE_CACHE.clear();
        REPAIR_CACHE.clear();
    }

    /**
     * Tools and melee weapons, including modded ones: anything damageable that is a tiered tool, is in a
     * vanilla or common (c:) tool/weapon tag, or deals melee damage when held.
     */
    public static boolean isCustomizable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return CUSTOMIZABLE_CACHE.computeIfAbsent(stack.getItem(), item -> compute(stack));
    }

    private static boolean compute(ItemStack stack) {
        if (stack.is(BLACKLIST)) return false;
        if (!stack.getItem().components().has(DataComponents.MAX_DAMAGE) && !(stack.getItem() instanceof TieredItem)) {
            return false; // only damageable items get their models wrapped on the client
        }
        if (stack.is(CUSTOMIZABLE) || stack.getItem() instanceof TieredItem) return true;
        if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
                || stack.is(Tags.Items.MELEE_WEAPON_TOOLS) || stack.is(Tags.Items.MINING_TOOL_TOOLS)
                || stack.is(Tags.Items.TOOLS_SHEAR) || stack.is(Tags.Items.TOOLS_MACE)) {
            return true;
        }
        // Modded melee weapons that skip the tags: anything with an attack damage bonus in the main hand.
        boolean[] melee = {false};
        ItemStack probe = new ItemStack(stack.getItem());
        try {
            probe.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                if (attribute.value() == Attributes.ATTACK_DAMAGE.value() && modifier.amount() > 0) melee[0] = true;
            });
        } catch (RuntimeException e) {
            ToolCanvas.LOGGER.debug("Could not read attributes of {}", stack.getItem(), e);
        }
        return melee[0];
    }

    /** Every item that can pay for repainting this tool: its repair materials, or the configured fallback. */
    public static List<Item> costItems(ItemStack tool) {
        if (tool.isEmpty()) return List.of();
        List<Item> repair = REPAIR_CACHE.computeIfAbsent(tool.getItem(), item -> findRepairItems(tool));
        return repair.isEmpty() ? List.of(CanvasConfig.fallbackCostItem()) : repair;
    }

    private static List<Item> findRepairItems(ItemStack tool) {
        ItemStack probeTool = new ItemStack(tool.getItem());
        List<Item> found = new ArrayList<>();
        for (Item candidate : BuiltInRegistries.ITEM) {
            if (candidate == Items.AIR) continue;
            try {
                if (probeTool.getItem().isValidRepairItem(probeTool, new ItemStack(candidate))) found.add(candidate);
            } catch (RuntimeException ignored) {
                // a modded item threw; treat as not a repair material
            }
        }
        return List.copyOf(found);
    }

    public static boolean isValidCost(ItemStack tool, ItemStack material) {
        return !material.isEmpty() && costItems(tool).contains(material.getItem());
    }

    public static boolean paysCost(Player player) {
        if (!CanvasConfig.costEnabled()) return false;
        return !(player.getAbilities().instabuild && CanvasConfig.freeInCreative());
    }
}
