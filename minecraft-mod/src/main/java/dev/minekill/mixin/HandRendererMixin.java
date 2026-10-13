package dev.minekill.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.minekill.render.HandCapture;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After Minecraft submits its hands, submit them once more into HandCapture's collector. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandRendererMixin {
    @Inject(method = "submitHandsWithItems", at = @At("TAIL"))
    private void minekill$captureHands(float partialTicks, PoseStack poseStack, SubmitNodeCollector collector,
                                         PlayerRenderState player, FirstPersonHandsAndItemsRenderState state, CallbackInfo ci) {
        if (HandCapture.INSTANCE.collecting(collector)) return;   // our own replay
        HandCapture.INSTANCE.submit((FirstPersonHandsAndItemsRenderer) (Object) this, partialTicks, poseStack, player, state);
    }
}
