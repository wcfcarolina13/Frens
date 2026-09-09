package net.wcfcarolina13.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import net.wcfcarolina13.GameAI.souls.SoulRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** One-time group-chat discovery, with server-owned eligibility and per-world receipts. */
public final class GroupChatHint {
    private static final Set<UUID> seen = new HashSet<>();
    private static final Set<UUID> offered = new HashSet<>();
    private static final java.util.Map<UUID, Integer> watchingUntil = new java.util.HashMap<>();
    private static Path receipts;
    private static CompletableFuture<Void> saves = CompletableFuture.completedFuture(null);

    private GroupChatHint() {}

    public record Notice(boolean eligible) implements CustomPayload {
        public static final Id<Notice> ID = new Id<>(Identifier.of("frens", "group_chat_hint"));
        public static final PacketCodec<PacketByteBuf, Notice> CODEC = PacketCodec.of(
                (value, buf) -> buf.writeBoolean(value.eligible()), buf -> new Notice(buf.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Notice.ID, Notice.CODEC);
        PayloadTypeRegistry.playC2S().register(Notice.ID, Notice.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            seen.clear();
            offered.clear();
            watchingUntil.clear();
            receipts = server.getSavePath(WorldSavePath.ROOT).resolve("frens/group-chat-hint-seen.txt");
            try {
                if (Files.exists(receipts)) {
                    for (String line : Files.readAllLines(receipts)) {
                        try { seen.add(UUID.fromString(line)); }
                        catch (IllegalArgumentException ignored) { }
                    }
                }
            } catch (java.io.IOException ex) {
                Frens.LOGGER.warn("Could not read group-chat hint receipts", ex);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            saves.join();
            seen.clear();
            offered.clear();
            receipts = null;
            watchingUntil.clear();
        });
        ServerPlayNetworking.registerGlobalReceiver(Notice.ID, (notice, context) ->
                context.server().execute(() -> {
                    UUID playerId = context.player().getUuid();
                    if (!notice.eligible() || receipts == null || !offered.remove(playerId)
                            || !seen.add(playerId)) return;
                    watchingUntil.put(playerId, context.server().getTicks() + 400);
                    Path destination = receipts;
                    var snapshot = seen.stream().map(UUID::toString).sorted().toList();
                    saves = saves.thenRunAsync(() -> {
                        try {
                            Files.createDirectories(destination.getParent());
                            Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
                            Files.write(temporary, snapshot);
                            Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        } catch (java.io.IOException ex) {
                            Frens.LOGGER.warn("Could not save group-chat hint receipts", ex);
                        }
                    });
                }));
        ServerTickEvents.END_SERVER_TICK.register(GroupChatHint::tick);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            watchingUntil.remove(handler.player.getUuid());
            offered.remove(handler.player.getUuid());
        });
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % 20 != 0) return;
        watchingUntil.values().removeIf(until -> until < server.getTicks());
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        boolean enabled = runtime != null && runtime.pipelineAvailable()
                && Frens.CONFIG != null && Frens.CONFIG.isSoulPartyEnabled();
        var bots = BotEventHandler.getRegisteredBots(server);
        double range = CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player instanceof createFakePlayer
                    || (seen.contains(player.getUuid()) && !watchingUntil.containsKey(player.getUuid()))
                    || !ServerPlayNetworking.canSend(player, Notice.ID)) continue;
            int nearby = 0;
            if (enabled && player.isAlive() && !player.isSleeping() && player.getAttacker() == null) {
                for (ServerPlayerEntity bot : bots) {
                    if (bot.isAlive() && !bot.isRemoved() && bot.getAttacker() == null
                            && player.getUuid().equals(BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot))
                            && bot.getEntityWorld() == player.getEntityWorld()
                            && bot.squaredDistanceTo(player) <= range * range
                            && runtime.hasActiveProfile(bot.getUuid()) && ++nearby == 2) break;
                }
            }
            boolean eligible = nearby >= 2;
            if (eligible && !seen.contains(player.getUuid())) offered.add(player.getUuid());
            ServerPlayNetworking.send(player, new Notice(eligible));
        }
    }

    @Environment(EnvType.CLIENT)
    public static final class Client {
        private static final net.minecraft.text.Text MESSAGE = net.minecraft.text.Text.literal(
                "Talk to your companions together\nType: Bots, what should we do next?");
        private static boolean eligible;
        private static boolean shown;
        private static long expires;
        private static long quietAfter;
        private static int x, y, width, buttonY;

        private Client() {}

        public static void register() {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                    Notice.ID, (notice, context) -> context.client().execute(() -> eligible = notice.eligible()));
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                    (handler, client) -> { eligible = false; shown = false; expires = 0; quietAfter = 0; });
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
                long now = net.minecraft.util.Util.getMeasuringTimeMs();
                if (client.player == null || client.player.hurtTime > 0 || client.player.handSwinging) {
                    quietAfter = now + 5000;
                }
            });
            net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(
                    (context, delta) -> {
                        if (net.minecraft.client.MinecraftClient.getInstance().currentScreen == null) render(context);
                    });
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
                if (!(screen instanceof net.minecraft.client.gui.screen.ChatScreen)) return;
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register(
                        (s, context, mouseX, mouseY, delta) -> render(context));
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register(
                        (s, click) -> !click(click.x(), click.y(), click.button()));
            });
        }

        private static boolean visible(net.minecraft.client.MinecraftClient client, long now) {
            return eligible && expires > now && now >= quietAfter && client.player != null && client.player.isAlive()
                    && !client.player.isSleeping() && !client.options.hudHidden
                    && (client.currentScreen == null
                        || client.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen);
        }

        private static void render(net.minecraft.client.gui.DrawContext context) {
            var client = net.minecraft.client.MinecraftClient.getInstance();
            long now = net.minecraft.util.Util.getMeasuringTimeMs();
            if (!shown && eligible && now >= quietAfter && client.currentScreen == null
                    && client.player != null && client.player.isAlive() && !client.player.isSleeping()
                    && !client.options.hudHidden) {
                shown = true;
                expires = now + 15_000;
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Notice(true));
            }
            if (!visible(client, now)) return;
            width = Math.min(280, context.getScaledWindowWidth() - 16);
            x = context.getScaledWindowWidth() - width - 8;
            y = 72;
            var lines = client.textRenderer.wrapLines(MESSAGE, width - 16);
            int height = lines.size() * client.textRenderer.fontHeight + 42;
            context.fill(x, y, x + width, y + height, 0xE0181818);
            int row = y + 8;
            for (var line : lines) {
                context.drawTextWithShadow(client.textRenderer, line, x + 8, row, 0xFFE6D7A3);
                row += client.textRenderer.fontHeight;
            }
            String key = client.options.chatKey.getBoundKeyLocalizedText().getString();
            buttonY = row + 17;
            context.drawTextWithShadow(client.textRenderer, "Press " + key + " to click:", x + 8, row + 3, 0xFFB0B0B0);
            context.drawTextWithShadow(client.textRenderer, "[Open guide]", x + 8, row + 17, 0xFF7FD97F);
            context.drawTextWithShadow(client.textRenderer, "[Dismiss]", x + width - 62, row + 17, 0xFFEFEFEF);
        }

        private static boolean click(double mouseX, double mouseY, int button) {
            var client = net.minecraft.client.MinecraftClient.getInstance();
            if (button != 0 || !visible(client, net.minecraft.util.Util.getMeasuringTimeMs())) return false;
            if (mouseY < buttonY - 3 || mouseY > buttonY + 11) return false;
            if (mouseX >= x + 8 && mouseX <= x + 8 + client.textRenderer.getWidth("[Open guide]")) {
                expires = 0;
                client.setScreen(new net.wcfcarolina13.GraphicalUserInterface.BotGuideScreen(
                        null, null, "basics_group_chat"));
                return true;
            }
            if (mouseX >= x + width - 62 && mouseX <= x + width - 8) {
                expires = 0;
                return true;
            }
            return false;
        }
    }
}
