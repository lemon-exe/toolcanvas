package io.github.toolcanvas.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.toolcanvas.client.ClientDesigns;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Many weapon mods (like vanilla's trident) swap in a separate "in hand" model at the start of
 * {@link ItemRenderer#render}, after the normal model lookup already picked the design model.
 * Right before the model is posed for its display context we put the design model back, so a
 * repainted tool looks the same in hand as in the inventory, whatever the owning mod did earlier.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;handleCameraTransforms(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemDisplayContext;Z)Lnet/minecraft/client/resources/model/BakedModel;"),
            require = 0)
    private BakedModel toolcanvas$keepDesignModel(PoseStack poseStack, BakedModel model, ItemDisplayContext context,
                                                 boolean leftHand, Operation<BakedModel> original,
                                                 @Local(argsOnly = true) ItemStack stack) {
        return original.call(poseStack, ClientDesigns.designModelFor(stack, model), context, leftHand);
    }
}
