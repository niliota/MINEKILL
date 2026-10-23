package space.niliota.minekill.mixin;

import space.niliota.minekill.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sand, gravel, anvils, concrete powder on ULTRAKILL floors. A gravity block starts falling when
 * the block state below it is "free" (air, fire, liquid, replaceable). Over an ULTRAKILL floor
 * that state is air, so a block placed on the floor fell at once (2026-10-15). The one lookup in
 * FallingBlock.tick, of the cell below, reports ULTRAKILL geometry as stone.
 */
@Mixin(net.minecraft.world.level.block.FallingBlock.class)
public abstract class FallingBlockMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState minekill$ukBelow(ServerLevel level, BlockPos below) {
        if (UkCollision.hasShapes() && UkCollision.blocksCell(below.getX(), below.getY(), below.getZ()))
            return Blocks.STONE.defaultBlockState();
        return level.getBlockState(below);
    }
}
