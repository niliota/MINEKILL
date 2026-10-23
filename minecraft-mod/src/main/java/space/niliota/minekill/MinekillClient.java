package space.niliota.minekill;

import space.niliota.minekill.bridge.Bridge;
import space.niliota.minekill.bridge.Protocol;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MINEKILL, the Minecraft side.
 *
 * <p>Milestone 3: open the shared file, complete the handshake with ULTRAKILL, log
 * both process ids, and keep the heartbeat alive. Nothing else yet. The point of
 * this stage is to prove both processes can see each other before any feature
 * depends on it.
 *
 * <p>Per docs/CONTRACT.md, Minecraft owns the player, inventory, mining and combat.
 * ULTRAKILL owns the world, camera and its own game systems.
 */
public class MinekillClient implements ClientModInitializer {

    public static final String MOD_ID = "minekill";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static MinekillClient instance;

    private Bridge bridge;
    private PlayerPublisher publisher;
    private InputApplier input;
    private final UkCollision collision = new UkCollision();
    private final PlayerSafety safety = new PlayerSafety();
    private space.niliota.minekill.bridge.Stream stream;
    private space.niliota.minekill.render.WorldMesher mesher;
    private final space.niliota.minekill.render.ParticleExport particles = new space.niliota.minekill.render.ParticleExport();
    private final space.niliota.minekill.render.WorldModels worldModels = new space.niliota.minekill.render.WorldModels();
    private final CollisionInterest collisionInterest = new CollisionInterest();
    private final BlockCollisionExport blockCollision = new BlockCollisionExport();
    private final EnemyProxies enemyProxies = new EnemyProxies();
    private boolean wasDead;

    /**
     * After a death, the first frame Minecraft's player is alive again (it pressed Respawn):
     * ULTRAKILL restarts from its checkpoint (combat design: both restart).
     */
    private void reportRespawn(net.minecraft.client.Minecraft mc) {
        if (mc.player == null) return;
        if (mc.player.isDeadOrDying()) { wasDead = true; return; }
        if (!wasDead) return;
        wasDead = false;
        if (ukLive) {
            bridge.pushEvent(new space.niliota.minekill.bridge.Bridge.Event(space.niliota.minekill.bridge.Protocol.EV_PLAYER_RESPAWN, 0, 0, 0f, 0f));
            LOG.info("[MINEKILL] combat: respawned; asking ULTRAKILL to restart its checkpoint");
        }
    }

    /**
     * Whether Minecraft should behave as if its window were focused.
     *
     * <p>Minecraft's window is in the background while ULTRAKILL has focus, and Minecraft
     * pauses when it loses focus. Telling it it is focused keeps the world ticking.
     *
     * <p>This flag has two halves that MUST be enabled together: {@link WindowMixin} makes
     * Minecraft believe it is focused, and {@link MouseHandlerMixin} stops it grabbing the
     * OS cursor as a result. Enabling only the first would steal the pointer from
     * ULTRAKILL.
     */
    private static volatile boolean treatAsFocused;
    private long nextHeartbeatMs;
    private boolean handshakeReported;
    private long missingHostSinceMs = -1;
    private boolean missingHostWarned;
    private int ticks;

    public static MinekillClient get() {
        return instance;
    }

    /**
     * True while the bridge to ULTRAKILL is up.
     *
     * <p>This, not {@link #ukHasFocus()}, is what decides whether Minecraft may grab the
     * OS cursor. ULTRAKILL reports {@code Application.isFocused} through the bridge, and
     * under Wine that was false on almost every tick in testing (flags 0 in the log, with
     * occasional spikes to 4). Gating on it therefore suppressed the grab almost never,
     * and Minecraft's {@code grabMouse()} calls {@code KeyMapping.setAll()}, which clears
     * every key mapping, so forwarded keys were wiped a frame after being applied.
     *
     * <p>Bridging is also the honest condition. ULTRAKILL is the window the player looks
     * at and drives; Minecraft is the simulation running behind it. When ULTRAKILL is
     * connected there is no interaction for a grabbed cursor to serve.
     */
    private static volatile boolean ukLive;

