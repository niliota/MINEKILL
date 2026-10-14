package dev.minekill.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.minekill.bridge.Bridge;
import dev.minekill.bridge.Protocol;
import dev.minekill.gui.GuiTextures;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/**
 * Model geometry that lives in the world, for ULTRAKILL to draw lit by its own scene: every
 * entity near the player (mobs, dropped items, arrows, item frames, ...; the player's own body
 * only in third person), block entities (chests, beds, signs, banners, ...), and the
 * block-breaking cracks.
 *
 * <p>Entities and block entities go through Minecraft's own renderers, extracted here for
 * ULTRAKILL's sake rather than taken from Minecraft's frame: Minecraft culls to its own narrower
 * view, ULTRAKILL's camera sees wider. Everything within {@link #RANGE} blocks is drawn.
 *
 * <p>The player goes through Minecraft's own entity renderer (extractEntity, then submit at its
 * world position) into a ModelCollector, so armour, held items, skin layers and every animation
 * are Minecraft's. Cracks follow LevelRenderer.submitBlockDestroyAnimation: the breaking block's
 * model quads, pushed out a hair along their face and textured with destroy_stage_N, its UVs laid
 * over the block face the way Minecraft's sheeted decal does. Quads are unlit, in Minecraft block
 * coords; the WORLD_MODELS table goes out every frame (empty when there is nothing).
 */
public final class WorldModels {
    private static final Vector3f NONE = new Vector3f();
    private static final float CRACK_LIFT = 0.002f;
    private static final double RANGE = 64.0;
    private static final int CHUNK_RANGE = 4;   // block entities: chunks around the player's

    /** The breaking blocks Minecraft drew this frame (LevelRendererMixin). */
    private static volatile List<BlockBreakingRenderState> breaking = List.of();

    private final ModelCollector collector = new ModelCollector();
    {
        // Depth-tested in ULTRAKILL: glint quads sit a hair off their base quad (blocks; a
        // thousandth of a block cannot be seen but keeps the additive pass from flickering).
        collector.glintLift = 0.001f;
    }
    private final float[] xyz = new float[Protocol.TBL_WORLD_MODELS_CAPACITY * 3];
    private final float[] uv = new float[Protocol.TBL_WORLD_MODELS_CAPACITY * 2];
    private final int[] rgba = new int[Protocol.TBL_WORLD_MODELS_CAPACITY];
    private final int[] tex = new int[Protocol.TBL_WORLD_MODELS_CAPACITY];
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private int n;
    private boolean playerFailureLogged, crackFailureLogged, wasThirdPerson;
    private final java.util.Set<Class<?>> failedTypes = new java.util.HashSet<>();

    public static void setBreaking(List<BlockBreakingRenderState> states) {
        breaking = states.isEmpty() ? List.of() : List.copyOf(states);
    }

    public void frame(Minecraft mc, Bridge bridge) {
        n = 0;
        if (mc.player != null && mc.level != null) {
            boolean third = !mc.options.getCameraType().isFirstPerson();
            if (third) player(mc, !wasThirdPerson);
            wasThirdPerson = third;
            entities(mc);
            blockEntities(mc);
            for (BlockBreakingRenderState s : breaking) crack(mc, s);
        }
        bridge.writeWorldModelsTable(System.currentTimeMillis(), n, xyz, uv, rgba, tex);
    }

    private void player(Minecraft mc, boolean report) {
        try {
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            var dispatcher = mc.getEntityRenderDispatcher();
            var state = dispatcher.extractEntity(mc.player, pt);
            collector.begin(new Matrix4f(), NONE, NONE, false);
            collector.report = report ? new StringBuilder() : null;
            dispatcher.submit(state, new CameraRenderState(), state.x, state.y, state.z, new PoseStack(), collector);
            if (report) {
                LoggerFactory.getLogger("minekill").info("[MINEKILL] world models: third-person body is {} quads ({}):{}",
                        collector.quads().size(), state.getClass().getSimpleName(), collector.report);
                collector.report = null;
            }
            for (ModelCollector.Quad q : collector.quads()) put(q.x, q.y, q.z, q.u, q.v, q.rgba, q.tex);
        } catch (RuntimeException e) {
            if (!playerFailureLogged) {
                playerFailureLogged = true;
                LoggerFactory.getLogger("minekill").warn("[MINEKILL] world models: player capture failed: {}", e.toString());
            }
        }
    }

