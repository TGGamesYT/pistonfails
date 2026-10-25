package dev.tggamesyt.pistonfails.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Replays a piston's "extend" or "retract" attempt on the client world and works out why it fails.
 * <p>
 * The server never tells the client about a failed attempt (it simply does nothing), and the piston code
 * is gated behind {@code !level.isClientSide()}. But everything the attempt depends on, the block states
 * around the piston, is replicated to the client, so the attempt can be reproduced locally.
 */
public final class PistonAnalyzer {
    /** Vanilla's push limit: a structure of more than this many blocks cannot be moved. */
    public static final int MAX_PUSH = PistonStructureResolver.MAX_PUSH_DEPTH;

    private PistonAnalyzer() {
    }

    /**
     * @param canExtend whether the piston would be able to move the structure right now (then {@code chain} is exactly what it moves)
     * @param blockers  the immovable block(s) that make the move impossible (empty when the structure is simply too big)
     * @param chain     the blocks that would have been moved if the blockers were not there; when
     *                  {@code overLimit} is set this is the whole structure
     * @param structureSize number of blocks in the structure
     * @param overLimit true when the move fails only because the structure has more than 12 blocks
     */
    public record Result(boolean canExtend, List<BlockPos> blockers, List<BlockPos> chain, int structureSize, boolean overLimit) {
    }

    /**
     * A piston base as the player sees it.
     *
     * @param pos      where the base is (for a base that is being carried by another piston: where it is going to)
     * @param extended whether the base is extended (its head is out)
     * @param sticky   whether it is a sticky piston (only those pull blocks back)
     * @param moving   whether the base is currently a moving block (carried by another piston, or retracting)
     */
    public record PistonInfo(BlockPos pos, Direction facing, boolean extended, boolean sticky, boolean moving) {
    }

    /** Same check vanilla's {@code PistonBaseBlock#getNeighborSignal} does, which is private. */
    public static boolean isPowered(Level level, BlockPos pos, Direction facing) {
        for (Direction direction : Direction.values()) {
            if (direction != facing && level.hasSignal(pos.relative(direction), direction)) {
                return true;
            }
        }
        if (level.hasSignal(pos, Direction.DOWN)) {
            return true;
        }
        BlockPos above = pos.above();
        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN && level.hasSignal(above.relative(direction), direction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The piston base at {@code pos}: a normal block, or a base that is currently being moved (a flying machine
     * carrying it, or the base of a retracting piston). Null when there is no piston base there.
     */
    public static PistonInfo pistonAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof PistonBaseBlock) {
            return new PistonInfo(pos.immutable(), state.getValue(PistonBaseBlock.FACING),
                    state.getValue(PistonBaseBlock.EXTENDED), state.is(Blocks.STICKY_PISTON), false);
        }
        if (state.is(Blocks.MOVING_PISTON) && level.getBlockEntity(pos) instanceof PistonMovingBlockEntity moving) {
            BlockState carried = moving.getMovedState();
            if (carried.getBlock() instanceof PistonBaseBlock) {
                return new PistonInfo(pos.immutable(), carried.getValue(PistonBaseBlock.FACING),
                        carried.getValue(PistonBaseBlock.EXTENDED), carried.is(Blocks.STICKY_PISTON), true);
            }
        }
        return null;
    }

