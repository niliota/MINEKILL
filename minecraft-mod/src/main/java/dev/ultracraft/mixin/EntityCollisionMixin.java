package dev.ultracraft.mixin;

import dev.ultracraft.UkCollision;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes ULTRAKILL's geometry solid to Minecraft's physics (Milestone D, spike).
 *
 * <p>Verified from the 26.3 bytecode: every collision query in an entity's movement goes
 * through {@code Entity.collectCollidersIgnoringWorldBorder(Entity, Level, List, AABB)}.
 * {@code Entity.collide} calls it via {@code collideBoundingBox} for the main move, and
 * calls it directly for the step-up attempt. It returns entity shapes + world border +
 * {@code level.getBlockCollisions(entity, box)}, and the caller resolves the movement
 * against that list with {@code collideWithShapes}. Appending ULTRAKILL's boxes here means
 * Minecraft's own sweep, step-up and onGround logic handle them like any block.
 *
 * <p>Players only, for the spike: both {@code LocalPlayer} (client) and
 * {@code ServerPlayer} (integrated server) are {@link Player}s, so the server agrees with
 * the client about where the walls are. Mobs come later, deliberately.
 *
 * <p>The {@code CollisionContext} overload of the same method is not patched yet; player
 * movement does not use it.
 */
@Mixin(Entity.class)
public abstract class EntityCollisionMixin {

    @Inject(
            method = "collectCollidersIgnoringWorldBorder(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/level/Level;Ljava/util/List;"
                    + "Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true)
    private static void ultracraft$addUltrakillShapes(Entity entity, Level level,
                                                      List<VoxelShape> entityColliders, AABB box,
                                                      CallbackInfoReturnable<List<VoxelShape>> cir) {
        if (!(entity instanceof Player) || !UkCollision.hasShapes()) return;
        List<VoxelShape> original = cir.getReturnValue();
        List<VoxelShape> merged = new ArrayList<>(original.size() + 8);
        merged.addAll(original);
        if (UkCollision.appendShapes(box, merged) > 0) {
            cir.setReturnValue(merged);
            UkCollision.countInjection();
        }
    }
}
