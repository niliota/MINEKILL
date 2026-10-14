package dev.minekill.render;

import dev.minekill.bridge.Bridge;
import dev.minekill.bridge.Protocol;
import dev.minekill.gui.GuiTextures;
import dev.minekill.mixin.ParticleAccessor;
import dev.minekill.mixin.ParticleEngineAccessor;
import dev.minekill.mixin.ParticleGroupAccessor;
import dev.minekill.mixin.SingleQuadParticleAccessor;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Minecraft's quad particles for ULTRAKILL to draw (world layer).
 *
 * <p>Every {@code SingleQuadParticle}: block-break and mining debris ({@code TerrainParticle}, a
 * quarter-sized patch of the block's texture in the block atlas ULTRAKILL already holds) and the
 * rest (critical hits, enchanted hits, smoke, hearts, ...), whose sprites live in the particle
 * atlas; that atlas is mirrored to ULTRAKILL on first use through GuiTextures, like the chest and
 * sign atlases, and each record names its texture. Minecraft simulates them (spawn, gravity,
 * collision, lifetime); this only samples the live ones each frame, interpolated with the frame's
 * partial tick like Minecraft's renderer. Render thread only (the particle engine ticks there too).
 */
public final class ParticleExport {
    private final float[] rec = new float[Protocol.TBL_PARTICLES_CAPACITY * 8];
    /** Colour kept as int: as float bits, alpha 255 would make a NaN pattern, which is not bit-safe. */
    private final int[] rgba = new int[Protocol.TBL_PARTICLES_CAPACITY];
    private final int[] tex = new int[Protocol.TBL_PARTICLES_CAPACITY];
    /** Atlas -> texture key, for this frame only (a resource reload re-mirrors under a new key). */
    private final Map<Identifier, Integer> keys = new HashMap<>();

    public void frame(Minecraft mc, Bridge bridge) {
        int n = 0;
        keys.clear();
        if (mc.level != null && mc.particleEngine != null) {
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            for (ParticleGroup<?> group : ((ParticleEngineAccessor) mc.particleEngine).minekill$groups().values()) {
                for (Particle p : ((ParticleGroupAccessor) group).minekill$particles()) {
                    if (!(p instanceof SingleQuadParticle sq) || n >= Protocol.TBL_PARTICLES_CAPACITY) continue;
                    SingleQuadParticleAccessor q = (SingleQuadParticleAccessor) p;
                    int key;
                    if (p instanceof TerrainParticle) {
                        key = Protocol.GUI_TEX_BLOCKS;
                    } else {
                        TextureAtlasSprite sprite = q.minekill$sprite();
                        if (sprite == null) continue;
                        key = keys.computeIfAbsent(sprite.atlasLocation(), id -> GuiTextures.keyForLocation(mc, id));
                    }
                    if (key < 0) continue;
                    ParticleAccessor pa = (ParticleAccessor) p;
                    int o = n * 8;
                    rec[o] = (float) (pa.minekill$xo() + (pa.minekill$x() - pa.minekill$xo()) * pt);
                    rec[o + 1] = (float) (pa.minekill$yo() + (pa.minekill$y() - pa.minekill$yo()) * pt);
                    rec[o + 2] = (float) (pa.minekill$zo() + (pa.minekill$z() - pa.minekill$zo()) * pt);
                    rec[o + 3] = sq.getQuadSize(pt);
                    rec[o + 4] = q.minekill$u0();
                    rec[o + 5] = q.minekill$v0();
                    rec[o + 6] = q.minekill$u1();
                    rec[o + 7] = q.minekill$v1();
                    rgba[n] = (Math.round(q.minekill$r() * 255) & 0xFF)
                            | (Math.round(q.minekill$g() * 255) & 0xFF) << 8
                            | (Math.round(q.minekill$b() * 255) & 0xFF) << 16
                            | (Math.round(q.minekill$alpha() * 255) & 0xFF) << 24;
                    tex[n] = key;
                    n++;
                }
            }
        }
        bridge.writeParticleTable(System.currentTimeMillis(), rec, rgba, tex, n);
    }
}
