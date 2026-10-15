package dev.minekill.mixin;

import dev.minekill.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Signs on ULTRAKILL geometry (2026-10-15: "I can't place signs on UK meshes"). Every sign asks
 * whether the block it hangs on is solid, from its block state only, which over ULTRAKILL
 * geometry is air: placement was refused (and a neighbour update would pop the sign). Each
 * survival check also accepts ULTRAKILL geometry in that cell (UkCollision.supports).
 */
@Mixin({StandingSignBlock.class, WallSignBlock.class, CeilingHangingSignBlock.class})
public abstract class SignSupportMixin {
    @Inject(method = "canSurvive", at = @At("RETURN"), cancellable = true)
    private void minekill$ukSupport(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || !UkCollision.hasShapes()) return;
        Object self = this;
        BlockPos support;
        if (self instanceof StandingSignBlock) support = pos.below();
        else if (self instanceof CeilingHangingSignBlock) support = pos.above();
        else support = pos.relative(state.getValue(WallSignBlock.FACING).getOpposite());
        if (UkCollision.supports(support)) cir.setReturnValue(true);
    }
}
