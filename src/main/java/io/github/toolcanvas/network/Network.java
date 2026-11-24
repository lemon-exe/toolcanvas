package io.github.toolcanvas.network;

import io.github.toolcanvas.CanvasConfig;
import io.github.toolcanvas.CanvasDesign;
import io.github.toolcanvas.CanvasRules;
import io.github.toolcanvas.DesignData;
import io.github.toolcanvas.DesignStorage;
import io.github.toolcanvas.ModRegistry;
import io.github.toolcanvas.ToolCanvas;
import io.github.toolcanvas.menu.DesignTableMenu;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

/**
 * Four small packets. Items only carry a design id; clients fetch the pixels for an id once and cache them,
 * so a customized tool costs nothing extra on the network after the first time someone sees it.
 */
public final class Network {
    private Network() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1"); // handlers run on the main thread by default
        registrar.playToServer(ApplyDesign.TYPE, ApplyDesign.STREAM_CODEC, Network::handleApply);
        registrar.playToServer(ResetDesign.TYPE, ResetDesign.STREAM_CODEC, Network::handleReset);
        registrar.playToServer(RequestDesigns.TYPE, RequestDesigns.STREAM_CODEC, Network::handleRequest);
        registrar.playToClient(DesignPixels.TYPE, DesignPixels.STREAM_CODEC,
                (payload, context) -> io.github.toolcanvas.client.ClientDesigns.receive(payload));
    }

    // ---------------------------------------------------------------- payloads

    /**
     * Client → server: set the look of the tool in the open Design Table.
     * {@code design == null} means "the tool's original texture"; {@code glint == -1} means vanilla glint;
     * {@code scale} is a percentage (100 = normal size).
     */
    public record ApplyDesign(int containerId, @Nullable DesignData design, int glint, int scale, boolean scaleInHand, int grip)
            implements CustomPacketPayload {
        public static final Type<ApplyDesign> TYPE = new Type<>(ToolCanvas.id("apply_design"));
        public static final StreamCodec<FriendlyByteBuf, ApplyDesign> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.containerId);
                    buf.writeBoolean(p.design != null);
                    if (p.design != null) DesignData.STREAM_CODEC.encode(buf, p.design);
                    buf.writeInt(p.glint);
                    buf.writeVarInt(p.scale);
                    buf.writeBoolean(p.scaleInHand);
                    buf.writeInt(p.grip);
                },
                buf -> new ApplyDesign(buf.readVarInt(),
                        buf.readBoolean() ? DesignData.STREAM_CODEC.decode(buf) : null,
                        buf.readInt(), buf.readVarInt(), buf.readBoolean(), buf.readInt()));

        @Override
        public Type<ApplyDesign> type() {
            return TYPE;
        }
    }

    /** Client → server: put the tool in the open Design Table back to its original look, glint and size. */
    public record ResetDesign(int containerId) implements CustomPacketPayload {
        public static final Type<ResetDesign> TYPE = new Type<>(ToolCanvas.id("reset_design"));
        public static final StreamCodec<ByteBuf, ResetDesign> STREAM_CODEC =
                ByteBufCodecs.VAR_INT.map(ResetDesign::new, ResetDesign::containerId);

        @Override
        public Type<ResetDesign> type() {
            return TYPE;
        }
    }

    /** Client → server: "I saw these design ids and don't have their pixels yet". */
    public record RequestDesigns(long[] ids) implements CustomPacketPayload {
        public static final int MAX_IDS = 64;
        public static final Type<RequestDesigns> TYPE = new Type<>(ToolCanvas.id("request_designs"));
        public static final StreamCodec<FriendlyByteBuf, RequestDesigns> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.ids.length);
                    for (long id : payload.ids) buf.writeLong(id);
                },
                buf -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_IDS) throw new DecoderException("Too many design ids");
                    long[] ids = new long[count];
                    for (int i = 0; i < count; i++) ids[i] = buf.readLong();
                    return new RequestDesigns(ids);
                });

        @Override
        public Type<RequestDesigns> type() {
            return TYPE;
        }
    }

    /** Server → client: pixels for a design id, or {@code design == null} if the server doesn't know it. */
    public record DesignPixels(long id, DesignData design) implements CustomPacketPayload {
        public static final Type<DesignPixels> TYPE = new Type<>(ToolCanvas.id("design_pixels"));
        public static final StreamCodec<FriendlyByteBuf, DesignPixels> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeLong(payload.id);
                    buf.writeBoolean(payload.design != null);
                    if (payload.design != null) DesignData.STREAM_CODEC.encode(buf, payload.design);
                },
                buf -> {
                    long id = buf.readLong();
                    return new DesignPixels(id, buf.readBoolean() ? DesignData.STREAM_CODEC.decode(buf) : null);
                });

        @Override
        public Type<DesignPixels> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- server handlers

    private static DesignTableMenu openMenu(ServerPlayer player, int containerId) {
        if (player.containerMenu instanceof DesignTableMenu menu && menu.containerId == containerId && menu.stillValid(player)) {
            long now = player.server.getTickCount();
            if (now - menu.lastActionTick < 4) return null; // ignore click spam
            menu.lastActionTick = now;
            return menu;
        }
        return null;
    }

    private static void handleApply(ApplyDesign payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        DesignTableMenu menu = openMenu(player, payload.containerId());
        if (menu == null) return;

        ItemStack tool = menu.getTool();
        if (!CanvasRules.isCustomizable(tool)) return;
        if (payload.glint() < -1 || payload.glint() > 0xFFFFFF) return;
        if (payload.scale() < ModRegistry.MIN_SCALE || payload.scale() > ModRegistry.MAX_SCALE) return;
        if (payload.grip() != -1 && !ModRegistry.validGrip(payload.grip())) return;

        DesignData design = null;
        long newId = 0;
        if (payload.design() != null) {
            DesignData raw = payload.design();
            int max = CanvasConfig.maxCanvasSize();
            if (raw.width() > max || raw.height() > max) return;
            design = DesignData.normalized(raw.width(), raw.height(), raw.pixels());
            newId = design.id();
        }

        CanvasDesign current = tool.get(ModRegistry.DESIGN.get());
        long currentId = current == null ? 0 : current.id();
        int currentGlint = tool.getOrDefault(ModRegistry.GLINT.get(), -1);
        int currentScale = tool.getOrDefault(ModRegistry.SCALE.get(), 100);
        boolean currentInHand = tool.has(ModRegistry.SCALE_IN_HAND.get());
        int currentGrip = tool.getOrDefault(ModRegistry.GRIP.get(), -1);

        boolean artChanged = newId != currentId;
        if (!artChanged && payload.glint() == currentGlint && payload.scale() == currentScale
                && payload.scaleInHand() == currentInHand && payload.grip() == currentGrip) return;

        // Only new pixel art costs material. Going back to the original texture, glint and size are free.
        boolean pays = artChanged && newId != 0 && CanvasRules.paysCost(player);
        ItemStack material = menu.getMaterial();
        if (pays && !CanvasRules.isValidCost(tool, material)) {
            player.displayClientMessage(Component.translatable("message.toolcanvas.need_material"), true);
            return;
        }

        if (design != null) {
            DesignStorage.get(player.server).put(design);
            tool.set(ModRegistry.DESIGN.get(), new CanvasDesign(newId));
        } else {
            tool.remove(ModRegistry.DESIGN.get());
        }
        if (payload.glint() >= 0) tool.set(ModRegistry.GLINT.get(), payload.glint());
        else tool.remove(ModRegistry.GLINT.get());
        if (payload.scale() != 100) tool.set(ModRegistry.SCALE.get(), payload.scale());
        else tool.remove(ModRegistry.SCALE.get());
        if (payload.scaleInHand()) tool.set(ModRegistry.SCALE_IN_HAND.get(), true);
        else tool.remove(ModRegistry.SCALE_IN_HAND.get());
        if (payload.grip() >= 0) tool.set(ModRegistry.GRIP.get(), payload.grip());
        else tool.remove(ModRegistry.GRIP.get());
        if (pays) material.shrink(1);
        menu.markChanged();

        // The painter sees the result immediately; everyone else fetches it the first time they see the tool.
        if (design != null) PacketDistributor.sendToPlayer(player, new DesignPixels(newId, design));
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void handleReset(ResetDesign payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        DesignTableMenu menu = openMenu(player, payload.containerId());
        if (menu == null) return;

        ItemStack tool = menu.getTool();
        if (tool.isEmpty()) return;
        if (!tool.has(ModRegistry.DESIGN.get()) && !tool.has(ModRegistry.GLINT.get()) && !tool.has(ModRegistry.SCALE.get())
                && !tool.has(ModRegistry.SCALE_IN_HAND.get()) && !tool.has(ModRegistry.GRIP.get())) return;
        tool.remove(ModRegistry.DESIGN.get());
        tool.remove(ModRegistry.GLINT.get());
        tool.remove(ModRegistry.SCALE.get());
        tool.remove(ModRegistry.SCALE_IN_HAND.get());
        tool.remove(ModRegistry.GRIP.get());
        menu.markChanged();
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void handleRequest(RequestDesigns payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        DesignStorage storage = DesignStorage.get(player.server);
        for (long id : payload.ids()) {
            PacketDistributor.sendToPlayer(player, new DesignPixels(id, storage.get(id)));
        }
    }
}
