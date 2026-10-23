package space.niliota.minekill;

import space.niliota.minekill.bridge.Bridge;
import space.niliota.minekill.bridge.Protocol;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Minecraft blocks, solid for ULTRAKILL: the collision boxes of the blocks around the player go
 * to ULTRAKILL (BLOCK_COLLISION), which turns them into colliders and navmesh obstacles for its
 * enemies and projectiles. The Minecraft world is a void, so these are the blocks the player
 * placed. Only non-empty chunk sections near the player are scanned. Full cubes are merged into
 * larger boxes (along x, then z, then y), so a wall is a few boxes, not one per block; any other
 * shape (slabs, stairs, fences, ...) is sent as its own collision boxes. The table is rewritten
 * only when the boxes change.
 */
final class BlockCollisionExport {
    private static final int CHUNK_RANGE = 2;      // chunks around the player's
    private static final int SECTION_RANGE = 2;    // sections above and below the player's
    private static final int SIZE = 16 * (2 * CHUNK_RANGE + 1);
    private static final int HEIGHT = 16 * (2 * SECTION_RANGE + 1);

    private final float[] boxes = new float[Protocol.TBL_BLOCK_COLLISION_CAPACITY * 6];
    private final boolean[] full = new boolean[SIZE * SIZE * HEIGHT];
    private float[] lastSent = new float[0];
    private int frame;
    private long lastWriteMs;

    void frame(Minecraft mc, Bridge bridge) {
        if (++frame % 6 != 0 || mc.level == null || mc.player == null) return;
        int n = collect(mc);
        float[] now = Arrays.copyOf(boxes, n * 6);
        // Unchanged boxes are still re-sent every ~2 s, so ULTRAKILL can tell live data from a
        // table a stopped Minecraft left behind.
        if (Arrays.equals(now, lastSent) && System.currentTimeMillis() - lastWriteMs < 2000) return;
        lastSent = now;
        lastWriteMs = System.currentTimeMillis();
        bridge.writeBlockCollision(System.currentTimeMillis(), n, boxes);
    }

    private int collect(Minecraft mc) {
        var level = mc.level;
        BlockPos pp = mc.player.blockPosition();
        int pcx = pp.getX() >> 4, pcz = pp.getZ() >> 4, psy = pp.getY() >> 4;
        int ox = (pcx - CHUNK_RANGE) * 16, oz = (pcz - CHUNK_RANGE) * 16, oy = (psy - SECTION_RANGE) * 16;
        Arrays.fill(full, false);
        int n = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = pcx - CHUNK_RANGE; cx <= pcx + CHUNK_RANGE; cx++) {
            for (int cz = pcz - CHUNK_RANGE; cz <= pcz + CHUNK_RANGE; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = psy - SECTION_RANGE; sy <= psy + SECTION_RANGE; sy++) {
                    int index = chunk.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= sections.length || sections[index].hasOnlyAir()) continue;
                    LevelChunkSection section = sections[index];
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        pos.set(cx * 16 + x, sy * 16 + y, cz * 16 + z);
                        VoxelShape shape = state.getCollisionShape(level, pos);
                        if (shape.isEmpty()) continue;
                        if (shape == Shapes.block()) {
                            full[idx(pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz)] = true;
                            continue;
                        }
                        for (AABB b : shape.toAabbs()) {
                            if (n >= Protocol.TBL_BLOCK_COLLISION_CAPACITY) return n;
                            put(n++, b.move(pos));
                        }
                    }
                }
            }
        }
        // Greedy merge of the full cubes.
        for (int y = 0; y < HEIGHT; y++) for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            if (!full[idx(x, y, z)]) continue;
            int x1 = x + 1;
            while (x1 < SIZE && full[idx(x1, y, z)]) x1++;
            int z1 = z + 1;
            while (z1 < SIZE && row(x, x1, y, z1)) z1++;
            int y1 = y + 1;
            while (y1 < HEIGHT && rect(x, x1, y1, z, z1)) y1++;
            for (int yy = y; yy < y1; yy++) for (int zz = z; zz < z1; zz++) for (int xx = x; xx < x1; xx++) full[idx(xx, yy, zz)] = false;
            if (n >= Protocol.TBL_BLOCK_COLLISION_CAPACITY) return n;
            put(n++, new AABB(ox + x, oy + y, oz + z, ox + x1, oy + y1, oz + z1));
        }
        return n;
    }

    private boolean row(int x0, int x1, int y, int z) {
        for (int x = x0; x < x1; x++) if (!full[idx(x, y, z)]) return false;
        return true;
    }

    private boolean rect(int x0, int x1, int y, int z0, int z1) {
        for (int z = z0; z < z1; z++) if (!row(x0, x1, y, z)) return false;
        return true;
    }

    private static int idx(int x, int y, int z) {
        return x + z * SIZE + y * SIZE * SIZE;
    }

    private void put(int i, AABB b) {
        int o = i * 6;
        boxes[o] = (float) b.minX; boxes[o + 1] = (float) b.minY; boxes[o + 2] = (float) b.minZ;
        boxes[o + 3] = (float) b.maxX; boxes[o + 4] = (float) b.maxY; boxes[o + 5] = (float) b.maxZ;
    }
}
