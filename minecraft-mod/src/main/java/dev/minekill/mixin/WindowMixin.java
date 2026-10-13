package dev.minekill.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.minekill.MinekillClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells Minecraft it is focused while its window is in the background.
 *
 * <p>Minecraft pauses when it loses focus, which would stop the world ticking behind
 * ULTRAKILL. SkyCraft solves this the same way: "The MC window is hidden but told it is
 * focused, so it doesn't pause or release the mouse."
 *
 * <p>MUST ship together with {@link MouseHandlerMixin}. Telling Minecraft it is focused
 * makes it believe it should grab the OS cursor; on its own that would steal the pointer
 * from ULTRAKILL and make the mouse unusable in both games. That mixin suppresses the
 * grab, and mouse movement is forwarded as a delta instead, so Minecraft still turns.
 * Either half alone is worse than not doing it at all.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    /** GUI layout for ULTRAKILL's screen (see GuiLayout). */
    @Inject(method = "getGuiScaledWidth", at = @At("HEAD"), cancellable = true)
    private void minekill$guiWidth(CallbackInfoReturnable<Integer> cir) {
        if (dev.minekill.gui.GuiLayout.active()) cir.setReturnValue(dev.minekill.gui.GuiLayout.scaledWidth());
    }

    @Inject(method = "getGuiScaledHeight", at = @At("HEAD"), cancellable = true)
    private void minekill$guiHeight(CallbackInfoReturnable<Integer> cir) {
        if (dev.minekill.gui.GuiLayout.active()) cir.setReturnValue(dev.minekill.gui.GuiLayout.scaledHeight());
    }

    @Inject(method = "getGuiScale", at = @At("HEAD"), cancellable = true)
    private void minekill$guiScale(CallbackInfoReturnable<Integer> cir) {
        if (dev.minekill.gui.GuiLayout.active()) cir.setReturnValue(dev.minekill.gui.GuiLayout.scale());
    }

    @Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
    private void minekill$treatAsFocused(CallbackInfoReturnable<Boolean> cir) {
        if (MinekillClient.treatAsFocused()) {
            cir.setReturnValue(true);
        }
    }
}