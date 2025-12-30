package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.shasankp000.GraphicalUserInterface.BaseManagerScreen;
import net.shasankp000.GraphicalUserInterface.BotPlayerInventoryScreen;
import net.shasankp000.GraphicalUserInterface.ConfigManager;
import net.shasankp000.Network.BasesListPayload;
import net.shasankp000.Network.ConfigJsonUtil;
import net.shasankp000.Network.OpenConfigPayload;
import org.lwjgl.glfw.GLFW;

public class AIPlayerClient implements ClientModInitializer {

    private static KeyBinding KEY_FOLLOW_TOGGLE_LOOK;
    private static KeyBinding KEY_GO_TO_LOOK;

    // Pending shelter type from the Topics menu (null = no pending shelter, use go_to_look as normal)
    private static String pendingShelterType = null;
    private static String pendingShelterBotTarget = null;

    public static void setPendingShelter(String type, String botTarget) {
        pendingShelterType = type;
        pendingShelterBotTarget = botTarget;
    }

    public static void clearPendingShelter() {
        pendingShelterType = null;
        pendingShelterBotTarget = null;
    }

    @Override
    public void onInitializeClient() {
        HandledScreens.register(AIPlayer.BOT_PLAYER_INV_HANDLER, BotPlayerInventoryScreen::new);

        // Keybind fallback for Shift+F1 / Shift+F2.
        // Notes:
        // - The mixin-based shortcut (Shift+F1/Shift+F2) still exists and suppresses vanilla F1/F2 side effects.
        // - These are regular keybinds (no modifier support), intended as a reliable, rebindable alternative
        //   especially on macOS where F-keys may be captured by the OS unless the user holds Fn or enables
        //   "Use F1, F2, etc. keys as standard function keys".
        // - Default: unbound, to avoid colliding with vanilla bindings.
        KEY_FOLLOW_TOGGLE_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ai-player.follow_toggle_look",
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));
        KEY_GO_TO_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.ai-player.go_to_look",
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.getNetworkHandler() == null) {
                return;
            }
            if (client.currentScreen != null) {
                return;
            }

            if (KEY_FOLLOW_TOGGLE_LOOK.wasPressed()) {
                handleFollowToggleLookedAt(client);
            }
            if (KEY_GO_TO_LOOK.wasPressed()) {
                handleGoToLook(client);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(OpenConfigPayload.ID, (payload, context) -> {
            ConfigJsonUtil.applyConfigJson(payload.configData());
            context.client().execute(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                Screen parent = client.currentScreen;
                client.setScreen(new ConfigManager(Text.literal("AI Player Configuration"), parent));
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(BasesListPayload.ID, (payload, context) -> {
            String json = payload.basesJson();
            context.client().execute(() -> BaseManagerScreen.applyBasesJson(json));
        });
    }

    private static void handleGoToLook(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        // Check if there's a pending shelter command from the Topics menu
        if (pendingShelterType != null) {
            String cmd = "bot shelter_look " + pendingShelterType;
            if (pendingShelterBotTarget != null && !pendingShelterBotTarget.isEmpty()) {
                cmd += " " + pendingShelterBotTarget;
            }
            sendChatCommand(client, cmd);
            clearPendingShelter();
        } else {
            // Normal go_to_look behavior
            sendChatCommand(client, "bot go_to_look");
        }
    }

    private static void handleFollowToggleLookedAt(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof EntityHitResult ehr)) {
            client.player.sendMessage(Text.literal("Look at a bot to toggle follow."), true);
            return;
        }
        if (!(ehr.getEntity() instanceof PlayerEntity target) || target == client.player) {
            client.player.sendMessage(Text.literal("Look at a bot to toggle follow."), true);
            return;
        }
        String name = target.getName().getString();
        if (name == null || name.isBlank()) {
            client.player.sendMessage(Text.literal("Couldn't identify that bot."), true);
            return;
        }
        sendChatCommand(client, "bot follow toggle " + name);
    }

    private static void sendChatCommand(MinecraftClient client, String command) {
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        client.getNetworkHandler().sendChatCommand(raw);
    }
}
