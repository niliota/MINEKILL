package space.niliota.minekill.mixin;

import space.niliota.minekill.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.GoalUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Random wandering over ULTRAKILL's level. A wander target is a random cell near the mob; when it
 * lands inside something solid, Minecraft moves it straight up until it is out. In Minecraft's
 * terrain that finds the surface of a hill. ULTRAKILL's walls and pillars are tall solid boxes, so
 * a target inside one was lifted to its top: mobs kept choosing the tops of walls, which they
 * cannot reach, and walked into them (2026-10-15: "every mob seems to be trying to move to higher
 * grounds"). A target that would be lifted through more than {@link #MAX_LIFT} cells of ULTRAKILL
 * geometry is dropped instead; Minecraft tries another (ten per choice).
 */
@Mixin(net.minecraft.world.entity.ai.util.LandRandomPos.class)
public abstract class LandRandomPosMixin {
    private static final int MAX_LIFT = 2;

    @Inject(method = "movePosUpOutOfSolid", at = @At("HEAD"), cancellable = true)
    private static void minekill$noWallTops(PathfinderMob mob, BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        if (!UkCollision.hasShapes()) return;
        int lift = 0;
        boolean uk = false;
        BlockPos.MutableBlockPos p = pos.mutable();
        while (lift <= MAX_LIFT && GoalUtils.isSolid(mob, p)) {
            uk |= UkCollision.blocksCell(p.getX(), p.getY(), p.getZ());
            p.move(0, 1, 0);
            lift++;
        }
        if (lift > MAX_LIFT && uk) cir.setReturnValue(null);
    }
}
