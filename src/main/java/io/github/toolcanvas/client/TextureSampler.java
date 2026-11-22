package io.github.toolcanvas.client;

import io.github.toolcanvas.ModRegistry;
import io.github.toolcanvas.ToolCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a tool's original look back out of the loaded resource packs, so the editor can start from it.
 * Works for any item model: layered/tinted sprites are flattened; 3D models fall back to their particle texture.
 */
public final class TextureSampler {
    public record Image(int width, int height, int[] pixels) {}

    private TextureSampler() {}

    public static Image original(ItemStack stack, int maxSize) {
        ItemStack plain = stack.copy();
        plain.remove(ModRegistry.DESIGN.get());
        Minecraft mc = Minecraft.getInstance();
        try {
            BakedModel model = mc.getItemRenderer().getModel(plain, mc.level, mc.player, 0);

            // Front-facing layers, in draw order, each with its tint index.
            Map<TextureAtlasSprite, Integer> layers = new LinkedHashMap<>();
            RandomSource random = RandomSource.create(42L);
            for (BakedModel pass : model.getRenderPasses(plain, true)) {
                List<BakedQuad> quads = new ArrayList<>(pass.getQuads(null, null, random));
                for (Direction d : Direction.values()) quads.addAll(pass.getQuads(null, d, random));
                for (BakedQuad quad : quads) {
                    if (quad.getDirection() == Direction.SOUTH) layers.putIfAbsent(quad.getSprite(), quad.getTintIndex());
                }
            }
            if (layers.isEmpty() || model.isGui3d()) {
                layers.clear();
                layers.put(model.getParticleIcon(), -1);
            }

            TextureAtlasSprite first = layers.keySet().iterator().next();
            int srcW = first.contents().width(), srcH = first.contents().height();
            float scale = Math.min(1F, maxSize / (float) Math.max(srcW, srcH));
            int w = Math.max(1, Math.round(srcW * scale)), h = Math.max(1, Math.round(srcH * scale));

            int[] out = new int[w * h];
            for (Map.Entry<TextureAtlasSprite, Integer> layer : layers.entrySet()) {
                TextureAtlasSprite sprite = layer.getKey();
                int tint = layer.getValue() >= 0 ? mc.getItemColors().getColor(plain, layer.getValue()) : -1;
                int sw = sprite.contents().width(), sh = sprite.contents().height();
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr = sprite.getPixelRGBA(0, x * sw / w, y * sh / h);
                        out[y * w + x] = over(tint(abgrToArgb(abgr), tint), out[y * w + x]);
                    }
                }
            }
            return new Image(w, h, out);
        } catch (RuntimeException e) {
            ToolCanvas.LOGGER.warn("Couldn't read the texture of {}, starting from a blank canvas", stack, e);
            return new Image(16, 16, new int[256]);
        }
    }

    static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
    }

    private static int tint(int argb, int tint) {
        if (tint == -1) return argb;
        int r = ((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Standard alpha-over compositing of src on top of dst. */
    private static int over(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) return 0;
        int r = (((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (255 - sa) / 255) / outA;
        int g = (((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (255 - sa) / 255) / outA;
        int b = ((src & 0xFF) * sa + (dst & 0xFF) * da * (255 - sa) / 255) / outA;
        return (outA << 24) | (r << 16) | (g << 8) | b;
    }
}
