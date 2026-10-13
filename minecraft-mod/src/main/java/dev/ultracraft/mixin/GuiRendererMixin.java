package dev.ultracraft.mixin;

import dev.ultracraft.UltraCraftClient;
import dev.ultracraft.gui.GuiCapture;
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
    private void ultracraft$begin(CallbackInfo ci) {
        GuiCapture.INSTANCE.begin();
    }

    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"))
    private void ultracraft$queueItem(GuiItemRenderState item, net.minecraft.client.gui.render.GuiItemAtlas.SlotView slot, CallbackInfo ci) {
        GuiCapture.INSTANCE.queueItem(item);
    }

    @Inject(method = "addElementToMesh", at = @At("HEAD"))
    private void ultracraft$element(GuiElementRenderState element, CallbackInfo ci) {
        GuiCapture.INSTANCE.element(Minecraft.getInstance(), element);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void ultracraft$end(CallbackInfo ci) {
        UltraCraftClient client = UltraCraftClient.get();
        GuiCapture.INSTANCE.end(client != null && UltraCraftClient.ukLive() ? client.bridge() : null);
    }
}
