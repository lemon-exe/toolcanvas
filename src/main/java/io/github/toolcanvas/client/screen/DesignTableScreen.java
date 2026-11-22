package io.github.toolcanvas.client.screen;

import io.github.toolcanvas.CanvasConfig;
import io.github.toolcanvas.CanvasDesign;
import io.github.toolcanvas.CanvasRules;
import io.github.toolcanvas.DesignData;
import io.github.toolcanvas.ModRegistry;
import io.github.toolcanvas.client.ClientDesigns;
import io.github.toolcanvas.client.TextureSampler;
import io.github.toolcanvas.client.render.CanvasRenderTypes;
import io.github.toolcanvas.menu.DesignTableMenu;
import io.github.toolcanvas.network.Network;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pixel editor + two slots (tool, payment) + player inventory.
 * Left panel: slots, preview, apply/reset, palette. Right panel: the canvas and drawing tools.
 */
public class DesignTableScreen extends AbstractContainerScreen<DesignTableMenu> {
    private enum Tool { PEN, ERASER, FILL, PICKER }

    private record Snapshot(int w, int h, int[] pixels) {}

    // Layout (relative to leftPos/topPos)
    private static final int CANVAS_X = 182, CANVAS_Y = 6, CANVAS_SIZE = 160;
    private static final int PREVIEW_X = 133, PREVIEW_Y = 19, PREVIEW_SIZE = 32;
    private static final int PALETTE_X = 8, PALETTE_Y = 92, SWATCH = 10, PALETTE_COLUMNS = 16;
    private static final int TEXT_X = 34, TEXT_WIDTH = 94;
    private static final int HISTORY_LIMIT = 64;
    private static final int ROW_Y = 170, ROW_STEP = 15, ROW_H = 14;
    private static final int[] SIZES = {50, 75, 100, 125, 150, 175, 200, 250, 300};

    private static final int[] BASE_PALETTE = {
            0xFF000000, 0xFF3F3F3F, 0xFF7F7F7F, 0xFFBFBFBF, 0xFFFFFFFF, 0xFF8B4A21, 0xFFD32F2F, 0xFFF57C00,
            0xFFFBC02D, 0xFF7CB342, 0xFF2E7D32, 0xFF00ACC1, 0xFF4FC3F7, 0xFF1E3FB4, 0xFF7B1FA2, 0xFFE040A0,
    };

    // Canvas state
    @Nullable private int[] pixels;
    private int cw, ch;
    @Nullable private TextureSampler.Image original;
    @Nullable private int[] baseline; // what the tool looks like right now (design or original)
    private int baseW, baseH;
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private boolean edited;

    // Which tool/design the canvas was loaded for
    private ItemStack loadedTool = ItemStack.EMPTY;
    private long loadedDesign;
    private boolean waitingForDesign;

    // Glint colour (-1 = vanilla) and size (percent): what the tool has now vs. what Apply will set
    private int baseGlint = -1, pendingGlint = -1;
    private int baseScale = 100, pendingScale = 100;

    // Copy from the reference slot, waiting for the reference's design pixels to arrive
    private boolean waitingForCopy;
    private long copyDesign;

    // Editor settings
    private Tool tool = Tool.PEN;
    private int color = 0xFF000000;
    private boolean grid = true;
    private boolean mirror;
    private boolean painting;
    private boolean paintErase;
    private int lastPx = -1, lastPy = -1;
    private final List<Integer> palette = new ArrayList<>();

    // Widgets
    private EditBox hexBox;
    private Button applyButton, resetButton, undoButton, redoButton, gridButton, mirrorButton, upscaleButton, shrinkButton;
    private Button copyButton, glintButton, sizeDownButton, sizeUpButton;
    private final Map<Tool, Button> toolButtons = new HashMap<>();
    private boolean updatingHex;

    public DesignTableScreen(DesignTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 346;
        imageHeight = 234;
        inventoryLabelX = DesignTableMenu.INVENTORY_X;
        inventoryLabelY = DesignTableMenu.INVENTORY_Y - 11;
        buildPalette(new int[0]);
    }

    // ================================================================ setup

    @Override
    protected void init() {
        super.init();
        int x = leftPos, y = topPos;

        // Row next to the reference slot: Copy / Apply / Reset
        copyButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.copy"), b -> copyReference())
                .bounds(x + 32, y + 67, 44, 18)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.copy.tooltip"))).build());
        applyButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.apply"), b -> apply())
                .bounds(x + 78, y + 67, 44, 18).build());
        resetButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.reset"), b -> reset())
                .bounds(x + 124, y + 67, 44, 18)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.reset.tooltip"))).build());

