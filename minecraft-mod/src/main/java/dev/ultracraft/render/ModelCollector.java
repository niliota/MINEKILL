package dev.ultracraft.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ultracraft.bridge.Protocol;
import dev.ultracraft.gui.GuiTextures;
import dev.ultracraft.mixin.RenderSetupAccessor;
import dev.ultracraft.mixin.RenderTypeAccessor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.LoggerFactory;

/**
 * Receives what a Minecraft renderer submits (entity models, model parts, item models) and keeps
 * it as posed, lit, textured quads instead of drawing it: the geometry ULTRAKILL redraws natively.
 * Used for the first-person hands (HandCapture) and the entity in the inventory (GuiCapture).
 *
 * <p>Models are built on the spot, exactly as ModelFeatureRenderer would later (setupAnim, then
 * renderToBuffer, through the submit's UvMapping), into a VertexConsumer that groups vertices into
 * quads. Positions are taken through {@code post} (e.g. into view space); normals are lit in the
 * space they arrive in, with Minecraft's two directional lights and, optionally, the light level.
 * Everything that is not a model or an item (shadows, name tags, maps, block entities) is ignored.
 */
public final class ModelCollector implements SubmitNodeCollector {

    public static final class Quad {
        public final float[] x = new float[4], y = new float[4], z = new float[4], u = new float[4], v = new float[4];
        public int rgba = 0xFFFFFFFF;
        public int tex;
        float depth;
    }

    private final List<Quad> quads = new ArrayList<>();
    private final Matrix4f post = new Matrix4f();
    private final Vector3f light0 = new Vector3f(), light1 = new Vector3f();
    private boolean useLightCoords, perspective, unlit;
    private final QuadBuilder builder = new QuadBuilder();
    /** When non-null, one line per submission (render type, texture key, quads) for diagnostics. */
    public StringBuilder report;

    /** Start collecting: positions through {@code post}, lights in the normals' space. */
    public void begin(Matrix4fc post, Vector3fc light0, Vector3fc light1, boolean useLightCoords) {
        begin(post, light0, light1, useLightCoords, false);
    }

    /**
     * {@code perspective}: {@code post} gives view space with the eye at the origin (the hands).
     * Then quads facing away from the eye are dropped, as Minecraft's culling render types do, and
     * the rest are ordered by distance from the eye. Painter's order alone cannot do this up close:
     * by average depth, a held block's side can sort in front of its face (seen inside out), and a
     * flat item's back face can cover its front (seen twice).
     */
    public void begin(Matrix4fc post, Vector3fc light0, Vector3fc light1, boolean useLightCoords, boolean perspective) {
        this.perspective = perspective;
        quads.clear();
        this.post.set(post);
        // Zero lights: unlit (ULTRAKILL lights the geometry itself).
        unlit = light0.lengthSquared() == 0 && light1.lengthSquared() == 0;
        if (!unlit) {
            this.light0.set(light0).normalize();
            this.light1.set(light1).normalize();
        }
        this.useLightCoords = useLightCoords;
    }

    /**
     * Keep a finished quad. With perspective, a back-facing quad is dropped (counter-clockwise is
     * front, as in GL) and depth becomes minus the squared distance, so far still sorts first.
     */
    private void add(Quad q) {
        if (perspective) {
            float ax = q.x[1] - q.x[0], ay = q.y[1] - q.y[0], az = q.z[1] - q.z[0];
            float bx = q.x[2] - q.x[0], by = q.y[2] - q.y[0], bz = q.z[2] - q.z[0];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            if (nx * q.x[0] + ny * q.y[0] + nz * q.z[0] >= 0) return;   // faces away from the eye
            float cx = 0, cy = 0, cz = 0;
            for (int k = 0; k < 4; k++) { cx += q.x[k]; cy += q.y[k]; cz += q.z[k]; }
            q.depth = -(cx * cx + cy * cy + cz * cz) / 16f;
        }
        quads.add(q);
    }

    /** The quads in submission order. */
    public List<Quad> quads() {
        return quads;
    }

    /** The quads, far first (smaller z is farther: true for GUI space and for view space). */
    public List<Quad> sortedQuads() {
        quads.sort((a, b) -> Float.compare(a.depth, b.depth));
        return quads;
    }

    // ---- lighting -------------------------------------------------------------

