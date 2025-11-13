package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.shasankp000.GraphicalUserInterface.BotInventoryScreen;

public class AIPlayerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HandledScreens.register(AIPlayer.BOT_INV_HANDLER, BotInventoryScreen::new);
    }
}
