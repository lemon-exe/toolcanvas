package io.github.toolcanvas;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Item data component: which stored design this one tool shows. */
public record CanvasDesign(long id) {
    public static final Codec<CanvasDesign> CODEC = Codec.LONG.xmap(CanvasDesign::new, CanvasDesign::id);
    public static final StreamCodec<ByteBuf, CanvasDesign> STREAM_CODEC =
            ByteBufCodecs.VAR_LONG.map(CanvasDesign::new, CanvasDesign::id);
}
