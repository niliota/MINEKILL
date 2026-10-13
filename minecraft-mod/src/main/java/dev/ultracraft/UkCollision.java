package dev.ultracraft;

import dev.ultracraft.bridge.Bridge;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ULTRAKILL's collision boxes, as Minecraft collision shapes (Milestone D, spike).
 *
 * <p>ULTRAKILL publishes the environment colliders around its player into the COLLISION
 * table, already converted to Minecraft block coordinates (it owns the mapping anchors, so
 * the inverse is computed there). This class copies the table once per client tick and
 * hands the boxes to {@code EntityCollisionMixin}, which appends them to the list of
 * shapes Minecraft's own movement code collides against. Minecraft's physics therefore
 * resolves the player against ULTRAKILL's walls exactly as it would against blocks: no
 * push-back, no correction loop, no second physics implementation.
 *
 * <p>Thread safety: the integrated server ticks on its own thread and runs the same
 * {@code Entity.move} for the server-side player, so it reads these shapes too. That is
 * deliberate, because a server that did not see the walls would disagree with the client
 * about where the player can be. The snapshot is immutable and published through a
 * volatile field, so either thread always sees one whole list.
 */
public final class UkCollision {

    private static final Logger LOG = LoggerFactory.getLogger(UltraCraftClient.MOD_ID);

    /** Immutable once published. */
    private record Snapshot(int generation, long tickTimestamp, AABB[] boxes, VoxelShape[] shapes) {}

    private static final Snapshot EMPTY = new Snapshot(-1, 0L, new AABB[0], new VoxelShape[0]);
    private static volatile Snapshot current = EMPTY;

    private final Bridge.CollisionTable table = new Bridge.CollisionTable();
    private long lastTimestamp = -1;
    private int lastLoggedGeneration = Integer.MIN_VALUE;
    private int lastLoggedCount = -1;

    /** Shapes appended to some entity's collision list since the last report. */
    static volatile int injections;

    /** Copy the table from the bridge. Called once per client tick. */
    void update(Bridge bridge, boolean ukAlive) {
        if (!ukAlive) {
            if (current != EMPTY) {
                current = EMPTY;
                LOG.info("[UltraCraft] collision: ULTRAKILL not live, dropped all ULTRAKILL boxes");
            }
            lastTimestamp = -1;
            return;
        }
        if (!bridge.readCollisionTable(table)) return;      // writer busy: keep previous
        if (table.tickTimestamp == lastTimestamp) return;   // nothing new
        lastTimestamp = table.tickTimestamp;

        int n = table.count;
        AABB[] boxes = new AABB[n];
        VoxelShape[] shapes = new VoxelShape[n];
        for (int i = 0; i < n; i++) {
            int o = i * 6;
            boxes[i] = new AABB(table.boxes[o], table.boxes[o + 1], table.boxes[o + 2],
                                table.boxes[o + 3], table.boxes[o + 4], table.boxes[o + 5]);
            shapes[i] = Shapes.create(boxes[i]);
        }
        current = new Snapshot(table.generation, table.tickTimestamp, boxes, shapes);

        if (table.generation != lastLoggedGeneration || (lastLoggedCount == 0) != (n == 0)) {
            lastLoggedGeneration = table.generation;
            lastLoggedCount = n;
            LOG.info("[UltraCraft] collision: generation {} with {} ULTRAKILL boxes{}",
                    table.generation, n,
                    n > 0 ? String.format(" (first: %.2f,%.2f,%.2f -> %.2f,%.2f,%.2f)",
                            boxes[0].minX, boxes[0].minY, boxes[0].minZ,
                            boxes[0].maxX, boxes[0].maxY, boxes[0].maxZ) : "");
        }
    }

    /** One-line state for the periodic log. */
    static String describe(AABB player) {
        Snapshot s = current;
        int touching = 0;
        if (player != null) {
            AABB near = player.inflate(0.05);
            for (AABB b : s.boxes) if (b.intersects(near)) touching++;
        }
        int inj = injections;
        injections = 0;
        return String.format("collision gen=%d boxes=%d touchingPlayer=%d injectionsSinceLast=%d",
                s.generation, s.boxes.length, touching, inj);
    }

    /**
     * Append ULTRAKILL's shapes that overlap {@code query} to {@code into}.
     *
     * @return how many were added
     */
    public static int appendShapes(AABB query, List<VoxelShape> into) {
        Snapshot s = current;
        int added = 0;
        for (int i = 0; i < s.boxes.length; i++) {
            if (s.boxes[i].intersects(query)) {
                into.add(s.shapes[i]);
                added++;
            }
        }
        return added;
    }

    /** Called by the mixin each time it actually added a shape. Diagnostics only. */
    public static void countInjection() {
        injections++;   // racy across threads, which is fine for a rough counter
    }

    /** True if any ULTRAKILL box overlaps {@code box} (strictly, like AABB.intersects). */
    public static boolean intersectsAny(AABB box) {
        for (AABB b : current.boxes) if (b.intersects(box)) return true;
        return false;
    }

    public static boolean hasShapes() {
        return current.boxes.length > 0;
    }
}