    private float shade(float nx, float ny, float nz) {
        if (unlit) return 1f;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return 1f;
        nx /= len; ny /= len; nz /= len;
        float d0 = Math.max(0, nx * light0.x + ny * light0.y + nz * light0.z);
        float d1 = Math.max(0, nx * light1.x + ny * light1.y + nz * light1.z);
        return Math.min(1f, 0.4f + 0.6f * (d0 + d1));
    }

    /** Minecraft's lightmap curve, roughly: brightness for packed block/sky light. */
    private float brightness(int lightCoords) {
        if (!useLightCoords) return 1f;
        int block = (lightCoords >> 4) & 0xF, sky = (lightCoords >> 20) & 0xF;
        float l = Math.max(block, sky) / 15f;
        return 0.05f + 0.95f * (l / (4f - 3f * l));
    }

    private static int scale(int rgba, float s) {
        int r = Math.round((rgba & 0xFF) * s), g = Math.round((rgba >> 8 & 0xFF) * s), b = Math.round((rgba >> 16 & 0xFF) * s);
        return (rgba & 0xFF000000) | Math.min(r, 255) | Math.min(g, 255) << 8 | Math.min(b, 255) << 16;
    }

    private static int argbToRgba(int argb) {
        return (argb >>> 24) << 24 | (argb & 0xFF) << 16 | (argb & 0xFF00) | (argb >> 16 & 0xFF);
    }

    // ---- textures -------------------------------------------------------------

    private static Method bindingLocation;
    private static boolean textureFailureLogged;

