package dev.ultracraft.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ultracraft.bridge.Bridge;
import dev.ultracraft.bridge.Protocol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Captures everything Minecraft's GUI draws each frame, for ULTRAKILL to replay (HUD milestone).
 *
 * <p>Minecraft 26.3 collects its GUI into a GuiRenderState, then GuiRenderer turns text into glyph
 * elements and calls {@code addElementToMesh(element)} for every element in final draw order; each
 * element writes its own quads with {@code buildVertices(VertexConsumer)}. GuiRendererMixin passes
 * each element here, and this class IS that VertexConsumer: hotbar, hearts with their flashing and
 * shaking, text, chat, boss bars, effects, screens — whatever Minecraft draws, with every animation,
 * because it is Minecraft's own geometry.
 *
 * <p>Items are the exception: GuiRenderer draws them into an item atlas on the GPU and blits from
 * it, and a GPU-rendered image cannot cross. So each queued item is recorded
 * ({@code submitBlitFromItemAtlas}), and when its blit reaches us we substitute the item's real
 * model: {@code ItemStackRenderState.submit} into our own SubmitNodeCollector, the same call
 * GuiItemAtlas.drawToSlot makes, projected the same way (slot centre, 16 px per unit, y up),
 * depth-sorted, and lit like Minecraft's GUI (flat items full bright; block items with two
 * directional lights).
 */
public final class GuiCapture implements VertexConsumer {
    public static final GuiCapture INSTANCE = new GuiCapture();

    private static final int CAP = Protocol.TBL_GUI_CAPACITY;
    private final float[] xyuv = new float[CAP * 4];
    private final int[] rgba = new int[CAP];
    private final int[] tex = new int[CAP];
    private int n;            // vertices in this frame
    private int curTex;
    private boolean skip;     // current element's texture is unavailable to ULTRAKILL
    private boolean capturing;
    private final Map<Long, GuiItemRenderState> queuedItems = new HashMap<>();
    private final Map<Long, List<dev.ultracraft.render.ModelCollector.Quad>> queuedEntities = new HashMap<>();

    private GuiCapture() {}

    // ---- frame ----------------------------------------------------------------

    /**
     * Only while a live ULTRAKILL is on the bridge (it skips startup) and Minecraft is in a world:
     * its title screen and world list are not ULTRAKILL's to show. Otherwise an empty table goes out.
     */
    public void begin() {
        n = 0;
        queuedItems.clear();
        queuedEntities.clear();
        capturing = dev.ultracraft.UltraCraftClient.ukLive() && Minecraft.getInstance().level != null;
    }

    public void end(Bridge bridge) {
        capturing = false;
        if (bridge == null || !bridge.connected() || !GuiLayout.active()) return;
        var screen = Minecraft.getInstance().gui.screen();
        bridge.writeGuiTable(System.currentTimeMillis(), n, GuiLayout.scale(), GuiLayout.scaledWidth(),
                GuiLayout.scaledHeight(), screen != null, screen != null && screen.isPauseScreen(), xyuv, rgba, tex);
    }

    private static long key(int x, int y, Matrix3x2fc pose) {
        return ((long) Float.floatToIntBits(pose.m20() + x) << 32) ^ (Float.floatToIntBits(pose.m21() + y) & 0xFFFFFFFFL);
    }

    /** GuiRenderer.submitBlitFromItemAtlas: remember which item a coming blit stands for. */
    public void queueItem(GuiItemRenderState item) {
        if (capturing) queuedItems.put(key(item.x(), item.y(), item.pose()), item);
    }

    // Entities in the GUI (the inventory's player): Minecraft renders them to an offscreen
    // texture (PictureInPictureRenderer) and blits that. The same pose, built in GUI pixels
    // instead of texture pixels (prepare: translate to the box centre, scale(s, s, -s)), feeds
    // the entity's submission into a ModelCollector; the quads replace the blit when it comes.
    private static final Vector3f UI_L0 = new Vector3f(0.2f, -1.0f, 1.0f);    // GUI space: y down, viewer at +z
    private static final Vector3f UI_L1 = new Vector3f(-0.2f, -1.0f, 0.0f);
    private final dev.ultracraft.render.ModelCollector entityCollector = new dev.ultracraft.render.ModelCollector();

