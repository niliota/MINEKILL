package dev.minekill.mixin;

import dev.minekill.ProjectileClip;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Thrown projectiles (snowballs, eggs, pearls, potions, ...) trace against ULTRAKILL's boxes too. */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
    @Redirect(method = "getHitResult(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;FLnet/minecraft/world/level/ClipContext$Block;)Lnet/minecraft/world/phys/HitResult;",
            at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult minekill$clip(Level level, ClipContext ctx) {
        return ProjectileClip.clip(level, ctx);
    }
}
