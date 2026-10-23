package space.niliota.minekill.mixin;

import space.niliota.minekill.UkCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sneaking stops at ULTRAKILL ledges the way it stops at block edges, instead of freezing.
 *
 * <p>Verified in the 26.3 bytecode: all of the crouch edge logic ({@code maybeBackOffFromEdge}
 * and {@code isAboveGround}) asks {@code canFallAtLeast}, whose one test is
 * {@code level.noCollision(this, box)} on a box under the player. That goes through block
 * collision only, never through the movement path {@link EntityCollisionMixin} patches, so on
 * an ULTRAKILL floor every direction looked like a drop and sneaking refused to move at all
 * (the 2026-10-13 playtest). Here the ground also counts if an ULTRAKILL box is under the
 * player. SkyCraft (MIT) instead switches the edge stop off entirely; this keeps it working.
 *
 * <p>Player is shared by LocalPlayer and the integrated server's ServerPlayer, so both agree.
 */
@Mixin(Player.class)
public abstract class PlayerEdgeMixin {
    @Redirect(
            method = "canFallAtLeast",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean minekill$groundIncludesUltrakill(Level level, Entity entity, AABB box) {
        return level.noCollision(entity, box) && !UkCollision.intersectsAny(box);
    }
}
