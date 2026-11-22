package io.github.toolcanvas.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.toolcanvas.CanvasDesign;
import io.github.toolcanvas.DesignData;
import io.github.toolcanvas.ModRegistry;
import io.github.toolcanvas.ToolCanvas;
import io.github.toolcanvas.client.model.DesignBakedModel;
import io.github.toolcanvas.client.model.DesignQuads;
import io.github.toolcanvas.network.Network;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side cache of design pixels, their GPU textures and baked models.
 * Everything here runs on the render thread (network handlers are queued onto it).
 */
public final class ClientDesigns {
    private static final Long2ObjectOpenHashMap<Entry> ENTRIES = new Long2ObjectOpenHashMap<>();
    private static final LongLinkedOpenHashSet PENDING = new LongLinkedOpenHashSet();
    @Nullable
    private static ItemTransforms defaultHandheld;

    private ClientDesigns() {}

    private static final class Entry {
        @Nullable DesignData data;
        boolean missing;
        @Nullable ResourceLocation texture;
        @Nullable List<BakedQuad> quads;
        final Map<ItemTransforms, BakedModel> models = new IdentityHashMap<>();
    }

    /** Pixels for a design, or null while they're being fetched (or if the server doesn't have them). */
    @Nullable
    public static synchronized DesignData data(long id) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            ENTRIES.put(id, new Entry());
            PENDING.add(id);
            return null;
        }
        return entry.data;
    }

    /** The model to draw for a tool showing this design, or null to draw the tool's normal model. */
    @Nullable
    public static synchronized BakedModel model(long id, BakedModel base) {
        DesignData data = data(id);
        if (data == null || !RenderSystem.isOnRenderThread()) return null;
        Entry entry = ENTRIES.get(id);
        if (entry.texture == null) {
            entry.texture = upload(id, data);
        }
        if (entry.quads == null) {
            entry.quads = DesignQuads.build(data, base.getParticleIcon());
        }
        // A flat repaint held like a sword. 3D source models (spears, guns...) get vanilla handheld poses instead.
        ItemTransforms transforms = base.isGui3d() || base.isCustomRenderer() ? defaultHandheld() : base.getTransforms();
        return entry.models.computeIfAbsent(transforms,
                t -> new DesignBakedModel(entry.quads, t, base.getParticleIcon(), entry.texture));
    }

    /**
     * Used right before an item is drawn: if the stack carries a design but some other code swapped in a
     * different model (e.g. a mod's separate in-hand model), returns the design model built on that model.
     */
    public static BakedModel designModelFor(ItemStack stack, BakedModel model) {
        if (model instanceof DesignBakedModel) return model;
        CanvasDesign design = stack.get(ModRegistry.DESIGN.get());
        if (design == null) return model;
        BakedModel custom = model(design.id(), model);
        return custom != null ? custom : model;
    }

    private static ResourceLocation upload(long id, DesignData data) {
        NativeImage image = new NativeImage(data.width(), data.height(), true);
        int[] pixels = data.pixels();
        for (int y = 0; y < data.height(); y++) {
            for (int x = 0; x < data.width(); x++) {
                image.setPixelRGBA(x, y, argbToAbgr(pixels[y * data.width() + x]));
            }
        }
        ResourceLocation location = ToolCanvas.id("dynamic/design_" + Long.toHexString(id).toLowerCase(Locale.ROOT));
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
        return location;
    }

    static int argbToAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
    }

    private static ItemTransforms defaultHandheld() {
        if (defaultHandheld == null) {
            BakedModel sword = Minecraft.getInstance().getModelManager()
                    .getModel(ModelResourceLocation.inventory(ResourceLocation.withDefaultNamespace("iron_sword")));
            defaultHandheld = sword.getTransforms();
        }
        return defaultHandheld;
    }

    // ---------------------------------------------------------------- network

    public static synchronized void receive(Network.DesignPixels payload) {
        Entry entry = ENTRIES.get(payload.id());
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(payload.id(), entry);
        }
        if (payload.design() == null) {
            entry.missing = true;
            return;
        }
        if (entry.data != null) return; // already have it
        entry.data = payload.design();
        entry.missing = false;
    }

    /** Sends queued requests, batched, once per client tick. */
    public static void tick() {
        if (Minecraft.getInstance().getConnection() == null) return;
        long[] batch;
        synchronized (ClientDesigns.class) {
            if (PENDING.isEmpty()) return;
            int n = Math.min(PENDING.size(), Network.RequestDesigns.MAX_IDS);
            batch = new long[n];
            for (int i = 0; i < n; i++) batch[i] = PENDING.removeFirstLong();
        }
        PacketDistributor.sendToServer(new Network.RequestDesigns(batch));
    }

    // ---------------------------------------------------------------- lifecycle

    /** Models were rebaked (resource reload): baked caches point at stale sprites/transforms. */
    public static synchronized void onModelsReloaded() {
        defaultHandheld = null;
        for (Entry entry : ENTRIES.values()) {
            entry.quads = null;
            entry.models.clear();
        }
    }

    /** Left the world: free every texture. */
    public static synchronized void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (Entry entry : ENTRIES.values()) {
            if (entry.texture != null) textures.release(entry.texture);
        }
        ENTRIES.clear();
        PENDING.clear();
    }
}
