package dev.ultracraft.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ultracraft.bridge.Bridge;
import dev.ultracraft.bridge.Protocol;
import dev.ultracraft.gui.GuiTextures;
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
 * Model geometry that lives in the world, for ULTRAKILL to draw lit by its own scene: the
 * player's body while Minecraft's camera is in third person (F5), and the block-breaking cracks.
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

    /** The breaking blocks Minecraft drew this frame (LevelRendererMixin). */
    private static volatile List<BlockBreakingRenderState> breaking = List.of();

    private final ModelCollector collector = new ModelCollector();
    private final float[] xyz = new float[Protocol.TBL_WORLD_MODELS_CAPACITY * 3];
    private final float[] uv = new float[Protocol.TBL_WORLD_MODELS_CAPACITY * 2];
    private final int[] rgba = new int[Protocol.TBL_WORLD_MODELS_CAPACITY];
    private final int[] tex = new int[Protocol.TBL_WORLD_MODELS_CAPACITY];
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private int n;
    private boolean playerFailureLogged, crackFailureLogged, wasThirdPerson;

    public static void setBreaking(List<BlockBreakingRenderState> states) {
        breaking = states.isEmpty() ? List.of() : List.copyOf(states);
    }

    public void frame(Minecraft mc, Bridge bridge) {
        n = 0;
        if (mc.player != null && mc.level != null) {
            boolean third = !mc.options.getCameraType().isFirstPerson();
            if (third) player(mc, !wasThirdPerson);
            wasThirdPerson = third;
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
                LoggerFactory.getLogger("ultracraft").info("[UltraCraft] world models: third-person body is {} quads ({}):{}",
                        collector.quads().size(), state.getClass().getSimpleName(), collector.report);
                collector.report = null;
            }
            for (ModelCollector.Quad q : collector.quads()) put(q.x, q.y, q.z, q.u, q.v, q.rgba, q.tex);
        } catch (RuntimeException e) {
            if (!playerFailureLogged) {
                playerFailureLogged = true;
                LoggerFactory.getLogger("ultracraft").warn("[UltraCraft] world models: player capture failed: {}", e.toString());
            }
        }
    }

    private void crack(Minecraft mc, BlockBreakingRenderState s) {
        try {
            if (s.blockState().getRenderShape() != RenderShape.MODEL) return;
            int stage = Math.clamp(s.progress(), 0, 9);
            int key = GuiTextures.keyForLocation(mc, Identifier.withDefaultNamespace("textures/block/destroy_stage_" + stage + ".png"));
            if (key < 0) return;
            var pos = s.blockPos();
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
        } catch (RuntimeException e) {
            if (!crackFailureLogged) {
                crackFailureLogged = true;
                LoggerFactory.getLogger("ultracraft").warn("[UltraCraft] world models: crack capture failed: {}", e.toString());
            }
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
