package dev.minekill.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.minekill.bridge.Protocol;
import dev.minekill.gui.GuiTextures;
import dev.minekill.mixin.RenderSetupAccessor;
import dev.minekill.mixin.RenderTypeAccessor;
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
import net.minecraft.client.renderer.rendertype.TextureTransform;
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
            Map<String, Object> textures = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) type).minekill$state()).minekill$textures();
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
                LoggerFactory.getLogger("minekill").warn("[MINEKILL] model: cannot read the texture of {}: {}", type, e.toString());
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
        glintOf(type);
        if (glintOnly) builder.tex = -1;
        VertexConsumer buffer = uvMapping != null ? uvMapping.wrap(builder) : builder;
        model.setupAnim(state);
        model.renderToBuffer(poseStack, buffer, light, overlay, tint);
        builder.finish();
        glintKey = -1;
        if (report != null) report.append("\n  model ").append(model.getClass().getSimpleName()).append(' ').append(type)
                .append(" tex=").append(tex).append(" tint=").append(Integer.toHexString(tint)).append(" quads=").append(quads.size() - before);
    }

    /** Groups a model's vertices (already posed by renderToBuffer) into lit quads. */
    private final class QuadBuilder implements VertexConsumer {
        private final float[] px = new float[4], py = new float[4], pz = new float[4], pu = new float[4], pv = new float[4];
        private int count, color;
        int tex;
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
            if (tex == -1) {                // a glint-only pass: the model was drawn by its own pass
                if (glintKey >= 0) glintOver(q, null, false);
                return;
            }
            int n = quads.size();
            add(q);
            if (glintKey >= 0 && quads.size() > n) glintOver(q, null, true);
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

    // ---- enchantment glint ------------------------------------------------------
    //
    // Minecraft 26.3 mixes the glint into the item in one pass: colour += (GlintAlpha * glint)^2,
    // where glint samples enchanted_glint_*.png at TextureMat * UV0 (TextureTransform: a scroll in
    // time, a 10 degree turn and a scale), only where the item itself is opaque. ULTRAKILL has no
    // such shader, so the glint goes out as extra quads, flagged MODEL_TEX_GLINT and drawn
    // additively right after the quad they cover, with the glint UVs already transformed here
    // (the scroll animates because this runs every frame). Flat items are mostly transparent: a
    // quad over a sprite is cut into rectangles covering only its opaque pixels.

    /**
     * How far glint quads are lifted off their base quad, along its normal: only where the result
     * is depth-tested (world models), against flicker. The hand and GUI draw in painter's order and
     * keep 0: a lift there showed as the glint floating above the item (2026-10-15).
     */
    public float glintLift;
    private int glintKey = -1;
    private boolean glintOnly;
    private final Matrix4f glintMat = new Matrix4f();
    private int glintRgba;
    /** Opaque-pixel rectangles per sprite (x0, y0, x1, y1 in pixels), from its first frame. */
    private static final Map<Object, int[]> MASKS = new java.util.IdentityHashMap<>();

    private void glintStart(Identifier texture, TextureTransform transform) {
        Minecraft mc = Minecraft.getInstance();
        float strength = mc.options.glintStrength().get().floatValue();
        if (strength <= 0) return;
        glintKey = GuiTextures.keyForLocation(mc, texture);
        if (glintKey < 0) return;
        glintMat.set(transform.createMatrix());
        // (strength * glint)^2 added: the square cannot be done per texel here, so the glint is
        // scaled by strength^2 and a fixed factor for a texture that is mostly mid-bright.
        int g = Math.round(Math.min(1f, strength * strength * 0.8f) * 255);
        glintRgba = g | g << 8 | g << 16 | 0xFF000000;
    }

    /** Glint state for a model's render type: combined glint types, or glint-only passes. */
    private void glintOf(RenderType type) {
        glintKey = -1;
        glintOnly = false;
        try {
            RenderSetupAccessor setup = (RenderSetupAccessor) (Object) ((RenderTypeAccessor) type).minekill$state();
            Map<String, Object> textures = setup.minekill$textures();
            Object glint = textures.get("GlintSampler");
            Object base = textures.get("Sampler0");
            if (glint == null && base != null && location(base).getPath().startsWith("textures/misc/enchanted_glint")) {
                glint = base;
                glintOnly = true;
            }
            if (glint == null) return;
            TextureTransform t = setup.minekill$textureTransform();
            glintStart(location(glint), t != null ? t : TextureTransform.ENTITY_GLINT_TEXTURING);
        } catch (ReflectiveOperationException | RuntimeException e) {
            glintKey = -1;
        }
    }

    private static Identifier location(Object binding) throws ReflectiveOperationException {
        if (bindingLocation == null) {
            bindingLocation = binding.getClass().getDeclaredMethod("location");
            bindingLocation.setAccessible(true);
        }
        return (Identifier) bindingLocation.invoke(binding);
    }

    /**
     * Glint quads over {@code base}: the whole quad, or with a sprite only its opaque pixels.
     * {@code sameDepth}: sort with the base quad (base first: the sort is stable), else by own depth.
     */
    private void glintOver(Quad base, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, boolean sameDepth) {
        // Corner (0/1, 0/1) of the quad's UV rectangle -> vertex index, when it is one.
        float umin = Math.min(Math.min(base.u[0], base.u[1]), Math.min(base.u[2], base.u[3]));
        float umax = Math.max(Math.max(base.u[0], base.u[1]), Math.max(base.u[2], base.u[3]));
        float vmin = Math.min(Math.min(base.v[0], base.v[1]), Math.min(base.v[2], base.v[3]));
        float vmax = Math.max(Math.max(base.v[0], base.v[1]), Math.max(base.v[2], base.v[3]));
        int[] corner = {-1, -1, -1, -1};
        float du = umax - umin, dv = vmax - vmin;
        boolean rect = du > 1e-7f && dv > 1e-7f;
        for (int k = 0; k < 4 && rect; k++) {
            float su = (base.u[k] - umin) / du, sv = (base.v[k] - vmin) / dv;
            int a = su < 0.01f ? 0 : su > 0.99f ? 1 : -1, b = sv < 0.01f ? 0 : sv > 0.99f ? 1 : -1;
            if (a < 0 || b < 0 || corner[a + 2 * b] >= 0) rect = false;
            else corner[a + 2 * b] = k;
        }
        int[] masks = rect && sprite != null ? mask(sprite) : null;
        if (masks == null) {
            emitGlint(base, corner, rect, 0, 0, 1, 1, sameDepth);
            return;
        }
        int w = sprite.contents().width(), h = sprite.contents().height();
        float su0 = sprite.getU0(), su1 = sprite.getU1(), sv0 = sprite.getV0(), sv1 = sprite.getV1();
        for (int i = 0; i < masks.length; i += 4) {
            // Pixel rectangle -> UV -> the quad's 0..1 parameters, clipped to the quad.
            float a0 = ((su0 + (su1 - su0) * masks[i] / w) - umin) / du;
            float a1 = ((su0 + (su1 - su0) * masks[i + 2] / w) - umin) / du;
            float b0 = ((sv0 + (sv1 - sv0) * masks[i + 1] / h) - vmin) / dv;
            float b1 = ((sv0 + (sv1 - sv0) * masks[i + 3] / h) - vmin) / dv;
            a0 = Math.max(0, a0); b0 = Math.max(0, b0); a1 = Math.min(1, a1); b1 = Math.min(1, b1);
            if (a1 - a0 > 1e-4f && b1 - b0 > 1e-4f) emitGlint(base, corner, true, a0, b0, a1, b1, sameDepth);
        }
    }

    private void emitGlint(Quad base, int[] corner, boolean rect, float a0, float b0, float a1, float b1, boolean sameDepth) {
        Quad g = new Quad();
        // Lifted by glintLift (world models only) so a depth-tested draw does not fight the base quad.
        float ax = base.x[1] - base.x[0], ay = base.y[1] - base.y[0], az = base.z[1] - base.z[0];
        float bx = base.x[2] - base.x[0], by = base.y[2] - base.y[0], bz = base.z[2] - base.z[0];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float off = len > 0 ? glintLift / len : 0;
        // The sub-rectangle's corners in the base quad's vertex order, so the winding is kept.
        Vector3f t = new Vector3f();
        for (int k = 0; k < 4; k++) {
            float x, y, z, u, v;
            if (!rect) {
                x = base.x[k]; y = base.y[k]; z = base.z[k]; u = base.u[k]; v = base.v[k];
            } else {
                int ck = 0;
                for (int c = 0; c < 4; c++) if (corner[c] == k) ck = c;
                float a = (ck & 1) != 0 ? a1 : a0, b = (ck & 2) != 0 ? b1 : b0;
                Quad q = base;
                int c00 = corner[0], c10 = corner[1], c01 = corner[2], c11 = corner[3];
                x = lerp2(q.x[c00], q.x[c10], q.x[c01], q.x[c11], a, b);
                y = lerp2(q.y[c00], q.y[c10], q.y[c01], q.y[c11], a, b);
                z = lerp2(q.z[c00], q.z[c10], q.z[c01], q.z[c11], a, b);
                u = lerp2(q.u[c00], q.u[c10], q.u[c01], q.u[c11], a, b);
                v = lerp2(q.v[c00], q.v[c10], q.v[c01], q.v[c11], a, b);
            }
            g.x[k] = x + nx * off; g.y[k] = y + ny * off; g.z[k] = z + nz * off;
            glintMat.transformPosition(u, v, 0, t);
            g.u[k] = t.x; g.v[k] = t.y;
        }
        g.tex = glintKey | Protocol.MODEL_TEX_GLINT;
        g.rgba = glintRgba;
        if (sameDepth) {
            g.depth = base.depth;
            quads.add(g);
        } else {
            float d = 0;
            for (int k = 0; k < 4; k++) d += g.z[k];
            g.depth = d / 4f;
            add(g);
        }
    }

    private static float lerp2(float p00, float p10, float p01, float p11, float a, float b) {
        float top = p00 + (p10 - p00) * a, bottom = p01 + (p11 - p01) * a;
        return top + (bottom - top) * b;
    }

    /** The sprite's opaque pixels as rectangles (rows of runs, merged downwards), or null. */
    private static int[] mask(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        synchronized (MASKS) {
            int[] cached = MASKS.get(sprite);
            if (cached != null) return cached.length == 0 ? null : cached;
            if (MASKS.size() > 8192) MASKS.clear();      // resource reloads leave old sprites behind
            int[] built = buildMask(sprite);
            MASKS.put(sprite, built == null ? new int[0] : built);
            return built;
        }
    }

    private static int[] buildMask(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
        var image = ((dev.minekill.mixin.SpriteContentsAccessor) sprite.contents()).minekill$originalImage();
        if (image == null) return null;
        int w = Math.min(sprite.contents().width(), image.getWidth());
        int h = Math.min(sprite.contents().height(), image.getHeight());
        boolean anyClear = false;
        boolean[] solid = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                solid[y * w + x] = (image.getPixel(x, y) >>> 24) >= 26;   // the cutout's 0.1
                anyClear |= !solid[y * w + x];
            }
        if (!anyClear) return null;                       // fully opaque: one quad does
        List<int[]> rects = new ArrayList<>();
        Map<Long, int[]> open = new java.util.HashMap<>(), next = new java.util.HashMap<>();
        for (int y = 0; y < h; y++) {
            next.clear();
            for (int x = 0; x < w; ) {
                if (!solid[y * w + x]) { x++; continue; }
                int x0 = x;
                while (x < w && solid[y * w + x]) x++;
                long key = (long) x0 << 32 | x;
                int[] r = open.get(key);
                if (r != null) r[3] = y + 1;
                else rects.add(r = new int[] {x0, y, x, y + 1});
                next.put(key, r);
            }
            var swap = open; open = next; next = swap;
        }
        int[] out = new int[rects.size() * 4];
        for (int i = 0; i < rects.size(); i++) System.arraycopy(rects.get(i), 0, out, i * 4, 4);
        return out;
    }

    // ---- items ----------------------------------------------------------------

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext ctx, int light, int overlay, int outline,
                           int[] tints, ItemQuads itemQuads, ItemStackRenderState.FoilType foil) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f m = pose.pose();
        float bright = brightness(light);
        Vector3f p = new Vector3f(), n = new Vector3f();
        glintKey = -1;
        if (foil != null && foil != ItemStackRenderState.FoilType.NONE) {
            glintStart(net.minecraft.client.renderer.feature.ItemFeatureRenderer.ENCHANTED_GLINT_ITEM, TextureTransform.GLINT_TEXTURING);
        }
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
            int count = quads.size();
            add(q);
            if (glintKey >= 0 && quads.size() > count) glintOver(q, info.sprite(), true);
        }
        glintKey = -1;
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
    /** Whole block models (falling blocks, TNT, minecart contents, held blocks of endermen). */
    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType t, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
                                 int[] tints, int light, int overlay, int outline) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f m = pose.pose();
        float bright = brightness(light);
        Vector3f p = new Vector3f(), n = new Vector3f();
        net.minecraft.core.Direction[] dirs = net.minecraft.core.Direction.values();
        for (var part : parts) {
            for (int d = 0; d <= dirs.length; d++) {
                for (BakedQuad bq : part.getQuads(d < dirs.length ? dirs[d] : null)) {
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
                    q.tex = Protocol.GUI_TEX_BLOCKS;
                    add(q);
                }
            }
        }
    }
    @Override public void submitBreakingBlockModel(PoseStack p, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int i, boolean b) {}
    @Override public void submitShapeOutline(PoseStack p, net.minecraft.world.phys.shapes.VoxelShape s, RenderType t, int i, float f, boolean b) {}
    @Override public void submitCustomGeometry(PoseStack p, RenderType t, SubmitNodeCollector.CustomGeometryRenderer r) {}
    @Override public void submitQuadParticleGroup(net.minecraft.client.renderer.state.level.QuadParticleRenderState s) {}
    @Override public void submitGizmoPrimitives(net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group g, net.minecraft.client.renderer.state.level.CameraRenderState s, boolean b) {}
}
