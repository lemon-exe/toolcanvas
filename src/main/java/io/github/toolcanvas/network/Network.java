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

    /** Client → server: paint the tool in the open Design Table with these pixels. */
    public record ApplyDesign(int containerId, DesignData design) implements CustomPacketPayload {
        public static final Type<ApplyDesign> TYPE = new Type<>(ToolCanvas.id("apply_design"));
        public static final StreamCodec<FriendlyByteBuf, ApplyDesign> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ApplyDesign::containerId,
                DesignData.STREAM_CODEC, ApplyDesign::design,
                ApplyDesign::new);

        @Override
        public Type<ApplyDesign> type() {
            return TYPE;
        }
    }

    /** Client → server: put the tool in the open Design Table back to its original look. */
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

        DesignData raw = payload.design();
        int max = CanvasConfig.maxCanvasSize();
        if (raw.width() > max || raw.height() > max) return;

        DesignData design = DesignData.normalized(raw.width(), raw.height(), raw.pixels());
        long id = design.id();
        CanvasDesign current = tool.get(ModRegistry.DESIGN.get());
        if (current != null && current.id() == id) return; // nothing changed, don't charge

        boolean pays = CanvasRules.paysCost(player);
        ItemStack material = menu.getMaterial();
        if (pays && !CanvasRules.isValidCost(tool, material)) {
            player.displayClientMessage(Component.translatable("message.toolcanvas.need_material"), true);
            return;
        }

        DesignStorage.get(player.server).put(design);
        tool.set(ModRegistry.DESIGN.get(), new CanvasDesign(id));
        if (pays) material.shrink(1);
        menu.markChanged();

        // The painter sees the result immediately; everyone else fetches it the first time they see the tool.
        PacketDistributor.sendToPlayer(player, new DesignPixels(id, design));
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_WORK_CARTOGRAPHER, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void handleReset(ResetDesign payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        DesignTableMenu menu = openMenu(player, payload.containerId());
        if (menu == null) return;

        ItemStack tool = menu.getTool();
        if (tool.isEmpty() || !tool.has(ModRegistry.DESIGN.get())) return;
        tool.remove(ModRegistry.DESIGN.get());
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