    /** PictureInPictureRenderer.prepare HEAD, for a GuiEntityRenderState. */
    public void queueEntity(net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState state) {
        if (!capturing) return;
        try {
            PoseStack pose = new PoseStack();
            pose.translate((state.x0() + state.x1()) / 2f, (state.y0() + state.y1()) / 2f, 0f);
            pose.scale(state.scale(), state.scale(), -state.scale());
            var t = state.translation();
            pose.translate(t.x(), t.y(), t.z());
            pose.rotate(state.rotation());
            var camera = new net.minecraft.client.renderer.state.level.CameraRenderState();
            if (state.overrideCameraAngle() != null) {
                camera.orientation = state.overrideCameraAngle().conjugate(new org.joml.Quaternionf()).rotateY((float) Math.PI);
            }
            entityCollector.begin(new Matrix4f(), UI_L0, UI_L1, false);
            Minecraft.getInstance().getEntityRenderDispatcher().submit(state.renderState(), camera, 0.0, 0.0, 0.0, pose, entityCollector);
            queuedEntities.put(key(state.x0(), state.y0(), state.pose()), new ArrayList<>(entityCollector.sortedQuads()));
        } catch (RuntimeException ex) {
            if (!failureLogged) {
                failureLogged = true;
                org.slf4j.LoggerFactory.getLogger("ultracraft").warn("[UltraCraft] gui: entity capture failed: {}", ex.toString());
            }
        }
    }

    private boolean failureLogged;

    /**
     * GuiRenderer.addElementToMesh: one element, in draw order. Must never break Minecraft's own
     * GUI: any failure skips this element and is logged once.
     */
    public void element(Minecraft mc, GuiElementRenderState e) {
        if (!capturing) return;
        try {
            capture(mc, e);
        } catch (RuntimeException ex) {
            skip = false;
            if (!failureLogged) {
                failureLogged = true;
                org.slf4j.LoggerFactory.getLogger("ultracraft").warn("[UltraCraft] gui: capture failed on {}: {}",
                        e.getClass().getSimpleName(), ex.toString());
            }
        }
    }

    private void capture(Minecraft mc, GuiElementRenderState e) {
        if (e instanceof BlitRenderState b) {
            GuiItemRenderState item = queuedItems.remove(key(b.x0(), b.y0(), b.pose()));
            if (item != null) { item(mc, item); return; }
            var entity = queuedEntities.remove(key(b.x0(), b.y0(), b.pose()));
            if (entity != null) { entityQuads(entity); return; }
        }
        if (!plainBlend(e.pipeline())) return;
        int k = GuiTextures.key(mc, e.textureSetup().texure0());
        skip = k < 0;
        curTex = Math.max(k, 0);
        e.buildVertices(this);
        skip = false;
    }

    private final java.util.Set<Object> skippedPipelines = new java.util.HashSet<>();

