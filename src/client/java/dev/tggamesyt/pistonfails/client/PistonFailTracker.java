package dev.tggamesyt.pistonfails.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notices when something next to a piston changed, replays the piston's attempt on the client
 * world, and highlights what stopped it for a few seconds.
 * <p>
 * Each failing piston gets one merged highlight (a single outline and fill around the whole group of blocks,
 * not one box per block). The highlights are re-emitted every tick instead of being handed to the gizmo
 * system as long-lived objects, so a repeated failed push replaces the old highlight and restarts its timer
 * at any moment, without stacking transparent layers.
 * <p>
 * While a highlight is visible it keeps watching its blocks (and the blocks next to them): when one changes,
 * the structure is analysed again, the count is updated, the timer restarts, and the highlight turns green as
 * soon as the structure fits (12 blocks or fewer).
 * <p>
 * Highlights follow their blocks: when a piston (a flying machine, say) moves highlighted blocks, the highlight
 * moves to where they went, waits for them to arrive, and keeps the time it had left instead of starting over.
 * If the piston that was being analysed can still say something about the blocks at their new place (it was
 * itself carried along and is retracted again) they are analysed again there; if it cannot (the piston just
 * pushed or pulled them and is extended or retracted now) the highlight stays on the blocks as a {@link Kind#MOVED}
 * snapshot instead of disappearing.
 * <p>
 * Besides failed pushes there are failed pulls (a sticky piston that retracts but cannot pull the blocks behind
 * its head, shown as a snapshot of what stopped it) and previews: not tied to a failure, they show what a piston
 * would push (see {@link PistonPlacementPreview}) or, for an extended sticky piston, what it would pull back.
 */
public final class PistonFailTracker {
    /** How long a highlight stays visible (it fades out over this time). */
    public static final int HIGHLIGHT_MS = 4000;
    /** Pistons further than this from the player are ignored. */
    public static final int MAX_DISTANCE = 64;
    /** More block changes than this in a single tick (world edits, big structure loads) are ignored. */
    private static final int MAX_CHANGES_PER_TICK = 4096;
    /** A piston part that changed within this many client ticks counts as "moving" for the fuzzy click search. */
    private static final int RECENT_TICKS = 12;
    private static final int MAX_RECENT = 8192;
    /** How many ticks to wait for the block entity of a block that just became a moving piston. */
    private static final int BLOCK_ENTITY_WAIT_TICKS = 3;

    /** Offset of the filled faces away from the blocks, so they do not z-fight with the block faces. */
    private static final double FACE_OFFSET = 0.005;
    private static final float LABEL_SCALE = 0.5F;

    private static final int RED_STROKE = 0xFFFF3030;
    private static final int RED_FILL = 0x40FF2020;
    private static final int ORANGE_STROKE = 0xFFFFA500;
    private static final int ORANGE_FILL = 0x20FFA500;
    private static final int GREEN_STROKE = 0xFF30FF30;
    private static final int GREEN_FILL = 0x4030FF30;
    private static final int LABEL_COLOR = 0xFFFFFFFF;

    /**
     * Blocks that changed since the last tick, with the state they had <i>before</i> (the first change of the tick
     * wins). The old state is what lets the tracker see where a moved block went.
     */
    private static final Map<Long, BlockState> CHANGED = new HashMap<>();
    /**
     * Per block that changed since the last tick: the first <i>real</i> block (not air, not a moving piston) that
     * stood there before it changed. It differs from {@link #CHANGED} when a block lands and is pushed again within
     * one tick (a fast machine): the cell then goes moving piston, real block, moving piston, and only the real
     * block says which block left. Without it a highlight on such a block is left behind and ends up offset.
     */
    private static final Map<Long, BlockState> LEFT_BLOCK = new HashMap<>();
    /**
     * Every recorded change gets a sequence number. {@link #CHANGED_SEQ} holds, per cell, the number of its most
     * recent change and {@link #LEFT_SEQ} the number of the most recent real block that left it. A move is
     * followed exactly once: only when one of the two changes it is made of is newer than the last follow.
     */
    private static final Map<Long, Long> CHANGED_SEQ = new HashMap<>();
    private static final Map<Long, Long> LEFT_SEQ = new HashMap<>();
    private static long changeSeq;
    private static long followedSeq;
    /**
     * Highlights that were moved by a piston and are now only a snapshot ({@link Kind#MOVED}). They live in their
     * own list, not in {@link #HIGHLIGHTS}, so a new failure of the piston they once belonged to (which is
     * stored under that piston's position) can no longer replace a snapshot that is still on screen.
     */
    private static final List<Highlight> SNAPSHOTS = new ArrayList<>();
    /** Whether a piston that has a push-failure highlight was powered the last time we looked (to see it being activated again). */
    private static final Map<Long, Boolean> LAST_POWERED = new HashMap<>();
    /** Moving blocks whose block entity has not arrived yet, with the number of ticks waited. */
    private static final Map<Long, Integer> PENDING_ENTITY = new HashMap<>();
    /** Piston parts that changed recently: position to the tick it happened in. */
    private static final Map<Long, Long> RECENT = new HashMap<>();
    /** Active highlights, keyed by the position of the piston they belong to. */
    private static final Map<Long, Highlight> HIGHLIGHTS = new HashMap<>();
    /** The "what would this piston push / pull" highlight, or null. Only one exists at a time. */
    private static Highlight preview;
    private static ClientLevel lastLevel;
    private static long ticks;

    private PistonFailTracker() {
    }

    /** Called by the {@code ClientLevel} mixin for every block change the client sees. */
    public static void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        long key = pos.asLong();
        if (CHANGED.size() <= MAX_CHANGES_PER_TICK) {
            long seq = ++changeSeq;
            CHANGED.putIfAbsent(key, oldState);
            CHANGED_SEQ.put(key, seq);
            if (!oldState.isAir() && !oldState.is(Blocks.MOVING_PISTON)) {
                LEFT_BLOCK.putIfAbsent(key, oldState);
                LEFT_SEQ.put(key, seq);
            }
        }
        if (RECENT.size() < MAX_RECENT && (isPistonPart(oldState) || isPistonPart(newState))) {
            RECENT.put(key, ticks);
        }
    }

    private static boolean isPistonPart(BlockState state) {
        return state.getBlock() instanceof PistonBaseBlock
                || state.getBlock() instanceof PistonHeadBlock
                || state.is(Blocks.MOVING_PISTON);
    }

    /** True when the block at {@code pos} was a piston part that changed in the last moments (it is moving). */
    public static boolean isRecentlyActive(BlockPos pos) {
        Long tick = RECENT.get(pos.asLong());
        return tick != null && ticks - tick <= RECENT_TICKS;
    }

    public static void clear() {
        CHANGED.clear();
        LEFT_BLOCK.clear();
        CHANGED_SEQ.clear();
        LEFT_SEQ.clear();
        followedSeq = changeSeq;
        PENDING_ENTITY.clear();
        RECENT.clear();
        HIGHLIGHTS.clear();
        SNAPSHOTS.clear();
        LAST_POWERED.clear();
        preview = null;
        lastLevel = null;
    }

    /**
     * Shows what a piston placed at {@code hitPos.relative(face)} would push into {@code hitPos}: the block
     * itself plus everything attached to it. Calling it again replaces the preview and restarts its timer.
     */
    public static void showPreview(ClientLevel level, BlockPos hitPos, Direction face) {
        followMovement(level); // blocks that already moved must not be followed again after this highlight exists
        BlockPos origin = hitPos.relative(face);
        Direction facing = face.getOpposite();
        preview = Highlight.build(PistonAnalyzer.analyze(level, origin, facing), origin, facing,
                Kind.PLACEMENT_PREVIEW, Util.getMillis() + HIGHLIGHT_MS, false);
    }

    /**
     * Shows what the piston at {@code pistonPos} would do if it activated right now, whether or not it is powered:
     * what a retracted piston would push, or what an extended sticky piston would pull back. It works on the base,
     * on the head of an extended piston, and on a piston that is being moved (the highlight follows it).
     *
     * @return whether anything was shown (false for a piston with nothing to push or pull)
     */
    public static boolean showPistonPreview(ClientLevel level, BlockPos pistonPos) {
        followMovement(level);
        BlockPos base = PistonAnalyzer.baseOf(level, pistonPos);
        PistonAnalyzer.PistonInfo info = base == null ? null : PistonAnalyzer.pistonAt(level, base);
        if (info == null) {
            return false;
        }
        HIGHLIGHTS.remove(base.asLong()); // the preview replaces it, never stack two on the same piston
        long expiresAt = Util.getMillis() + HIGHLIGHT_MS;

        if (info.extended()) {
            if (!info.sticky()) {
                return false; // a plain piston just lets go: nothing is pulled
            }
            PistonAnalyzer.Result result = PistonAnalyzer.analyzePull(level, base, info.facing());
            if (result == null) {
                return false; // nothing pullable behind the head
            }
            preview = Highlight.build(result, base, info.facing(), Kind.PULL_PREVIEW, expiresAt, false);
            return true;
        }
        preview = Highlight.build(PistonAnalyzer.analyze(level, base, info.facing()), base, info.facing(),
                Kind.PUSH_PREVIEW, expiresAt, false);
        return true;
    }

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        if (level == null || player == null) {
            clear();
            return;
        }
        if (level != lastLevel) { // dimension change: positions queued for the old level mean nothing here
            clear();
            lastLevel = level;
            return;
        }
        ticks++;

        long now = Util.getMillis();
        HIGHLIGHTS.values().removeIf(highlight -> highlight.expiresAt <= now);
        if (preview != null && preview.expiresAt <= now) {
            preview = null;
        }
        SNAPSHOTS.removeIf(highlight -> highlight.expiresAt <= now);
        LAST_POWERED.keySet().removeIf(key -> !HIGHLIGHTS.containsKey(key));
        RECENT.values().removeIf(tick -> ticks - tick > RECENT_TICKS * 3L);

        processChanges(level, player, now);
    }

    /**
     * Draws every highlight. Called once per rendered frame (not per tick) by the {@code LevelExtractor} mixin,
     * just before the frame's gizmos are collected, because a highlight that follows moving blocks has to move
     * smoothly with them: the highlights are emitted again every frame instead of being handed to the gizmo
     * system once per tick, so a repeated failed push still replaces the old highlight without stacking layers.
     */
    public static void emitFrame(LevelExtractor extractor) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || level != lastLevel) {
            return;
        }
        // Block changes arrive between ticks. Follow them right now, in this frame, instead of waiting for the
        // end of the tick: otherwise the highlight sits on the old cells for up to a tick while the world
        // already shows the new ones.
        followMovement(level);
        if (HIGHLIGHTS.isEmpty() && SNAPSHOTS.isEmpty() && preview == null) {
            return;
        }
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long now = Util.getMillis();
        try (Gizmos.TemporaryCollection ignored = extractor.collectPerFrameMainThreadGizmos()) {
            for (Highlight highlight : HIGHLIGHTS.values()) {
                if (highlight.expiresAt > now) {
                    highlight.emit(now, level, partialTick);
                }
            }
            for (Highlight highlight : SNAPSHOTS) {
                if (highlight.expiresAt > now) {
                    highlight.emit(now, level, partialTick);
                }
            }
            if (preview != null && preview.expiresAt > now) {
                preview.emit(now, level, partialTick);
            }
        }
    }

    /** Where a group of moved blocks is going: the cell, the direction of the move and the block it carries. */
    private record Anchor(BlockPos cell, Direction direction, Block block) {
    }

    /**
     * How far the highlighted blocks are from the cell they are travelling to right now. It is read from the one
     * moving block the highlight was attached to when it followed the move (never from "whatever moving block is
     * in these cells"), and only while that cell still holds a moving block that really is that move: same
     * direction, same carried block. That is exactly the offset vanilla renders the block with, so a highlight
     * shifted by it stays on top of the block for the whole animation. Zero once the block has arrived.
     */
    private static Vec3 offsetOf(ClientLevel level, Anchor anchor, float partialTick) {
        if (anchor == null || !level.getBlockState(anchor.cell()).is(Blocks.MOVING_PISTON)) {
            return Vec3.ZERO;
        }
        if (level.getBlockEntity(anchor.cell()) instanceof PistonMovingBlockEntity moving) {
            if (moving.getMovementDirection() != anchor.direction() || !moving.getMovedState().is(anchor.block())) {
                return Vec3.ZERO; // another move took this cell: ours is over
            }
            return new Vec3(moving.getXOff(partialTick), moving.getYOff(partialTick), moving.getZOff(partialTick));
        }
        // The moving block is there but its block entity is still on its way: the move has not started, and a
        // block starts one cell behind its destination.
        Direction direction = anchor.direction();
        return new Vec3(-direction.getStepX(), -direction.getStepY(), -direction.getStepZ());
    }

    private static void processChanges(ClientLevel level, LocalPlayer player, long now) {
        if (CHANGED.size() > MAX_CHANGES_PER_TICK) {
            CHANGED.clear();
            LEFT_BLOCK.clear();
            CHANGED_SEQ.clear();
            LEFT_SEQ.clear();
            followedSeq = changeSeq;
            return;
        }
        // Highlights that were moved still need their re-analysis once the blocks have arrived, even in a
        // tick where nothing near them changed.
        boolean waiting = preview != null && preview.moved;
        if (!waiting) {
            for (Highlight highlight : HIGHLIGHTS.values()) {
                if (highlight.moved) {
                    waiting = true;
                    break;
                }
            }
        }
        if (!waiting) {
            for (Highlight snapshot : SNAPSHOTS) {
                if (snapshot.moved) {
                    waiting = true;
                    break;
                }
            }
        }
        if (CHANGED.isEmpty() && PENDING_ENTITY.isEmpty() && !waiting) {
            return;
        }

        followMovement(level);
        detectPullFailures(level, player, now);

        // A piston is told about changes to blocks next to it, and to blocks next to those (strong powering),
        // so every piston within a manhattan distance of 2 of a changed block may have just tried to move.
        Set<Long> pistons = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long packed : CHANGED.keySet()) {
            int cx = BlockPos.getX(packed);
            int cy = BlockPos.getY(packed);
            int cz = BlockPos.getZ(packed);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2) {
                            continue;
                        }
                        cursor.set(cx + dx, cy + dy, cz + dz);
                        if (level.getBlockState(cursor).getBlock() instanceof PistonBaseBlock) {
                            pistons.add(cursor.asLong());
                        }
                    }
                }
            }
        }

        // Highlights that are still visible are analysed again when one of their blocks (or a neighbour)
        // changed, even if that change is far from the piston itself.
        Set<Long> affected = new HashSet<>();
        for (Map.Entry<Long, Highlight> entry : HIGHLIGHTS.entrySet()) {
            if (entry.getValue().moved) {
                affected.add(entry.getKey());
            }
        }
        boolean previewAffected = preview != null && preview.moved;
        for (long packed : CHANGED.keySet()) {
            for (Map.Entry<Long, Highlight> entry : HIGHLIGHTS.entrySet()) {
                if (entry.getValue().watch.contains(packed)) {
                    affected.add(entry.getKey());
                }
            }
            if (preview != null && preview.watch.contains(packed)) {
                previewAffected = true;
            }
        }
        CHANGED.clear();
        LEFT_BLOCK.clear();
        CHANGED_SEQ.clear();
        LEFT_SEQ.clear();

        for (long packed : pistons) {
            // Moved snapshots are not in HIGHLIGHTS: they only follow blocks around, say nothing about this piston
            // any more, and must neither stop a new failure of the piston from being reported nor be replaced by it.
            Highlight existing = HIGHLIGHTS.get(packed);
            if (existing == null) {
                evaluate(level, player, packed, now);
            } else if (existing.kind == Kind.PUSH_FAILURE && !existing.moved) {
                restartIfActivatedAgain(level, packed, now);
            }
        }
        for (long packed : affected) {
            refreshHighlight(level, packed, now);
        }
        if (previewAffected && preview != null && !anyMoving(level, preview)) {
            preview = refresh(level, preview, now);
            if (preview != null && preview.kind == Kind.MOVED) {
                SNAPSHOTS.add(preview);
                preview = null;
            }
        }
        // snapshots whose blocks have arrived stop waiting (and stop being offset)
        for (int i = 0; i < SNAPSHOTS.size(); i++) {
            Highlight snapshot = SNAPSHOTS.get(i);
            if (snapshot.moved && !anyMoving(level, snapshot)) {
                Highlight settled = snapshot(snapshot, Kind.MOVED);
                if (settled != null) {
                    SNAPSHOTS.set(i, settled);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Following blocks that were moved by a piston
    // ---------------------------------------------------------------------------------------------------------

    /** A block that a piston carried from one cell to the next. */
    private record Move(BlockPos to, Direction direction, Block block) {
    }

    /**
     * Where the block that stood at {@code pos} went, if a piston moved it since sequence number {@code since};
     * otherwise null.
     * <p>
     * When a piston moves a block, the block's old cell changes, and the cell next to it, in the direction of the
     * move, becomes a moving piston block that carries the block there. So: this cell changed away from a real
     * block, and a neighbour that changed too is a moving block, going away from this cell, carrying the same
     * kind of block. A move whose changes are all {@code since} or older was already followed.
     */
    private static Move movedTo(ClientLevel level, BlockPos pos, long since) {
        long key = pos.asLong();
        BlockState old = LEFT_BLOCK.get(key);
        if (old == null) {
            return null; // no real block left this cell this tick
        }
        if (level.getBlockState(pos).is(old.getBlock())) {
            return null; // still there (a changed property is not a move)
        }
        long leftSeq = LEFT_SEQ.getOrDefault(key, 0L);
        for (Direction direction : Direction.values()) {
            BlockPos target = pos.relative(direction);
            Long targetSeq = CHANGED_SEQ.get(target.asLong());
            if (targetSeq == null || Math.max(leftSeq, targetSeq) <= since) {
                continue; // nothing changed there, or that move was already followed
            }
            BlockState targetState = level.getBlockState(target);
            if (!targetState.is(Blocks.MOVING_PISTON)) {
                continue;
            }
            if (level.getBlockEntity(target) instanceof PistonMovingBlockEntity moving) {
                if (moving.getMovementDirection() != direction || !moving.getMovedState().is(old.getBlock())) {
                    continue;
                }
            } else if (targetState.getValue(MovingPistonBlock.FACING).getAxis() != direction.getAxis()) {
                continue; // no block entity yet: at least the move has to be along the piston's axis
            }
            return new Move(target.immutable(), direction, old.getBlock());
        }
        return null;
    }

    /** Moves a list of positions to where their blocks went; {@code anchor[0]} gets the first move found. */
    private static List<BlockPos> movedAll(ClientLevel level, List<BlockPos> positions, long since, Anchor[] anchor,
                                           boolean[] any) {
        List<BlockPos> result = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            Move move = movedTo(level, pos, since);
            if (move != null) {
                any[0] = true;
                if (anchor[0] == null) {
                    anchor[0] = new Anchor(move.to(), move.direction(), move.block());
                }
                result.add(move.to());
            } else {
                result.add(pos);
            }
        }
        return result;
    }

    /**
     * Moves every highlight to where its blocks were moved. The remaining time is kept. Runs both every frame and
     * every tick, as soon as there is a block change nobody followed yet, so the highlight is never behind the world.
     * Each move is followed once (see {@link #changeSeq}), however often this is called.
     */
    private static void followMovement(ClientLevel level) {
        if (changeSeq == followedSeq) {
            return;
        }
        long since = followedSeq;
        followedSeq = changeSeq;

        // Take every moved highlight out first and only then put them back under their new keys, so two of them
        // can never overwrite each other halfway through.
        List<Highlight> followedList = new ArrayList<>();
        for (Map.Entry<Long, Highlight> entry : new ArrayList<>(HIGHLIGHTS.entrySet())) {
            Highlight followed = entry.getValue().followed(level, since);
            if (followed != null) {
                HIGHLIGHTS.remove(entry.getKey());
                followedList.add(followed);
            }
        }
        for (Highlight followed : followedList) {
            HIGHLIGHTS.put(followed.origin.asLong(), followed);
        }
        for (int i = 0; i < SNAPSHOTS.size(); i++) {
            Highlight followed = SNAPSHOTS.get(i).followed(level, since);
            if (followed != null) {
                SNAPSHOTS.set(i, followed);
            }
        }
        if (preview != null) {
            Highlight followed = preview.followed(level, since);
            if (followed != null) {
                preview = followed;
            }
        }
    }

    /** True while any block of the highlight is still travelling: the structure cannot be analysed yet. */
    private static boolean anyMoving(ClientLevel level, Highlight highlight) {
        if (level.getBlockState(highlight.origin).is(Blocks.MOVING_PISTON)) {
            return true;
        }
        for (List<BlockPos> group : List.of(highlight.result.chain(), highlight.result.blockers())) {
            for (BlockPos pos : group) {
                if (level.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pushes that fail
    // ---------------------------------------------------------------------------------------------------------

    /** Looks at a piston that has no highlight yet: is it powered and failing to push? */
    private static void evaluate(ClientLevel level, LocalPlayer player, long pistonKey, long now) {
        BlockPos pistonPos = BlockPos.of(pistonKey);
        BlockState state = level.getBlockState(pistonPos);
        if (!(state.getBlock() instanceof PistonBaseBlock) || state.getValue(PistonBaseBlock.EXTENDED)) {
            return; // already extended (or gone): nothing to fail
        }
        if (pistonPos.distSqr(player.blockPosition()) > (double) MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }

        Direction facing = state.getValue(PistonBaseBlock.FACING);
        // A new failure is only reported while powered (the server would not even try otherwise).
        if (!PistonAnalyzer.isPowered(level, pistonPos, facing)) {
            return;
        }

        PistonAnalyzer.Result result = PistonAnalyzer.analyze(level, pistonPos, facing);
        if (result.canExtend()) {
            return; // it will move, the server's block event is on its way
        }
        // A piston that is still mid-animation blocks the next push for a tick or two. The client's animation
        // can be a tick off from the server's, so this is the one case that is not reliable enough to flag.
        if (PistonAnalyzer.onlyBlockedByMovingPiston(level, result.blockers())) {
            return;
        }

        Highlight highlight = Highlight.build(result, pistonPos, facing, Kind.PUSH_FAILURE, now + HIGHLIGHT_MS, false);
        if (highlight != null) {
            HIGHLIGHTS.put(pistonKey, highlight);
            LAST_POWERED.put(pistonKey, true);
            if (preview != null && preview.origin.equals(pistonPos)) {
                preview = null; // this piston has its own highlight now, do not stack a second one
            }
        }
    }

    /**
     * A piston that already shows a failure is activated again: it is switched off and on once more (a fresh
     * rising edge of its power) and it still cannot push. The highlight is built again, which restarts its timer.
     * A piston that simply stays powered, or unrelated changes next to it, do not restart anything.
     */
    private static void restartIfActivatedAgain(ClientLevel level, long pistonKey, long now) {
        BlockPos pistonPos = BlockPos.of(pistonKey);
        BlockState state = level.getBlockState(pistonPos);
        if (!(state.getBlock() instanceof PistonBaseBlock) || state.getValue(PistonBaseBlock.EXTENDED)) {
            LAST_POWERED.remove(pistonKey);
            return;
        }
        Direction facing = state.getValue(PistonBaseBlock.FACING);
        boolean powered = PistonAnalyzer.isPowered(level, pistonPos, facing);
        boolean wasPowered = LAST_POWERED.getOrDefault(pistonKey, true);
        LAST_POWERED.put(pistonKey, powered);
        if (!powered || wasPowered) {
            return;
        }
        PistonAnalyzer.Result result = PistonAnalyzer.analyze(level, pistonPos, facing);
        if (result.canExtend() || PistonAnalyzer.onlyBlockedByMovingPiston(level, result.blockers())) {
            return; // it will move now, or it is too early to tell: the normal update handles those
        }
        Highlight highlight = Highlight.build(result, pistonPos, facing, Kind.PUSH_FAILURE, now + HIGHLIGHT_MS, false);
        if (highlight != null) {
            HIGHLIGHTS.put(pistonKey, highlight);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pulls that fail
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A sticky piston that retracts turns its base into a moving block. If it also moved the blocks behind its head,
     * those changed in the same tick. If they did not (and it would have tried to) the pull failed.
     */
    private static void detectPullFailures(ClientLevel level, LocalPlayer player, long now) {
        if (CHANGED.isEmpty() && PENDING_ENTITY.isEmpty()) {
            return;
        }
        Set<Long> candidates = new HashSet<>(PENDING_ENTITY.keySet());
        candidates.addAll(CHANGED.keySet());

        for (long key : candidates) {
            BlockPos pos = BlockPos.of(key);
            if (!level.getBlockState(pos).is(Blocks.MOVING_PISTON)) {
                PENDING_ENTITY.remove(key);
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity moving)) {
                // The block entity comes in a packet right behind the block. Give it a moment.
                int waited = PENDING_ENTITY.merge(key, 1, Integer::sum);
                if (waited > BLOCK_ENTITY_WAIT_TICKS) {
                    PENDING_ENTITY.remove(key);
                }
                continue;
            }
            PENDING_ENTITY.remove(key);

            // The base of a sticky piston that is retracting (not a head that is extending, not a carried block).
            if (!moving.isSourcePiston() || moving.isExtending() || !moving.getMovedState().is(Blocks.STICKY_PISTON)) {
                continue;
            }
            onStickyRetract(level, player, pos, moving.getDirection(), now);
        }
    }

    private static void onStickyRetract(ClientLevel level, LocalPlayer player, BlockPos base, Direction facing, long now) {
        if (base.distSqr(player.blockPosition()) > (double) MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        // Null: vanilla did not even try to pull (nothing there, or something that cannot be pulled).
        PistonAnalyzer.Result result = PistonAnalyzer.analyzePull(level, base, facing);
        if (result == null || result.canExtend()) {
            return; // a pull that fits was already carried out, or skipped on purpose (a very short pulse)
        }
        if (PistonAnalyzer.onlyBlockedByMovingPiston(level, result.blockers())) {
            return;
        }
        Highlight highlight = Highlight.build(result, base, facing, Kind.PULL_FAILURE, now + HIGHLIGHT_MS, false);
        if (highlight != null) {
            HIGHLIGHTS.put(base.asLong(), highlight);
            if (preview != null && preview.origin.equals(base)) {
                preview = null;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Re-analysing highlights that are showing
    // ---------------------------------------------------------------------------------------------------------

    private static void refreshHighlight(ClientLevel level, long key, long now) {
        Highlight highlight = HIGHLIGHTS.get(key);
        if (highlight == null || anyMoving(level, highlight)) {
            return; // wait for the blocks to arrive
        }
        Highlight updated = refresh(level, highlight, now);
        if (updated == highlight) {
            return;
        }
        HIGHLIGHTS.remove(key);
        if (updated != null) {
            if (updated.kind == Kind.MOVED) {
                SNAPSHOTS.add(updated); // never stored under the piston's position: a new failure must not replace it
            } else {
                HIGHLIGHTS.put(updated.origin.asLong(), updated);
            }
        }
    }

    /**
     * Analyses a highlight again. Returns the new highlight, the same object when nothing should change, or
     * null when the highlight no longer means anything. A highlight that was just moved keeps its remaining time;
     * any other change restarts the timer.
     */
    private static Highlight refresh(ClientLevel level, Highlight highlight, long now) {
        if (highlight.kind == Kind.MOVED) {
            // A snapshot of blocks that were moved: the piston is not analysed again.
            // If it was moved (again) it only needs to stop being flagged as "moved"; the time it has left stays.
            return highlight.moved ? snapshot(highlight, Kind.MOVED) : highlight;
        }
        long expiresAt = highlight.moved ? highlight.expiresAt : now + HIGHLIGHT_MS;
        PistonAnalyzer.PistonInfo info = PistonAnalyzer.pistonAt(level, highlight.origin);

        if (highlight.kind == Kind.PULL_FAILURE) {
            // The pull can be replayed from the blocks around the piston whether it is retracted or has pushed out
            // again (the head counts as gone, exactly as vanilla removes it first), so the highlight keeps updating
            // when one of them changes (it turns green once the pull would fit), just like a push.
            if (info == null || !info.sticky()) {
                return snapshot(highlight, Kind.MOVED); // the piston is gone: keep showing why it failed
            }
            PistonAnalyzer.Result result = PistonAnalyzer.analyzePull(level, highlight.origin, info.facing());
            if (result == null) {
                return highlight.moved ? snapshot(highlight, Kind.MOVED) : null; // nothing left that it would try to pull
            }
            if (!result.canExtend() && PistonAnalyzer.onlyBlockedByMovingPiston(level, result.blockers())) {
                return highlight; // see evaluate(): too unreliable while a block is mid-animation
            }
            if (!highlight.moved && result.equals(highlight.result)) {
                return highlight; // nothing about the pull changed (the piston just extended or retracted): keep the time
            }
            return Highlight.build(result, highlight.origin, info.facing(), Kind.PULL_FAILURE, expiresAt, false);
        }

        if (highlight.kind == Kind.PULL_PREVIEW) {
            if (info == null || !info.extended() || !info.sticky()) {
                // It retracted (or is gone): the pull has happened. If it took the highlighted blocks along,
                // the highlight stays on them at their new place.
                return highlight.moved ? snapshot(highlight, Kind.MOVED) : null;
            }
            PistonAnalyzer.Result result = PistonAnalyzer.analyzePull(level, highlight.origin, info.facing());
            return result == null ? null
                    : Highlight.build(result, highlight.origin, info.facing(), Kind.PULL_PREVIEW, expiresAt, false);
        }

        boolean hypothetical = highlight.kind == Kind.PLACEMENT_PREVIEW; // a piston that is not there (yet)
        if ((info != null && info.extended()) || (info == null && !hypothetical)) {
            // The piston has pushed (it is extended) or is gone, so analysing it again would say nothing about the
            // blocks. If it took the highlighted blocks along, the highlight stays on them at their new place.
            return highlight.moved ? snapshot(highlight, Kind.MOVED) : null;
        }
        Direction facing = info != null ? info.facing() : highlight.facing;
        PistonAnalyzer.Result result = PistonAnalyzer.analyze(level, highlight.origin, facing);
        if (highlight.kind == Kind.PUSH_FAILURE && !result.canExtend()
                && PistonAnalyzer.onlyBlockedByMovingPiston(level, result.blockers())) {
            return highlight; // see evaluate(): too unreliable while a block is mid-animation
        }
        return Highlight.build(result, highlight.origin, facing, highlight.kind, expiresAt, false);
    }

    /** The same highlight, no longer waiting for blocks to arrive and no longer analysed, with the time it had left. */
    private static Highlight snapshot(Highlight highlight, Kind kind) {
        return Highlight.build(highlight.result, highlight.origin, highlight.facing, kind, highlight.expiresAt, false);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Highlights
    // ---------------------------------------------------------------------------------------------------------

    private enum Kind {
        /** A powered piston that cannot push (found automatically). */
        PUSH_FAILURE,
        /** What an existing, retracted piston would push (right click). */
        PUSH_PREVIEW,
        /** What a piston placed against a block would push. The piston is not there. */
        PLACEMENT_PREVIEW,
        /** What an extended sticky piston would pull back (right click). */
        PULL_PREVIEW,
        /** A sticky piston that retracted without being able to pull its blocks (found automatically); updates like a push. */
        PULL_FAILURE,
        /**
         * Blocks that were highlighted and then moved by a piston, shown where they are now. Not analysed again:
         * it only follows the blocks if they are moved once more, and fades out with the time it had left.
         */
        MOVED
    }

    private static int withAlpha(int argb, float factor) {
        int alpha = Math.round((argb >>> 24) * Math.max(0.0F, Math.min(1.0F, factor)));
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /** One merged outline: its shape, the blocks it was built from (to find how far they are moving) and its colours. */
    private record Layer(Shape shape, List<BlockPos> blocks, int stroke, float strokeWidth, int fill, boolean alwaysOnTop,
                         Anchor anchor) {
        private static Vec3 shift(Vec3 point, Vec3 offset) {
            return offset == Vec3.ZERO ? point : point.add(offset);
        }

        void emit(float fade, Vec3 offset) {
            GizmoStyle fillStyle = GizmoStyle.fill(withAlpha(fill, fade));
            int strokeColor = withAlpha(stroke, fade);
            for (Vec3[] face : shape.faces) {
                var gizmo = Gizmos.rect(shift(face[0], offset), shift(face[1], offset), shift(face[2], offset),
                        shift(face[3], offset), fillStyle);
                if (alwaysOnTop) {
                    gizmo.setAlwaysOnTop();
                }
            }
            for (Vec3[] edge : shape.edges) {
                var gizmo = Gizmos.line(shift(edge[0], offset), shift(edge[1], offset), strokeColor, strokeWidth);
                if (alwaysOnTop) {
                    gizmo.setAlwaysOnTop();
                }
            }
        }
    }

    private static final class Highlight {
        final PistonAnalyzer.Result result;
        /** The piston (or the cell a previewed piston would stand in) and the direction it pushes. */
        final BlockPos origin;
        final Direction facing;
        final Kind kind;
        final long expiresAt;
        /**
         * True from the moment a piston moved this highlight's blocks until it was analysed again at the new place.
         * That analysis keeps {@link #expiresAt} instead of starting the timer over.
         */
        final boolean moved;
        final List<Layer> layers;
        final String label;
        final Vec3 labelPos;
        /** Packed positions whose change makes this highlight stale. */
        final Set<Long> watch;
        /** The move that carries the chain / the blockers right now (null: they are not travelling). */
        final Anchor chainAnchor;
        final Anchor blockerAnchor;

        private Highlight(PistonAnalyzer.Result result, BlockPos origin, Direction facing, Kind kind, long expiresAt,
                          boolean moved, List<Layer> layers, String label, Vec3 labelPos, Set<Long> watch,
                          Anchor chainAnchor, Anchor blockerAnchor) {
            this.result = result;
            this.origin = origin;
            this.facing = facing;
            this.kind = kind;
            this.expiresAt = expiresAt;
            this.moved = moved;
            this.layers = layers;
            this.label = label;
            this.labelPos = labelPos;
            this.watch = watch;
            this.chainAnchor = chainAnchor;
            this.blockerAnchor = blockerAnchor;
        }

        /** Builds the highlight for an analysis result, or null when there is nothing to show. */
        static Highlight build(PistonAnalyzer.Result result, BlockPos origin, Direction facing, Kind kind,
                               long expiresAt, boolean moved) {
            return build(result, origin, facing, kind, expiresAt, moved, null, null);
        }

        static Highlight build(PistonAnalyzer.Result result, BlockPos origin, Direction facing, Kind kind,
                               long expiresAt, boolean moved, Anchor chainAnchor, Anchor blockerAnchor) {
            List<Layer> layers = new ArrayList<>();
            String label = null;
            Vec3 labelPos = null;

            if (result.canExtend()) {
                if (result.chain().isEmpty()) {
                    return null;
                }
                // Fits: one green merged highlight with "count/limit".
                Shape shape = Shape.of(result.chain());
                layers.add(new Layer(shape, result.chain(), GREEN_STROKE, 3.0F, GREEN_FILL, true, chainAnchor));
                label = result.structureSize() + "/" + PistonAnalyzer.MAX_PUSH;
                labelPos = shape.center();
            } else if (result.overLimit()) {
                // The whole structure is the problem: one red merged highlight and "count/limit" over it.
                Shape shape = Shape.of(result.chain());
                layers.add(new Layer(shape, result.chain(), RED_STROKE, 3.0F, RED_FILL, true, chainAnchor));
                label = result.structureSize() + "/" + PistonAnalyzer.MAX_PUSH;
                labelPos = shape.center();
            } else {
                if (!result.chain().isEmpty()) {
                    layers.add(new Layer(Shape.of(result.chain()), result.chain(), ORANGE_STROKE, 2.0F, ORANGE_FILL, false, chainAnchor));
                }
                layers.add(new Layer(Shape.of(result.blockers()), result.blockers(), RED_STROKE, 3.0F, RED_FILL, true, blockerAnchor));
            }

            // Any change to these blocks, or to a block touching them, can change the result.
            Set<Long> watch = new HashSet<>();
            watch.add(origin.asLong());
            watch.add(origin.relative(facing).asLong());
            watch.add(origin.relative(facing, 2).asLong());
            for (List<BlockPos> group : List.of(result.chain(), result.blockers())) {
                for (BlockPos pos : group) {
                    watch.add(pos.asLong());
                    for (Direction direction : Direction.values()) {
                        watch.add(pos.relative(direction).asLong());
                    }
                }
            }
            return new Highlight(result, origin, facing, kind, expiresAt, moved, layers, label, labelPos, watch, chainAnchor, blockerAnchor);
        }

        /**
         * The same highlight moved to where a piston just carried its blocks (and the piston itself, if it was
         * carried too), with the same remaining time. Null when nothing of it moved since {@code since}.
         * Whatever part of it did not move this time keeps the move it is still travelling with.
         */
        Highlight followed(ClientLevel level, long since) {
            boolean[] any = {false};
            Move originMove = movedTo(level, origin, since);
            if (originMove != null) {
                any[0] = true;
            }
            Anchor[] chainMove = {null};
            Anchor[] blockerMove = {null};
            List<BlockPos> chain = movedAll(level, result.chain(), since, chainMove, any);
            List<BlockPos> blockers = movedAll(level, result.blockers(), since, blockerMove, any);
            if (!any[0]) {
                return null;
            }
            PistonAnalyzer.Result shifted = new PistonAnalyzer.Result(result.canExtend(), List.copyOf(blockers),
                    List.copyOf(chain), result.structureSize(), result.overLimit());
            return build(shifted, originMove != null ? originMove.to() : origin, facing, kind, expiresAt, true,
                    chainMove[0] != null ? chainMove[0] : chainAnchor,
                    blockerMove[0] != null ? blockerMove[0] : blockerAnchor);
        }

        void emit(long now, ClientLevel level, float partialTick) {
            float fade = (float) (expiresAt - now) / HIGHLIGHT_MS;
            Vec3 labelOffset = Vec3.ZERO;
            for (int i = 0; i < layers.size(); i++) {
                Layer layer = layers.get(i);
                // While blocks that this highlight followed are still travelling, the highlight travels with them.
                Vec3 offset = moved ? offsetOf(level, layer.anchor(), partialTick) : Vec3.ZERO;
                if (i == 0) {
                    labelOffset = offset;
                }
                layer.emit(fade, offset);
            }
            if (label != null) {
                TextGizmo.Style style = TextGizmo.Style.forColorAndCentered(withAlpha(LABEL_COLOR, fade)).withScale(LABEL_SCALE);
                Gizmos.billboardText(label, labelPos.add(labelOffset), style).setAlwaysOnTop();
            }
        }
    }

    /**
     * The merged surface of a group of blocks: one quad per exposed block face and one line per real edge of
     * the combined shape (edges between two coplanar faces are skipped, so flat areas have no grid lines).
     */
    private static final class Shape {
        final List<Vec3[]> faces = new ArrayList<>();
        final List<Vec3[]> edges = new ArrayList<>();
        private Vec3 center = Vec3.ZERO;

        Vec3 center() {
            return center;
        }

        private record Point(int x, int y, int z) {
        }

        /** An undirected lattice segment. */
        private record Edge(Point a, Point b) {
            static Edge of(Point p, Point q) {
                return compare(p, q) <= 0 ? new Edge(p, q) : new Edge(q, p);
            }

            private static int compare(Point p, Point q) {
                if (p.x != q.x) {
                    return Integer.compare(p.x, q.x);
                }
                if (p.y != q.y) {
                    return Integer.compare(p.y, q.y);
                }
                return Integer.compare(p.z, q.z);
            }
        }

        private record DirectedEdge(Edge edge, Direction faceDirection) {
        }

        static Shape of(List<BlockPos> blocks) {
            Shape shape = new Shape();
            Set<BlockPos> set = new HashSet<>(blocks);
            Map<DirectedEdge, Integer> edgeCounts = new HashMap<>();
            Set<Edge> keptEdges = new HashSet<>();

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

            for (BlockPos pos : set) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX() + 1);
                maxY = Math.max(maxY, pos.getY() + 1);
                maxZ = Math.max(maxZ, pos.getZ() + 1);

                for (Direction direction : Direction.values()) {
                    if (set.contains(pos.relative(direction))) {
                        continue; // hidden face between two blocks of the group
                    }
                    Point[] corners = faceCorners(pos, direction);

                    double nx = direction.getStepX() * FACE_OFFSET;
                    double ny = direction.getStepY() * FACE_OFFSET;
                    double nz = direction.getStepZ() * FACE_OFFSET;
                    Vec3[] quad = new Vec3[4];
                    for (int i = 0; i < 4; i++) {
                        quad[i] = new Vec3(corners[i].x + nx, corners[i].y + ny, corners[i].z + nz);
                    }
                    shape.faces.add(quad);

                    for (int i = 0; i < 4; i++) {
                        Edge edge = Edge.of(corners[i], corners[(i + 1) % 4]);
                        edgeCounts.merge(new DirectedEdge(edge, direction), 1, Integer::sum);
                    }
                }
            }

            // An edge shared by two exposed faces that point the same way is inside a flat area: no line.
            for (Map.Entry<DirectedEdge, Integer> entry : edgeCounts.entrySet()) {
                if (entry.getValue() == 1) {
                    keptEdges.add(entry.getKey().edge);
                }
            }
            shape.addMergedLines(keptEdges);

            if (!set.isEmpty()) {
                shape.center = new Vec3((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
            }
            return shape;
        }

        /** A straight lattice line: the axis it runs along and its two fixed coordinates. */
        private record Line(int axis, int c1, int c2) {
        }

        /** Joins unit edges that continue each other into single long lines (a 13 block row has 12 edges, not 60). */
        private void addMergedLines(Set<Edge> unitEdges) {
            Map<Line, List<int[]>> lines = new HashMap<>();
            for (Edge edge : unitEdges) {
                int[] a = {edge.a.x, edge.a.y, edge.a.z};
                int[] b = {edge.b.x, edge.b.y, edge.b.z};
                int axis = a[0] != b[0] ? 0 : a[1] != b[1] ? 1 : 2;
                Line line = new Line(axis, a[(axis + 1) % 3], a[(axis + 2) % 3]);
                lines.computeIfAbsent(line, k -> new ArrayList<>()).add(new int[]{a[axis], b[axis]});
            }
            for (Map.Entry<Line, List<int[]>> entry : lines.entrySet()) {
                Line line = entry.getKey();
                List<int[]> spans = entry.getValue();
                spans.sort((p, q) -> Integer.compare(p[0], q[0]));
                int start = spans.get(0)[0];
                int end = spans.get(0)[1];
                for (int i = 1; i <= spans.size(); i++) {
                    if (i < spans.size() && spans.get(i)[0] == end) {
                        end = spans.get(i)[1];
                        continue;
                    }
                    edges.add(new Vec3[]{linePoint(line, start), linePoint(line, end)});
                    if (i < spans.size()) {
                        start = spans.get(i)[0];
                        end = spans.get(i)[1];
                    }
                }
            }
        }

        private static Vec3 linePoint(Line line, int along) {
            double[] p = new double[3];
            p[line.axis] = along;
            p[(line.axis + 1) % 3] = line.c1;
            p[(line.axis + 2) % 3] = line.c2;
            return new Vec3(p[0], p[1], p[2]);
        }

        /** The four corners of one face of a block, in order around the face. */
        private static Point[] faceCorners(BlockPos pos, Direction direction) {
            int[] base = {pos.getX(), pos.getY(), pos.getZ()};
            int axis = direction.getAxis().ordinal();
            if (direction.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
                base[axis] += 1;
            }
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;

            Point[] corners = new Point[4];
            int[][] offsets = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int i = 0; i < 4; i++) {
                int[] p = base.clone();
                p[u] += offsets[i][0];
                p[v] += offsets[i][1];
                corners[i] = new Point(p[0], p[1], p[2]);
            }
            return corners;
        }
    }
}