package space.niliota.minekill.mixin;

import space.niliota.minekill.UkCollision;
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
        // The highest surface in (y - 1, y + 0.5]. Not narrower: before a step up, tryJumpOn asks
        // for the floor one cell above the mob's own column and needs the surface it stands on
        // back, which can be in the lower half of its cell (with (y - 0.5, ...] mobs refused every
        // step whose lower floor sat low in its cell, 2026-10-15).
        double top = UkCollision.topIn(pos.getX(), pos.getZ(), pos.getY() - 1, pos.getY() + 0.5);
        if (!Double.isNaN(top) && top > cir.getReturnValue()) cir.setReturnValue(top);
    }

    /**
     * A mob in the air (mid-jump, falling) starts its path at the first non-air block below it,
     * found from block states only. Over an ULTRAKILL floor in a void world there is none, so the
     * path started at the bottom of the world, and paths planned during a jump went nowhere. The
     * scan's block lookup (the fourth getBlockState in getStart) reports ULTRAKILL geometry as
     * stone, so the scan stops on it like on a block.
     */
    @org.spongepowered.asm.mixin.injection.Redirect(method = "getStart",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/PathfindingContext;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", ordinal = 3))
    private net.minecraft.world.level.block.state.BlockState minekill$ukStartFloor(PathfindingContext context, BlockPos pos) {
        if (UkCollision.hasShapes() && UkCollision.blocksCell(pos.getX(), pos.getY(), pos.getZ()))
            return net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        return context.getBlockState(pos);
    }

    @Inject(method = "hasCollisions", at = @At("RETURN"), cancellable = true)
    private void minekill$ukCollides(net.minecraft.world.phys.AABB box, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && UkCollision.hasShapes() && UkCollision.intersectsAny(box)) cir.setReturnValue(true);
    }
}
