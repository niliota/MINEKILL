package space.niliota.minekill.mixin;

import space.niliota.minekill.EnemyProxies;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ULTRAKILL enemy proxies are never written to the save; they are rebuilt from ULTRAKILL. */
@Mixin(Entity.class)
public abstract class EntityProxyMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void minekill$proxyNotSaved(CallbackInfoReturnable<Boolean> cir) {
        if (EnemyProxies.isProxy((Entity) (Object) this)) cir.setReturnValue(false);
    }
}
