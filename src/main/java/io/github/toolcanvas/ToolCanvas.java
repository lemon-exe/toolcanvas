package io.github.toolcanvas;

import com.mojang.logging.LogUtils;
import io.github.toolcanvas.network.Network;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.slf4j.Logger;

@Mod(ToolCanvas.MODID)
public final class ToolCanvas {
    public static final String MODID = "toolcanvas";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ToolCanvas(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, CanvasConfig.SPEC);

        modBus.addListener(Network::register);
        modBus.addListener(ToolCanvas::addToCreativeTab);

        // Repair-material lookups depend on tags, so drop the cache whenever tags reload.
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, e -> CanvasRules.clearCaches());
        NeoForge.EVENT_BUS.addListener(ToolCanvas::onTooltip);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModRegistry.DESIGN_TABLE_ITEM.get());
        }
    }

    private static void onTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().has(ModRegistry.DESIGN.get())) {
            event.getToolTip().add(Component.translatable("tooltip.toolcanvas.custom_design")
                    .withStyle(s -> s.withColor(0x9F7FD8).withItalic(true)));
        }
    }
}
