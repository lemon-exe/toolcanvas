package io.github.toolcanvas.client;

import io.github.toolcanvas.ModRegistry;
import io.github.toolcanvas.ToolCanvas;
import io.github.toolcanvas.client.model.CanvasWrappedModel;
import io.github.toolcanvas.client.render.CanvasRenderTypes;
import io.github.toolcanvas.client.render.GlintRendering;
import io.github.toolcanvas.client.screen.DesignTableScreen;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TieredItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Map;

@Mod(value = ToolCanvas.MODID, dist = Dist.CLIENT)
public final class ToolCanvasClient {
    public ToolCanvasClient(IEventBus modBus) {
        modBus.addListener(ToolCanvasClient::wrapToolModels);
        modBus.addListener(ModelEvent.BakingCompleted.class, e -> {
            ClientDesigns.onModelsReloaded();
            GlintRendering.onResourcesReloaded();
        });
        modBus.addListener(RegisterRenderBuffersEvent.class, CanvasRenderTypes::registerBuffers);
        modBus.addListener(RegisterMenuScreensEvent.class, e -> e.register(ModRegistry.DESIGN_TABLE_MENU.get(), DesignTableScreen::new));

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> ClientDesigns.tick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> ClientDesigns.clear());
    }

    /**
     * Wrap the item model of every damageable item (tools, weapons, from any mod) so a stack carrying a design
     * can swap in its custom model. Non-damageable items are left completely untouched.
     */
    private static void wrapToolModels(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        int wrapped = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (!item.components().has(DataComponents.MAX_DAMAGE) && !(item instanceof TieredItem)) continue;
            ModelResourceLocation location = ModelResourceLocation.inventory(BuiltInRegistries.ITEM.getKey(item));
            BakedModel model = models.get(location);
            if (model == null || model instanceof CanvasWrappedModel) continue;
            models.put(location, new CanvasWrappedModel(model));
            wrapped++;
        }
        ToolCanvas.LOGGER.debug("Tool Canvas wrapped {} tool models", wrapped);
    }
}
