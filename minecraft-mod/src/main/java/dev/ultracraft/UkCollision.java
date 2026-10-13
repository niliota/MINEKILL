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

    /**
     * Immutable once published. {@code grid}: box indices per {@link #CELL}-block column cell,
     * so a query looks at the boxes around it instead of all of them (every mob queries several
     * times a tick, on two threads). Boxes spanning more than {@link #MAX_CELLS} cells sit in
     * {@code big}, checked by every query.
     */
    private record Snapshot(int generation, long tickTimestamp, AABB[] boxes, VoxelShape[] shapes,
                            java.util.Map<Long, int[]> grid, int[] big) {}

    private static final int CELL = 8;
    private static final int MAX_CELLS = 64;
    private static final Snapshot EMPTY = new Snapshot(-1, 0L, new AABB[0], new VoxelShape[0], java.util.Map.of(), new int[0]);
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
        current = index(table.generation, table.tickTimestamp, boxes, shapes);

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

    private static long cellKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL); }

    private static Snapshot index(int generation, long ts, AABB[] boxes, VoxelShape[] shapes) {
        java.util.Map<Long, java.util.List<Integer>> cells = new java.util.HashMap<>();
        java.util.List<Integer> big = new java.util.ArrayList<>();
        for (int i = 0; i < boxes.length; i++) {
            AABB b = boxes[i];
            int x0 = Math.floorDiv((int) Math.floor(b.minX), CELL), x1 = Math.floorDiv((int) Math.floor(b.maxX), CELL);
            int z0 = Math.floorDiv((int) Math.floor(b.minZ), CELL), z1 = Math.floorDiv((int) Math.floor(b.maxZ), CELL);
            if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > MAX_CELLS) { big.add(i); continue; }
            for (int cx = x0; cx <= x1; cx++)
                for (int cz = z0; cz <= z1; cz++)
                    cells.computeIfAbsent(cellKey(cx, cz), k -> new java.util.ArrayList<>()).add(i);
        }
        java.util.Map<Long, int[]> grid = new java.util.HashMap<>(cells.size() * 2);
        for (var e : cells.entrySet()) grid.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        return new Snapshot(generation, ts, boxes, shapes, grid, big.stream().mapToInt(Integer::intValue).toArray());
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
        for (int i : s.big) {
            if (s.boxes[i].intersects(query)) { into.add(s.shapes[i]); added++; }
        }
        int x0 = Math.floorDiv((int) Math.floor(query.minX), CELL), x1 = Math.floorDiv((int) Math.floor(query.maxX), CELL);
        int z0 = Math.floorDiv((int) Math.floor(query.minZ), CELL), z1 = Math.floorDiv((int) Math.floor(query.maxZ), CELL);
        boolean multi = x0 != x1 || z0 != z1;
        java.util.Set<Integer> seen = multi ? new java.util.HashSet<>() : null;   // a box listed in several cells
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                int[] list = s.grid.get(cellKey(cx, cz));
                if (list == null) continue;
                for (int i : list) {
                    if (!s.boxes[i].intersects(query) || (multi && !seen.add(i))) continue;
                    into.add(s.shapes[i]);
                    added++;
                }
            }
        }
        return added;
    }

    /** Horizontal margin for the pathfinding questions: a box clipping a cell's edge does not count. */
    private static final double PATH_MARGIN = 0.2;
    /** A floor this thin at the top of a cell is the cell above's floor, not a wall in this one. */
    private static final double PATH_SLIVER = 0.2;

    /**
     * Pathfinding: does ULTRAKILL's geometry fill block cell (x,y,z), the way a solid block
     * would? Checked without allocating (pathfinding asks this thousands of times per path).
     */
    public static boolean blocksCell(int x, int y, int z) {
        Snapshot s = current;
        if (s.boxes.length == 0) return false;
        double x0 = x + PATH_MARGIN, x1 = x + 1 - PATH_MARGIN, z0 = z + PATH_MARGIN, z1 = z + 1 - PATH_MARGIN;
        double y0 = y + PATH_SLIVER, y1 = y + 1 - 0.01;
        for (int i : s.big) if (overlaps(s.boxes[i], x0, y0, z0, x1, y1, z1)) return true;
        int[] list = s.grid.get(cellKey(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)));
        if (list != null) for (int i : list) if (overlaps(s.boxes[i], x0, y0, z0, x1, y1, z1)) return true;
        return false;
    }

    /**
     * Pathfinding: the highest ULTRAKILL surface in column (x,z) between {@code yMin} and
     * {@code yMax}, or NaN. Minecraft's floor level comes from the block below's collision shape,
     * which is empty over an ULTRAKILL floor.
     */
    public static double topIn(int x, int z, double yMin, double yMax) {
        Snapshot s = current;
        double best = Double.NaN;
        if (s.boxes.length == 0) return best;
        double x0 = x + PATH_MARGIN, x1 = x + 1 - PATH_MARGIN, z0 = z + PATH_MARGIN, z1 = z + 1 - PATH_MARGIN;
        for (int pass = 0; pass < 2; pass++) {
            int[] list = pass == 0 ? s.big : s.grid.get(cellKey(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)));
            if (list == null) continue;
            for (int i : list) {
                AABB b = s.boxes[i];
                if (b.maxX <= x0 || b.minX >= x1 || b.maxZ <= z0 || b.minZ >= z1) continue;
                if (b.maxY <= yMin || b.maxY > yMax) continue;
                if (Double.isNaN(best) || b.maxY > best) best = b.maxY;
            }
        }
        return best;
    }

    private static boolean overlaps(AABB b, double x0, double y0, double z0, double x1, double y1, double z1) {
        return b.maxX > x0 && b.minX < x1 && b.maxY > y0 && b.minY < y1 && b.maxZ > z0 && b.minZ < z1;
    }

    /** Called by the mixin each time it actually added a shape. Diagnostics only. */
    public static void countInjection() {
        injections++;   // racy across threads, which is fine for a rough counter
    }

    /** A ray hit on an ULTRAKILL box: segment parameter t in [0,1], point, and the face's axis and sign. */
    public record RayHit(double t, double x, double y, double z, int axis, int sign) {}

    /**
     * Nearest ULTRAKILL box the segment {@code from -> to} enters, or null. Slab test per box;
     * a box the segment starts inside is skipped (the eye in a wall should not block the view).
     * The face is the axis of the last slab entered, its sign the side it was entered from.
     */
    public static RayHit raycast(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        Snapshot s = current;
        double[] o = {from.x, from.y, from.z};
        double[] d = {to.x - from.x, to.y - from.y, to.z - from.z};
        RayHit best = null;
        for (AABB b : s.boxes) {
            double[] mn = {b.minX, b.minY, b.minZ}, mx = {b.maxX, b.maxY, b.maxZ};
            double tEnter = Double.NEGATIVE_INFINITY, tExit = Double.POSITIVE_INFINITY;
            int axis = -1, sign = 0;
            boolean miss = false;
            for (int k = 0; k < 3 && !miss; k++) {
                if (Math.abs(d[k]) < 1e-12) {
                    if (o[k] <= mn[k] || o[k] >= mx[k]) miss = true;
                    continue;
                }
                double t1 = (mn[k] - o[k]) / d[k], t2 = (mx[k] - o[k]) / d[k];
                int sg = -1;                       // entering through the min face: normal points -k
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; sg = 1; }
                if (t1 > tEnter) { tEnter = t1; axis = k; sign = sg; }
                if (t2 < tExit) tExit = t2;
                if (tEnter > tExit) miss = true;
            }
            if (miss || axis < 0 || tEnter < 0 || tEnter > 1) continue;
            if (best == null || tEnter < best.t()) {
                best = new RayHit(tEnter, o[0] + d[0] * tEnter, o[1] + d[1] * tEnter, o[2] + d[2] * tEnter, axis, sign);
            }
        }
        return best;
    }

    /** True if any ULTRAKILL box overlaps {@code box} (strictly, like AABB.intersects). */
    public static boolean intersectsAny(AABB box) {
        Snapshot s = current;
        for (int i : s.big) if (s.boxes[i].intersects(box)) return true;
        int x0 = Math.floorDiv((int) Math.floor(box.minX), CELL), x1 = Math.floorDiv((int) Math.floor(box.maxX), CELL);
        int z0 = Math.floorDiv((int) Math.floor(box.minZ), CELL), z1 = Math.floorDiv((int) Math.floor(box.maxZ), CELL);
        for (int cx = x0; cx <= x1; cx++)
            for (int cz = z0; cz <= z1; cz++) {
                int[] list = s.grid.get(cellKey(cx, cz));
                if (list != null) for (int i : list) if (s.boxes[i].intersects(box)) return true;
            }
        return false;
    }

    public static boolean hasShapes() {
        return current.boxes.length > 0;
    }
}
