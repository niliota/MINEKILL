package dev.minekill.mixin;

import dev.minekill.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where a ground mob's path goes. Before planning, GroundPathNavigation moves the target onto the
 * surface: down through air to the first block, else up through air, then up out of solid. It
 * reads block states only. Over an ULTRAKILL floor in a void world there is no block below, so
 * the target went up to the top of the world: every chase and wander aimed at the build limit,
 * mobs climbed whatever they could and froze where they could climb no higher (2026-10-15: "once
 * a mob reaches a higher ground, they won't go down"). The same walk, with ULTRAKILL geometry
 * counted as solid and as not-air (UkCollision.blocksCell, the pathfinder's own rule).
 */
@Mixin(net.minecraft.world.entity.ai.navigation.GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin {
    @Inject(method = "findSurfacePosition", at = @At("HEAD"), cancellable = true)
    private void minekill$ukSurface(LevelChunk chunk, BlockPos pos, int reachRange, CallbackInfoReturnable<BlockPos> cir) {
        if (!UkCollision.hasShapes()) return;
        var level = chunk.getLevel();
        int minY = level.getMinY(), maxY = level.getMaxY();
        BlockPos.MutableBlockPos p;
        if (air(chunk, pos)) {
            p = pos.mutable().move(0, -1, 0);
            while (p.getY() >= minY && air(chunk, p)) p.move(0, -1, 0);
            // Nothing below (outside the area ULTRAKILL publishes collision for, or a real drop
            // into the void): keep the target where it is. Minecraft would search upwards and,
            // in a void world, end at the build limit.
            cir.setReturnValue(p.getY() >= minY ? p.above() : pos);
            return;
        }
        if (solid(chunk, pos)) {
            p = pos.mutable().move(0, 1, 0);
            while (p.getY() <= maxY && solid(chunk, p)) p.move(0, 1, 0);
            cir.setReturnValue(p.immutable());
            return;
        }
        cir.setReturnValue(pos);
    }

    private static boolean air(LevelChunk chunk, BlockPos p) {
        return chunk.getBlockState(p).isAir() && !UkCollision.blocksCell(p.getX(), p.getY(), p.getZ());
    }

    private static boolean solid(LevelChunk chunk, BlockPos p) {
        return chunk.getBlockState(p).isSolid() || UkCollision.blocksCell(p.getX(), p.getY(), p.getZ());
    }
}
