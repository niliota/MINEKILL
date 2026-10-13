package dev.minekill.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Colour and UV rectangle of a quad particle. The UV getters are protected and overridden by
 * TerrainParticle (which picks a random quarter-sized patch of the block's texture); invokers
 * dispatch virtually, so the override is what runs.
 */
@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleAccessor {
    @Accessor("rCol") float minekill$r();
    @Accessor("gCol") float minekill$g();
    @Accessor("bCol") float minekill$b();
    @Accessor("alpha") float minekill$alpha();
    @Invoker("getU0") float minekill$u0();
    @Invoker("getU1") float minekill$u1();
    @Invoker("getV0") float minekill$v0();
    @Invoker("getV1") float minekill$v1();
}
