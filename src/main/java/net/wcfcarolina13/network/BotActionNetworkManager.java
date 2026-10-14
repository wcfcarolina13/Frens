package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.GameAI.services.BotRegistry;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of the Frens UI buttons for players without {@code /bot} permission (a non-op bot
 * owner on a dedicated server, a LAN guest). The client sends a typed {@link BotActionPayload};
 * the server resolves the bot itself, authorises the sender against it, builds the canonical
 * command with {@link BotActionPolicy#build} and runs it through the normal dispatcher with the
 * player's own source via {@link AuthorizedBotDispatch#run}.
 *
 * <p>Threads: the receiver hops to the server thread; every map here is server-thread only.
 */
public final class BotActionNetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-access");

    /**
     * {@link BotActionPolicy#build} returns the command below the {@code /bot} root
     * ({@code "stop Bob"}); the dispatcher needs the root ({@code "bot stop Bob"}).
     */
    private static final String BOT_ROOT = "bot ";

    private BotActionNetworkManager() {}

    // ── Payload ─────────────────────────────────────────────────────────────

    /**
     * Client → server: one UI action. {@code action} is a {@link BotActionPolicy.Action} name;
     * {@code botAlias} is the bot's name, or "" for "my only bot"; {@code args} are the action's
     * arguments as documented on the enum constant.
     */
    public record BotActionPayload(String action, String botAlias, List<String> args) implements CustomPayload {
        public static final Id<BotActionPayload> ID = new Id<>(Identifier.of("frens", "bot_action"));
        private static final PacketCodec<PacketByteBuf, String> ACTION = new StringCodec(32);
        private static final PacketCodec<PacketByteBuf, String> ALIAS = new StringCodec(64);
        private static final PacketCodec<PacketByteBuf, List<String>> ARGS = PacketCodecs.collection(
                ArrayList::new, new StringCodec(BotActionPolicy.MAX_LABEL_LEN + 16), BotActionPolicy.MAX_ARGS);
        public static final PacketCodec<PacketByteBuf, BotActionPayload> CODEC = PacketCodec.tuple(
                ACTION, BotActionPayload::action,
                ALIAS, BotActionPayload::botAlias,
                ARGS, BotActionPayload::args,
                BotActionPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ── Registration ────────────────────────────────────────────────────────

    private static volatile boolean registered;

    /** Registers the payload and its receiver. Called once from {@code Frens.onInitialize}. */
    public static void registerOnce() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.playC2S().register(BotActionPayload.ID, BotActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BotActionPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            server.execute(() -> THROTTLES.remove(id));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> THROTTLES.clear());
        if (!BotActionPolicy.HOBBY_NAMES.equals(net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler.HOBBY_BIT_ORDER)) {
            LOGGER.warn("[bot-access] BotActionPolicy.HOBBY_NAMES differs from HOBBY_BIT_ORDER; "
                    + "hobby buttons for non-op owners may refuse some hobbies");
        }
    }

    // ── State (server thread only) ──────────────────────────────────────────

    private static final Map<UUID, BotActionPolicy.Throttle> THROTTLES = new HashMap<>();

    // ── Receiver ────────────────────────────────────────────────────────────

    private static void handle(MinecraftServer server, ServerPlayerEntity player, BotActionPayload payload) {
        if (server == null || payload == null || !isRealPlayer(player)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!THROTTLES.computeIfAbsent(player.getUuid(), id -> new BotActionPolicy.Throttle()).tryAcquire(now)) {
            LOGGER.debug("[bot-access] throttled bot action from {}", player.getName().getString());
            return;
        }
        Optional<BotActionPolicy.Action> parsed = BotActionPolicy.parseAction(payload.action());
        if (parsed.isEmpty()) {
            BotAccessGate.warnDenied(player, payload.botAlias(), payload.action(), "UNKNOWN_ACTION");
            return;
        }
        BotActionPolicy.Action action = parsed.get();
        List<String> args = payload.args() == null ? List.of() : payload.args();
        String alias = payload.botAlias() == null ? "" : payload.botAlias().trim();

        if (action == BotActionPolicy.Action.SPAWN) {
            handleSpawn(server, player, alias, args);
            return;
        }

        ServerPlayerEntity bot;
        if (!alias.isEmpty()) {
            if (!BotActionPolicy.isValidAlias(alias)) {
                BotAccessGate.warnDenied(player, alias, action.name(), "BAD_ALIAS");
                tell(player, "That bot name is not valid.");
                return;
            }
            bot = findRegisteredBot(server, alias);
            if (bot == null) {
                BotAccessGate.warnDenied(player, alias, action.name(), "NO_SUCH_BOT");
                tell(player, "There is no bot called " + alias + " here.");
                return;
            }
        } else {
            List<ServerPlayerEntity> owned = ownedBots(server, player);
            if (owned.size() == 1) {
                bot = owned.get(0);
            } else if (BotAccessGate.isServerAdmin(player) && action.hasUntargetedForm()) {
                // Operators and the host already have /bot. With no alias they keep today's
                // behaviour: the untargeted command acts on their remembered or active bot.
                bot = null;
            } else {
                BotAccessGate.warnDenied(player, "", action.name(),
                        owned.isEmpty() ? "NO_OWNED_BOT" : "SEVERAL_OWNED_BOTS");
                tell(player, owned.isEmpty()
                        ? "You do not have a bot to send that to."
                        : "You have more than one bot. Pick one first.");
                return;
            }
        }
        authorizeBuildRun(server, player, bot, action, args);
    }

    private static void handleSpawn(MinecraftServer server, ServerPlayerEntity player, String name, List<String> args) {
        if (!BotAccessGate.isServerAdmin(player)) {
            BotAccessGate.warnDenied(player, name, "SPAWN", "NOT_SERVER_ADMIN");
            tell(player, "Only an operator or the host can create bots.");
            return;
        }
        if (!BotActionPolicy.isValidNewBotName(name)) {
            BotAccessGate.warnDenied(player, name, "SPAWN", "BAD_NAME");
            tell(player, "That bot name is not valid.");
            return;
        }
        Optional<String> command = BotActionPolicy.build(BotActionPolicy.Action.SPAWN, args, name, knownBotNamesLower(server));
        if (command.isEmpty()) {
            BotAccessGate.warnDenied(player, name, "SPAWN", "BAD_ARGS");
            tell(player, "That action had a value the server does not accept.");
            return;
        }
        AuthorizedBotDispatch.run(player, BOT_ROOT + command.get());
    }

    // ── Shared path (also used by dispatchFor) ──────────────────────────────

    /**
     * Server-side entry for code that already holds the bot, e.g. a hunt re-dispatch or a skill
     * resume: authorises {@code player} for {@code action} on {@code bot}, builds the canonical
     * command and runs it under the grant. Server thread only. Denials are WARN-logged and also
     * told to the player.
     *
     * @return true when the command was dispatched
     */
    public static boolean dispatchFor(ServerPlayerEntity player, ServerPlayerEntity bot,
                                      BotActionPolicy.Action action, List<String> args) {
        if (!isRealPlayer(player) || bot == null || action == null || action == BotActionPolicy.Action.SPAWN) {
            return false;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        if (!server.isOnThread()) {
            throw new IllegalStateException("BotActionNetworkManager.dispatchFor must be called on the server thread");
        }
        return authorizeBuildRun(server, player, bot, action, args == null ? List.of() : args);
    }

    /** {@code bot} null means the untargeted form, which the caller allows only for a server admin. */
    private static boolean authorizeBuildRun(MinecraftServer server, ServerPlayerEntity player, ServerPlayerEntity bot,
                                             BotActionPolicy.Action action, List<String> args) {
        String targetName = bot == null ? "" : bot.getName().getString();
        boolean allowed = switch (action.access()) {
            case BOT_OWNER -> bot == null
                    ? BotAccessGate.isServerAdmin(player)
                    : BotAccessGate.permits(player, bot, action.name());
            case SERVER_ADMIN -> BotAccessGate.isServerAdmin(player);
        };
        if (!allowed) {
            if (action.access() == BotActionPolicy.Access.SERVER_ADMIN || bot == null) {
                BotAccessGate.warnDenied(player, targetName, action.name(), "NOT_SERVER_ADMIN");
            }
            tell(player, bot == null
                    ? "You are not allowed to do that."
                    : "You can only do that for your own bot.");
            return false;
        }
        Set<String> known = knownBotNamesLower(server);
        String botName = bot == null ? null : bot.getGameProfile().name();
        Optional<String> command = BotActionPolicy.build(action, args, botName, known);
        if (command.isEmpty()) {
            BotAccessGate.warnDenied(player, targetName, action.name(), "BAD_ARGS");
            tell(player, "That action had a value the server does not accept.");
            return false;
        }
        if (BotActionPolicy.fortifyEditsSavedWall(action, args)
                && !mayEditWall(player, bot, args.size() > 1 ? args.get(1) : "")) {
            BotAccessGate.warnDenied(player, targetName, action.name(), "WALL_NOT_EDITABLE");
            tell(player, "You cannot work on that wall.");
            return false;
        }
        return AuthorizedBotDispatch.run(player, BOT_ROOT + command.get()) > 0;
    }

    /**
     * Whether {@code player} may have {@code bot} edit the saved wall {@code label} ("" = the
     * nearest wall to the bot, which the skill auto-picks). Owner of that wall, operator or host;
     * no wall found is allowed because the skill then refuses or builds a new one (see
     * {@link FortifyWallEditPolicy}). {@code bot} null is the untargeted form, admins only.
     */
    private static boolean mayEditWall(ServerPlayerEntity player, ServerPlayerEntity bot, String label) {
        if (bot == null) {
            return BotAccessGate.isServerAdmin(player);
        }
        return BaseNetworkManager.canEditWallForBot(player, bot, label);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isRealPlayer(ServerPlayerEntity player) {
        return player != null && !player.isRemoved() && !(player instanceof createFakePlayer);
    }

    /** The online registered Frens bot whose profile name equals {@code alias} (ignoring case), or null. */
    private static ServerPlayerEntity findRegisteredBot(MinecraftServer server, String alias) {
        for (ServerPlayerEntity bot : BotRegistry.getPlayers(server)) {
            if (bot instanceof createFakePlayer && !bot.isRemoved()
                    && bot.getGameProfile().name().equalsIgnoreCase(alias)) {
                return bot;
            }
        }
        return null;
    }

    /** Online registered bots whose configured owner is {@code player}. */
    private static List<ServerPlayerEntity> ownedBots(MinecraftServer server, ServerPlayerEntity player) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        UUID me = player.getUuid();
        for (ServerPlayerEntity bot : BotRegistry.getPlayers(server)) {
            if (bot instanceof createFakePlayer && !bot.isRemoved()
                    && me.equals(CompanionCommunicationPolicy.resolveOwnerUuid(bot))) {
                out.add(bot);
            }
        }
        return out;
    }

    /** Same set the skill parser treats as targets: every online fake player. */
    private static Set<String> knownBotNamesLower(MinecraftServer server) {
        Set<String> names = new HashSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p instanceof createFakePlayer) {
                names.add(p.getGameProfile().name().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static void tell(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.YELLOW), false);
    }
}