    /** Every entity within range except the local player (drawn by player() in third person). */
    private void entities(Minecraft mc) {
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        var dispatcher = mc.getEntityRenderDispatcher();
        var camera = new CameraRenderState();
        collector.begin(new Matrix4f(), NONE, NONE, false);
        for (var e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e.distanceToSqr(mc.player) > RANGE * RANGE) continue;
            if (dev.minekill.EnemyProxies.isProxyId(e.getId())) continue;   // ULTRAKILL enemies' stand-ins
            try {
                var state = dispatcher.extractEntity(e, pt);
                dispatcher.submit(state, camera, state.x, state.y, state.z, new PoseStack(), collector);
            } catch (RuntimeException ex) {
                logOnce(e.getClass(), "entity", ex);
            }
        }
        for (ModelCollector.Quad q : collector.quads()) put(q.x, q.y, q.z, q.u, q.v, q.rgba, q.tex);
    }

    /** Block entities in the chunks around the player, through Minecraft's block entity renderers. */
    private void blockEntities(Minecraft mc) {
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        var camera = new CameraRenderState();
        int pcx = mc.player.blockPosition().getX() >> 4, pcz = mc.player.blockPosition().getZ() >> 4;
        collector.begin(new Matrix4f(), NONE, NONE, false);
        for (int cx = pcx - CHUNK_RANGE; cx <= pcx + CHUNK_RANGE; cx++) {
            for (int cz = pcz - CHUNK_RANGE; cz <= pcz + CHUNK_RANGE; cz++) {
                var chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    try {
                        // Off-screen renderers (beacons) answer only to the "globally rendered" call.
                        var state = dispatcher.tryExtractRenderState(be, pt, null, false);
                        if (state == null) state = dispatcher.tryExtractRenderState(be, pt, null, true);
                        if (state == null) continue;
                        var pos = be.getBlockPos();
                        PoseStack pose = new PoseStack();
                        pose.translate(pos.getX(), pos.getY(), pos.getZ());
                        dispatcher.submit(state, pose, collector, camera);
                    } catch (RuntimeException ex) {
                        logOnce(be.getClass(), "block entity", ex);
                    }
                }
            }
        }
        for (ModelCollector.Quad q : collector.quads()) put(q.x, q.y, q.z, q.u, q.v, q.rgba, q.tex);
    }

    private void logOnce(Class<?> type, String what, RuntimeException e) {
        if (failedTypes.add(type)) {
            LoggerFactory.getLogger("minekill").warn("[MINEKILL] world models: {} {} failed: {}", what, type.getSimpleName(), e.toString());
        }
    }

    private void crack(Minecraft mc, BlockBreakingRenderState s) {
        try {
            int stage = Math.clamp(s.progress(), 0, 9);
            int key = GuiTextures.keyForLocation(mc, Identifier.withDefaultNamespace("textures/block/destroy_stage_" + stage + ".png"));
            if (key < 0) return;
            var pos = s.blockPos();
            if (s.blockState().getRenderShape() != RenderShape.MODEL) {
                crackShape(mc, s, key);
                return;
            }
            int before = n;
            var offset = s.blockState().getOffset(pos);
            var model = mc.getModelManager().getBlockStateModelSet().get(s.blockState());
            RandomSource random = RandomSource.create(s.blockState().getSeed(pos));
            parts.clear();
            model.collectParts(random, parts);
            float[] x = new float[4], y = new float[4], z = new float[4], u = new float[4], v = new float[4];
            for (BlockStateModelPart part : parts) {
                for (int d = 0; d <= 6; d++) {
                    Direction dir = d < 6 ? Direction.values()[d] : null;
                    for (BakedQuad q : part.getQuads(dir)) {
                        Direction face = q.direction();
                        var nrm = face.getUnitVec3f();
                        for (int k = 0; k < 4; k++) {
                            var p = q.position(k);
                            float lx = p.x() + (float) offset.x, ly = p.y() + (float) offset.y, lz = p.z() + (float) offset.z;
                            x[k] = pos.getX() + lx + nrm.x() * CRACK_LIFT;
                            y[k] = pos.getY() + ly + nrm.y() * CRACK_LIFT;
                            z[k] = pos.getZ() + lz + nrm.z() * CRACK_LIFT;
                            // The crack laid flat over the face, as seen from outside it.
                            switch (face) {
                                case UP -> { u[k] = lx; v[k] = lz; }
                                case DOWN -> { u[k] = lx; v[k] = 1 - lz; }
                                case NORTH -> { u[k] = 1 - lx; v[k] = 1 - ly; }
                                case SOUTH -> { u[k] = lx; v[k] = 1 - ly; }
                                case WEST -> { u[k] = lz; v[k] = 1 - ly; }
                                case EAST -> { u[k] = 1 - lz; v[k] = 1 - ly; }
                            }
                        }
                        put(x, y, z, u, v, 0xFFFFFFFF, key | Protocol.MODEL_TEX_MULTIPLY);
                    }
                }
            }
            // Chests, banners, signs, ... count as MODEL blocks in 26.3 but their block model is
            // empty (a particle texture only); what you see is their block entity renderer.
            if (n == before) crackShape(mc, s, key);
        } catch (RuntimeException e) {
            if (!crackFailureLogged) {
                crackFailureLogged = true;
                LoggerFactory.getLogger("minekill").warn("[MINEKILL] world models: crack capture failed: {}", e.toString());
            }
        }
    }

    /** Block entities: their renderer would crack the model; crack the outline shape instead. */
    private void crackShape(Minecraft mc, BlockBreakingRenderState s, int key) {
        for (var box : s.blockState().getShape(mc.level, s.blockPos()).toAabbs()) crackBox(box.move(s.blockPos()), key);
    }

    /** Six crack faces over a box (world coords), lifted a hair, UVs laid over each face. */
    private void crackBox(net.minecraft.world.phys.AABB b, int key) {
        float e = CRACK_LIFT;
        float x0 = (float) b.minX - e, y0 = (float) b.minY - e, z0 = (float) b.minZ - e;
        float x1 = (float) b.maxX + e, y1 = (float) b.maxY + e, z1 = (float) b.maxZ + e;
        float bx = (float) Math.floor(b.minX), by = (float) Math.floor(b.minY), bz = (float) Math.floor(b.minZ);
        float[][] faces = {
            {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0},   // up
            {x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1},   // down
            {x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0},   // north
            {x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1},   // south
            {x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1},   // west
            {x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0},   // east
        };
        float[] x = new float[4], y = new float[4], z = new float[4], u = new float[4], v = new float[4];
        for (int f = 0; f < 6; f++) {
            for (int k = 0; k < 4; k++) {
                x[k] = faces[f][k * 3]; y[k] = faces[f][k * 3 + 1]; z[k] = faces[f][k * 3 + 2];
                float lx = x[k] - bx, ly = y[k] - by, lz = z[k] - bz;
                switch (f) {
                    case 0 -> { u[k] = lx; v[k] = lz; }
                    case 1 -> { u[k] = lx; v[k] = 1 - lz; }
                    case 2 -> { u[k] = 1 - lx; v[k] = 1 - ly; }
                    case 3 -> { u[k] = lx; v[k] = 1 - ly; }
                    case 4 -> { u[k] = lz; v[k] = 1 - ly; }
                    default -> { u[k] = 1 - lz; v[k] = 1 - ly; }
                }
            }
            put(x, y, z, u, v, 0xFFFFFFFF, key | Protocol.MODEL_TEX_MULTIPLY);
        }
    }

    private void put(float[] x, float[] y, float[] z, float[] u, float[] v, int color, int key) {
        if (n + 4 > Protocol.TBL_WORLD_MODELS_CAPACITY) return;
        for (int k = 0; k < 4; k++, n++) {
            xyz[n * 3] = x[k]; xyz[n * 3 + 1] = y[k]; xyz[n * 3 + 2] = z[k];
            uv[n * 2] = u[k]; uv[n * 2 + 1] = v[k];
            rgba[n] = color;
            tex[n] = key;
        }
    }
}
