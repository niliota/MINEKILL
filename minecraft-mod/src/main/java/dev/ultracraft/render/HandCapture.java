package dev.ultracraft.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ultracraft.UltraCraftClient;
import dev.ultracraft.bridge.Bridge;
import dev.ultracraft.bridge.Protocol;
import java.util.List;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/**
 * Minecraft's first-person hands and held items, for ULTRAKILL to draw natively over its world.
 *
 * <p>GameRenderer.renderItemInHand poses the hands with the inverse view rotation (and its view
 * bob) and draws them with the view rotation as model-view. Each frame, the same submission is
 * replayed into a ModelCollector whose post-transform is that view rotation, giving quads in view
 * space, lit with Minecraft's world lights. They go out in the HAND table with the hand FOV; when
 * Minecraft draws no hands (third person, hidden HUD, spectator) the table goes out empty.
 */
public final class HandCapture {
    public static final HandCapture INSTANCE = new HandCapture();

    // Minecraft's world diffuse lights (as in GuiCapture's items), in world orientation: the
    // space the hand normals arrive in before the view rotation.
    private static final Vector3f L0 = new Vector3f(0.2f, 1.0f, -0.7f);
    private static final Vector3f L1 = new Vector3f(-0.2f, 1.0f, 0.7f);

    private final ModelCollector collector = new ModelCollector();
    private final float[] xyz = new float[Protocol.TBL_HAND_CAPACITY * 3];
    private final float[] uv = new float[Protocol.TBL_HAND_CAPACITY * 2];
    private final int[] rgba = new int[Protocol.TBL_HAND_CAPACITY];
    private final int[] tex = new int[Protocol.TBL_HAND_CAPACITY];
    private CameraRenderState camera;
    private boolean active, captured, failureLogged;

    private HandCapture() {}

    /** GameRenderer.renderItemInHand HEAD. */
    public void begin(CameraRenderState camera) {
        this.camera = camera;
        active = UltraCraftClient.ukLive();
        captured = false;
    }

    /** After Minecraft's own submitHandsWithItems: the same call, into the collector. */
    public void submit(FirstPersonHandsAndItemsRenderer renderer, float partialTicks, PoseStack poseStack,
                       PlayerRenderState player, FirstPersonHandsAndItemsRenderState state) {
        if (!active || camera == null) return;
        collector.begin(new Matrix4f(camera.viewRotationMatrix), L0, L1, true, true);
        try {
            renderer.submitHandsWithItems(partialTicks, poseStack, collector, player, state);
            captured = true;
        } catch (RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                LoggerFactory.getLogger("ultracraft").warn("[UltraCraft] hand: capture failed: {}", e.toString());
            }
        }
    }

    public boolean collecting(Object collector) {
        return collector == this.collector;
    }

    /** GameRenderer.renderItemInHand RETURN (every return): publish, empty if nothing was drawn. */
    public void end() {
        if (!active) return;
        active = false;
        UltraCraftClient client = UltraCraftClient.get();
        Bridge bridge = client != null ? client.bridge() : null;
        if (bridge == null) return;
        int n = 0;
        if (captured) {
            List<ModelCollector.Quad> quads = collector.sortedQuads();
            for (ModelCollector.Quad q : quads) {
                if (n + 4 > Protocol.TBL_HAND_CAPACITY) break;
                for (int k = 0; k < 4; k++, n++) {
                    xyz[n * 3] = q.x[k]; xyz[n * 3 + 1] = q.y[k]; xyz[n * 3 + 2] = q.z[k];
                    uv[n * 2] = q.u[k]; uv[n * 2 + 1] = q.v[k];
                    rgba[n] = q.rgba;
                    tex[n] = q.tex;
                }
            }
        }
        bridge.writeHandTable(System.currentTimeMillis(), n, camera.hudFov, xyz, uv, rgba, tex);
    }
}
