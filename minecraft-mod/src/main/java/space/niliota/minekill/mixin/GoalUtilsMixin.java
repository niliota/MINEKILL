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
@Mixin(net.minecraft.world.entity.ai.util.GoalUtils.class)
public abstract class GoalUtilsMixin {
    @Inject(method = "isSolid", at = @At("RETURN"), cancellable = true)
    private static void minekill$ukSolid(net.minecraft.world.entity.PathfinderMob mob, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && UkCollision.hasShapes() && UkCollision.blocksCell(pos.getX(), pos.getY(), pos.getZ()))
            cir.setReturnValue(true);
    }
}
