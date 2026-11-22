package io.github.toolcanvas.client.model;

import io.github.toolcanvas.DesignData;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.ClientHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the same kind of 1/16-thick "extruded sprite" geometry vanilla uses for item/generated models,
 * but with UVs (0..1) into the design's own texture rather than the block atlas.
 * Edge strips are merged into runs, so a 16x16 tool is typically under ~100 quads.
 */
public final class DesignQuads {
    private static final float Z_FRONT = 8.5F / 16F;
    private static final float Z_BACK = 7.5F / 16F;

    private DesignQuads() {}

    /**
     * @param sprite only stored on the quads for mods that inspect {@link BakedQuad#getSprite()}; UVs don't use it.
     */
    public static List<BakedQuad> build(DesignData design, TextureAtlasSprite sprite) {
        int w = design.width(), h = design.height();
        int[] px = design.pixels();
        List<BakedQuad> quads = new ArrayList<>();

        // Front and back: one quad each; transparent pixels are discarded by the shader.
        quads.add(quad(Direction.SOUTH, 0, 0, Z_FRONT, 1, 1, Z_FRONT, 0, 0, 1, 1, sprite));
        quads.add(quad(Direction.NORTH, 0, 0, Z_BACK, 1, 1, Z_BACK, 1, 0, 0, 1, sprite));

        float insetU = 1F / (w * 64F), insetV = 1F / (h * 64F);

        // Top and bottom edges of each row, merged horizontally.
        for (int y = 0; y < h; y++) {
            for (int dir = 0; dir < 2; dir++) {
                boolean up = dir == 0;
                int start = -1;
                for (int x = 0; x <= w; x++) {
                    boolean edge = x < w && solid(px, w, h, x, y) && !solid(px, w, h, x, up ? y - 1 : y + 1);
                    if (edge && start < 0) start = x;
                    if (!edge && start >= 0) {
                        float x0 = start / (float) w, x1 = x / (float) w;
                        float yy = 1F - (up ? y : y + 1) / (float) h;
                        float v = (y + 0.5F) / h;
                        quads.add(quad(up ? Direction.UP : Direction.DOWN, x0, yy, Z_BACK, x1, yy, Z_FRONT,
                                x0 + insetU, v, x1 - insetU, v, sprite));
                        start = -1;
                    }
                }
            }
        }

        // Left and right edges of each column, merged vertically.
        for (int x = 0; x < w; x++) {
            for (int dir = 0; dir < 2; dir++) {
                boolean west = dir == 0;
                int start = -1;
                for (int y = 0; y <= h; y++) {
                    boolean edge = y < h && solid(px, w, h, x, y) && !solid(px, w, h, west ? x - 1 : x + 1, y);
                    if (edge && start < 0) start = y;
                    if (!edge && start >= 0) {
                        float xx = (west ? x : x + 1) / (float) w;
                        float y0 = 1F - y / (float) h, y1 = 1F - start / (float) h;
                        float u = (x + 0.5F) / w;
                        quads.add(quad(west ? Direction.WEST : Direction.EAST, xx, y0, Z_BACK, xx, y1, Z_FRONT,
                                u, start / (float) h + insetV, u, y / (float) h - insetV, sprite));
                        start = -1;
                    }
                }
            }
        }
        return List.copyOf(quads);
    }

    private static boolean solid(int[] px, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && (px[y * w + x] >>> 24) != 0;
    }

    /**
     * One quad on the given face of the box (x0,y0,z0)-(x1,y1,z1), vertices in vanilla FaceInfo order
     * (counter-clockwise seen from outside). Vertex i gets UV: 0=(u0,v0) 1=(u0,v1) 2=(u1,v1) 3=(u1,v0).
     */
    private static BakedQuad quad(Direction face, float x0, float y0, float z0, float x1, float y1, float z1,
                                  float u0, float v0, float u1, float v1, TextureAtlasSprite sprite) {
        float[][] pos = switch (face) {
            case DOWN -> new float[][]{{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
            case UP -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case NORTH -> new float[][]{{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
            case SOUTH -> new float[][]{{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
            case WEST -> new float[][]{{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
            case EAST -> new float[][]{{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
        };
        float[][] uv = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};

        int[] data = new int[32]; // DefaultVertexFormat.BLOCK: 8 ints per vertex
        for (int i = 0; i < 4; i++) {
            int o = i * 8;
            data[o] = Float.floatToRawIntBits(pos[i][0]);
            data[o + 1] = Float.floatToRawIntBits(pos[i][1]);
            data[o + 2] = Float.floatToRawIntBits(pos[i][2]);
            data[o + 3] = -1; // white vertex colour
            data[o + 4] = Float.floatToRawIntBits(uv[i][0]);
            data[o + 5] = Float.floatToRawIntBits(uv[i][1]);
            data[o + 6] = 0; // no baked light
        }
        ClientHooks.fillNormal(data, face);
        return new BakedQuad(data, -1, face, sprite, true);
    }
}