    /** True while a live ULTRAKILL is on the bridge (updated every client frame). */
    public static boolean ukLive() {
        return ukLive;
    }

    public static boolean bridgeConnected() {
        return instance != null && instance.bridge != null && instance.bridge.connected();
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        bridge = Bridge.open(null);   // null -> MINEKILL_BRIDGE env var, else the default
        if (!bridge.connected()) {
            LOG.error("[MINEKILL] bridge failed to open ({}). Running vanilla Minecraft. "
                    + "Is ULTRAKILL running?", bridge.lastError());
            return;
        }

        LOG.info("[MINEKILL] bridge open at {} (pid {}, {} bytes, awaiting ULTRAKILL pid {})",
                bridge.path(), selfPid(), Protocol.TOTAL_SIZE, bridge.ukPid());

        stream = space.niliota.minekill.bridge.Stream.openNextTo(bridge.path());
        if (stream.ok()) {
            mesher = new space.niliota.minekill.render.WorldMesher(stream);
            LOG.info("[MINEKILL] stream open at {} ({} MB ring)", stream.path(),
                    Protocol.STREAM_RING_SIZE / (1024 * 1024));
        } else {
            LOG.error("[MINEKILL] stream failed to open ({}); blocks will not appear in ULTRAKILL",
                    stream.lastError());
        }

        publisher = new PlayerPublisher();
        publisher.attach(bridge);
        input = new InputApplier(bridge);

        // ULTRAKILL owns OS focus. Only start claiming focus once the bridge is live, so a
        // plain Minecraft session with the mod loaded but no ULTRAKILL behaves normally.
        treatAsFocused = true;
        LOG.info("[MINEKILL] treating Minecraft as focused while ULTRAKILL has OS focus "
                + "(cursor grab suppressed, mouse delta forwarded instead)");
        LOG.info("[MINEKILL] ULTRAKILL should be using the same file. Under Wine that "
                        + "is the same path with a Z: prefix and backslashes, for example "
                        + "Z:{}",
                bridge.path().replace('/', '\\'));
    }

    public Bridge bridge() {
        return bridge;
    }

