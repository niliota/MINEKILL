package dev.ultracraft.mixin;

import dev.ultracraft.gui.GuiCapture;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entities in the GUI (the player in the inventory) are rendered to an offscreen texture and
 * blitted; ULTRAKILL cannot have that texture. GuiCapture rebuilds the entity's geometry instead.
 */
@Mixin(PictureInPictureRenderer.class)
public abstract class PictureInPictureRendererMixin {
    @Inject(method = "prepare", at = @At("HEAD"))
    private void ultracraft$entity(PictureInPictureRenderState state, GuiRenderState gui, FeatureRenderDispatcher dispatcher,
                                   int guiScale, CallbackInfo ci) {
        if (state instanceof GuiEntityRenderState entity) GuiCapture.INSTANCE.queueEntity(entity);
    }
}
