package dev.minekill.mixin;

import java.util.Map;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.3 keeps the live particles in a private map of render type -> group. */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor("particles")
    Map<ParticleRenderType, ParticleGroup<?>> minekill$groups();
}
