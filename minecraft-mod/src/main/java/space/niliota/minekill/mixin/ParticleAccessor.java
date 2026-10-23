package space.niliota.minekill.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Current and previous-tick position, to interpolate like Minecraft's own renderer. */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x") double minekill$x();
    @Accessor("y") double minekill$y();
    @Accessor("z") double minekill$z();
    @Accessor("xo") double minekill$xo();
    @Accessor("yo") double minekill$yo();
    @Accessor("zo") double minekill$zo();
}
