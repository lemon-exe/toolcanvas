package io.github.toolcanvas.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import io.github.toolcanvas.ModRegistry;
import io.github.toolcanvas.ToolCanvas;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** Enchantment glint for repainted tools: vanilla-looking on designs, and optionally recoloured. */
public final class GlintRendering {
    private static final ResourceLocation VANILLA_GLINT = ResourceLocation.withDefaultNamespace("textures/misc/enchanted_glint_item.png");
    private static final Set<RenderType> DESIGN_TYPES = new ReferenceOpenHashSet<>();
    private static boolean grayGlintReady;

    private GlintRendering() {}

    /** Called by DesignBakedModel so we can tell its render types apart from normal item ones. */
    public static synchronized void markDesignType(RenderType type) {
        DESIGN_TYPES.add(type);
    }

    /**
     * Replacement for ItemRenderer#getFoilBufferDirect, or null to let vanilla handle it.
     * Designs draw into their own small texture with UVs 0..1, so vanilla's glint (which scales UVs by 8)
     * would tile into a grid. We remap the glint UVs onto the tool's original atlas sprite instead, which
     * gives exactly the glint vanilla would draw on the untouched tool.
     */
    @Nullable
    public static VertexConsumer foilBuffer(MultiBufferSource buffers, RenderType type, boolean glint, ItemStack stack) {
        if (!glint) return null;
        Integer color = stack.get(ModRegistry.GLINT.get());
        boolean design;
        synchronized (GlintRendering.class) {
            design = DESIGN_TYPES.contains(type);
        }
        if (color == null && !design) return null;

        RenderType glintType = null;
        if (color != null) {
            ensureGrayGlint();
            glintType = CanvasRenderTypes.glint(color);
        }
        if (glintType == null) glintType = RenderType.glintDirect();

        VertexConsumer glintConsumer = buffers.getBuffer(glintType);
        if (design) {
            TextureAtlasSprite sprite = Minecraft.getInstance().getItemRenderer().getItemModelShaper().getItemModel(stack).getParticleIcon();
            glintConsumer = new SpriteUv(glintConsumer, sprite);
        }
        return VertexMultiConsumer.create(glintConsumer, buffers.getBuffer(type));
    }

    /** Resource packs may change the glint texture; rebuild the grayscale copy on reload. */
    public static void onResourcesReloaded() {
        if (grayGlintReady) {
            Minecraft.getInstance().getTextureManager().release(CanvasRenderTypes.GRAY_GLINT);
            grayGlintReady = false;
        }
    }

    private static void ensureGrayGlint() {
        if (grayGlintReady) return;
        grayGlintReady = true;
        NativeImage gray;
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(VANILLA_GLINT);
             NativeImage source = NativeImage.read(in)) {
            gray = new NativeImage(source.getWidth(), source.getHeight(), true);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int abgr = source.getPixelRGBA(x, y);
                    int a = abgr >>> 24;
                    int v = Math.max(abgr & 0xFF, Math.max((abgr >> 8) & 0xFF, (abgr >> 16) & 0xFF));
                    gray.setPixelRGBA(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                }
            }
        } catch (IOException | RuntimeException e) {
            ToolCanvas.LOGGER.warn("Couldn't read the glint texture, using a plain one", e);
            gray = new NativeImage(64, 64, true);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int v = ((x + y) % 32) < 6 ? 200 : 60;
                    gray.setPixelRGBA(x, y, 0xFF000000 | (v << 16) | (v << 8) | v);
                }
            }
        }
        Minecraft.getInstance().getTextureManager().register(CanvasRenderTypes.GRAY_GLINT, new DynamicTexture(gray));
    }

    /** Maps 0..1 design UVs onto an atlas sprite's UV range, for the glint pass only. */
    private static final class SpriteUv extends VertexConsumerWrapper {
        private final float u0, v0, du, dv;

        SpriteUv(VertexConsumer parent, TextureAtlasSprite sprite) {
            super(parent);
            this.u0 = sprite.getU0();
            this.v0 = sprite.getV0();
            this.du = sprite.getU1() - sprite.getU0();
            this.dv = sprite.getV1() - sprite.getV0();
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            parent.setUv(u0 + u * du, v0 + v * dv);
            return this;
        }
    }
}
