package dev.tggamesyt.pistonfails.client.mixin;

import dev.tggamesyt.pistonfails.client.PistonFailTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-only. {@code setBlocksDirty} runs for every real block state change in the client world: the ones the
 * server pushes to us, the ones the client predicts itself, and the ones the client's own copy of the piston
 * code makes when it replays a block event.
 * <p>
 * {@code sendBlockUpdated} is not good enough for this: {@code Level#setBlock} only calls it when the flags
 * contain "update clients" and (on the client) do not contain "no re-render". The piston code places its moving
 * blocks with flags 276 and 324, which have neither, so a retracting piston base and every pushed or pulled block
 * would be invisible to the tracker. {@code setBlocksDirty} is called whenever the state really changed,
 * whatever the flags are.
 * <p>
 * The old state is passed on too: it is how a highlighted block that was moved by a piston is followed.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void pistonfails$onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        PistonFailTracker.onBlockChanged(pos, oldState, newState);
    }
}