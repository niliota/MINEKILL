package dev.minekill.mixin;

import dev.minekill.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Water and lava treat cells ULTRAKILL's geometry fills like solid blocks: they cannot flow into
 * or be held there. Every spread decision (falling, sideways flow, the search for the nearest
 * drop) goes through these two checks, so fluids pool on ULTRAKILL floors, run along them and
 * pour off their edges. Fluids tick on the integrated server; the boxes are an immutable
 * snapshot.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "canPassThroughWall", at = @At("RETURN"), cancellable = true)
    private static void minekill$wall(Direction direction, BlockGetter level, BlockPos sourcePos, BlockState sourceState,
                                        BlockPos targetPos, BlockState targetState, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && UkCollision.hasShapes() && UkCollision.blocksCellForFluid(targetPos.getX(), targetPos.getY(), targetPos.getZ()))
            cir.setReturnValue(false);
    }

    @Inject(method = "canHoldSpecificFluid", at = @At("RETURN"), cancellable = true)
    private static void minekill$hold(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && UkCollision.hasShapes() && UkCollision.blocksCellForFluid(pos.getX(), pos.getY(), pos.getZ()))
            cir.setReturnValue(false);
    }
}
