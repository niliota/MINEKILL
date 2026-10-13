package dev.minekill.render;

import dev.minekill.bridge.Bridge;
import dev.minekill.bridge.Protocol;
import dev.minekill.mixin.ParticleAccessor;
import dev.minekill.mixin.ParticleEngineAccessor;
import dev.minekill.mixin.ParticleGroupAccessor;
import dev.minekill.mixin.SingleQuadParticleAccessor;
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
            for (ParticleGroup<?> group : ((ParticleEngineAccessor) mc.particleEngine).minekill$groups().values()) {
                for (Particle p : ((ParticleGroupAccessor) group).minekill$particles()) {
                    if (!(p instanceof TerrainParticle tp) || n >= Protocol.TBL_PARTICLES_CAPACITY) continue;
                    ParticleAccessor pa = (ParticleAccessor) p;
                    SingleQuadParticleAccessor q = (SingleQuadParticleAccessor) p;
                    int o = n * 8;
                    rec[o] = (float) (pa.minekill$xo() + (pa.minekill$x() - pa.minekill$xo()) * pt);
                    rec[o + 1] = (float) (pa.minekill$yo() + (pa.minekill$y() - pa.minekill$yo()) * pt);
                    rec[o + 2] = (float) (pa.minekill$zo() + (pa.minekill$z() - pa.minekill$zo()) * pt);
                    rec[o + 3] = tp.getQuadSize(pt);
                    rec[o + 4] = q.minekill$u0();
                    rec[o + 5] = q.minekill$v0();
                    rec[o + 6] = q.minekill$u1();
                    rec[o + 7] = q.minekill$v1();
                    rgba[n] = (Math.round(q.minekill$r() * 255) & 0xFF)
                            | (Math.round(q.minekill$g() * 255) & 0xFF) << 8
                            | (Math.round(q.minekill$b() * 255) & 0xFF) << 16
                            | (Math.round(q.minekill$alpha() * 255) & 0xFF) << 24;
                    n++;
                }
            }
        }
        bridge.writeParticleTable(System.currentTimeMillis(), rec, rgba, n);
    }
}
