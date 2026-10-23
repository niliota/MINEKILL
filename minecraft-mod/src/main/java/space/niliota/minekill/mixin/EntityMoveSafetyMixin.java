package space.niliota.minekill.mixin;

import space.niliota.minekill.EntitySafety;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EntitySafety's guard (before) and rescue (after) around every entity's own movement. */
@Mixin(Entity.class)
public abstract class EntityMoveSafetyMixin {
    @Unique private double minekill$fromY = Double.NaN;

    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 minekill$guard(Vec3 movement) {
        Entity self = (Entity) (Object) this;
        minekill$fromY = self.getY();
        return EntitySafety.guard(self, movement);
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void minekill$rescue(MoverType type, Vec3 movement, CallbackInfo ci) {
        double from = minekill$fromY;
        minekill$fromY = Double.NaN;
        EntitySafety.rescue((Entity) (Object) this, from);
    }
}
