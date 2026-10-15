package dev.minekill.mixin;

import dev.minekill.UkCollision;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A falling block landing on an ULTRAKILL floor. On the ground it becomes a block again only if
 * it "would not continue falling": FallingBlock.isFree(state below) must be false. Over an
 * ULTRAKILL floor that state is air, so it always dropped as an item (2026-10-15). The check also
 * fails when ULTRAKILL geometry fills the cell below the landing cell.
 */
@Mixin(net.minecraft.world.entity.item.FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FallingBlock;isFree(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean minekill$ukLanding(BlockState below) {
        var pos = ((net.minecraft.world.entity.Entity) (Object) this).blockPosition().below();
        if (UkCollision.hasShapes() && UkCollision.blocksCell(pos.getX(), pos.getY(), pos.getZ())) return false;
        return net.minecraft.world.level.block.FallingBlock.isFree(below);
    }
}
