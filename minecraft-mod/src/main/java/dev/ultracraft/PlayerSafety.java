package dev.ultracraft;

import dev.ultracraft.bridge.Bridge;
import dev.ultracraft.bridge.Protocol;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the Minecraft player somewhere sane while ULTRAKILL's floors are not solid yet.
 *
 * <p>Three mechanisms, modelled on how SkyCraft treats the Minecraft player (real body,
 * real gravity, but MOVED by the host on a world change, and frozen while the host is
 * loading):
 *
 * <ol>
 *   <li><b>Spawn sync.</b> ULTRAKILL bumps the header's {@code ukSpawnRequest} on every level
 *       load. Minecraft teleports its player to {@link #SPAWN}, zeroes velocity and fall
 *       distance, holds it, and echoes the request in {@code PLAYER.spawnAck}. ULTRAKILL
 *       anchors only after seeing the echo, so every level starts from the same still,
 *       known body instead of whatever Minecraft's player was doing at the time (in the
 *       2026-10-13 playtest: falling through the void at y=-115, -60 and -129).</li>
 *   <li><b>Hold.</b> While held, gravity is off and velocity is zeroed every tick. A spawn
 *       hold ends when the player presses a movement key. H in ULTRAKILL toggles a hard
 *       lock, which movement keys do not break (InputApplier drops them while locked).</li>
 *   <li><b>Void net.</b> Below the world floor the player is sent back to {@link #SPAWN} and
 *       held, before void damage starts ({@code minY - 64}). Spawn is the anchor, so the
 *       ULTRAKILL puppet lands back at the level's spawn point too, with no re-anchor.</li>
 * </ol>
 *
 * <p>Teleports go through the integrated server's own {@code teleportTo}, the path
 * {@code /tp} uses, so the server and client agree and the server does not later apply
 * fall damage for the drop it did not see. The client is moved too, so the position is
 * right on this very tick.
 */
final class PlayerSafety {

    private static final Logger LOG = LoggerFactory.getLogger(UltraCraftClient.MOD_ID);

    /**
     * Where every ULTRAKILL level starts in Minecraft. Away from the void preset's stone start
     * platform (around 0,64,0) so ULTRAKILL geometry never overlaps it, and at y=128 so there
     * are 192 blocks of descent before the world floor (about 370 ULTRAKILL units) and the
     * same above.
     */
    static final Vec3 SPAWN = new Vec3(1000.5, 128.0, 1000.5);

    /** Below minY by this much, the void net fires. Void damage starts at minY - 64. */
    private static final double NET_BELOW_MIN_Y = 8.0;

    private static final int MOVEMENT_KEYS = Protocol.KEY_FORWARD | Protocol.KEY_BACK
            | Protocol.KEY_LEFT | Protocol.KEY_RIGHT | Protocol.KEY_JUMP | Protocol.KEY_SNEAK;

    private static volatile int spawnAck;
    private static volatile boolean locked;

    private final Bridge.InputState in = new Bridge.InputState();
    private int handledRequest;
    private int pendingAck;
    private boolean spawnHold;
    private boolean prevToggle;
    private boolean wasHeld;
    private Vec3 lockPos;

    /** Last spawn request completed, for PLAYER.spawnAck. */
    static int spawnAck() { return spawnAck; }

    /** True while H-locked; InputApplier drops movement keys. */
    static boolean locked() { return locked; }

    void tick(Minecraft mc, Bridge bridge, boolean ukAlive) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (!ukAlive && (spawnHold || locked || pendingAck != 0)) {
            // The release keys come from ULTRAKILL; without it, a held player could never
            // move again. Hand the body back to plain Minecraft.
            spawnHold = false;
            locked = false;
            pendingAck = 0;
            LOG.info("[UltraCraft] safety: ULTRAKILL not live, released hold/lock");
        }

        // 1. Spawn sync.
        int request = bridge.ukSpawnRequest();
        if (ukAlive && request != 0 && request != handledRequest) {
            handledRequest = request;
            pendingAck = request;
            teleport(mc, player, SPAWN, "ULTRAKILL level load (spawn request " + request + ")");
            spawnHold = true;
        }
        if (pendingAck != 0 && player.position().distanceTo(SPAWN) < 0.05) {
            spawnAck = pendingAck;
            LOG.info("[UltraCraft] safety: at spawn, acknowledged spawn request {}", pendingAck);
            pendingAck = 0;
        }

        // 2. Hold / lock, from ULTRAKILL's forwarded keys.
        boolean haveInput = ukAlive && bridge.readInput(in);
        boolean toggle = haveInput && (in.keys & Protocol.KEY_HOLD_TOGGLE) != 0;
        if (toggle && !prevToggle) {
            locked = !locked;
            lockPos = locked ? player.position() : null;
            LOG.info("[UltraCraft] safety: H pressed, player {}", locked ? "LOCKED in place" : "unlocked");
        }
        prevToggle = toggle;
        if (spawnHold && pendingAck == 0 && haveInput && (in.keys & MOVEMENT_KEYS) != 0) {
            spawnHold = false;
            LOG.info("[UltraCraft] safety: movement key pressed, spawn hold released");
        }

        // 3. Void net.
        if (player.getY() < player.level().getMinY() - NET_BELOW_MIN_Y) {
            teleport(mc, player, SPAWN, "void net at y=" + String.format("%.1f", player.getY()));
            spawnHold = true;
            if (locked) lockPos = SPAWN;
        }

        boolean held = spawnHold || locked || pendingAck != 0;
        if (held) {
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            if (locked && lockPos != null && player.position().distanceToSqr(lockPos) > 1e-6) {
                player.setPos(lockPos);
            }
        } else if (wasHeld) {
            player.setNoGravity(false);
        }
        wasHeld = held;
    }

    /** One-line state for the periodic log. */
    String describe() {
        return String.format("safety request=%d ack=%d spawnHold=%b locked=%b",
                handledRequest, spawnAck, spawnHold, locked);
    }

    private static void teleport(Minecraft mc, LocalPlayer player, Vec3 to, String why) {
        LOG.info("[UltraCraft] safety: teleporting to spawn {} from {}: {}",
                fmt(to), fmt(player.position()), why);
        player.snapTo(to.x, to.y, to.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();

        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            sp.teleportTo(sp.level(), to.x, to.y, to.z, Set.of(), sp.getYRot(), sp.getXRot(), false);
            sp.setDeltaMovement(Vec3.ZERO);
            sp.resetFallDistance();
        });
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }
}
