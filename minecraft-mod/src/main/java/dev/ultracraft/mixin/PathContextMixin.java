package dev.ultracraft.mixin;

import dev.ultracraft.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mob pathfinding over ULTRAKILL's level. Minecraft's pathfinder only reads blocks: to it an
 * ULTRAKILL floor is air, so mobs stood on it (collision) but never planned a route across it.
 * These hooks answer its questions from ULTRAKILL's collision boxes as well (UkCollision):
 * <ul>
 *   <li>what a cell is: filled by ULTRAKILL geometry = BLOCKED, like a solid block, so the cell
 *       above it becomes WALKABLE through Minecraft's own rules;</li>
 *   <li>the floor height under a node (for steps and jumps): the ULTRAKILL surface there;</li>
 *   <li>whether a mob's box fits (jumps, diagonal moves): ULTRAKILL boxes count;</li>
 *   <li>where random wandering may go: ULTRAKILL floors are stable, solid ground.</li>
 * </ul>
 * Mobs path on the integrated server thread; the queries read an immutable snapshot.
 */
@Mixin(PathfindingContext.class)
public abstract class PathContextMixin {
    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private void ultracraft$ukBlocked(int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
        if (UkCollision.hasShapes() && UkCollision.blocksCell(x, y, z)) cir.setReturnValue(PathType.BLOCKED);
    }
}
