package dev.minekill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Milestone E: the crosshair targets ULTRAKILL geometry, so blocks can be placed against it.
 *
 * <p>The crosshair raycast ({@code Entity.pick} -> {@code level.clip}) only knows Minecraft
 * blocks. {@link #refine} also casts against the ULTRAKILL boxes and keeps whichever hit is
 * nearer. An ULTRAKILL hit becomes a {@link UltrakillHitResult} whose block position is the EMPTY
 * cell on the near side of the surface, the cell a block placed against it would occupy.
 * Minecraft's own logic then does the right thing with no further patching:
 * <ul>
 *   <li>Right click with a block: the target cell is air, which Minecraft treats as replaceable,
 *       so BlockItem places INTO that cell. The server accepts it: the hit location lies within
 *       one block of the cell centre on every axis (its 1.0000001 check) and within reach.</li>
 *   <li>Left click: Minecraft's attack skips breaking when the target block is air and only
 *       swings, so ULTRAKILL geometry cannot be mined.</li>
 * </ul>
 * Idea after SkyCraft's SkyClip (MIT), which does the same against Skyrim's triangles.
 */
public final class UkPick {
    private UkPick() {}

    /** A crosshair hit on ULTRAKILL geometry. getBlockPos() is the placement cell (air). */
    public static final class UltrakillHitResult extends BlockHitResult {
        public UltrakillHitResult(Vec3 location, Direction face, BlockPos cell) {
            super(location, face, cell, false);
        }
    }

    public static BlockHitResult refine(Vec3 from, Vec3 to, BlockHitResult vanilla) {
        if (!UkCollision.hasShapes()) return vanilla;
        UkCollision.RayHit hit = UkCollision.raycast(from, to);
        if (hit == null) return vanilla;
        Vec3 location = new Vec3(hit.x(), hit.y(), hit.z());
        if (vanilla.getType() != HitResult.Type.MISS
                && from.distanceToSqr(vanilla.getLocation()) <= from.distanceToSqr(location)) {
            return vanilla;                                  // a real block is nearer
        }
        Direction.Axis axis = Direction.Axis.values()[hit.axis()];
        Direction face = Direction.fromAxisAndDirection(axis,
                hit.sign() > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        // Step off the surface along its normal, into the empty side.
        BlockPos cell = BlockPos.containing(location.x + face.getStepX() * 1e-4,
                location.y + face.getStepY() * 1e-4, location.z + face.getStepZ() * 1e-4);
        return new UltrakillHitResult(location, face, cell);
    }
}
