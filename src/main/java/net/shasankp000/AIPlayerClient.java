package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.text.Text;
import net.shasankp000.GraphicalUserInterface.BotPlayerInventoryScreen;
import net.shasankp000.GraphicalUserInterface.ConfigManager;
import net.shasankp000.Network.ConfigJsonUtil;
import net.shasankp000.Network.OpenConfigPayload;

public class AIPlayerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HandledScreens.register(AIPlayer.BOT_PLAYER_INV_HANDLER, BotPlayerInventoryScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(OpenConfigPayload.ID, (payload, context) -> {
            ConfigJsonUtil.applyConfigJson(payload.configData());
            context.client().execute(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                Screen parent = client.currentScreen;
                client.setScreen(new ConfigManager(Text.literal("AI Player Configuration"), parent));
            });
        });
    }
}
