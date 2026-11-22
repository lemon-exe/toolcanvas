package io.github.toolcanvas.client.model;

import io.github.toolcanvas.client.render.GlintRendering;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A flat item model drawn from a design's own dynamic texture. */
public final class DesignBakedModel implements BakedModel {
    private final List<BakedQuad> quads;
    private final ItemTransforms transforms;
    private final TextureAtlasSprite particle;
    private final List<RenderType> culled;
    private final List<RenderType> unculled;

    public DesignBakedModel(List<BakedQuad> quads, ItemTransforms transforms, TextureAtlasSprite particle, ResourceLocation texture) {
        this.quads = quads;
        this.transforms = transforms;
        this.particle = particle;
        // Same choice NeoForge makes for normal items (RenderTypeHelper#getFallbackItemRenderType),
        // just bound to our texture instead of the block atlas.
        this.culled = List.of(RenderType.entityTranslucentCull(texture));
        this.unculled = List.of(RenderType.itemEntityTranslucentCull(texture));
        GlintRendering.markDesignType(culled.get(0));
        GlintRendering.markDesignType(unculled.get(0));
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
        return side == null ? quads : List.of();
    }

    @Override
    public List<RenderType> getRenderTypes(ItemStack stack, boolean cull) {
        return cull ? culled : unculled;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return false;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return particle;
    }

    @Override
    public ItemTransforms getTransforms() {
        return transforms;
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
