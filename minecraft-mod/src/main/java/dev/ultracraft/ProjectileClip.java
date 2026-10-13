package dev.ultracraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Projectiles (arrows, tridents, snowballs, eggs, pearls, potions, ...) do not move by collision:
 * each tick they trace a segment against blocks ({@code Level.clipIncludingBorder}) and hit
 * whatever it meets first. This traces the same segment against ULTRAKILL's boxes and, when one
 * is nearer than Minecraft's block hit, returns a block hit on it, so the projectile hits, sticks
 * in or breaks on ULTRAKILL's walls and floors. Server and client both trace; the boxes are an
 * immutable snapshot readable from either thread.
 */
public final class ProjectileClip {
    private ProjectileClip() {}

    public static BlockHitResult clip(Level level, ClipContext ctx) {
        return nearer(level.clipIncludingBorder(ctx), ctx);
    }

    /** Same, for {@code Level.clip} callers (what the player aims a bucket, boat, ... at). */
    public static BlockHitResult clipNoBorder(Level level, ClipContext ctx) {
        return nearer(level.clip(ctx), ctx);
    }

    private static BlockHitResult nearer(BlockHitResult mc, ClipContext ctx) {
        if (!UkCollision.hasShapes()) return mc;
        Vec3 from = ctx.getFrom(), to = ctx.getTo();
        UkCollision.RayHit h = UkCollision.raycast(from, to);
        if (h == null) return mc;
        Vec3 p = new Vec3(h.x(), h.y(), h.z());
        if (mc.getType() != HitResult.Type.MISS && mc.getLocation().distanceToSqr(from) <= p.distanceToSqr(from)) return mc;
        Direction face = Direction.fromAxisAndDirection(Direction.Axis.values()[h.axis()],
                h.sign() > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        // The cell just inside the surface, like a block hit's position.
        BlockPos cell = BlockPos.containing(p.x - face.getStepX() * 0.01, p.y - face.getStepY() * 0.01, p.z - face.getStepZ() * 0.01);
        return new BlockHitResult(p, face, cell, false);
    }
}
