package space.niliota.minekill.mixin;

import space.niliota.minekill.EnemyProxies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ULTRAKILL enemy proxies (EnemyProxies): their damage is forwarded to ULTRAKILL instead of being
 * taken, and they push nothing. Also the player's grace window after a (re)start (PlayerSafety).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityProxyMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void minekill$proxyHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (EnemyProxies.isProxy(self)) cir.setReturnValue(EnemyProxies.onHurt(self, source, amount, level.getGameTime()));
        else if (self instanceof net.minecraft.world.entity.player.Player && space.niliota.minekill.PlayerSafety.inGrace()) cir.setReturnValue(false);
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void minekill$proxyPush(CallbackInfoReturnable<Boolean> cir) {
        if (EnemyProxies.isProxy((LivingEntity) (Object) this)) cir.setReturnValue(false);
    }
}
