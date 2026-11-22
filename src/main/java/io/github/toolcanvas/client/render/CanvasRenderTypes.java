package io.github.toolcanvas.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.toolcanvas.ToolCanvas;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Coloured enchantment glints: vanilla's glint shader and animation, a grayscale copy of the (resource pack's)
 * glint texture, tinted through the shader colour. Colours are snapped to a 5x5x5 cube (125 render types) so
 * every one can have its own fixed render buffer; that keeps them from clashing with the design texture's
 * buffer when both are drawn for the same item.
 */
public final class CanvasRenderTypes extends RenderType {
    public static final ResourceLocation GRAY_GLINT = ToolCanvas.id("dynamic/glint_gray");
    private static final int LEVELS = 5;
    private static final RenderType[] GLINTS = new RenderType[LEVELS * LEVELS * LEVELS];
    private static boolean registered;

    private CanvasRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                              boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, crumbling, sort, setup, clear);
    }

    private static int level(int channel) {
        return Math.round(channel * (LEVELS - 1) / 255F);
    }

    /** The colour actually shown for a requested glint colour. */
    public static int snap(int rgb) {
        int r = level((rgb >> 16) & 0xFF) * 255 / (LEVELS - 1);
        int g = level((rgb >> 8) & 0xFF) * 255 / (LEVELS - 1);
        int b = level(rgb & 0xFF) * 255 / (LEVELS - 1);
        return (r << 16) | (g << 8) | b;
    }

    /** Glint render type for a colour, or null if the buffers couldn't be registered. */
    @Nullable
    public static RenderType glint(int rgb) {
        if (!registered) return null;
        int index = level((rgb >> 16) & 0xFF) * LEVELS * LEVELS + level((rgb >> 8) & 0xFF) * LEVELS + level(rgb & 0xFF);
        return GLINTS[index];
    }

    public static void registerBuffers(RegisterRenderBuffersEvent event) {
        for (int r = 0; r < LEVELS; r++) {
            for (int g = 0; g < LEVELS; g++) {
                for (int b = 0; b < LEVELS; b++) {
                    int index = r * LEVELS * LEVELS + g * LEVELS + b;
                    GLINTS[index] = makeGlint(index, r / (LEVELS - 1F), g / (LEVELS - 1F), b / (LEVELS - 1F));
                    event.registerRenderBuffer(GLINTS[index]);
                }
            }
        }
        registered = true;
    }

    private static RenderType makeGlint(int index, float r, float g, float b) {
        return RenderType.create("toolcanvas_glint_" + index, DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 1536, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_GLINT_DIRECT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(GRAY_GLINT, true, false))
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(EQUAL_DEPTH_TEST)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(new RenderStateShard.TexturingStateShard("toolcanvas_glint_texturing_" + index,
                                () -> {
                                    RenderStateShard.setupGlintTexturing(8.0F);
                                    RenderSystem.setShaderColor(r, g, b, 1.0F);
                                },
                                () -> {
                                    RenderSystem.resetTextureMatrix();
                                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                                }))
                        .createCompositeState(false));
    }
}
