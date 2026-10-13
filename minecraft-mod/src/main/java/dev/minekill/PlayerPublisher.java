package dev.minekill;

import dev.minekill.bridge.Bridge;
import dev.minekill.bridge.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes Minecraft's player state onto the bridge.
 *
 * <p>Per docs/CONTRACT.md, Minecraft owns the player. This is the value ULTRAKILL cares about
 * most: where the player is, where they are looking, and how they are moving.
 *
 * <p>Milestone 4. Nothing is consumed yet on the ULTRAKILL side except for logging, which is
 * deliberate: the point is to prove a real value crosses before anything depends on it.
 *
 * <p>26.3 API notes, checked against the client jar rather than assumed:
 * <ul>
 *   <li>{@code Minecraft.player} is the local player.</li>
 *   <li>{@code Entity.getX()/getY()/getZ()} return block coordinates.</li>
 *   <li>{@code Entity.yRot}/{@code xRot} are private, so yaw and pitch come from
 *       {@code getYRot(partialTick)}/{@code getXRot(partialTick)}, in degrees.</li>
 *   <li>{@code Entity.getDeltaMovement()} gives a {@code Vec3} of per-tick velocity.</li>
 * </ul>
 */
final class PlayerPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(MinekillClient.MOD_ID);

    /** Full air supply. Minecraft's own accessor is not on the classes we can see. */
    private static final float FULL_AIR = 300f;

    private final Bridge.PlayerState state = new Bridge.PlayerState();
    private int lastLoggedX = Integer.MIN_VALUE;
    private int lastLoggedY = Integer.MIN_VALUE;
    private int lastLoggedZ = Integer.MIN_VALUE;

    /**
     * Sample the player and write the PLAYER and CAMERA slots.
     *
     * <p>Called once per client tick. Safe to call with no player loaded (main menu),
     * in which case nothing is written.
     */
    void publish(Minecraft mc, float tickCounter) {
        if (!bridgeReady(mc)) return;

        LocalPlayer p = mc.player;
        if (p == null) return;                    // main menu or loading

        // Render-interpolated position, the same value Minecraft draws the player at.
        // p.position() only changes once per 20 Hz game tick, so ULTRAKILL (60+ FPS)
        // showed the puppet moving in visible steps. Interpolating between the last two
        // ticks costs at most one tick of latency and is smooth at Minecraft's frame rate.
        // After a teleport (snapTo) the old and new positions are equal, so a spawn or
        // void-net jump is not smeared.
        Vec3 pos = p.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        Vec3 vel = p.getDeltaMovement();

        state.tickTimestamp = System.nanoTime() / 1_000_000L;
        state.spawnAck = PlayerSafety.spawnAck();
        state.x = pos.x;
        state.y = pos.y;
        state.z = pos.z;
        state.vx = (float) vel.x;
        state.vy = (float) vel.y;
        state.vz = (float) vel.z;

        // getYRot/getXRot take a partial tick. 1.0f means "fully interpolate to now",
        // which is what we want: docs/CONTRACT.md says never send the 20-tick position
        // without its timestamp, and the renderer reads these on its own clock.
        state.yaw = p.getYRot(1.0f);
        state.pitch = p.getXRot(1.0f);

        state.onGround = p.onGround();
        state.sprinting = p.isSprinting();
        state.crouching = p.isCrouching();
        state.sneaking = p.isShiftKeyDown();
        state.inWater = p.isInWater();
        state.onLadder = p.onClimbable();

        state.health = p.getHealth();                        // half-hearts, 20 = full
        state.food = p.getFoodData().getFoodLevel();          // 20 = full
        state.air = FULL_AIR;

        Bridge bridge = mcBridge;
        if (bridge == null) return;

        bridge.writePlayer(state);
        publishSelection(mc, p);

        Bridge.CameraState cam = new Bridge.CameraState();
        cam.yaw = state.yaw;
        cam.pitch = state.pitch;
        // The crosshair ray starts at getEyePosition = feet + getEyeHeight(). ULTRAKILL puts its
        // camera there, so the line you look down is the line Minecraft raycasts.
        cam.eyeHeight = p.getEyeHeight();
        // F5: which perspective, and how far Minecraft's own camera sits from the eye (its
        // distance after clipping against Minecraft's blocks; ULTRAKILL clips against its own).
        var type = mc.options.getCameraType();
        cam.cameraType = type.isFirstPerson() ? 0 : type.isMirrored() ? 2 : 1;
        cam.cameraDistance = cam.cameraType == 0 ? 0f
                : (float) mc.gameRenderer.mainCamera().position().distanceTo(p.getEyePosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)));
        // FOV is only carried for diagnostics. 26.3 wraps it in an OptionInstance
        // reached via Options.fov(); the old public field no longer exists.
        cam.fov = 70f;
        if (mc.options != null && mc.options.fov() != null) {
            try {
                cam.fov = mc.options.fov().get().floatValue();
            } catch (RuntimeException ignored) {
                // Option not ready yet; the default above is fine.
            }
        }
        bridge.writeCamera(cam);

        logMovement(p);
    }

    private Bridge mcBridge;

    void attach(Bridge bridge) {
        this.mcBridge = bridge;
    }

    private boolean bridgeReady(Minecraft mc) {
        return mcBridge != null && mcBridge.connected();
    }

    /**
     * Log only when the player crosses a block boundary, so the log proves movement
     * without filling up. Silent while standing still, which is the signal we want.
     */
    private void logMovement(LocalPlayer p) {
        int bx = (int) Math.floor(p.getX());
        int by = (int) Math.floor(p.getY());
        int bz = (int) Math.floor(p.getZ());
        if (bx == lastLoggedX && by == lastLoggedY && bz == lastLoggedZ) return;
        lastLoggedX = bx;
        lastLoggedY = by;
        lastLoggedZ = bz;
        // NOTE: SLF4J does not understand format specifiers such as {:.3f} -- it
        // renders them literally. Using them here also desynchronised the placeholder
        // count from the argument count, so `onGround` and `hp` were printing velocity
        // values instead. Numeric formatting goes through String.format; SLF4J only
        // ever sees plain {} placeholders.
        Vec3 v = p.getDeltaMovement();
        String pos = String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", v.x, v.y, v.z);
        String yawPitch = String.format(java.util.Locale.ROOT, "%.2f/%.2f",
                p.getYRot(1.0f), p.getXRot(1.0f));
        LOG.info("[MINEKILL] player at block {}, {}, {}  yaw/pitch {}  vel {}  onGround={} hp={}",
                bx, by, bz, yawPitch, pos, p.onGround(), p.getHealth());
    }

    /**
     * What the crosshair targets, for ULTRAKILL to draw Minecraft's outline (the world layer has
     * no other way to show it). A Minecraft block: its outline shape's bounds. ULTRAKILL geometry
     * (UkPick): the cell a held block would go into, only while holding a block, as SkyCraft
     * does. Inflated slightly, like Minecraft's own outline, so it does not z-fight the faces.
     */
    private void publishSelection(Minecraft mc, LocalPlayer p) {
        var hit = mc.hitResult;
        int kind = 0;
        net.minecraft.world.phys.AABB box = null;
        if (mc.gui.screen() == null && hit instanceof net.minecraft.world.phys.BlockHitResult bh
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && mc.level != null) {
            if (bh instanceof UkPick.UltrakillHitResult) {
                boolean holdingBlock = p.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem
                        || p.getOffhandItem().getItem() instanceof net.minecraft.world.item.BlockItem;
                if (holdingBlock) {
                    kind = 2;
                    box = new net.minecraft.world.phys.AABB(bh.getBlockPos());
                }
            } else {
                var shape = mc.level.getBlockState(bh.getBlockPos()).getShape(mc.level, bh.getBlockPos());
                if (!shape.isEmpty()) {
                    kind = 1;
                    box = shape.bounds().move(bh.getBlockPos());
                }
            }
        }
        if (box == null) {
            mcBridge.writeSelection(0, 0, 0, 0, 0, 0, 0);
        } else {
            box = box.inflate(0.002);
            mcBridge.writeSelection(kind, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }
    }
}
