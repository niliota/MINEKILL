package space.niliota.minekill.mixin;

import space.niliota.minekill.MinekillClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ticks the bridge once per client tick.
 *
 * <p>Minecraft 26.3 names: the class formerly called MinecraftClient is now
 * {@code net.minecraft.client.Minecraft}, and its per-tick method is
 * {@code runTick(boolean)}, not {@code tick()}. Confirmed with javap against the
 * 26.3 client jar.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "runTick", at = @At("HEAD"))
    private void minekill$tick(boolean tickStart, CallbackInfo ci) {
        MinekillClient client = MinekillClient.get();
        if (client != null) client.tick((Minecraft) (Object) this);
    }

    /**
     * Lets held attacks sustain mining without Minecraft believing it owns the cursor.
     *
     * <p>Verified from the 26.3 bytecode. {@code handleKeybinds} ends with:
     *
     * <pre>
     * 700: getfield      keyAttack
     * 703: invokevirtual KeyMapping.isDown()
     * 706: ifeq          723          // not held -> false
     * 709: getfield      mouseHandler
     * 713: invokevirtual MouseHandler.isMouseGrabbed()
     * 716: ifeq          723          // NOT GRABBED -> false
     * 719: iconst_1
     * 724: invokevirtual continueAttack(Z)V
     * </pre>
     *
     * <p>So mining is the AND of "attack is held" and "the OS cursor is grabbed". We
     * suppress the grab on purpose, because a grabbed cursor in Minecraft's background
     * window steals the pointer from ULTRAKILL and hides it, which is the bug
     * {@link MouseHandlerMixin} exists to prevent. The consequence was that
     * {@code continueAttack(false)} ran every single tick, which Minecraft reads as
     * "the button was released", and mining could never begin or continue. Movement was
     * unaffected because it runs in the player tick rather than here, which is exactly
     * why walking worked while left-click did nothing.
     *
     * <p>This is the one and only {@code isMouseGrabbed()} call in the whole class, so
     * redirecting it is as narrow as the problem: nothing else in Minecraft's input
     * handling is affected, and when the bridge is down the original result is returned
     * unchanged. The {@code keyAttack.isDown()} half of the gate is left alone, so
     * mining still only happens while the attack key is genuinely held.
     */
    @Redirect(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean minekill$allowHeldAttackWithoutGrab(MouseHandler handler) {
        return (MinekillClient.bridgeConnected() && ((Minecraft) (Object) this).gui.screen() == null)
                || handler.isMouseGrabbed();
    }
}