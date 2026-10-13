package dev.minekill;

import dev.minekill.bridge.Bridge;
import dev.minekill.bridge.Protocol;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * Tells ULTRAKILL where the entities other than the player are, so it publishes its collision
 * around them as well as around the player. Entities within {@link #RANGE} blocks are clustered
 * into {@link #CLUSTER}-block cells (one point per occupied cell, nearest the player first when
 * there are more than the table holds), so a herd costs one point, not one per animal.
 */
final class CollisionInterest {
    private static final double RANGE = 48.0;
    private static final int CLUSTER = 4;

    private final float[] xyz = new float[Protocol.TBL_COLLISION_INTEREST_CAPACITY * 3];
    private final Set<Long> cells = new HashSet<>();
    private int frame;

    void frame(Minecraft mc, Bridge bridge) {
        if (++frame % 5 != 0) return;   // entities move slowly next to ULTRAKILL's 0.1 s sampling
        int n = 0;
        cells.clear();
        if (mc.level != null && mc.player != null) {
            var list = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
            for (var e : mc.level.entitiesForRendering())
                if (e != mc.player && e.distanceToSqr(mc.player) <= RANGE * RANGE) list.add(e);
            list.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
            for (var e : list) {
                if (n >= Protocol.TBL_COLLISION_INTEREST_CAPACITY) break;
                int cx = Math.floorDiv((int) Math.floor(e.getX()), CLUSTER), cy = Math.floorDiv((int) Math.floor(e.getY()), CLUSTER);
                int cz = Math.floorDiv((int) Math.floor(e.getZ()), CLUSTER);
                long key = ((long) cx & 0x1FFFFF) << 42 | ((long) cy & 0x1FFFFF) << 21 | ((long) cz & 0x1FFFFF);
                if (!cells.add(key)) continue;
                xyz[n * 3] = (cx + 0.5f) * CLUSTER;
                xyz[n * 3 + 1] = (cy + 0.5f) * CLUSTER;
                xyz[n * 3 + 2] = (cz + 0.5f) * CLUSTER;
                n++;
            }
        }
        bridge.writeCollisionInterest(System.currentTimeMillis(), n, xyz);
    }
}
