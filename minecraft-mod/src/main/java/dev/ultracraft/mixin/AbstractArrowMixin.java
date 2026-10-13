package dev.ultracraft.mixin;

import dev.ultracraft.ProjectileClip;
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
    private BlockHitResult ultracraft$clip(Level level, ClipContext ctx) {
        return ProjectileClip.clip(level, ctx);
    }
}
