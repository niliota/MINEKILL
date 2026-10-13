package dev.ultracraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ultracraft.render.WorldModels;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The blocks being broken this frame, for WorldModels' cracks. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "submitBlockDestroyAnimation", at = @At("HEAD"))
    private void ultracraft$breaking(PoseStack poseStack, SubmitNodeCollector collector, LevelRenderState state, CallbackInfo ci) {
        WorldModels.setBreaking(state.blockBreakingRenderStates);
    }
}
