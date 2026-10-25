package dev.tggamesyt.pistonfails.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Right clicking with a piston or sticky piston when it cannot be placed shows what a piston would push there.
 * <ul>
 *   <li><b>Too far:</b> the click hits no block within reach, so a long raycast finds the block you are looking at.</li>
 *   <li><b>Too close:</b> the click hits a block, but the piston would stand inside you, so vanilla refuses it.</li>
 * </ul>
 * In both cases the preview is the structure a piston standing against the face you are looking at would
 * push into that block (see {@link PistonFailTracker#showPreview}), and the hand swings.
 * <p>
 * Right clicking a piston with both hands empty (which does nothing in vanilla) swings the hand and shows
 * what that piston would push when it activates, or, for an extended sticky piston, what it would pull back
 * when it retracts. Either part of an extended piston works (the base and the head are separate blocks), and so
 * does a piston that is moving (a flying machine): see {@link PistonTargeting}. Like the placement preview, this
 * also works on a piston that is out of reach: a long raycast finds it.
 * <p>
 * The swing is only played on your own client (the game has no swing-only packet to tell the server about).
 */
public final class PistonPlacementPreview {
    /** How far the "too far" raycast looks. */
    private static final double FAR_RANGE = 128.0;

    private static boolean useWasDown;

    private PistonPlacementPreview() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(PistonPlacementPreview::onUseBlock);
        UseItemCallback.EVENT.register(PistonPlacementPreview::onUseItem);
        ClientTickEvents.END_CLIENT_TICK.register(PistonPlacementPreview::tick);
    }

    /**
     * A right click with empty hands on something out of reach fires no callback at all (the game does not
     * even try to use an empty hand), so the use key is watched directly. It is only read, never consumed.
     */
    private static void tick(Minecraft client) {
        boolean down = client.options.keyUse.isDown();
        boolean pressed = down && !useWasDown;
        useWasDown = down;

        LocalPlayer player = client.player;
        if (!pressed || player == null || client.level == null || !client.mouseHandler.isMouseGrabbed() || player.isSpectator()) {
            return;
        }
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) {
            return;
        }
        HitResult current = client.hitResult;
        if (current != null && current.getType() != HitResult.Type.MISS) {
            return; // a block or entity is in reach: the normal callbacks handle it
        }

        // Nothing is in reach, so nothing can be interacted with by accident: any piston the crosshair is on,
        // or almost on, counts.
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        BlockPos target = PistonTargeting.find(client.level, player, partialTick, FAR_RANGE, null, true);
        if (target != null && PistonFailTracker.showPistonPreview(client.level, target)) {
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getInteractAnimation(), false);
        }
    }

    private static boolean isPiston(ItemStack stack) {
        return stack.is(Items.PISTON) || stack.is(Items.STICKY_PISTON);
    }

    /** Right click on a block within reach. */
    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide() || !(level instanceof ClientLevel clientLevel) || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);

        // Empty hands on a piston: nothing happens in vanilla, so use the click to inspect the piston. The base,
        // the head and a moving piston all count, and so does a moving piston the crosshair is only close to
        // (a resting piston has to be hit exactly, so a lever or button next to it stays usable).
        if (hand == InteractionHand.MAIN_HAND && stack.isEmpty() && player.getOffhandItem().isEmpty()) {
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            BlockPos target = PistonTargeting.find(level, player, partialTick, FAR_RANGE, hit, false);
            if (target != null && PistonFailTracker.showPistonPreview(clientLevel, target)) {
                return InteractionResult.SUCCESS; // success makes the game swing the hand
            }
        }

        if (!isPiston(stack) || canPlace(player, level, hand, stack, hit)) {
            return InteractionResult.PASS;
        }
        PistonFailTracker.showPreview(clientLevel, hit.getBlockPos(), hit.getDirection());
        return InteractionResult.SUCCESS; // vanilla would refuse the placement silently; swing instead
    }

    /** Right click that did not hit a block within reach. */
    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (!level.isClientSide() || !(level instanceof ClientLevel clientLevel) || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        if (!isPiston(player.getItemInHand(hand))) {
            return InteractionResult.PASS;
        }
        Minecraft client = Minecraft.getInstance();
        HitResult current = client.hitResult;
        if (current != null && current.getType() == HitResult.Type.BLOCK) {
            return InteractionResult.PASS; // a block is in reach: UseBlockCallback handles that click
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        HitResult far = player.pick(FAR_RANGE, partialTick, false);
        if (far instanceof BlockHitResult blockHit && far.getType() == HitResult.Type.BLOCK) {
            PistonFailTracker.showPreview(clientLevel, blockHit.getBlockPos(), blockHit.getDirection());
            return InteractionResult.SUCCESS; // swing the hand
        }
        return InteractionResult.PASS;
    }

    /** The same checks vanilla's {@code BlockItem} does before it places a block. */
    private static boolean canPlace(Player player, Level level, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return true;
        }
        BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hit);
        if (!context.canPlace()) {
            return false;
        }
        BlockState state = blockItem.getBlock().getStateForPlacement(context);
        if (state == null) {
            return false;
        }
        BlockPos pos = context.getClickedPos();
        return state.canSurvive(level, pos) && level.isUnobstructed(state, pos, CollisionContext.placementContext(player));
    }
}