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
@Mixin(net.minecraft.world.entity.ai.navigation.PathNavigation.class)
public abstract class PathNavigationMixin {
    @Inject(method = "isStableDestination", at = @At("RETURN"), cancellable = true)
    private void minekill$ukStable(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && UkCollision.hasShapes() && UkCollision.blocksCell(pos.getX(), pos.getY() - 1, pos.getZ()))
            cir.setReturnValue(true);
    }
}
