package space.niliota.minekill.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import space.niliota.minekill.render.ModelCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glowing sign text (glow ink sac). Minecraft draws it full bright; it says so only through the
 * light value it passes, which in a void world is full bright for all text anyway. This marks the
 * text of one sign side as glowing while that side is submitted (ModelCollector.glowingText), so
 * the capture can send it unlit.
 */
@Mixin(net.minecraft.client.renderer.blockentity.AbstractSignRenderer.class)
public abstract class SignTextGlowMixin {
    @Inject(method = "submitSignText", at = @At("HEAD"))
    private void minekill$glowStart(SignRenderState state, PoseStack poseStack, SubmitNodeCollector collector, SignText text, CallbackInfo ci) {
        ModelCollector.glowingText = text.hasGlowingText();
    }

    @Inject(method = "submitSignText", at = @At("RETURN"))
    private void minekill$glowEnd(SignRenderState state, PoseStack poseStack, SubmitNodeCollector collector, SignText text, CallbackInfo ci) {
        ModelCollector.glowingText = false;
    }
}
