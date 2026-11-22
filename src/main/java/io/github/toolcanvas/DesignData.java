package io.github.toolcanvas;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Pixel art for one design. Pixels are ARGB (0xAARRGGBB), row-major, top row first.
 * The id is a hash of the content, so identical drawings share one stored copy.
 */
public record DesignData(int width, int height, int[] pixels) {
    public static final int HARD_MAX = 64;

    /** Writes pixels palette-compressed: most pixel art uses a handful of colours. */
    public static final StreamCodec<FriendlyByteBuf, DesignData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> data.write(buf),
            DesignData::read);

    public DesignData {
        if (width < 1 || height < 1 || width > HARD_MAX || height > HARD_MAX || pixels.length != width * height) {
            throw new IllegalArgumentException("Bad design size " + width + "x" + height + " / " + pixels.length);
        }
    }

    /** Copies and cleans the pixels: fully transparent pixels all become 0 so they don't affect the id. */
    public static DesignData normalized(int width, int height, int[] pixels) {
        int[] copy = Arrays.copyOf(pixels, pixels.length);
        for (int i = 0; i < copy.length; i++) {
            if ((copy[i] >>> 24) == 0) copy[i] = 0;
        }
        return new DesignData(width, height, copy);
    }

    public long id() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            ByteBuffer buf = ByteBuffer.allocate(8 + pixels.length * 4);
            buf.putInt(width).putInt(height);
            for (int p : pixels) buf.putInt(p);
            long id = ByteBuffer.wrap(sha.digest(buf.array())).getLong();
            return id == 0 ? 1 : id;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean samePixels(int w, int h, int[] other) {
        return w == width && h == height && Arrays.equals(pixels, other);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(width);
        buf.writeVarInt(height);
        int[] palette = Arrays.stream(pixels).distinct().toArray();
        buf.writeVarInt(palette.length);
        for (int c : palette) buf.writeInt(c);
        if (palette.length <= 256) {
            for (int p : pixels) buf.writeByte(indexOf(palette, p));
        } else {
            for (int p : pixels) buf.writeInt(p);
        }
    }

    private static DesignData read(FriendlyByteBuf buf) {
        int w = buf.readVarInt();
        int h = buf.readVarInt();
        if (w < 1 || h < 1 || w > HARD_MAX || h > HARD_MAX) throw new DecoderException("Bad design size");
        int paletteSize = buf.readVarInt();
        if (paletteSize < 1 || paletteSize > w * h) throw new DecoderException("Bad palette");
        int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) palette[i] = buf.readInt();
        int[] pixels = new int[w * h];
        if (paletteSize <= 256) {
            for (int i = 0; i < pixels.length; i++) {
                int idx = buf.readUnsignedByte();
                if (idx >= paletteSize) throw new DecoderException("Bad palette index");
                pixels[i] = palette[idx];
            }
        } else {
            for (int i = 0; i < pixels.length; i++) pixels[i] = buf.readInt();
        }
        return new DesignData(w, h, pixels);
    }

    private static int indexOf(int[] palette, int color) {
        for (int i = 0; i < palette.length; i++) if (palette[i] == color) return i;
        return 0;
    }
}
