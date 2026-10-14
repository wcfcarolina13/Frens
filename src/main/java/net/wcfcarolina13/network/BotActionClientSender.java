package net.wcfcarolina13.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side of {@link BotActionNetworkManager}: the one call every Frens UI button uses to act
 * on a bot. Sends a typed {@code BotActionPayload} when the server has the channel (Frens with
 * this surface), else falls back to the old {@code /bot} chat command, which only works for
 * players with {@code /bot} permission.
 *
 * <p>Loaded only from client code (screens, FrensClient), so a dedicated server never resolves
 * the client classes referenced by fully qualified name below. Render thread only.
 */
@Environment(EnvType.CLIENT)
public final class BotActionClientSender {

    private BotActionClientSender() {}

    /**
     * @param action              what to do
     * @param botAliasOrBlank     the bot's name (surrounding double quotes are stripped), or
     *                            blank for "my only bot" (an operator or host gets the old
     *                            untargeted behaviour instead)
     * @param args                the action's arguments, as documented on the enum constant
     * @param fallbackChatCommand the exact {@code /bot ...} command the site sent before, with or
     *                            without the leading slash; null or blank sends nothing on fallback
     */
    public static void send(BotActionPolicy.Action action, String botAliasOrBlank, List<String> args,
                            String fallbackChatCommand) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (action == null || client == null || client.getNetworkHandler() == null) {
            return;
        }
        if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                BotActionNetworkManager.BotActionPayload.ID)) {
            List<String> safeArgs = new ArrayList<>();
            if (args != null) {
                for (String a : args) {
                    safeArgs.add(a == null ? "" : a);
                }
            }
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new BotActionNetworkManager.BotActionPayload(action.name(), normalizeAlias(botAliasOrBlank), safeArgs));
            return;
        }
        if (fallbackChatCommand == null || fallbackChatCommand.isBlank()) {
            return;
        }
        String raw = fallbackChatCommand.startsWith("/") ? fallbackChatCommand.substring(1) : fallbackChatCommand;
        client.getNetworkHandler().sendChatCommand(raw);
    }

    /** Trims, and strips one pair of surrounding double quotes ({@code "Big Bob"} -> {@code Big Bob}). */
    static String normalizeAlias(String alias) {
        if (alias == null) {
            return "";
        }
        String t = alias.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }
}