        hexBox = new EditBox(font, x + 30, y + 117, 60, 12, Component.translatable("gui.toolcanvas.hex"));
        hexBox.setMaxLength(9);
        hexBox.setResponder(this::onHexTyped);
        addRenderableWidget(hexBox);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> setColor(shade(color, 1.2F)))
                .bounds(x + 93, y + 116, 16, 14)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.lighter"))).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> setColor(shade(color, 0.8F)))
                .bounds(x + 111, y + 116, 16, 14)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.darker"))).build());

        int cx = x + CANVAS_X;
        toolButtons.clear();
        Tool[] tools = Tool.values();
        for (int i = 0; i < tools.length; i++) {
            Tool t = tools[i];
            String key = t.name().toLowerCase(Locale.ROOT);
            toolButtons.put(t, addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.tool." + key), b -> tool = t)
                    .bounds(cx + i * 40, y + ROW_Y, 39, ROW_H)
                    .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.tool." + key + ".tooltip"))).build()));
        }
        int r2 = y + ROW_Y + ROW_STEP, r3 = r2 + ROW_STEP, r4 = r3 + ROW_STEP;
        undoButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.undo"), b -> undo())
                .bounds(cx, r2, 39, ROW_H).tooltip(Tooltip.create(Component.literal("Ctrl+Z"))).build());
        redoButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.redo"), b -> redo())
                .bounds(cx + 40, r2, 39, ROW_H).tooltip(Tooltip.create(Component.literal("Ctrl+Y"))).build());
        gridButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.grid"), b -> grid = !grid)
                .bounds(cx + 80, r2, 39, ROW_H).build());
        mirrorButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.mirror"), b -> mirror = !mirror)
                .bounds(cx + 120, r2, 39, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.mirror.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.clear"), b -> clearCanvas())
                .bounds(cx, r3, 39, ROW_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.original"), b -> loadOriginal())
                .bounds(cx + 40, r3, 39, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.original.tooltip"))).build());
        shrinkButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.shrink"), b -> shrink())
                .bounds(cx + 80, r3, 39, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.shrink.tooltip"))).build());
        upscaleButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.upscale"), b -> upscale(hasShiftDown()))
                .bounds(cx + 120, r3, 39, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.upscale.tooltip"))).build());
        sizeDownButton = addRenderableWidget(Button.builder(Component.literal("-"), b -> stepSize(-1))
                .bounds(cx, r4, 19, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.size.tooltip"))).build());
        sizeUpButton = addRenderableWidget(Button.builder(Component.literal("+"), b -> stepSize(1))
                .bounds(cx + 60, r4, 19, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.size.tooltip"))).build());
        glintButton = addRenderableWidget(Button.builder(Component.translatable("gui.toolcanvas.glint"), b -> setGlint(hasShiftDown()))
                .bounds(cx + 80, r4, 79, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("gui.toolcanvas.glint.tooltip"))).build());

        setColor(color);
        updateButtons();
    }

    // ================================================================ loading

    @Override
    protected void containerTick() {
        super.containerTick();
        ItemStack tool = menu.getTool();
        long designId = designId(tool);
        boolean changed = !ItemStack.isSameItem(tool, loadedTool) || designId != loadedDesign
                || glintOf(tool) != baseGlint || scaleOf(tool) != baseScale;
        if (changed || (waitingForDesign && ClientDesigns.data(designId) != null)) {
            load(tool, designId);
        }
        if (waitingForCopy) {
            if (designId(menu.getReference()) != copyDesign) waitingForCopy = false;
            else if (ClientDesigns.data(copyDesign) != null) copyReference();
        }
        updateButtons();
    }

    private static int glintOf(ItemStack stack) {
        return stack.isEmpty() ? -1 : stack.getOrDefault(ModRegistry.GLINT.get(), -1);
    }

    private static int scaleOf(ItemStack stack) {
        return stack.isEmpty() ? 100 : stack.getOrDefault(ModRegistry.SCALE.get(), 100);
    }

    private static long designId(ItemStack stack) {
        CanvasDesign design = stack.get(ModRegistry.DESIGN.get());
        return design == null ? 0 : design.id();
    }

    private void load(ItemStack tool, long designId) {
        boolean sameItem = !tool.isEmpty() && ItemStack.isSameItem(tool, loadedTool);
        loadedTool = tool.copy();
        loadedDesign = designId;
        baseGlint = glintOf(tool);
        baseScale = scaleOf(tool);
        if (tool.isEmpty()) {
            pixels = null;
            original = null;
            baseline = null;
            waitingForDesign = false;
            return;
        }
        if (!sameItem || original == null) {
            original = TextureSampler.original(tool, CanvasConfig.maxCanvasSize());
            buildPalette(original.pixels());
            undo.clear();
            redo.clear();
            edited = false;
        }

        DesignData design = designId != 0 ? ClientDesigns.data(designId) : null;
        waitingForDesign = designId != 0 && design == null;
        if (design != null) {
            baseline = design.pixels().clone();
            baseW = design.width();
            baseH = design.height();
        } else {
            baseline = original.pixels().clone();
            baseW = original.width();
            baseH = original.height();
        }
        if (!edited || pixels == null) {
            pixels = baseline.clone();
            cw = baseW;
            ch = baseH;
            pendingGlint = baseGlint;
            pendingScale = baseScale;
        }
        edited = isDirty();
    }

    private void buildPalette(int[] source) {
        palette.clear();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int c : source) if ((c >>> 24) != 0) counts.merge(c, 1, Integer::sum);
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(PALETTE_COLUMNS)
                .forEach(e -> palette.add(e.getKey()));
        while (palette.size() < PALETTE_COLUMNS) palette.add(0);
        for (int c : BASE_PALETTE) palette.add(c);
    }

    // ================================================================ actions

    private boolean pixelsDirty() {
        return pixels != null && baseline != null && !(cw == baseW && ch == baseH && Arrays.equals(pixels, baseline));
    }

    /** Anything Apply would change: pixels, glint colour or size. */
    private boolean isDirty() {
        return pixels != null && (pixelsDirty() || pendingGlint != baseGlint || pendingScale != baseScale);
    }

    private boolean matchesOriginal() {
        return pixels != null && original != null && cw == original.width() && ch == original.height()
                && Arrays.equals(pixels, original.pixels());
    }

    /** Only new pixel art costs material; glint, size and going back to the original texture are free. */
    private boolean artCosts() {
        return pixelsDirty() && !matchesOriginal();
    }

    private boolean isBlank() {
        if (pixels == null) return true;
        for (int p : pixels) if ((p >>> 24) != 0) return false;
        return true;
    }

    private boolean canPay() {
        if (minecraft == null || minecraft.player == null) return false;
        return !CanvasRules.paysCost(minecraft.player) || CanvasRules.isValidCost(menu.getTool(), menu.getMaterial());
    }

    private void apply() {
        if (pixels == null || !isDirty() || waitingForDesign) return;
        boolean backToOriginal = matchesOriginal();
        if (isBlank() && !backToOriginal) return;
        if (artCosts() && !canPay()) return;
        // When the server confirms, the tool's new look becomes the baseline (see load()).
        DesignData design = backToOriginal ? null : DesignData.normalized(cw, ch, pixels);
        PacketDistributor.sendToServer(new Network.ApplyDesign(menu.containerId, design, pendingGlint, pendingScale));
    }

    private void reset() {
        if (!hasCustomization()) return;
        PacketDistributor.sendToServer(new Network.ResetDesign(menu.containerId));
        edited = false; // show the original again once the server confirms
    }

    private boolean hasCustomization() {
        return loadedDesign != 0 || baseGlint != -1 || baseScale != 100;
    }

    /** Puts the look of the item in the reference slot on the canvas, with its glint colour and size. */
    private void copyReference() {
        ItemStack reference = menu.getReference();
        if (reference.isEmpty() || pixels == null) return;
        long id = designId(reference);
        DesignData design = id != 0 ? ClientDesigns.data(id) : null;
        if (id != 0 && design == null) {
            waitingForCopy = true; // pixels requested; copyReference() runs again when they arrive
            copyDesign = id;
            return;
        }
        waitingForCopy = false;
        pushUndo();
        if (design != null) {
            pixels = design.pixels().clone();
            cw = design.width();
            ch = design.height();
        } else {
            TextureSampler.Image image = TextureSampler.original(reference, CanvasConfig.maxCanvasSize());
            pixels = image.pixels().clone();
            cw = image.width();
            ch = image.height();
        }
        pendingGlint = glintOf(reference);
        pendingScale = scaleOf(reference);
        edited = true;
    }

    private void setGlint(boolean vanilla) {
        if (pixels == null) return;
        pendingGlint = vanilla ? -1 : CanvasRenderTypes.snap(color & 0xFFFFFF);
        edited = true;
    }

    private void stepSize(int direction) {
        if (pixels == null) return;
        int index = 0;
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] <= pendingScale) index = i;
        if (direction < 0 && SIZES[index] == pendingScale) index--;
        else if (direction > 0) index++;
        pendingScale = SIZES[Math.clamp(index, 0, SIZES.length - 1)];
        edited = true;
    }

    private void loadOriginal() {
        if (original == null) return;
        pushUndo();
        pixels = original.pixels().clone();
        cw = original.width();
        ch = original.height();
        edited = true;
    }

    private void clearCanvas() {
        if (pixels == null) return;
        pushUndo();
        Arrays.fill(pixels, 0);
        edited = true;
    }

    /** Doubles the resolution. Sharp: every pixel becomes 2x2. Smooth: Scale2x, which rounds off diagonal edges. */
    private void upscale(boolean smooth) {
        if (pixels == null || !canUpscale()) return;
        pushUndo();
        int nw = cw * 2, nh = ch * 2;
        int[] out = new int[nw * nh];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int p = pixels[y * cw + x];
                int e0 = p, e1 = p, e2 = p, e3 = p;
                if (smooth) {
                    int a = y > 0 ? pixels[(y - 1) * cw + x] : p;      // above
                    int b = x < cw - 1 ? pixels[y * cw + x + 1] : p;   // right
                    int c = x > 0 ? pixels[y * cw + x - 1] : p;        // left
                    int d = y < ch - 1 ? pixels[(y + 1) * cw + x] : p; // below
                    if (c == a && c != d && a != b) e0 = a;
                    if (a == b && a != c && b != d) e1 = b;
                    if (d == c && d != b && c != a) e2 = c;
                    if (b == d && b != a && d != c) e3 = d;
                }
                out[(2 * y) * nw + 2 * x] = e0;
                out[(2 * y) * nw + 2 * x + 1] = e1;
                out[(2 * y + 1) * nw + 2 * x] = e2;
                out[(2 * y + 1) * nw + 2 * x + 1] = e3;
            }
        }
        pixels = out;
        cw = nw;
        ch = nh;
        edited = true;
    }

    /** Halves the resolution; each 2x2 block becomes its most common colour. */
    private void shrink() {
        if (pixels == null || !canShrink()) return;
        pushUndo();
        int nw = cw / 2, nh = ch / 2;
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int[] block = {
                        pixels[(2 * y) * cw + 2 * x], pixels[(2 * y) * cw + 2 * x + 1],
                        pixels[(2 * y + 1) * cw + 2 * x], pixels[(2 * y + 1) * cw + 2 * x + 1]};
                int best = block[0], bestCount = 0;
                for (int candidate : block) {
                    int count = 0;
                    for (int other : block) if (other == candidate) count++;
                    if (count > bestCount) {
                        best = candidate;
                        bestCount = count;
                    }
                }
                out[y * nw + x] = best;
            }
        }
        pixels = out;
        cw = nw;
        ch = nh;
        edited = true;
    }

    private boolean canUpscale() {
        return pixels != null && Math.max(cw, ch) * 2 <= Math.min(CanvasConfig.maxCanvasSize(), DesignData.HARD_MAX);
    }

    private boolean canShrink() {
        return pixels != null && cw % 2 == 0 && ch % 2 == 0 && Math.min(cw, ch) >= 8;
    }

    private void pushUndo() {
        if (pixels == null) return;
        undo.push(new Snapshot(cw, ch, pixels.clone()));
        while (undo.size() > HISTORY_LIMIT) undo.removeLast();
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty() || pixels == null) return;
        redo.push(new Snapshot(cw, ch, pixels.clone()));
        restore(undo.pop());
    }

    private void redo() {
        if (redo.isEmpty() || pixels == null) return;
        undo.push(new Snapshot(cw, ch, pixels.clone()));
        restore(redo.pop());
    }

    private void restore(Snapshot s) {
        pixels = s.pixels();
        cw = s.w();
        ch = s.h();
        edited = true;
    }

    private void updateButtons() {
        if (applyButton == null) return;
        boolean hasTool = pixels != null;
        boolean dirty = hasTool && isDirty();
        applyButton.active = dirty && !waitingForDesign && (!isBlank() || matchesOriginal()) && (!artCosts() || canPay());
        applyButton.setTooltip(Tooltip.create(applyTooltip(dirty)));
        resetButton.active = hasCustomization();
        copyButton.active = hasTool && !menu.getReference().isEmpty() && !waitingForCopy;
        undoButton.active = !undo.isEmpty();
        redoButton.active = !redo.isEmpty();
        upscaleButton.active = canUpscale();
        shrinkButton.active = canShrink();
        sizeDownButton.active = hasTool && pendingScale > SIZES[0];
        sizeUpButton.active = hasTool && pendingScale < SIZES[SIZES.length - 1];
        glintButton.active = hasTool;
        glintButton.setMessage(pendingGlint < 0
                ? Component.translatable("gui.toolcanvas.glint")
                : Component.translatable("gui.toolcanvas.glint").append(" ")
                        .append(Component.literal("■").withStyle(st -> st.withColor(pendingGlint))));
        gridButton.setMessage(Component.translatable(grid ? "gui.toolcanvas.grid.on" : "gui.toolcanvas.grid"));
        mirrorButton.setMessage(Component.translatable(mirror ? "gui.toolcanvas.mirror.on" : "gui.toolcanvas.mirror"));
    }

    private Component applyTooltip(boolean dirty) {
        if (pixels == null) return Component.translatable("gui.toolcanvas.apply.no_tool");
        if (!dirty) return Component.translatable("gui.toolcanvas.apply.no_changes");
        if (isBlank() && !matchesOriginal()) return Component.translatable("gui.toolcanvas.apply.blank");
        if (!artCosts()) return Component.translatable("gui.toolcanvas.apply.free");
        if (!canPay()) return Component.translatable("gui.toolcanvas.apply.need_material", costName());
        return Component.translatable("gui.toolcanvas.apply.tooltip");
    }

    private Component costName() {
        List<Item> items = CanvasRules.costItems(menu.getTool());
        return items.isEmpty() ? Component.literal("?") : items.get(0).getDescription();
    }

    // ================================================================ colour

    private void setColor(int argb) {
        color = argb;
        if (hexBox != null) {
            updatingHex = true;
            hexBox.setValue((argb >>> 24) == 0xFF
                    ? String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF)
                    : String.format(Locale.ROOT, "#%08X", argb));
            updatingHex = false;
        }
    }

    private void onHexTyped(String text) {
        if (updatingHex) return;
        String hex = text.startsWith("#") ? text.substring(1) : text;
        try {
            if (hex.length() == 6) color = 0xFF000000 | Integer.parseUnsignedInt(hex, 16);
            else if (hex.length() == 8) color = Integer.parseUnsignedInt(hex, 16);
        } catch (NumberFormatException ignored) {
            // keep the previous colour until the text is valid
        }
    }

    private static int shade(int argb, float factor) {
        int a = argb & 0xFF000000;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        if (factor > 1) {
            r += (int) ((255 - r) * (factor - 1));
            g += (int) ((255 - g) * (factor - 1));
            b += (int) ((255 - b) * (factor - 1));
        } else {
            r = (int) (r * factor);
            g = (int) (g * factor);
            b = (int) (b * factor);
        }
        return a | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    // ================================================================ canvas geometry

    private int scale() {
        return Math.max(1, Math.min(CANVAS_SIZE / cw, CANVAS_SIZE / ch));
    }

    private int canvasLeft() {
        return leftPos + CANVAS_X + (CANVAS_SIZE - cw * scale()) / 2;
    }

    private int canvasTop() {
        return topPos + CANVAS_Y + (CANVAS_SIZE - ch * scale()) / 2;
    }

    /** Canvas pixel under the mouse as {x, y}, or null. */
    @Nullable
    private int[] pixelAt(double mx, double my) {
        if (pixels == null) return null;
        int s = scale();
        int px = (int) Math.floor((mx - canvasLeft()) / s);
        int py = (int) Math.floor((my - canvasTop()) / s);
        return px >= 0 && py >= 0 && px < cw && py < ch ? new int[]{px, py} : null;
    }

    private void plot(int x, int y, int c) {
        if (pixels == null || x < 0 || y < 0 || x >= cw || y >= ch) return;
        pixels[y * cw + x] = c;
        if (mirror) pixels[y * cw + (cw - 1 - x)] = c;
        edited = true;
    }

    private void line(int x0, int y0, int x1, int y1, int c) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            plot(x0, y0, c);
            if (x0 == x1 && y0 == y1) return;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private void floodFill(int x, int y, int c) {
        if (pixels == null) return;
        int target = pixels[y * cw + x];
        if (target == c) return;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{x, y});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int px = p[0], py = p[1];
            if (px < 0 || py < 0 || px >= cw || py >= ch || pixels[py * cw + px] != target) continue;
            pixels[py * cw + px] = c;
            stack.push(new int[]{px + 1, py});
            stack.push(new int[]{px - 1, py});
            stack.push(new int[]{px, py + 1});
            stack.push(new int[]{px, py - 1});
        }
        edited = true;
    }

    // ================================================================ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int[] p = pixelAt(mx, my);
        if (p != null && button <= 2) {
            setFocused(null);
            hexBox.setFocused(false);
            if (button == 2 || tool == Tool.PICKER) {
                setColor(pixels[p[1] * cw + p[0]]);
                if (tool == Tool.PICKER) tool = Tool.PEN;
                return true;
            }
            pushUndo();
            boolean erase = button == 1 || tool == Tool.ERASER;
            int c = erase ? 0 : color;
            if (tool == Tool.FILL) {
                floodFill(p[0], p[1], c);
            } else {
                painting = true;
                paintErase = erase;
                lastPx = p[0];
                lastPy = p[1];
                plot(p[0], p[1], c);
            }
            return true;
        }

        int swatch = swatchAt(mx, my);
        if (swatch >= 0) {
            int c = palette.get(swatch);
            if ((c >>> 24) != 0) setColor(c);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (painting) {
            int s = scale();
            int px = Math.clamp((int) Math.floor((mx - canvasLeft()) / s), 0, cw - 1);
            int py = Math.clamp((int) Math.floor((my - canvasTop()) / s), 0, ch - 1);
            line(lastPx, lastPy, px, py, paintErase ? 0 : color);
            lastPx = px;
            lastPy = py;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (painting) {
            painting = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (hexBox.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                hexBox.setFocused(false);
                setFocused(null);
                setColor(color);
                return true;
            }
            hexBox.keyPressed(key, scan, modifiers);
            return true; // don't let 'E' etc. close the screen while typing
        }
        if (hasControlDown() && key == GLFW.GLFW_KEY_Z) {
            if (hasShiftDown()) redo(); else undo();
            return true;
        }
        if (hasControlDown() && key == GLFW.GLFW_KEY_Y) {
            redo();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private int swatchAt(double mx, double my) {
        int rx = (int) Math.floor(mx) - leftPos - PALETTE_X;
        int ry = (int) Math.floor(my) - topPos - PALETTE_Y;
        if (rx < 0 || ry < 0 || rx >= PALETTE_COLUMNS * SWATCH || ry >= 2 * SWATCH) return -1;
        int index = (ry / SWATCH) * PALETTE_COLUMNS + rx / SWATCH;
        return index < palette.size() ? index : -1;
    }

    // ================================================================ rendering

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        Button selected = toolButtons.get(tool);
        if (selected != null) outline(g, selected.getX() - 1, selected.getY() - 1, selected.getWidth() + 2, selected.getHeight() + 2, 0xFFFFE04A);
        renderTooltip(g, mx, my);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        int x = leftPos, y = topPos;
        panel(g, x, y, imageWidth, imageHeight);

        inset(g, x + DesignTableMenu.TOOL_X - 1, y + DesignTableMenu.TOOL_Y - 1, 18, 18);
        inset(g, x + DesignTableMenu.MATERIAL_X - 1, y + DesignTableMenu.MATERIAL_Y - 1, 18, 18);
        inset(g, x + DesignTableMenu.REFERENCE_X - 1, y + DesignTableMenu.REFERENCE_Y - 1, 18, 18);
        if (menu.getReference().isEmpty()) {
            // faint sword silhouette: "put a tool to copy here"
            g.renderFakeItem(new ItemStack(Items.IRON_SWORD), x + DesignTableMenu.REFERENCE_X, y + DesignTableMenu.REFERENCE_Y);
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.fill(x + DesignTableMenu.REFERENCE_X, y + DesignTableMenu.REFERENCE_Y,
                    x + DesignTableMenu.REFERENCE_X + 16, y + DesignTableMenu.REFERENCE_Y + 16, 0xC08B8B8B);
            g.pose().popPose();
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                inset(g, x + DesignTableMenu.INVENTORY_X - 1 + col * 18, y + DesignTableMenu.INVENTORY_Y - 1 + row * 18, 18, 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            inset(g, x + DesignTableMenu.INVENTORY_X - 1 + col * 18, y + DesignTableMenu.INVENTORY_Y + 57, 18, 18);
        }

        // Ghost of the payment item
        ItemStack tool = menu.getTool();
        if (!tool.isEmpty() && menu.getMaterial().isEmpty() && minecraft != null && minecraft.player != null
                && CanvasRules.paysCost(minecraft.player)) {
            List<Item> cost = CanvasRules.costItems(tool);
            if (!cost.isEmpty()) {
                Item ghost = cost.get((int) ((System.currentTimeMillis() / 1000) % cost.size()));
                g.renderFakeItem(new ItemStack(ghost), x + DesignTableMenu.MATERIAL_X, y + DesignTableMenu.MATERIAL_Y);
                g.pose().pushPose();
                g.pose().translate(0, 0, 300);
                g.fill(x + DesignTableMenu.MATERIAL_X, y + DesignTableMenu.MATERIAL_Y,
                        x + DesignTableMenu.MATERIAL_X + 16, y + DesignTableMenu.MATERIAL_Y + 16, 0x998B8B8B);
                g.pose().popPose();
            }
        }

        // Preview at true-ish size
        inset(g, x + PREVIEW_X - 2, y + PREVIEW_Y - 2, PREVIEW_SIZE + 4, PREVIEW_SIZE + 4);
        if (pixels != null) drawImage(g, x + PREVIEW_X, y + PREVIEW_Y, PREVIEW_SIZE, pixels, cw, ch);

        // Palette
        for (int i = 0; i < palette.size(); i++) {
            int sx = x + PALETTE_X + (i % PALETTE_COLUMNS) * SWATCH;
            int sy = y + PALETTE_Y + (i / PALETTE_COLUMNS) * SWATCH;
            int c = palette.get(i);
            g.fill(sx, sy, sx + SWATCH, sy + SWATCH, 0xFF555555);
            if ((c >>> 24) != 0) g.fill(sx + 1, sy + 1, sx + SWATCH - 1, sy + SWATCH - 1, c);
            if (c == color && (c >>> 24) != 0) outline(g, sx, sy, SWATCH, SWATCH, 0xFFFFFFFF);
        }

        // Current colour
        inset(g, x + 8, y + 116, 18, 14);
        checker(g, x + 9, y + 117, 16, 12, 4);
        g.fill(x + 9, y + 117, x + 25, y + 129, color);

        // Canvas
        inset(g, x + CANVAS_X - 2, y + CANVAS_Y - 2, CANVAS_SIZE + 4, CANVAS_SIZE + 4);
        g.fill(x + CANVAS_X, y + CANVAS_Y, x + CANVAS_X + CANVAS_SIZE, y + CANVAS_Y + CANVAS_SIZE, 0xFF6F6F6F);
        if (pixels == null) {
            Component hint = Component.translatable("gui.toolcanvas.place_tool");
            g.drawCenteredString(font, hint, x + CANVAS_X + CANVAS_SIZE / 2, y + CANVAS_Y + CANVAS_SIZE / 2 - 4, 0xFFE0E0E0);
            return;
        }
        int s = scale(), ox = canvasLeft(), oy = canvasTop();
        for (int py = 0; py < ch; py++) {
            for (int px = 0; px < cw; px++) {
                int c = pixels[py * cw + px];
                int x0 = ox + px * s, y0 = oy + py * s;
                if ((c >>> 24) != 0xFF) g.fill(x0, y0, x0 + s, y0 + s, ((px + py) & 1) == 0 ? 0xFFC8C8C8 : 0xFF9C9C9C);
                if ((c >>> 24) != 0) g.fill(x0, y0, x0 + s, y0 + s, c);
            }
        }
        if (grid && s >= 4) {
            for (int i = 1; i < cw; i++) g.fill(ox + i * s, oy, ox + i * s + 1, oy + ch * s, 0x33000000);
            for (int i = 1; i < ch; i++) g.fill(ox, oy + i * s, ox + cw * s, oy + i * s + 1, 0x33000000);
        }
        int[] hover = pixelAt(mx, my);
        if (hover != null) {
            outline(g, ox + hover[0] * s, oy + hover[1] * s, s, s, 0xCCFFFFFF);
            if (mirror) outline(g, ox + (cw - 1 - hover[0]) * s, oy + hover[1] * s, s, s, 0x66FFFFFF);
        }
        if (mirror) {
            int mid = ox + cw * s / 2;
            g.fill(mid, oy, mid + 1, oy + ch * s, 0x55FF4040);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
        if (pixels != null) {
            String size = cw + "×" + ch;
            g.drawString(font, size, PREVIEW_X + PREVIEW_SIZE / 2 - font.width(size) / 2, PREVIEW_Y + PREVIEW_SIZE + 4, 0x404040, false);
        }

        if (pixels != null) {
            String size = Component.translatable("gui.toolcanvas.size", pendingScale).getString();
            g.drawString(font, size, CANVAS_X + 40 - font.width(size) / 2, ROW_Y + 3 * ROW_STEP + 3, 0x404040, false);
        }

        ItemStack tool = menu.getTool();
        if (tool.isEmpty()) {
            g.drawString(font, Component.translatable("gui.toolcanvas.status.empty"), TEXT_X, 25, 0x707070, false);
        } else {
            g.drawString(font, font.plainSubstrByWidth(tool.getHoverName().getString(), TEXT_WIDTH), TEXT_X, 21, 0x404040, false);
            Component state = waitingForDesign ? Component.translatable("gui.toolcanvas.status.loading")
                    : hasCustomization() ? Component.translatable("gui.toolcanvas.status.custom")
                    : Component.translatable("gui.toolcanvas.status.original");
            g.drawString(font, state, TEXT_X, 30, hasCustomization() ? 0x7A4FC0 : 0x707070, false);

            if (minecraft != null && minecraft.player != null) {
                boolean pays = CanvasRules.paysCost(minecraft.player);
                Component cost = pays ? Component.translatable("gui.toolcanvas.cost", costName())
                        : Component.translatable("gui.toolcanvas.cost.free");
                g.drawString(font, font.plainSubstrByWidth(cost.getString(), TEXT_WIDTH), TEXT_X, 45, 0x404040, false);
                ItemStack material = menu.getMaterial();
                if (pays && !material.isEmpty()) {
                    boolean ok = CanvasRules.isValidCost(tool, material);
                    g.drawString(font, Component.translatable(ok ? "gui.toolcanvas.cost.ok" : "gui.toolcanvas.cost.wrong"),
                            TEXT_X, 54, ok ? 0x2E7D32 : 0xC62828, false);
                }
            }
        }
    }

    // ================================================================ drawing helpers

    /** Draws an image scaled to fit a square box, with a checkerboard behind transparent pixels. */
    private static void drawImage(GuiGraphics g, int x, int y, int box, int[] px, int w, int h) {
        checker(g, x, y, box, box, 4);
        int big = Math.max(w, h);
        if (big <= box) {
            int s = box / big;
            int ox = x + (box - w * s) / 2, oy = y + (box - h * s) / 2;
            for (int py = 0; py < h; py++) {
                for (int pxl = 0; pxl < w; pxl++) {
                    int c = px[py * w + pxl];
                    if ((c >>> 24) != 0) g.fill(ox + pxl * s, oy + py * s, ox + pxl * s + s, oy + py * s + s, c);
                }
            }
        } else {
            for (int oy = 0; oy < box; oy++) {
                for (int ox = 0; ox < box; ox++) {
                    int sx = ox * big / box, sy = oy * big / box;
                    if (sx >= w || sy >= h) continue;
                    int c = px[sy * w + sx];
                    if ((c >>> 24) != 0) g.fill(x + ox, y + oy, x + ox + 1, y + oy + 1, c);
                }
            }
        }
    }

    private static void checker(GuiGraphics g, int x, int y, int w, int h, int cell) {
        for (int cy = 0; cy < h; cy += cell) {
            for (int cx = 0; cx < w; cx += cell) {
                int c = (((cx + cy) / cell) & 1) == 0 ? 0xFFC8C8C8 : 0xFF9C9C9C;
                g.fill(x + cx, y + cy, x + Math.min(w, cx + cell), y + Math.min(h, cy + cell), c);
            }
        }
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    /** Vanilla-style raised grey panel. */
    private static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
        g.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
        g.fill(x + 1, y + 1, x + w - 3, y + 3, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 3, y + h - 3, 0xFFFFFFFF);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
    }

    /** Vanilla-style sunken slot/box. */
    private static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF8B8B8B);
        g.fill(x, y, x + w - 1, y + 1, 0xFF373737);
        g.fill(x, y, x + 1, y + h - 1, 0xFF373737);
        g.fill(x + 1, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        g.fill(x + w - 1, y + 1, x + w, y + h, 0xFFFFFFFF);
    }
}
