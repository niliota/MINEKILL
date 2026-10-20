package dev.minekill.mixin;

import dev.minekill.MinekillClient;
import dev.minekill.gui.GuiCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds Minecraft's GUI, element by element in final draw order, to {@link GuiCapture}.
 * Verified in the 26.3 bytecode: {@code render()} runs {@code prepare()} (prepareText turns text
 * into glyph elements; prepareItemElements queues each item via submitBlitFromItemAtlas), whose
 * {@code addElementsToMeshes} calls {@code addElementToMesh(element)} for every element, then draws.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void minekill$begin(CallbackInfo ci) {
        GuiCapture.INSTANCE.begin();
    }

    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"))
    private void minekill$queueItem(GuiItemRenderState item, net.minecraft.client.gui.render.GuiItemAtlas.SlotView slot, CallbackInfo ci) {
        GuiCapture.INSTANCE.queueItem(item);
    }

    @Inject(method = "addElementToMesh", at = @At("HEAD"))
    private void minekill$element(GuiElementRenderState element, CallbackInfo ci) {
        GuiCapture.INSTANCE.element(Minecraft.getInstance(), element);
    }

    /**
     * Minecraft clamps a clip rectangle to its real window. With GuiLayout the GUI is laid out
     * for ULTRAKILL's screen, which is larger, so a scrolled list reaching below the real window
     * got a clip 0 pixels tall, which 26.3 rejects with a crash ("Scissor size must be >0",
     * scrolling the video settings, 2026-10-15). Such a clip now becomes 1x1 in the corner: that
     * part is simply not drawn in Minecraft's own window (ULTRAKILL draws the GUI from the capture).
     */
    @Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
    private void minekill$noEmptyScissor(net.minecraft.client.gui.navigation.ScreenRectangle rect,
                                         com.mojang.renderpearl.api.commands.RenderPass pass, CallbackInfo ci) {
        var window = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState;
        int scale = window.guiScale;
        double left = rect.left() * (double) scale, top = rect.top() * (double) scale;
        double right = Math.min(rect.right() * (double) scale, window.width);
        double bottom = Math.min(rect.bottom() * (double) scale, window.height);
        if ((int) (right - left) > 0 && (int) (bottom - top) > 0) return;
        pass.enableScissor(0, 0, 1, 1);
        ci.cancel();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void minekill$end(CallbackInfo ci) {
        MinekillClient client = MinekillClient.get();
        GuiCapture.INSTANCE.end(client != null && MinekillClient.ukLive() ? client.bridge() : null);
    }
}
