package dev.minekill.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.minekill.render.HandCapture;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brackets the hand pass for HandCapture: the camera at the start, publish at every return. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void minekill$handBegin(CameraRenderState camera, PlayerRenderState player, GpuTextureView depth, CallbackInfo ci) {
        HandCapture.INSTANCE.begin(camera);
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void minekill$handEnd(CameraRenderState camera, PlayerRenderState player, GpuTextureView depth, CallbackInfo ci) {
        HandCapture.INSTANCE.end();
    }
}
