package space.niliota.minekill.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import space.niliota.minekill.bridge.Bridge;
import space.niliota.minekill.bridge.Protocol;
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
    private final Map<Long, List<space.niliota.minekill.render.ModelCollector.Quad>> queuedEntities = new HashMap<>();

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
        capturing = space.niliota.minekill.MinekillClient.ukLive() && Minecraft.getInstance().level != null;
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
    private final space.niliota.minekill.render.ModelCollector entityCollector = new space.niliota.minekill.render.ModelCollector();

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
                org.slf4j.LoggerFactory.getLogger("minekill").warn("[MINEKILL] gui: entity capture failed: {}", ex.toString());
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
                org.slf4j.LoggerFactory.getLogger("minekill").warn("[MINEKILL] gui: capture failed on {}: {}",
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
            org.slf4j.LoggerFactory.getLogger("minekill").info("[MINEKILL] gui: pipeline {} blends {}; its quads are skipped",
                    p.getLocation(), f);
        }
        return false;
    }

    /** The entity's quads, already in GUI pixels and sorted far first. */
    private void entityQuads(List<space.niliota.minekill.render.ModelCollector.Quad> quads) {
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

    private final space.niliota.minekill.render.ModelCollector itemCollector = new space.niliota.minekill.render.ModelCollector();
    private static final Vector3f L0 = new Vector3f(0.2f, 1.0f, -0.7f).normalize();
    private static final Vector3f L1 = new Vector3f(-0.2f, 1.0f, 0.7f).normalize();
    private static final Vector3f NO_LIGHT = new Vector3f();

    /**
     * An item in a slot, from its real model, projected like GuiItemAtlas.drawToSlot. The shared
     * ModelCollector takes plain item models and also the special ones drawn as entity models
     * (chests, beds, shields, banners, heads, ...). Lit with Minecraft's GUI item lights when the
     * model uses block light (3D blocks), unlit when flat.
     */
    private void item(Minecraft mc, GuiItemRenderState state) {
        ItemStackRenderState rs = state.itemStackRenderState();
        boolean lit = rs.usesBlockLight();
        itemCollector.begin(new Matrix4f(), lit ? L0 : NO_LIGHT, lit ? L1 : NO_LIGHT, false);
        try {
            rs.submit(new PoseStack(), itemCollector, 0xF000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0);
        } catch (RuntimeException ex) {
            return;
        }
        float cx = state.x() + 8, cy = state.y() + 8;
        Vector2f p = new Vector2f();
        for (var q : itemCollector.sortedQuads()) {   // far first
            if (n + 4 > CAP) break;
            for (int k = 0; k < 4; k++) {
                // Model y is up; GUI y is down. Then the item's own GUI pose (e.g. a scaled hotbar).
                state.pose().transformPosition(cx + q.x[k] * 16f, cy - q.y[k] * 16f, p);
                xyuv[n * 4] = p.x;
                xyuv[n * 4 + 1] = p.y;
                xyuv[n * 4 + 2] = q.u[k];
                xyuv[n * 4 + 3] = q.v[k];
                rgba[n] = q.rgba;
                tex[n] = q.tex;
                n++;
            }
        }
    }

}
