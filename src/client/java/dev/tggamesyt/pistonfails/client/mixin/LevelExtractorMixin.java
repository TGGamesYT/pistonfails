package dev.tggamesyt.pistonfails.client.mixin;

import dev.tggamesyt.pistonfails.client.PistonFailTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-only. {@code extractGizmos} runs once per rendered frame and collects the gizmos of the frame. The highlights
 * are emitted just before that, into the per-frame collector, so they can move smoothly with moving piston blocks
 * (gizmos emitted from the client tick only change once per tick).
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "extractGizmos", at = @At("HEAD"))
    private void pistonfails$emitHighlights(CallbackInfo ci) {
        PistonFailTracker.emitFrame((LevelExtractor) (Object) this);
    }
}