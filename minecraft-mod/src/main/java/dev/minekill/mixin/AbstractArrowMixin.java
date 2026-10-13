package dev.minekill.mixin;

import dev.minekill.ProjectileClip;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Arrows and tridents trace against ULTRAKILL's boxes too (ProjectileClip). */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult minekill$clip(Level level, ClipContext ctx) {
        return ProjectileClip.clip(level, ctx);
    }

    /**
     * A stuck arrow falls when no collision is left around its tip, and that test only sees
     * Minecraft blocks: arrows stuck in ULTRAKILL walls dropped and swung round to point down.
     */
    @Redirect(method = "shouldFall", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean minekill$noCollision(Level level, net.minecraft.world.phys.AABB box) {
        return level.noCollision(box) && !(dev.minekill.UkCollision.hasShapes() && dev.minekill.UkCollision.intersectsAny(box));
    }
}
