package io.github.toolcanvas.client.model;

import com.google.common.collect.ImmutableList;
import io.github.toolcanvas.CanvasDesign;
import io.github.toolcanvas.ModRegistry;
import io.github.toolcanvas.client.ClientDesigns;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps a tool's normal item model. Everything is passed straight through to the original model;
 * the only change is that a stack carrying a design resolves to the design's model.
 * Stacks without a design cost one component lookup, so untouched tools render exactly as before.
 */
public final class CanvasWrappedModel extends net.neoforged.neoforge.client.model.BakedModelWrapper<BakedModel> {
    private final ItemOverrides overrides;

    public CanvasWrappedModel(BakedModel original) {
        super(original);
        this.overrides = new Overrides(original);
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }

    private static final class Overrides extends ItemOverrides {
        private final BakedModel original;

        Overrides(BakedModel original) {
            this.original = original;
        }

        @Nullable
        @Override
        public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
            // Let the original model (and any overrides it has, e.g. damage-based variants) resolve first.
            BakedModel base = original.getOverrides().resolve(original, stack, level, entity, seed);
            CanvasDesign design = stack.get(ModRegistry.DESIGN.get());
            if (design == null) return base;
            BakedModel custom = ClientDesigns.model(design.id(), base != null ? base : original);
            return custom != null ? custom : base;
        }

        @Override
        public ImmutableList<BakedOverride> getOverrides() {
            return original.getOverrides().getOverrides();
        }
    }
}
