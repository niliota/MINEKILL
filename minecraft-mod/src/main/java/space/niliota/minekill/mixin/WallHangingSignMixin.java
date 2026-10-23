package space.niliota.minekill.mixin;

import space.niliota.minekill.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Wall hanging signs on ULTRAKILL walls; see SignSupportMixin. */
@Mixin(net.minecraft.world.level.block.WallHangingSignBlock.class)
public abstract class WallHangingSignMixin {
    @Inject(method = "canAttachTo", at = @At("RETURN"), cancellable = true)
    private void minekill$ukAttach(LevelReader level, BlockState state, BlockPos attachPos, Direction attachFace, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && UkCollision.supports(attachPos)) cir.setReturnValue(true);
    }
}
