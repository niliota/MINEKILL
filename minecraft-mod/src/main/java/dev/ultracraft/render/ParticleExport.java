package dev.ultracraft.render;

import dev.ultracraft.bridge.Bridge;
import dev.ultracraft.bridge.Protocol;
import dev.ultracraft.mixin.ParticleAccessor;
import dev.ultracraft.mixin.ParticleEngineAccessor;
import dev.ultracraft.mixin.ParticleGroupAccessor;
import dev.ultracraft.mixin.SingleQuadParticleAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.TerrainParticle;

/**
 * Minecraft's block particles for ULTRAKILL to draw (world layer).
 *
 * <p>Block-break and mining debris is {@code TerrainParticle}: a small camera-facing quad textured
 * with a quarter-sized patch of the block's own texture, from the block atlas ULTRAKILL already
 * holds. Minecraft simulates them (spawn, gravity, collision, lifetime); this only samples the
 * live ones each frame, interpolated with the frame's partial tick like Minecraft's renderer, and
 * publishes them to the PARTICLES table. Other particle types use the particle atlas, which is not
 * exported yet. Render thread only (the particle engine ticks there too).
 */
public final class ParticleExport {
    private final float[] rec = new float[Protocol.TBL_PARTICLES_CAPACITY * 8];
    /** Colour kept as int: as float bits, alpha 255 would make a NaN pattern, which is not bit-safe. */
    private final int[] rgba = new int[Protocol.TBL_PARTICLES_CAPACITY];

    public void frame(Minecraft mc, Bridge bridge) {
        int n = 0;
        if (mc.level != null && mc.particleEngine != null) {
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            for (ParticleGroup<?> group : ((ParticleEngineAccessor) mc.particleEngine).ultracraft$groups().values()) {
                for (Particle p : ((ParticleGroupAccessor) group).ultracraft$particles()) {
                    if (!(p instanceof TerrainParticle tp) || n >= Protocol.TBL_PARTICLES_CAPACITY) continue;
                    ParticleAccessor pa = (ParticleAccessor) p;
                    SingleQuadParticleAccessor q = (SingleQuadParticleAccessor) p;
                    int o = n * 8;
                    rec[o] = (float) (pa.ultracraft$xo() + (pa.ultracraft$x() - pa.ultracraft$xo()) * pt);
                    rec[o + 1] = (float) (pa.ultracraft$yo() + (pa.ultracraft$y() - pa.ultracraft$yo()) * pt);
                    rec[o + 2] = (float) (pa.ultracraft$zo() + (pa.ultracraft$z() - pa.ultracraft$zo()) * pt);
                    rec[o + 3] = tp.getQuadSize(pt);
                    rec[o + 4] = q.ultracraft$u0();
                    rec[o + 5] = q.ultracraft$v0();
                    rec[o + 6] = q.ultracraft$u1();
                    rec[o + 7] = q.ultracraft$v1();
                    rgba[n] = (Math.round(q.ultracraft$r() * 255) & 0xFF)
                            | (Math.round(q.ultracraft$g() * 255) & 0xFF) << 8
                            | (Math.round(q.ultracraft$b() * 255) & 0xFF) << 16
                            | (Math.round(q.ultracraft$alpha() * 255) & 0xFF) << 24;
                    n++;
                }
            }
        }
        bridge.writeParticleTable(System.currentTimeMillis(), rec, rgba, n);
    }
}