    /**
     * The position of the piston base that a clicked block belongs to: the base itself, the head of an extended
     * piston (which is a separate block one cell away), or a moving block that is a piston base / head in transit.
     * Null when the block is not part of a piston.
     */
    public static BlockPos baseOf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof PistonBaseBlock) {
            return pos.immutable();
        }
        if (state.getBlock() instanceof PistonHeadBlock) {
            return baseBehindHead(level, pos, state);
        }
        if (state.is(Blocks.MOVING_PISTON) && level.getBlockEntity(pos) instanceof PistonMovingBlockEntity moving) {
            BlockState carried = moving.getMovedState();
            if (carried.getBlock() instanceof PistonBaseBlock) {
                return pos.immutable();
            }
            if (carried.getBlock() instanceof PistonHeadBlock) {
                return baseBehindHead(level, pos, carried);
            }
        }
        return null;
    }

    private static BlockPos baseBehindHead(Level level, BlockPos headPos, BlockState headState) {
        BlockPos base = headPos.relative(headState.getValue(PistonHeadBlock.FACING).getOpposite());
        return pistonAt(level, base) != null ? base.immutable() : null;
    }

    /**
     * Whether a sticky piston that retracts right now would even try to pull the block behind its head. This is
     * the check in vanilla's {@code PistonBaseBlock#triggerEvent}: if it fails the piston just retracts and
     * nothing is pulled, which is not a failure.
     */
    public static boolean wouldAttemptPull(Level level, BlockPos basePos, Direction facing) {
        BlockPos twoPos = basePos.relative(facing, 2);
        BlockState state = level.getBlockState(twoPos);
        if (state.isAir() || state.is(Blocks.MOVING_PISTON)) {
            return false;
        }
        if (!PistonBaseBlock.isPushable(state, level, twoPos, facing.getOpposite(), false, facing)) {
            return false;
        }
        return state.getPistonPushReaction() == PushReaction.PUSH_PULL
                || state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON);
    }

    /** What a piston at {@code pistonPos} pushes when it extends. {@code pistonPos} may be an empty cell. */
    public static Result analyze(Level level, BlockPos pistonPos, Direction facing) {
        // The verdict always comes from the real game logic, so modded blocks / rules stay correct.
        boolean canExtend = new PistonStructureResolver(level, pistonPos, facing, true).resolve();

        // The real resolver only says "yes" or "no". The tracer is a copy of it that remembers the structure
        // and where it said no. It has no 12 block limit (only a large safety cap), so it can tell "immovable
        // block in the way" apart from "structure too big" and count the whole structure.
        // {@code pistonPos} does not have to hold a piston: it can be an empty cell, which is how the
        // "what would a piston here push" preview works.
        Trace trace = new Trace(level, pistonPos, facing, true);
        boolean traced = trace.resolve();
        return toResult(trace, traced, canExtend);
    }

    /**
     * What a sticky piston at {@code basePos} pulls back when it retracts. Works whether the piston is extended
     * (the head is still out: it is treated as already gone, exactly as vanilla removes it first) or already
     * retracted. Returns null when the piston would not even try to pull anything (see {@link #wouldAttemptPull}).
     */
    public static Result analyzePull(Level level, BlockPos basePos, Direction facing) {
        if (!wouldAttemptPull(level, basePos, facing)) {
            return null;
        }
        Trace trace = new Trace(level, basePos, facing, false);
        boolean traced = trace.resolve();
        // The verdict comes from the trace here: the real resolver would see the head that vanilla removes first.
        boolean canMove = traced && !trace.capped && new LinkedHashSet<>(trace.toPush).size() <= MAX_PUSH;
        return toResult(trace, traced, canMove);
    }

    private static Result toResult(Trace trace, boolean traced, boolean canMove) {
        List<BlockPos> structure = new ArrayList<>(new LinkedHashSet<>(trace.toPush));

        if (canMove) {
            return new Result(true, List.of(), List.copyOf(structure), structure.size(), false);
        }

        if (trace.blockers.isEmpty() && (trace.capped || (traced && structure.size() > MAX_PUSH))) {
            return new Result(false, List.of(), List.copyOf(structure), structure.size(), true);
        }

        List<BlockPos> blockers = new ArrayList<>(trace.blockers);
        if (blockers.isEmpty()) {
            // Should not happen, but never leave the player with nothing to look at.
            blockers.add(trace.startPos);
        }
        List<BlockPos> chain = new ArrayList<>();
        for (BlockPos pos : structure) {
            if (!blockers.contains(pos)) {
                chain.add(pos);
            }
        }
        return new Result(false, List.copyOf(blockers), List.copyOf(chain), chain.size(), false);
    }

    /** True when the only thing in the way is a block that is currently mid-move (it will be gone in a tick or two). */
    public static boolean onlyBlockedByMovingPiston(Level level, List<BlockPos> blockers) {
        for (BlockPos pos : blockers) {
            if (!level.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                return false;
            }
        }
        return !blockers.isEmpty();
    }

    /**
     * Mirror of {@link PistonStructureResolver} that records the blocks causing a failure. It handles both
     * directions: extending (push, starts right in front of the piston) and retracting (pull, starts behind the head).
     */
    private static final class Trace {
        /** Safety cap so a huge slime structure cannot make the trace expensive. Far above the real limit. */
        private static final int TRACE_CAP = 1024;

        private final Level level;
        private final BlockPos pistonPos;
        private final boolean extending;
        /** The direction the blocks move in. */
        private final Direction pushDir;
        /** The direction the piston faces. */
        private final Direction pistonDir;
        final BlockPos startPos;
        /** The cell of the head. When retracting, vanilla removes the head before resolving, so it counts as air. */
        private final BlockPos armPos;
        final List<BlockPos> toPush = new ArrayList<>();
        final Set<BlockPos> blockers = new LinkedHashSet<>();
        /** Set when the trace gave up because the structure passed {@link #TRACE_CAP}. */
        boolean capped;

        Trace(Level level, BlockPos pistonPos, Direction facing, boolean extending) {
            this.level = level;
            this.pistonPos = pistonPos;
            this.extending = extending;
            this.pistonDir = facing;
            this.armPos = pistonPos.relative(facing);
            if (extending) {
                this.pushDir = facing;
                this.startPos = pistonPos.relative(facing);
            } else {
                this.pushDir = facing.getOpposite();
                this.startPos = pistonPos.relative(facing, 2);
            }
        }

        private BlockState stateAt(BlockPos pos) {
            if (!extending && pos.equals(armPos)) {
                return Blocks.AIR.defaultBlockState();
            }
            return level.getBlockState(pos);
        }

        boolean resolve() {
            BlockState nextState = stateAt(startPos);
            if (!PistonBaseBlock.isPushable(nextState, level, startPos, pushDir, false, pistonDir)) {
                if (extending && nextState.getPistonPushReaction() == PushReaction.POPPED) {
                    return true;
                }
                blockers.add(startPos);
                return false;
            }
            if (!addBlockLine(startPos, pushDir)) {
                return false;
            }
            for (int i = 0; i < toPush.size(); i++) {
                BlockPos pos = toPush.get(i);
                if (isSticky(stateAt(pos)) && !addBranchingBlocks(pos)) {
                    return false;
                }
            }
            return true;
        }

        private boolean addBlockLine(BlockPos start, Direction direction) {
            BlockState nextState = stateAt(start);
            if (nextState.isAir()) {
                return true;
            }
            if (!PistonBaseBlock.isPushable(nextState, level, start, pushDir, false, direction)) {
                return true;
            }
            if (start.equals(pistonPos) || toPush.contains(start)) {
                return true;
            }

            int blockCount = 1;
            if (blockCount + toPush.size() > TRACE_CAP) {
                capped = true;
                return false;
            }
            while (isSticky(nextState)) {
                BlockPos pos = start.relative(pushDir.getOpposite(), blockCount);
                BlockState previousState = nextState;
                nextState = stateAt(pos);
                if (nextState.isAir()
                        || !canStickToEachOther(previousState, nextState)
                        || !PistonBaseBlock.isPushable(nextState, level, pos, pushDir, false, pushDir.getOpposite())
                        || pos.equals(pistonPos)) {
                    break;
                }
                if (++blockCount + toPush.size() > TRACE_CAP) {
                    capped = true;
                    return false;
                }
            }

            int blocksAdded = 0;
            for (int i = blockCount - 1; i >= 0; i--) {
                toPush.add(start.relative(pushDir.getOpposite(), i));
                blocksAdded++;
            }

            int i = 1;
            while (true) {
                BlockPos pos = start.relative(pushDir, i);
                int collisionPos = toPush.indexOf(pos);
                if (collisionPos > -1) {
                    reorderListAtCollision(blocksAdded, collisionPos);
                    for (int j = 0; j <= collisionPos + blocksAdded; j++) {
                        BlockPos blockPos = toPush.get(j);
                        if (isSticky(stateAt(blockPos)) && !addBranchingBlocks(blockPos)) {
                            return false;
                        }
                    }
                    return true;
                }

                nextState = stateAt(pos);
                if (nextState.isAir()) {
                    return true;
                }
                if (!PistonBaseBlock.isPushable(nextState, level, pos, pushDir, true, pushDir) || pos.equals(pistonPos)) {
                    blockers.add(pos);
                    return false;
                }
                if (nextState.getPistonPushReaction() == PushReaction.POPPED) {
                    return true;
                }
                if (toPush.size() >= TRACE_CAP) {
                    capped = true;
                    return false;
                }
                toPush.add(pos);
                blocksAdded++;
                i++;
            }
        }

        private void reorderListAtCollision(int blocksAdded, int collisionPos) {
            List<BlockPos> head = new ArrayList<>(toPush.subList(0, collisionPos));
            List<BlockPos> lastLineAdded = new ArrayList<>(toPush.subList(toPush.size() - blocksAdded, toPush.size()));
            List<BlockPos> collisionToLine = new ArrayList<>(toPush.subList(collisionPos, toPush.size() - blocksAdded));
            toPush.clear();
            toPush.addAll(head);
            toPush.addAll(lastLineAdded);
            toPush.addAll(collisionToLine);
        }

        private boolean addBranchingBlocks(BlockPos fromPos) {
            BlockState fromState = stateAt(fromPos);
            for (Direction direction : Direction.values()) {
                if (direction.getAxis() != pushDir.getAxis()) {
                    BlockPos neighbourPos = fromPos.relative(direction);
                    BlockState neighbourState = stateAt(neighbourPos);
                    if (canStickToEachOther(neighbourState, fromState) && !addBlockLine(neighbourPos, direction)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static boolean isSticky(BlockState state) {
            return state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK);
        }

        private static boolean canStickToEachOther(BlockState a, BlockState b) {
            if (a.is(Blocks.HONEY_BLOCK) && b.is(Blocks.SLIME_BLOCK)) {
                return false;
            }
            if (a.is(Blocks.SLIME_BLOCK) && b.is(Blocks.HONEY_BLOCK)) {
                return false;
            }
            return isSticky(a) || isSticky(b);
        }
    }
}