    /**
     * ULTRAKILL replays every quad with plain alpha blending. Elements Minecraft blends otherwise
     * (the vignette multiplies the screen darker; replayed as alpha it is an opaque full-screen
     * sheet) are left out rather than drawn wrong. Invert (the crosshair) reads fine as alpha.
     */
    private boolean plainBlend(com.mojang.renderpearl.api.pipeline.RenderPipeline p) {
        var targets = p.getColorTargetStates();
        if (targets.isEmpty()) return true;
        var blend = targets.get(0).blendFunction();
        if (blend.isEmpty()) return true;
        var f = blend.get();
        if (f.equals(com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT)
                || f.equals(com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
                || f.equals(com.mojang.renderpearl.api.pipeline.BlendFunction.INVERT)) return true;
        if (skippedPipelines.add(p.getLocation())) {
            org.slf4j.LoggerFactory.getLogger("ultracraft").info("[UltraCraft] gui: pipeline {} blends {}; its quads are skipped",
                    p.getLocation(), f);
        }
        return false;
    }

    /** The entity's quads, already in GUI pixels and sorted far first. */
    private void entityQuads(List<dev.ultracraft.render.ModelCollector.Quad> quads) {
        for (var q : quads) {
            if (n + 4 > CAP) break;
            for (int k = 0; k < 4; k++) {
                xyuv[n * 4] = q.x[k];
                xyuv[n * 4 + 1] = q.y[k];
                xyuv[n * 4 + 2] = q.u[k];
                xyuv[n * 4 + 3] = q.v[k];
                rgba[n] = q.rgba;
                tex[n] = q.tex;
                n++;
            }
        }
    }

    // ---- VertexConsumer: Minecraft's GUI quads ---------------------------------

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (skip || n >= CAP) { n = Math.min(n, CAP); return this; }
        xyuv[n * 4] = x;
        xyuv[n * 4 + 1] = y;
        xyuv[n * 4 + 2] = 0;
        xyuv[n * 4 + 3] = 0;
        rgba[n] = 0xFFFFFFFF;
        tex[n] = curTex;
        n++;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        if (!skip && n > 0) rgba[n - 1] = (r & 0xFF) | (g & 0xFF) << 8 | (b & 0xFF) << 16 | (a & 0xFF) << 24;
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        return setColor(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24);
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        if (!skip && n > 0) { xyuv[(n - 1) * 4 + 2] = u; xyuv[(n - 1) * 4 + 3] = v; }
        return this;
    }

    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setUv3(float u, float v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float w) { return this; }

    // ---- items: the real model, projected like GuiItemAtlas.drawToSlot ----------

    private record ItemQuad(float[] x, float[] y, float[] u, float[] v, int color, int tex, float depth) {}

    private final List<ItemQuad> itemQuads = new ArrayList<>();
    private final ItemCollector collector = new ItemCollector();
    private static final Vector3f L0 = new Vector3f(0.2f, 1.0f, -0.7f).normalize();
    private static final Vector3f L1 = new Vector3f(-0.2f, 1.0f, 0.7f).normalize();

    private void item(Minecraft mc, GuiItemRenderState state) {
        ItemStackRenderState rs = state.itemStackRenderState();
        itemQuads.clear();
        collector.blockLight = rs.usesBlockLight();
        PoseStack pose = new PoseStack();
        try {
            rs.submit(pose, collector, 0xF000F0, 0, 0);
        } catch (RuntimeException ex) {
            return;
        }
        itemQuads.sort((a, b) -> Float.compare(a.depth(), b.depth()));   // far first
        float cx = state.x() + 8, cy = state.y() + 8;
        Vector2f p = new Vector2f();
        for (ItemQuad q : itemQuads) {
            if (n + 4 > CAP) break;
            for (int k = 0; k < 4; k++) {
                // Model y is up; GUI y is down. Then the item's own GUI pose (e.g. a scaled hotbar).
                state.pose().transformPosition(cx + q.x()[k] * 16f, cy - q.y()[k] * 16f, p);
                xyuv[n * 4] = p.x;
                xyuv[n * 4 + 1] = p.y;
                xyuv[n * 4 + 2] = q.u()[k];
                xyuv[n * 4 + 3] = q.v()[k];
                rgba[n] = q.color();
                tex[n] = q.tex();
                n++;
            }
        }
    }

    /** Receives the item's posed quads; everything that is not a plain item model is ignored. */
    private final class ItemCollector implements SubmitNodeCollector {
        boolean blockLight;

