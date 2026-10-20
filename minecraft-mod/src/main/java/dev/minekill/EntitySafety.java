package dev.minekill;

import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nothing falls through ULTRAKILL floors (2026-10-17: "Nothing should ever fall through the
 * floors"). Two rules, applied to every entity's own movement ({@code Entity.move}, via
 * EntityMoveSafetyMixin): the player, mobs, items, falling blocks, minecarts.
 *
 * <ol>
 *   <li><b>Guard</b>, before the move. ULTRAKILL publishes, with its collision boxes, the regions
 *       where those boxes are complete (UkCollision.isKnown). A move into space outside them is
 *       cut short: a floor may be there that Minecraft has not received yet. Falls wait at the
 *       edge of what is known instead of passing through the floor that arrives a moment
 *       later.</li>
 *   <li><b>Rescue</b>, after the move. Minecraft's collision ignores a box the entity already
 *       overlaps, so an entity that ends up even slightly inside a floor (spawned in it, a box
 *       that arrived around it, rounding) falls straight through. If, after a move, the entity's
 *       footprint has a box top above its feet that it passed this move (between its old and new
 *       feet) or that it sank into by at most {@link #MAX_SINK}, it is put on top, when there is
 *       room.</li>
 * </ol>
 *
 * <p>The integrated server's player is left alone: the client moves the player and the server
 * checks the client's moves by repeating them, so changing them there would make it reject
 * them. The client player's rescue is sent to the server as a teleport.
 */
public final class EntitySafety {
    private EntitySafety() {}

    private static final Logger LOG = LoggerFactory.getLogger(MinekillClient.MOD_ID);

    /** How deep into a floor (blocks) an entity may be found and still be lifted onto it. */
    private static final double MAX_SINK = 0.75;
    /**
     * In an entity's first ticks: spawn eggs and spawning put the entity's feet at the bottom of
     * the block cell, which an ULTRAKILL floor can fill almost entirely (the 2026-10-17 logs:
     * iron golems 0.375-0.75 into the floor, deeper ones fell into the void).
     */
    private static final double SPAWN_SINK = 1.3;
    /** More than any fall moves in one tick (terminal velocity is 3.92 blocks per tick). */
    private static final double MAX_PASSED = 5.0;

    private static int guardLogs, rescueLogs;
    private static long guardLogMs;

    private static boolean applies(Entity e) {
        return !e.noPhysics && !e.isSpectator() && !e.isPassenger()
                && !(e instanceof ServerPlayer)
                && !(e instanceof net.minecraft.world.entity.projectile.Projectile)
                && !EnemyProxies.isProxy(e);
    }

    /** Before Entity.move: the movement, cut short where it would leave the known regions. */
    public static Vec3 guard(Entity e, Vec3 mv) {
        if (!UkCollision.guardActive() || mv.lengthSqr() == 0 || !applies(e)) return mv;
        AABB box = e.getBoundingBox();
        double dx = mv.x, dy = mv.y, dz = mv.z;
        boolean cutH = false, cutV = false;
        if ((dx != 0 || dz != 0) && !UkCollision.isKnown(box.expandTowards(dx, 0, dz))) {
            dx = 0;
            dz = 0;
            cutH = true;
        }
        if (dy != 0) {
            AABB from = box.move(dx, 0, dz);
            if (!UkCollision.isKnown(from.expandTowards(0, dy, 0))) {
                double ok = 0;
                for (double f = 0.5; f > 0.03; f *= 0.5)
                    if (UkCollision.isKnown(from.expandTowards(0, dy * f, 0))) { ok = dy * f; break; }
                dy = ok;
                cutV = true;
            }
        }
        if (!cutH && !cutV) return mv;
        if (cutV && dy == 0 && mv.y < 0) {
            Vec3 d = e.getDeltaMovement();
            e.setDeltaMovement(d.x, 0, d.z);
        }
        long now = System.currentTimeMillis();
        if (guardLogs < 300 && now - guardLogMs > 1000) {
            guardLogs++;
            guardLogMs = now;
            LOG.info("[MINEKILL] safety: {} at {} held at the edge of known ULTRAKILL collision (move {} -> {})",
                    name(e), fmt(e.position()), fmt(mv), fmt(new Vec3(dx, dy, dz)));
        }
        return new Vec3(dx, dy, dz);
    }

    /** After Entity.move: put the entity back on a floor it passed or sank into. */
    public static void rescue(Entity e, double fromY) {
        if (Double.isNaN(fromY) || !UkCollision.hasShapes() || !applies(e)) return;
        if (e instanceof LocalPlayer p && p.getAbilities().flying) return;
        double y = e.getY();
        double reach = e.tickCount <= 3 ? SPAWN_SINK : MAX_SINK;
        double maxTop = Math.min(Math.max(fromY, y + reach), y + MAX_PASSED);
        AABB b = e.getBoundingBox();
        AABB column = new AABB(b.minX + 1e-3, y, b.minZ + 1e-3, b.maxX - 1e-3, maxTop, b.maxZ - 1e-3);
        double top = UkCollision.highestTopInside(column, maxTop + 1e-6);
        if (Double.isNaN(top) || top <= y + 1e-3) return;
        double dy = top - y + 1e-4;
        AABB lifted = b.move(0, dy, 0).deflate(1e-3);
        if (UkCollision.intersectsAny(lifted) || !e.level().noCollision(e, lifted)) return;
        if (rescueLogs < 400) {
            rescueLogs++;
            LOG.info("[MINEKILL] safety: {} {} {} blocks into ULTRAKILL geometry at {} (feet were at {}); put on top",
                    name(e), fromY >= top ? "passed through a floor," : "sank", String.format("%.3f", dy),
                    fmt(e.position()), String.format("%.3f", fromY));
        }
        Vec3 d = e.getDeltaMovement();
        e.setPos(e.getX(), top + 1e-4, e.getZ());
        e.setDeltaMovement(d.x, Math.max(0, d.y), d.z);
        e.resetFallDistance();
        if (e instanceof LocalPlayer) syncServerPlayer(e);
    }

    private static void syncServerPlayer(Entity player) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        UUID id = player.getUUID();
        Vec3 to = player.position();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) return;
            sp.teleportTo(sp.level(), to.x, to.y, to.z, Set.of(), sp.getYRot(), sp.getXRot(), false);
            sp.resetFallDistance();
        });
    }

    private static String name(Entity e) {
        return e instanceof LocalPlayer ? "player" : e.getType().toShortString();
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }
}
