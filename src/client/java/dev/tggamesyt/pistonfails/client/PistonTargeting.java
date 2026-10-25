package dev.tggamesyt.pistonfails.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Works out which piston the player means when they click.
 * <p>
 * A piston is often hard to hit exactly: it is out of reach, it is one of two separate blocks (base and head),
 * or it is part of a flying machine and only occupies a given cell for a tick or two. So besides the block the
 * crosshair is exactly on, this also looks for a piston the crosshair is <i>almost</i> on.
 */
public final class PistonTargeting {
    /** Distance between two samples along the view ray. */
    private static final double STEP = 0.25;
    /** A fuzzy hit this far (or closer) from the ray counts, at the player's face. */
    private static final double BASE_TOLERANCE = 0.55;
    /** The tolerance grows with distance (a far piston is a tiny target), up to {@link #MAX_TOLERANCE}. */
    private static final double TOLERANCE_PER_BLOCK = 0.012;
    private static final double MAX_TOLERANCE = 1.1;

    private PistonTargeting() {
    }

    /**
     * @param exact         the block the game itself says the crosshair is on (inside reach), or null to look it up
     * @param includeStatic when false only pistons that are moving, or changed in the last moments, may be picked
     *                      by the fuzzy search. That keeps a lever or button next to a resting piston clickable;
     *                      a click that hits a piston exactly always counts.
     * @return the position of the piston base (see {@link PistonAnalyzer#baseOf}) or null
     */
    public static BlockPos find(Level level, Player player, float partialTick, double maxDistance,
                                BlockHitResult exact, boolean includeStatic) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);

        if (exact == null) {
            HitResult far = player.pick(maxDistance, partialTick, false);
            if (far instanceof BlockHitResult blockHit && far.getType() == HitResult.Type.BLOCK) {
                exact = blockHit;
            }
        }

        double limit = maxDistance;
        if (exact != null) {
            BlockPos direct = PistonAnalyzer.baseOf(level, exact.getBlockPos());
            if (direct != null) {
                return direct;
            }
            // Anything the player is almost pointing at has to be in front of the block they are pointing at.
            limit = Math.min(limit, eye.distanceTo(exact.getLocation()));
        }

        Vec3 end = eye.add(look.scale(limit));
        Set<Long> seen = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (double t = 0; t <= limit + STEP; t += STEP) {
            Vec3 sample = eye.add(look.scale(t));
            int bx = (int) Math.floor(sample.x);
            int by = (int) Math.floor(sample.y);
            int bz = (int) Math.floor(sample.z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = bx + dx;
                        int y = by + dy;
                        int z = bz + dz;
                        if (!seen.add(BlockPos.asLong(x, y, z))) {
                            continue;
                        }
                        cursor.set(x, y, z);
                        BlockPos base = PistonAnalyzer.baseOf(level, cursor);
                        if (base == null) {
                            continue;
                        }
                        if (!includeStatic && !level.getBlockState(cursor).is(Blocks.MOVING_PISTON)
                                && !PistonFailTracker.isRecentlyActive(cursor)) {
                            continue;
                        }

                        Vec3 center = new Vec3(x + 0.5, y + 0.5, z + 0.5);
                        double along = Math.max(0.0, Math.min(limit, center.subtract(eye).dot(look)));
                        double score;
                        Optional<Vec3> inside = new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0).clip(eye, end);
                        if (inside.isPresent()) {
                            score = eye.distanceTo(inside.get()); // on the block itself: nearest wins
                        } else {
                            double lateral = center.distanceTo(eye.add(look.scale(along)));
                            double tolerance = Math.min(MAX_TOLERANCE, BASE_TOLERANCE + TOLERANCE_PER_BLOCK * along);
                            if (lateral > tolerance) {
                                continue;
                            }
                            score = 1000.0 + lateral; // near misses rank behind every direct hit
                        }
                        if (score < bestScore) {
                            bestScore = score;
                            best = base;
                        }
                    }
                }
            }
        }
        return best;
    }
}