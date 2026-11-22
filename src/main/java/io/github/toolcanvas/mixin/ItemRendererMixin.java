package io.github.toolcanvas.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.toolcanvas.ModRegistry;
import io.github.toolcanvas.client.ClientDesigns;
import io.github.toolcanvas.client.render.GlintRendering;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    /**
     * Many weapon mods (like vanilla's trident) swap in a separate "in hand" model at the start of
     * {@link ItemRenderer#render}, after the normal model lookup already picked the design model.
     * Right before the model is posed we put the design model back, then apply the tool's size setting
     * (displays, item frames and dropped items only).
     */
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;handleCameraTransforms(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemDisplayContext;Z)Lnet/minecraft/client/resources/model/BakedModel;"),
            require = 0)
    private BakedModel toolcanvas$keepDesignModel(PoseStack poseStack, BakedModel model, ItemDisplayContext context,
                                                 boolean leftHand, Operation<BakedModel> original,
                                                 @Local(argsOnly = true) ItemStack stack) {
        BakedModel result = original.call(poseStack, ClientDesigns.designModelFor(stack, model), context, leftHand);
        // Size is for showing tools off: displays, item frames, dropped items. Inventory and hands stay normal.
        if (context != ItemDisplayContext.GUI && !context.firstPerson()
                && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND && context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) {
            Integer percent = stack.get(ModRegistry.SCALE.get());
            if (percent != null && percent != 100) {
                float f = percent / 100F;
                poseStack.scale(f, f, f);
            }
        }
        return result;
    }

    /** Smooth (vanilla-like) glint on designs, and custom glint colours. */
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getFoilBufferDirect(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 0)
    private VertexConsumer toolcanvas$glintDirect(MultiBufferSource buffers, RenderType type, boolean noEntity, boolean glint,
                                                  Operation<VertexConsumer> original, @Local(argsOnly = true) ItemStack stack) {
        VertexConsumer custom = GlintRendering.foilBuffer(buffers, type, glint, stack);
        return custom != null ? custom : original.call(buffers, type, noEntity, glint);
    }

    /** Second pass: draw the glint on top of the already-written tool (see GlintRendering#foilBuffer). */
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"),
            require = 0)
    private void toolcanvas$glintPass(ItemRenderer self, BakedModel model, ItemStack stack, int light, int overlay,
                                      PoseStack poseStack, VertexConsumer consumer, Operation<Void> original,
                                      @Local(argsOnly = true) MultiBufferSource buffers) {
        original.call(self, model, stack, light, overlay, poseStack, consumer);
        if (GlintRendering.handlesGlint(model, stack)) {
            original.call(self, model, stack, light, overlay, poseStack, GlintRendering.glintBuffer(buffers, model, stack));
        }
    }

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getFoilBuffer(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 0)
    private VertexConsumer toolcanvas$glint(MultiBufferSource buffers, RenderType type, boolean noEntity, boolean glint,
                                            Operation<VertexConsumer> original, @Local(argsOnly = true) ItemStack stack) {
        VertexConsumer custom = GlintRendering.foilBuffer(buffers, type, glint, stack);
        return custom != null ? custom : original.call(buffers, type, noEntity, glint);
    }
}
