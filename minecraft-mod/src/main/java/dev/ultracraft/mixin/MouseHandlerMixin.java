package dev.ultracraft.mixin;

import dev.ultracraft.UltraCraftClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Minecraft grabbing the OS cursor while ULTRAKILL has focus.
 *
 * <p>ULTRAKILL is the window the player looks at and uses. If Minecraft grabs the cursor
 * while running in the background, the pointer is hidden and recentred into a background
 * window, and both games lose mouse control.
 *
 * <p>Mouse look is unaffected because {@code InputApplier} forwards ULTRAKILL's mouse
 * delta into {@code MouseHandler.onMove} directly; Minecraft accumulates that delta and
 * turns the player from it, so it never needs the real cursor.
 *
 * <p>Suppressed for as long as the bridge to ULTRAKILL is connected, rather than while
 * ULTRAKILL reports OS focus. Both of those used to be true at different times and the
 * distinction cost real debugging time for no benefit: ULTRAKILL's focus flag comes from
 * {@code Application.isFocused}, which under Wine was false on almost every tick, so the
 * grab went almost unsuppressed. That matters more than it sounds, because
 * {@code grabMouse()} calls {@code KeyMapping.setAll()}, and that clears <em>every</em>
 * key mapping. Minecraft grabs repeatedly, so forwarded keys were cleared a frame after
 * being applied, which looked exactly like "the keys never arrive". Bridging is also the
 * honest condition: ULTRAKILL is the surface the player drives and Minecraft is the
 * simulation behind it, so there is nothing for a grabbed cursor to serve.
 *
 * <p>Instrumented: counts grab attempts and cancellations. "Stuck trying to go back to
 * the centre" is the signature of a grab that was NOT prevented, so this needs to be
 * provable rather than assumed.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    private static int ultracraft$grabAttempts;
    private static int ultracraft$grabCancelled;
    private static boolean ultracraft$reported;

    /**
     * Lets the camera turn from forwarded mouse movement without a grabbed cursor.
     *
     * <p>Verified from the 26.3 bytecode. {@code handleAccumulatedMovement} ends with:
     *
     * <pre>
     *  287: aload_0
     *  288: invokevirtual MouseHandler.isMouseGrabbed()
     *  291: ifeq          309      // not grabbed -> SKIP THE TURN
     *  294: getfield      minecraft.player
     *  301: ifnull        309
     *  305: invokevirtual turnPlayer(D)V
     *  309: accumulatedDX = 0.0    // then unconditionally discarded
     *  315: accumulatedDY = 0.0
     * </pre>
     *
     * <p>So the accumulated movement is not merely unused while ungrabbed, it is read and
     * then thrown away. Forwarding mouse deltas into {@code accumulatedDX}/{@code
     * accumulatedDY} therefore achieved nothing at all: the camera could not turn no matter
     * how much movement was delivered. That is why a session in which ULTRAKILL forwarded
     * hundreds of non-zero mouse samples left Minecraft's yaw at a single unchanging value.
     *
     * <p>This is the same gate as the one in {@code Minecraft.handleKeybinds}, which
     * {@code MinecraftMixin} already redirects, but in a <b>different method</b>. Patching
     * one does not affect the other, which is precisely what went wrong: the mining gate was
     * opened and the camera gate was left shut, so the fix looked applied and changed
     * nothing. Two call sites means two redirects, and there is no way to reach both from one
     * {@code @Redirect}.
     *
     * <p>Scoped to {@code handleAccumulatedMovement} so that everything else in
     * {@code MouseHandler} still sees the real grab state.
     */
    @Redirect(
            method = "handleAccumulatedMovement",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean ultracraft$allowTurnWithoutGrab(MouseHandler self) {
        return UltraCraftClient.bridgeConnected() || self.isMouseGrabbed();
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void ultracraft$noGrab(CallbackInfo ci) {
        ultracraft$grabAttempts++;

        // Cancelling here also stops KeyMapping.setAll(), which grabMouse() calls and
        // which clears EVERY key mapping. That call was the reason forwarded keys read
        // as set-then-immediately-false: Minecraft grabs the mouse repeatedly, wiping the
        // keys a frame after they were applied.

        boolean suppress = UltraCraftClient.bridgeConnected();
        if (suppress) {
            ultracraft$grabCancelled++;
            ci.cancel();
        }

        if (!ultracraft$reported) {
            ultracraft$reported = true;
            org.slf4j.LoggerFactory.getLogger("ultracraft").info(
                    "[UltraCraft] cursor grab: attempt #{} suppressed={} "
                            + "(bridge connected={}, ULTRAKILL focused={}); "
                            + "suppressed {} of {} so far",
                    ultracraft$grabAttempts,
                    suppress,
                    UltraCraftClient.bridgeConnected(),
                    UltraCraftClient.ukHasFocus(),
                    ultracraft$grabCancelled,
                    ultracraft$grabAttempts);
        }
    }
}