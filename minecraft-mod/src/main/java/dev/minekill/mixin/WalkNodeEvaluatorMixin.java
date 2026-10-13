package dev.minekill.mixin;

import dev.minekill.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mob pathfinding over ULTRAKILL's level; see PathContextMixin. */
@Mixin(net.minecraft.world.level.pathfinder.WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Inject(method = "getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D",
            at = @At("RETURN"), cancellable = true)
    private static void minekill$ukFloor(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Double> cir) {
        if (!UkCollision.hasShapes()) return;
        double top = UkCollision.topIn(pos.getX(), pos.getZ(), pos.getY() - 1, pos.getY() + 0.5);
        if (!Double.isNaN(top) && top > cir.getReturnValue()) cir.setReturnValue(top);
    }

    @Inject(method = "hasCollisions", at = @At("RETURN"), cancellable = true)
    private void minekill$ukCollides(net.minecraft.world.phys.AABB box, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && UkCollision.hasShapes() && UkCollision.intersectsAny(box)) cir.setReturnValue(true);
    }
}