    /** Called once per client tick by {@code MinekillMixin}. */
    public void tick(net.minecraft.client.Minecraft mc) {
        if (bridge == null || !bridge.connected()) return;

        long now = System.nanoTime() / 1_000_000L;
        if (now >= nextHeartbeatMs) {
            nextHeartbeatMs = now + 100;
            bridge.tickHeartbeat();
        }

        // Liveness is decided by heartbeat freshness, NOT by a non-zero pid. A game
        // that was hard-killed leaves its pid and last heartbeat behind, so
        // `ukPid() != 0` alone once made this report
        // "HANDSHAKE OK, ULTRAKILL pid 32" for a process dead for twenty minutes.
        boolean ukAlive = bridge.ukPid() != 0 && !bridge.ukStale(2000);
        ukLive = ukAlive;

        if (!handshakeReported && ukAlive) {
            handshakeReported = true;
            missingHostSinceMs = -1;
            LOG.info("[MINEKILL] HANDSHAKE OK, ULTRAKILL pid {} last heartbeat {} ms",
                    bridge.ukPid(), bridge.ukHeartbeat());
        }

        // If ULTRAKILL never shows up, say so once and name the exact file we are
        // waiting on. Otherwise the symptom is just "no handshake", and the reader
        // has to guess whether the game is missing, the plugin failed, or the two
        // sides opened different files.
        if (!ukAlive) {
            // Either absent, or present but stale. Treat both as "no host".
            handshakeReported = false;
            if (missingHostSinceMs < 0) missingHostSinceMs = now;
            if (!missingHostWarned && now - missingHostSinceMs > 10_000) {
                missingHostWarned = true;
                LOG.warn("[MINEKILL] no live ULTRAKILL on the bridge after 10s "
                                + "(pid {} present but stale). Waiting on: {}",
                        bridge.ukPid(), bridge.path());
            }
        }

        // Milestone 4: publish the player state every tick. ULTRAKILL is the only
        // consumer for now, and it only logs it. The point is to prove a real value
        // crosses before anything depends on it.
        // The player is playing through ULTRAKILL, so Minecraft never sees its own input
        // and decides it is AFK: 30 FPS after 60 s, 10 FPS after 10 min (from the 26.3
        // FramerateLimitTracker). The bridge runs once per Minecraft FRAME, so that
        // throttle made the puppet update 4x less often. Tell it input is happening.
        if (ukAlive) mc.getFramerateLimitTracker().onInputReceived();

        // Before publishing, so a spawn teleport and its acknowledgement go out together.
        safety.tick(mc, bridge, ukAlive);
        if (publisher != null) publisher.publish(mc, 0f);
        collision.update(bridge, ukAlive);

        pollUkFocus(mc);

        // ULTRAKILL has OS focus, so apply its keyboard and mouse to Minecraft.
        if (input != null && bridge.connected()) input.apply(mc);
        if (mesher != null && ukAlive) mesher.frame(mc);
        if (ukAlive) particles.frame(mc, bridge);
        if (ukAlive) worldModels.frame(mc, bridge);
        if (ukAlive) collisionInterest.frame(mc, bridge);
        if (ukAlive) blockCollision.frame(mc, bridge);
        enemyProxies.frame(mc, bridge, ukAlive);
        reportRespawn(mc);
        if (ukAlive && stream != null && stream.ok()) space.niliota.minekill.gui.GuiTextures.flush(stream);
        if (ukAlive && stream != null && stream.ok() && mc.level != null) space.niliota.minekill.render.AtlasAnimator.INSTANCE.frame(mc, stream);

        if (++ticks % 200 == 0) {
            LOG.info("[MINEKILL] tick {}: uk pid {} hb {} flags {} dropped {} | {}",
                    ticks, bridge.ukPid(), bridge.ukHeartbeat(), bridge.ukFlags(),
                    bridge.ukDroppedEvents(),
                    safety.describe() + " | " + (mesher != null ? mesher.describe() : "world off") + " | "
                            + UkCollision.describe(mc.player != null ? mc.player.getBoundingBox() : null));
        }
    }

    /** Read by WindowMixin, so Minecraft does not pause while in the background. */
    public static boolean treatAsFocused() {
        return treatAsFocused;
    }

    /** True while ULTRAKILL reports it holds OS focus. Read by MouseHandlerMixin. */
    public static volatile boolean ukHasFocus;
    private boolean bridgeWasConnected;

    public static boolean ukHasFocus() {
        return ukHasFocus;
    }

    /**
     * Latch ULTRAKILL's reported focus flag from the bridge header each tick, and hand the
     * cursor back whenever ULTRAKILL becomes the interactive surface.
     *
     * <p>Releases on the rising edge of either signal. Suppressing the grab (see
     * MouseHandlerMixin) stops Minecraft taking the cursor in the first place, but it
     * cannot give back a cursor Minecraft already took before the bridge came up, so the
     * release still has to happen explicitly.
     *
     * <p>Both edges are checked rather than only the focus flag. The flag comes from
     * {@code Application.isFocused} and was false on almost every tick under Wine, so
     * gating on it alone left the pointer trapped in a background window, which is the
     * "stuck trying to go back to the centre" symptom.
     */
    private void pollUkFocus(Minecraft mc) {
        boolean connected = bridge != null && bridge.connected();
        boolean now = connected && (bridge.ukFlags() & Protocol.FLAG_UK_FOCUSED) != 0;

        boolean gained = (now && !ukHasFocus) || (connected && !bridgeWasConnected);
        ukHasFocus = now;
        bridgeWasConnected = connected;

        if (gained) {
            try {
                if (mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed()) {
                    mc.mouseHandler.releaseMouse();
                    LOG.info("[MINEKILL] released Minecraft's cursor: ULTRAKILL is now the "
                            + "interactive surface (connected={}, focused={})",
                            connected, now);
                }
            } catch (Throwable t) {
                LOG.warn("[MINEKILL] could not release Minecraft's cursor: {}", t.toString());
            }
        }
    }

    private static int selfPid() {
        return (int) ProcessHandle.current().pid();
    }
}