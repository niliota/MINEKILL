package dev.minekill.mixin;

import dev.minekill.UkPick;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The crosshair sees ULTRAKILL geometry (see {@link UkPick}). Verified in the 26.3 bytecode:
 * {@code Entity.pick(double, float, boolean)} makes exactly one {@code Level.clip} call, and its
 * block result also caps how far entity targeting reaches, so ULTRAKILL walls will stop the
 * player hitting things through them. Players only.
 */
@Mixin(Entity.class)
public abstract class EntityPickMixin {
    @Redirect(
            method = "pick(DFZ)Lnet/minecraft/world/phys/HitResult;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult minekill$pickUltrakill(Level level, ClipContext context) {
        BlockHitResult vanilla = level.clip(context);
        if (!((Object) this instanceof Player)) return vanilla;
        return UkPick.refine(context.getFrom(), context.getTo(), vanilla);
    }
}
