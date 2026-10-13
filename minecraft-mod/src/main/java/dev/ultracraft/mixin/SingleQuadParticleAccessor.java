package dev.ultracraft.mixin;

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
    @Accessor("rCol") float ultracraft$r();
    @Accessor("gCol") float ultracraft$g();
    @Accessor("bCol") float ultracraft$b();
    @Accessor("alpha") float ultracraft$alpha();
    @Invoker("getU0") float ultracraft$u0();
    @Invoker("getU1") float ultracraft$u1();
    @Invoker("getV0") float ultracraft$v0();
    @Invoker("getV1") float ultracraft$v1();
}