    /** The GUI_TEX key of the texture a render type binds as Sampler0, or -1. */
    private static int texKey(RenderType type) {
        try {
            Map<String, Object> textures = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) type).ultracraft$state()).ultracraft$textures();
            Object binding = textures.get("Sampler0");
            if (binding == null) return -1;
            if (bindingLocation == null) {
                bindingLocation = binding.getClass().getDeclaredMethod("location");
                bindingLocation.setAccessible(true);
            }
            return GuiTextures.keyForLocation(Minecraft.getInstance(), (Identifier) bindingLocation.invoke(binding));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!textureFailureLogged) {
                textureFailureLogged = true;
                LoggerFactory.getLogger("ultracraft").warn("[UltraCraft] model: cannot read the texture of {}: {}", type, e.toString());
            }
            return -1;
        }
    }

    // ---- models ---------------------------------------------------------------

    @Override public OrderedSubmitNodeCollector order(int order) { return this; }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType type, int light, int overlay,
                                int tint, UvMapping uvMapping, int outline) {
        int tex = texKey(type);
        if (tex < 0) {
            if (report != null) report.append("\n  model ").append(model.getClass().getSimpleName()).append(' ').append(type).append(" -> no texture, skipped");
            return;
        }
        int before = quads.size();
        builder.start(tex, brightness(light));
        VertexConsumer buffer = uvMapping != null ? uvMapping.wrap(builder) : builder;
        model.setupAnim(state);
        model.renderToBuffer(poseStack, buffer, light, overlay, tint);
        builder.finish();
        if (report != null) report.append("\n  model ").append(model.getClass().getSimpleName()).append(' ').append(type)
                .append(" tex=").append(tex).append(" tint=").append(Integer.toHexString(tint)).append(" quads=").append(quads.size() - before);
    }

    /** Groups a model's vertices (already posed by renderToBuffer) into lit quads. */
    private final class QuadBuilder implements VertexConsumer {
        private final float[] px = new float[4], py = new float[4], pz = new float[4], pu = new float[4], pv = new float[4];
        private int count, color, tex;
        private float nx, ny, nz, bright;
        private final Vector3f t = new Vector3f();

        void start(int tex, float bright) { this.tex = tex; this.bright = bright; count = 0; }

        void finish() {
            if (count == 4) emit();
            count = 0;
        }

        private void emit() {
            Quad q = new Quad();
            float depth = 0;
            for (int k = 0; k < 4; k++) {
                post.transformPosition(px[k], py[k], pz[k], t);
                q.x[k] = t.x; q.y[k] = t.y; q.z[k] = t.z;
                q.u[k] = pu[k]; q.v[k] = pv[k];
                depth += t.z;
            }
            q.depth = depth / 4f;
            q.tex = tex;
            q.rgba = scale(argbToRgba(color), shade(nx, ny, nz) * bright);
            add(q);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count == 4) { emit(); count = 0; }
            px[count] = x; py[count] = y; pz[count] = z;
            if (count == 0) { color = 0xFFFFFFFF; nx = ny = nz = 0; }
            count++;
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            if (count == 1) color = (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
            return this;
        }
        @Override public VertexConsumer setColor(int argb) { if (count == 1) color = argb; return this; }
        @Override public VertexConsumer setUv(float u, float v) {
            if (count > 0) { pu[count - 1] = u; pv[count - 1] = v; }
            return this;
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            nx += x; ny += y; nz += z;
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setLineWidth(float w) { return this; }
    }

    // ---- items ----------------------------------------------------------------

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext ctx, int light, int overlay, int outline,
                           int[] tints, ItemQuads itemQuads, ItemStackRenderState.FoilType foil) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f m = pose.pose();
        float bright = brightness(light);
        Vector3f p = new Vector3f(), n = new Vector3f();
        for (BakedQuad bq : itemQuads.all()) {
            Quad q = new Quad();
            float depth = 0;
            for (int k = 0; k < 4; k++) {
                var pos = bq.position(k);
                m.transformPosition(pos.x(), pos.y(), pos.z(), p);
                post.transformPosition(p);
                q.x[k] = p.x; q.y[k] = p.y; q.z[k] = p.z;
                depth += p.z;
                long uv = bq.packedUV(k);
                q.u[k] = UVPair.unpackU(uv); q.v[k] = UVPair.unpackV(uv);
            }
            q.depth = depth / 4f;
            int col = 0xFFFFFFFF;
            var info = bq.materialInfo();
            if (info.isTinted() && tints != null && info.tintIndex() >= 0 && info.tintIndex() < tints.length) {
                col = 0xFF000000 | argbToRgba(tints[info.tintIndex()]);
            }
            pose.transformNormal(bq.direction().getUnitVec3f(), n);
            q.rgba = scale(col, shade(n.x, n.y, n.z) * bright);
            q.tex = info.sprite().atlasLocation().equals(TextureAtlas.LOCATION_ITEMS)
                    ? Protocol.GUI_TEX_ITEMS : Protocol.GUI_TEX_BLOCKS;
            add(q);
        }
    }

    // ---- not models: ignored ----------------------------------------------------

    @Override public void submitShadow(PoseStack p, float f, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> l) {}
    @Override public void submitNameTag(PoseStack p, net.minecraft.world.phys.Vec3 v, int i, net.minecraft.network.chat.Component c, boolean b, int j, net.minecraft.client.renderer.state.level.CameraRenderState s) {}
    @Override public void submitText(PoseStack p, float x, float y, net.minecraft.util.FormattedCharSequence t, boolean d, net.minecraft.client.gui.Font.DisplayMode m, int a, int b, int c, int e) {}
    @Override public void submitTextBackground(PoseStack p, float a, float b, float c, float d, int e, net.minecraft.client.gui.Font.DisplayMode m, int f) {}
    @Override public void submitFlame(PoseStack p, net.minecraft.client.renderer.entity.state.EntityRenderState s, org.joml.Quaternionf q) {}
    @Override public void submitLeash(PoseStack p, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState s) {}
    @Override public <S> void submitCrumblingOverlay(Model<? super S> model, S s, PoseStack p, RenderType t, int a, int b, int c, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay o) {}
    @Override public void submitMovingBlock(PoseStack p, net.minecraft.client.renderer.block.MovingBlockRenderState s, int i) {}
    @Override public void submitBlockModel(PoseStack p, RenderType t, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int[] tints, int a, int b, int c) {}
    @Override public void submitBreakingBlockModel(PoseStack p, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int i, boolean b) {}
    @Override public void submitShapeOutline(PoseStack p, net.minecraft.world.phys.shapes.VoxelShape s, RenderType t, int i, float f, boolean b) {}
    @Override public void submitCustomGeometry(PoseStack p, RenderType t, SubmitNodeCollector.CustomGeometryRenderer r) {}
    @Override public void submitQuadParticleGroup(net.minecraft.client.renderer.state.level.QuadParticleRenderState s) {}
    @Override public void submitGizmoPrimitives(net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group g, net.minecraft.client.renderer.state.level.CameraRenderState s, boolean b) {}
}
