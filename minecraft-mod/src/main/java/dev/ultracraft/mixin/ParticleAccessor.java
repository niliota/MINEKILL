package dev.ultracraft.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Current and previous-tick position, to interpolate like Minecraft's own renderer. */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x") double ultracraft$x();
    @Accessor("y") double ultracraft$y();
    @Accessor("z") double ultracraft$z();
    @Accessor("xo") double ultracraft$xo();
    @Accessor("yo") double ultracraft$yo();
    @Accessor("zo") double ultracraft$zo();
}
