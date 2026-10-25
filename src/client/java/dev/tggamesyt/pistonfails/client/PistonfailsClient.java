package dev.tggamesyt.pistonfails.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class PistonfailsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(PistonFailTracker::tick);
        PistonPlacementPreview.register();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PistonFailTracker.clear());
    }
}