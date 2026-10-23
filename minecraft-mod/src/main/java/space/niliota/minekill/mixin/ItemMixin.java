package space.niliota.minekill.mixin;

import space.niliota.minekill.ProjectileClip;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * What items like buckets, boats, lily pads and glass bottles are aimed at
 * ({@code Item.getPlayerPOVHitResult}) includes ULTRAKILL's surfaces, so a water or lava bucket
 * empties onto an ULTRAKILL floor or against an ULTRAKILL wall.
 */
@Mixin(Item.class)
public abstract class ItemMixin {
    @Redirect(method = "getPlayerPOVHitResult", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult minekill$clip(Level level, ClipContext ctx) {
        return ProjectileClip.clipNoBorder(level, ctx);
    }
}
