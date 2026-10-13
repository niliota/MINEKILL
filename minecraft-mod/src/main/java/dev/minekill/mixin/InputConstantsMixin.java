package dev.minekill.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft polls its own window for held modifier keys in places (Minecraft.hasShiftDown and
 * friends: shift tooltips, some creative-menu and container actions). ULTRAKILL has the keyboard,
 * so the window reports nothing; the shift, ctrl and alt state forwarded from ULTRAKILL answers
 * instead.
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
    @Inject(method = "isKeyDown(I)Z", at = @At("RETURN"), cancellable = true)
    private static void minekill$forwardedModifiers(int key, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return;
        int mods = dev.minekill.InputApplier.heldMods();
        boolean down = switch (key) {
            case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> (mods & 0x1) != 0;
            case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> (mods & 0x40) != 0;
            case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> (mods & 0x100) != 0;
            default -> false;
        };
        if (down) cir.setReturnValue(true);
    }
}
