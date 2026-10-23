package space.niliota.minekill.mixin;

import space.niliota.minekill.UkCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code Projectile.hitTargetOrDeflectSelf} drops a block hit whose block is air. A hit on
 * ULTRAKILL geometry (ProjectileClip) is in a cell that is air to Minecraft, so arrows were moved
 * onto the wall every tick but never stuck: gravity kept building, and they turned and sped up.
 * For that one check, a cell ULTRAKILL geometry reaches into reads as solid, and the hit goes
 * through (onHit: arrows stick, snowballs and potions break, pearls teleport).
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Redirect(method = "hitTargetOrDeflectSelf", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState minekill$hitState(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() && UkCollision.hasShapes() && UkCollision.intersectsAny(new AABB(pos))) return Blocks.STONE.defaultBlockState();
        return state;
    }
}