        @Override public OrderedSubmitNodeCollector order(int order) { return this; }

        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext ctx, int light, int overlay, int outline,
                               int[] tints, ItemQuads quads, ItemStackRenderState.FoilType foil) {
            Matrix4f m = poseStack.last().pose();
            Vector3f a = new Vector3f(), b = new Vector3f(), c = new Vector3f();
            for (BakedQuad q : quads.all()) {
                float[] xs = new float[4], ys = new float[4], us = new float[4], vs = new float[4];
                float depth = 0;
                Vector3f[] pts = new Vector3f[4];
                for (int k = 0; k < 4; k++) {
                    var pos = q.position(k);
                    Vector3f t = m.transformPosition(pos.x(), pos.y(), pos.z(), new Vector3f());
                    pts[k] = t;
                    xs[k] = t.x; ys[k] = t.y; depth += t.z;
                    long uv = q.packedUV(k);
                    us[k] = UVPair.unpackU(uv); vs[k] = UVPair.unpackV(uv);
                }
                int col = 0xFFFFFFFF;
                var info = q.materialInfo();
                if (info.isTinted() && tints != null && info.tintIndex() >= 0 && info.tintIndex() < tints.length) {
                    int argb = tints[info.tintIndex()];
                    col = 0xFF000000 | (argb & 0xFF) << 16 | (argb & 0xFF00) | (argb >> 16 & 0xFF);
                }
                if (blockLight) {
                    pts[1].sub(pts[0], a); pts[2].sub(pts[0], b);
                    a.cross(b, c);
                    if (c.lengthSquared() > 1e-12f) c.normalize();
                    float shade = Math.min(1f, 0.4f + 0.6f * (Math.max(0, c.dot(L0)) + Math.max(0, c.dot(L1))));
                    col = shadeColor(col, shade);
                }
                int texKey = info.sprite().atlasLocation().equals(TextureAtlas.LOCATION_ITEMS)
                        ? Protocol.GUI_TEX_ITEMS : Protocol.GUI_TEX_BLOCKS;
                itemQuads.add(new ItemQuad(xs, ys, us, vs, col, texKey, depth / 4f));
            }
        }

        private int shadeColor(int rgba, float s) {
            int r = Math.round((rgba & 0xFF) * s), g = Math.round((rgba >> 8 & 0xFF) * s), b = Math.round((rgba >> 16 & 0xFF) * s);
            return (rgba & 0xFF000000) | r | g << 8 | b << 16;
        }

        // Not plain item models (chests, banners, heads, ...): not drawn yet.
        @Override public void submitShadow(PoseStack p, float f, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> l) {}
        @Override public void submitNameTag(PoseStack p, net.minecraft.world.phys.Vec3 v, int i, net.minecraft.network.chat.Component c, boolean b, int j, net.minecraft.client.renderer.state.level.CameraRenderState s) {}
        @Override public void submitText(PoseStack p, float x, float y, net.minecraft.util.FormattedCharSequence t, boolean d, net.minecraft.client.gui.Font.DisplayMode m, int a, int b, int c, int e) {}
        @Override public void submitTextBackground(PoseStack p, float a, float b, float c, float d, int e, net.minecraft.client.gui.Font.DisplayMode m, int f) {}
        @Override public void submitFlame(PoseStack p, net.minecraft.client.renderer.entity.state.EntityRenderState s, org.joml.Quaternionf q) {}
        @Override public void submitLeash(PoseStack p, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState s) {}
        @Override public <S> void submitModel(net.minecraft.client.model.Model<? super S> model, S s, PoseStack p, net.minecraft.client.renderer.rendertype.RenderType t, int a, int b, int c, net.minecraft.client.renderer.texture.UvMapping u, int d) {}
        @Override public <S> void submitCrumblingOverlay(net.minecraft.client.model.Model<? super S> model, S s, PoseStack p, net.minecraft.client.renderer.rendertype.RenderType t, int a, int b, int c, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay o) {}
        @Override public void submitMovingBlock(PoseStack p, net.minecraft.client.renderer.block.MovingBlockRenderState s, int i) {}
        @Override public void submitBlockModel(PoseStack p, net.minecraft.client.renderer.rendertype.RenderType t, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int[] tints, int a, int b, int c) {}
        @Override public void submitBreakingBlockModel(PoseStack p, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts, int i, boolean b) {}
        @Override public void submitShapeOutline(PoseStack p, net.minecraft.world.phys.shapes.VoxelShape s, net.minecraft.client.renderer.rendertype.RenderType t, int i, float f, boolean b) {}
        @Override public void submitCustomGeometry(PoseStack p, net.minecraft.client.renderer.rendertype.RenderType t, SubmitNodeCollector.CustomGeometryRenderer r) {}
        @Override public void submitQuadParticleGroup(net.minecraft.client.renderer.state.level.QuadParticleRenderState s) {}
        @Override public void submitGizmoPrimitives(net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group g, net.minecraft.client.renderer.state.level.CameraRenderState s, boolean b) {}
    }
}
