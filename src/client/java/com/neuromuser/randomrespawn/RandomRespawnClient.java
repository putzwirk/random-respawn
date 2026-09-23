package com.neuromuser.randomrespawn;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class RandomRespawnClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ConfigNetworking.ProgressPayload.ID, (payload, context) -> {
            int progress = payload.progress();
            String key = payload.key();

            context.client().execute(() -> {
                if (progress >= 100) {
                    if (context.client().currentScreen instanceof RespawnLoadingScreen) {
                        context.client().setScreen(null);
                    }
                } else {
                    if (!(context.client().currentScreen instanceof RespawnLoadingScreen)) {
                        context.client().setScreen(new RespawnLoadingScreen());
                    }
                    if (context.client().currentScreen instanceof RespawnLoadingScreen screen) {
                        screen.updateProgress(key, progress);
                    }
                }
            });
        });
    }
}
